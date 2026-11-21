package pl.kuba.radiocentrum.radio;

import android.app.Activity;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Test mikrofonów: jakie wejścia widzi radio i które z nich słyszy drapanie kratki przy lusterku.
 */
final class MicTest {
    private MicTest() {
    }

    private static volatile boolean running;

    static void show(final Activity a) {
        if (Build.VERSION.SDK_INT >= 23
                && a.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 21);
            android.widget.Toast.makeText(a, "Zezwól na mikrofon i naciśnij test jeszcze raz", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        Okno o = new Okno(a, "🎤 Test mikrofonów",
                "Naciśnij START, a potem przez cały test (ok. 30 s) drap palcem kratkę mikrofonu przy lusterku. Nic nie mów.");
        final TextView out = o.message(inputs(a), 16);
        o.row(o.btn("▶ START", Ui.GREEN, false, () -> {
            if (running) return;
            out.setText("⏳ Test trwa – drap kratkę przy lusterku…\n\n" + inputs(a));
            new Thread(() -> run(a, out), "rc-mictest").start();
        }));
        o.cancel("✖ Zamknij");
        o.show();
    }

    private static String inputs(Activity a) {
        StringBuilder sb = new StringBuilder("Wejścia dźwięku w radiu:\n");
        if (Build.VERSION.SDK_INT >= 23) {
            AudioManager am = (AudioManager) a.getSystemService(Activity.AUDIO_SERVICE);
            AudioDeviceInfo[] ds = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
            for (AudioDeviceInfo d : ds) {
                sb.append("• ").append(typeName(d.getType())).append(" (").append(d.getProductName()).append(", id ")
                        .append(d.getId()).append(")\n");
            }
            if (ds.length == 0) sb.append("• (brak)\n");
        }
        return sb.toString();
    }

    static String typeName(int t) {
        switch (t) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return "Mikrofon wbudowany";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "Mikrofon z jacka (zestaw słuchawkowy)";
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
                return "Wejście liniowe (AUX)";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "Bluetooth (rozmowa)";
            case AudioDeviceInfo.TYPE_FM_TUNER:
                return "Tuner FM";
            case AudioDeviceInfo.TYPE_TELEPHONY:
                return "Telefonia";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "USB";
            case AudioDeviceInfo.TYPE_REMOTE_SUBMIX:
                return "Wewnętrzne (submix)";
            default:
                return "Typ " + t;
        }
    }

    private static void run(Activity a, TextView out) {
        running = true;
        Handler h = new Handler(Looper.getMainLooper());
        StringBuilder res = new StringBuilder("Wyniki (głośność drapania, im więcej tym lepiej słyszy):\n");
        List<Object[]> tests = new ArrayList<>();
        int[] srcs = {MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION, 9 /*UNPROCESSED*/};
        String[] names = {"MIC", "CAMCORDER", "VOICE_RECOGNITION", "VOICE_COMMUNICATION", "UNPROCESSED"};
        for (int i = 0; i < srcs.length; i++) tests.add(new Object[]{srcs[i], names[i], null});
        if (Build.VERSION.SDK_INT >= 23) {
            AudioManager am = (AudioManager) a.getSystemService(Activity.AUDIO_SERVICE);
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BUILTIN_MIC || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || t == AudioDeviceInfo.TYPE_LINE_ANALOG || t == AudioDeviceInfo.TYPE_USB_DEVICE
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    tests.add(new Object[]{MediaRecorder.AudioSource.MIC, "urządzenie: " + typeName(t) + " #" + d.getId(), d});
                }
            }
        }
        for (Object[] t : tests) {
            String r = measure((Integer) t[0], (AudioDeviceInfo) t[2]);
            res.append("• ").append(t[1]).append(": ").append(r).append("\n");
            final String now = res.toString();
            h.post(() -> out.setText("⏳ Drap dalej…\n\n" + now));
        }
        final String fin = res.toString();
        h.post(() -> out.setText("✅ Koniec testu. Zrób zdjęcie i wyślij.\n\n" + fin + "\n" + inputs(a)));
        running = false;
    }

    /** Nagrywa 3 s i zwraca poziom (średni i szczyt). */
    private static String measure(int source, AudioDeviceInfo dev) {
        int rate = 16000;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return "nie działa";
        AudioRecord rec = null;
        try {
            rec = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) return "nie da się otworzyć";
            if (dev != null && Build.VERSION.SDK_INT >= 23) rec.setPreferredDevice(dev);
            rec.startRecording();
            short[] buf = new short[min];
            long end = System.currentTimeMillis() + 3000;
            double sum = 0;
            long n = 0;
            int peak = 0;
            while (System.currentTimeMillis() < end) {
                int k = rec.read(buf, 0, buf.length);
                for (int i = 0; i < k; i++) {
                    int v = Math.abs(buf[i]);
                    sum += (double) v * v;
                    if (v > peak) peak = v;
                }
                if (k > 0) n += k;
            }
            int rms = n == 0 ? 0 : (int) Math.sqrt(sum / n);
            return String.format(Locale.US, "średnio %d, szczyt %d", rms, peak);
        } catch (Exception e) {
            return "błąd: " + e.getMessage();
        } finally {
            try {
                if (rec != null) {
                    rec.stop();
                    rec.release();
                }
            } catch (Exception ignored) {
            }
        }
    }
}
