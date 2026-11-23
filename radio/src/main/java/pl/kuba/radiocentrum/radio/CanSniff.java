package pl.kuba.radiocentrum.radio;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Podsłuch komunikatów modułu CAN (do znalezienia paliwa, zasięgu itp.).
 * Przez 2 minuty zapisuje wszystko do Pobrane/CAN-podsluch.txt.
 */
final class CanSniff {
    private CanSniff() {
    }

    static final String[] ACTIONS = {
            "canbus.intent.action.msg", "canbus.intent.action.canbus.info", "canbus.intent.action.send.data",
            "canbus.intent.action.send.data.two", "canbus.intent.actionfamily.msg", "canbus.back.intent.action.msg",
            "canbus.intent.action.cmd", "canbus.intent.action.change", "canbus.intent.action.connect",
            "action.canbus.send.outtemp", "com.xygala.canbus.airconstatus",
            "com.acloud.intent.android.XY.ANDROID.CANBUSDISPINFO1",
            "com.acloud.intent.android.XY.ANDROID.CANBUSDISPINFO2",
            "com.acloud.intent.android.XY.ANDROID.CANBUSDISPINFO3",
            "action.mcukey.rx",
    };

    private static boolean running;

    static void show(final Activity a) {
        if (running) {
            android.widget.Toast.makeText(a, "Podsłuch już trwa", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Okno o = new Okno(a, "🔎 Podsłuch CAN (2 minuty)",
                "Silnik ma pracować. W tym czasie otwórz fabryczne „car information” (Car Set), "
                        + "możesz też włączyć i wyłączyć światła. Wszystko zapisze się w Pobranych.");
        final TextView out = o.message("Naciśnij START.", 16);
        o.row(o.btn("▶ START", Ui.GREEN, false, () -> start(a, out)));
        o.cancel("✖ Zamknij");
        o.show();
    }

    private static void start(final Activity a, final TextView out) {
        if (running) return;
        running = true;
        final Context app = a.getApplicationContext();
        final File f = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "CAN-podsluch-" + new SimpleDateFormat("HHmmss", Locale.ROOT).format(new Date()) + ".txt");
        final Map<String, Integer> count = new HashMap<>();
        final StringBuilder buf = new StringBuilder();
        final SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
        final BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String act = String.valueOf(i.getAction());
                int n = count.containsKey(act) ? count.get(act) + 1 : 1;
                count.put(act, n);
                StringBuilder sb = new StringBuilder(tf.format(new Date())).append(" ").append(act);
                Bundle b = i.getExtras();
                if (b != null) {
                    for (String k : b.keySet()) {
                        Object v;
                        try {
                            v = b.get(k);
                        } catch (Exception e) {
                            v = "?";
                        }
                        sb.append(" | ").append(k).append("=").append(fmt(v));
                    }
                }
                synchronized (buf) {
                    buf.append(sb).append('\n');
                }
            }
        };
        IntentFilter fl = new IntentFilter();
        for (String s : ACTIONS) fl.addAction(s);
        app.registerReceiver(r, fl);
        final Handler h = new Handler(Looper.getMainLooper());
        final long end = System.currentTimeMillis() + 120000;
        h.post(new Runnable() {
            @Override
            public void run() {
                flush(f, buf);
                long left = (end - System.currentTimeMillis()) / 1000;
                StringBuilder s = new StringBuilder();
                int total = 0;
                for (Map.Entry<String, Integer> e : count.entrySet()) {
                    s.append("• ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
                    total += e.getValue();
                }
                if (left > 0) {
                    out.setText("⏳ Zostało " + left + " s · odebrano " + total + "\n\n" + s);
                    h.postDelayed(this, 1000);
                } else {
                    try {
                        app.unregisterReceiver(r);
                    } catch (Exception ignored) {
                    }
                    running = false;
                    out.setText("✅ Koniec. Odebrano " + total + " komunikatów.\nPlik: Pobrane/" + f.getName()
                            + "\n\nNa telefonie: „📥 Pobierz pliki z radia” i wyślij ten plik w czacie.\n\n" + s);
                }
            }
        });
    }

    private static void flush(File f, StringBuilder buf) {
        String s;
        synchronized (buf) {
            if (buf.length() == 0) return;
            s = buf.toString();
            buf.setLength(0);
        }
        try (FileOutputStream os = new FileOutputStream(f, true)) {
            os.write(s.getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
    }

    private static String fmt(Object v) {
        if (v == null) return "null";
        if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            StringBuilder s = new StringBuilder("[");
            for (int i = 0; i < b.length; i++) {
                if (i > 0) s.append(' ');
                s.append(String.format("%02X", b[i] & 0xFF));
            }
            return s.append("]").toString();
        }
        if (v instanceof int[]) return java.util.Arrays.toString((int[]) v);
        if (v instanceof long[]) return java.util.Arrays.toString((long[]) v);
        if (v instanceof String[]) return java.util.Arrays.toString((String[]) v);
        if (v instanceof char[]) return new String((char[]) v);
        return v.toString();
    }
}
