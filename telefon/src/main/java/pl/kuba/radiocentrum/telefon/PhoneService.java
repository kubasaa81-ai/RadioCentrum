package pl.kuba.radiocentrum.telefon;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.RemoteInput;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Działa w tle (jako usługa dostępu do powiadomień) i wysyła do radia:
 * powiadomienia, stan rozmowy, historię połączeń i kontakty.
 * Z radia przyjmuje polecenia: zadzwoń, odbierz, odrzuć, wyślij ponownie.
 */
public class PhoneService extends NotificationListenerService implements Link.Handler {

    public static final UUID APP_UUID = UUID.fromString("7a3c2e10-5b1f-4c8e-9d2a-6f4b8e1c3a51");
    public static final int PORT = 47800;
    public static final int BEACON_PORT = 47801;

    public static volatile String status = "Usługa nie działa";
    public static volatile PhoneService instance;

    private volatile boolean running;
    private volatile Link link;
    private volatile ServerSocket server;
    private final ExecutorService sendExec = Executors.newSingleThreadExecutor();
    private Handler main;
    private BroadcastReceiver phoneReceiver;
    private final Map<String, Notification.Action> replyActions = new ConcurrentHashMap<>();
    private TextToSpeech tts;
    private volatile boolean ttsReady;

    // ------------------------------------------------------------------ cykl życia

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        running = true;
        main = new Handler(Looper.getMainLooper());
        status = "Szukam radia…";
        new Thread(this::bluetoothLoop, "rc-bt").start();
        new Thread(this::wifiLoop, "rc-wifi").start();
        new Thread(this::beaconLoop, "rc-beacon").start();
        registerPhoneReceiver();
        try {
            tts = new TextToSpeech(this, st -> {
                if (st == TextToSpeech.SUCCESS && tts != null) {
                    tts.setLanguage(new Locale("pl", "PL"));
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String id) {
                        }

                        @Override
                        public void onDone(String id) {
                            main.postDelayed(PhoneService.this::releaseAudio, 600);
                        }

                        @Override
                        @SuppressWarnings("deprecation")
                        public void onError(String id) {
                            main.post(PhoneService.this::releaseAudio);
                        }
                    });
                    ttsReady = true;
                }
            });
        } catch (Exception ignored) {
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                send(J.obj("t", "ping"));
                main.postDelayed(this, 20000);
            }
        }, 20000);
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        if (main != null) main.removeCallbacksAndMessages(null);
        try {
            if (phoneReceiver != null) unregisterReceiver(phoneReceiver);
        } catch (Exception ignored) {
        }
        Link l = link;
        if (l != null) l.close();
        try {
            ServerSocket s = server;
            if (s != null) s.close();
        } catch (IOException ignored) {
        }
        try {
            if (tts != null) tts.shutdown();
        } catch (Exception ignored) {
        }
        status = "Usługa zatrzymana";
        super.onDestroy();
    }

    @Override
    public void onListenerDisconnected() {
        try {
            requestRebind(new ComponentName(this, PhoneService.class));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ publiczne (dla ekranu)

    public boolean isConnected() {
        Link l = link;
        return l != null && !l.isClosed();
    }

    /** Zerwij połączenie – pętla połączy się od nowa (np. z nowo wybranym radiem). */
    public void reconnect() {
        Link l = link;
        if (l != null) l.close();
    }

    public void resendAll() {
        final Link l = link;
        if (l != null) sendExec.execute(() -> sendAll(l));
    }

    // ------------------------------------------------------------------ połączenie

    private void send(final JSONObject m) {
        sendExec.execute(() -> {
            Link l = link;
            if (l != null) l.send(m);
        });
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private boolean granted(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void bluetoothLoop() {
        while (running) {
            if (!isConnected()) {
                try {
                    tryBluetooth();
                } catch (Throwable ignored) {
                }
            }
            sleep(isConnected() ? 3000 : 10000);
        }
    }

    @SuppressLint("MissingPermission")
    private void tryBluetooth() throws IOException {
        String mac = Prefs.radioMac(this);
        if (mac == null) {
            status = "Nie wybrano radia (krok 4)";
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            status = "Brak uprawnienia Bluetooth (krok 1)";
            return;
        }
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) {
            status = "Bluetooth w telefonie jest wyłączony";
            return;
        }
        BluetoothDevice d = ad.getRemoteDevice(mac);
        BluetoothSocket s = d.createInsecureRfcommSocketToServiceRecord(APP_UUID);
        try {
            s.connect();
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            if (!isConnected()) status = "Szukam radia… (radio włączone, aplikacja na radiu uruchomiona?)";
            return;
        }
        startLink(new Link(s, s.getInputStream(), s.getOutputStream(), "Bluetooth"));
    }

    /** Zapasowo: radio podłączone do hotspotu telefonu łączy się tutaj po Wi-Fi. */
    private void wifiLoop() {
        while (running) {
            try (ServerSocket ss = new ServerSocket(PORT)) {
                server = ss;
                while (running) {
                    final Socket c = ss.accept();
                    new Thread(() -> handleTcp(c), "rc-tcp").start();
                }
            } catch (IOException e) {
                sleep(5000);
            }
        }
    }

    /** W sieci Wi-Fi (np. router w aucie) co kilka sekund ogłasza: „tu jest telefon”. Radio samo się wtedy łączy. */
    private void beaconLoop() {
        byte[] msg = ("RADIOCENTRUM " + PORT).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        while (running) {
            if (!isConnected()) {
                try (DatagramSocket ds = new DatagramSocket()) {
                    ds.setBroadcast(true);
                    ds.send(new DatagramPacket(msg, msg.length, InetAddress.getByName("255.255.255.255"), BEACON_PORT));
                } catch (Exception ignored) {
                }
            }
            sleep(4000);
        }
    }

    private void handleTcp(Socket c) {
        try {
            c.setSoTimeout(10000);
            Link l = new Link(c, c.getInputStream(), c.getOutputStream(), "Wi-Fi");
            String first = l.readLine();
            JSONObject m = first == null ? null : new JSONObject(first);
            if (m == null || !"auth".equals(m.optString("t"))
                    || !Prefs.code(this).equals(m.optString("code"))) {
                l.send(J.obj("t", "bad_code"));
                l.close();
                return;
            }
            c.setSoTimeout(0);
            startLink(l);
        } catch (Exception e) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void startLink(final Link l) {
        synchronized (this) {
            if (isConnected()) {
                l.close();
                return;
            }
            link = l;
        }
        status = "Połączono z radiem (" + l.kind + ")";
        new Thread(() -> l.runReader(this), "rc-read").start();
        sendExec.execute(() -> {
            l.send(J.obj("t", "hello", "name", deviceName()));
            sendAll(l);
        });
    }

    @Override
    public void onClosed(Link l) {
        synchronized (this) {
            if (link == l) link = null;
        }
        if (running && !isConnected()) status = "Rozłączono – szukam radia…";
    }

    @Override
    public void onMessage(final Link l, JSONObject m) {
        switch (m.optString("t")) {
            case "dial":
                dial(m.optString("num"));
                break;
            case "sync":
                sendExec.execute(() -> sendAll(l));
                break;
            case "answer":
                main.post(this::answer);
                break;
            case "reject":
                main.post(this::endCall);
                break;
            case "reply":
                final String key = m.optString("key");
                final String text = m.optString("text");
                main.post(() -> reply(key, text));
                break;
            case "sms":
                final String smsNum = m.optString("num");
                final String smsText = m.optString("text");
                sendExec.execute(() -> sendSms(smsNum, smsText));
                break;
            case "dictate":
                main.post(this::startDictation);
                break;
            case "speak":
                final String say = m.optString("text");
                final boolean viaCall = !"media".equals(m.optString("route"));
                main.post(() -> speak(say, viaCall));
                break;
            default:
                break;
        }
    }

    private String deviceName() {
        try {
            String n = Settings.Global.getString(getContentResolver(), "device_name");
            if (n != null && !n.isEmpty()) return n;
        } catch (Exception ignored) {
        }
        return Build.MODEL;
    }

    // ------------------------------------------------------------------ polecenia z radia

    @SuppressLint("MissingPermission")
    private void dial(final String num) {
        if (num == null || num.isEmpty() || !granted(Manifest.permission.CALL_PHONE)) return;
        main.post(() -> {
            try {
                TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                tm.placeCall(Uri.fromParts("tel", num, null), new Bundle());
            } catch (Exception ignored) {
            }
        });
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void answer() {
        try {
            if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) return;
            TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            tm.acceptRingingCall();
        } catch (Exception ignored) {
        }
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void endCall() {
        try {
            if (Build.VERSION.SDK_INT < 28 || !granted(Manifest.permission.ANSWER_PHONE_CALLS)) return;
            TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            tm.endCall();
        } catch (Exception ignored) {
        }
    }

    /** Odpowiedź przez przycisk „Odpowiedz” z powiadomienia (SMS, WhatsApp, Messenger…). */
    private void reply(String key, String text) {
        Notification.Action a = replyActions.get(key);
        if (a == null || text == null || text.trim().isEmpty()) {
            send(J.obj("t", "toast", "text", "Na tę wiadomość nie da się odpowiedzieć z radia"));
            return;
        }
        try {
            RemoteInput[] ris = a.getRemoteInputs();
            Intent i = new Intent();
            i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            Bundle b = new Bundle();
            for (RemoteInput ri : ris) b.putCharSequence(ri.getResultKey(), text);
            RemoteInput.addResultsToIntent(ris, i, b);
            a.actionIntent.send(this, 0, i);
            send(J.obj("t", "toast", "text", "Wysłano odpowiedź ✅"));
        } catch (Exception e) {
            send(J.obj("t", "toast", "text", "Nie udało się wysłać odpowiedzi"));
        }
    }

    /** Radio prosi o dyktowanie – słucha telefon (radio nie ma rozpoznawania mowy). */
    private void startDictation() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            send(J.obj("t", "dictated", "err",
                    "W aplikacji na telefonie zrób krok 5 (wyświetlanie nad aplikacjami) – wtedy dyktowanie zadziała"));
            return;
        }
        try {
            Intent i = new Intent(this, DictateActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(i);
            send(J.obj("t", "toast", "text", "🎤 Mów – tekst pojawi się na bieżąco. Koniec: 3 s ciszy"));
        } catch (Exception e) {
            send(J.obj("t", "dictated", "err", "Nie udało się włączyć mikrofonu w telefonie"));
        }
    }

    void sendLiveDictation(String text) {
        send(J.obj("t", "dictating", "text", text));
    }

    void deliverDictation(String text, String err) {
        send(J.obj("t", "dictated", "text", text, "err", err));
    }

    /** Nowy SMS wpisany lub podyktowany na radiu. */
    @SuppressWarnings("deprecation")
    private void sendSms(String num, String text) {
        Link l = link;
        if (num == null || num.isEmpty() || text == null || text.trim().isEmpty()) return;
        if (!granted(Manifest.permission.SEND_SMS)) {
            if (l != null) l.send(J.obj("t", "toast", "text", "Telefon nie ma uprawnienia do SMS – w aplikacji na telefonie kliknij krok 1"));
            return;
        }
        try {
            SmsManager sm = Build.VERSION.SDK_INT >= 31 ? getSystemService(SmsManager.class) : SmsManager.getDefault();
            sm.sendMultipartTextMessage(num, null, sm.divideMessage(text), null, null);
            String who = lookupName(num);
            if (l != null) l.send(J.obj("t", "toast", "text", "SMS wysłany ✅ do " + (who == null ? num : who)));
        } catch (Exception e) {
            if (l != null) l.send(J.obj("t", "toast", "text", "Nie udało się wysłać SMS-a"));
        }
    }

    private boolean audioRouted;

    /**
     * Czytanie na głos przez kanał rozmowy Bluetooth (HFP) – radio zawsze go odtwarza,
     * nawet gdy gra FM. Gdy nie ma połączenia z zestawem, czyta głośnikiem telefonu.
     */
    private void speak(final String text, boolean viaCall) {
        if (text == null || text.isEmpty()) return;
        if (!ttsReady || tts == null) {
            send(J.obj("t", "toast", "text", "Syntezator mowy w telefonie nie jest gotowy"));
            return;
        }
        boolean routed = viaCall && CarAudio.route(this);
        audioRouted = routed;
        tts.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(routed ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        if (viaCall && !routed) {
            send(J.obj("t", "toast", "text", "Nie udało się otworzyć kanału rozmowy – czytam jako muzykę Bluetooth"));
        }
        // chwila na przełączenie radia na kanał rozmowy, żeby nie uciąć początku
        main.postDelayed(() -> tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rc"), routed ? 1800 : 0);
    }

    private void releaseAudio() {
        if (!audioRouted) return;
        audioRouted = false;
        CarAudio.release(this);
    }

    private static Notification.Action findReply(Notification n) {
        List<Notification.Action> all = new ArrayList<>();
        if (n.actions != null) all.addAll(Arrays.asList(n.actions));
        try {
            all.addAll(new Notification.WearableExtender(n).getActions());
        } catch (Exception ignored) {
        }
        for (Notification.Action a : all) {
            RemoteInput[] r = a.getRemoteInputs();
            if (r == null) continue;
            for (RemoteInput ri : r) {
                if (ri.getAllowFreeFormInput()) return a;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ dane do wysłania

    private void sendAll(Link l) {
        l.send(J.obj("t", "contacts", "list", contactsJson()));
        l.send(J.obj("t", "calls", "list", callsJson()));
        try {
            StatusBarNotification[] act = getActiveNotifications();
            if (act != null) {
                for (StatusBarNotification sbn : act) {
                    JSONObject j = notifJson(sbn, true);
                    if (j != null) l.send(j);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private JSONArray contactsJson() {
        JSONArray arr = new JSONArray();
        if (!granted(Manifest.permission.READ_CONTACTS)) return arr;
        LinkedHashMap<Long, JSONObject> map = new LinkedHashMap<>();
        String[] proj = {
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER};
        try (Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                proj, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String name = c.getString(1);
                    String num = c.getString(2);
                    if (num == null) continue;
                    num = num.replaceAll("[^0-9+*#]", "");
                    if (num.isEmpty()) continue;
                    JSONObject o = map.get(id);
                    if (o == null) {
                        o = J.obj("n", name == null ? num : name, "p", new JSONArray());
                        map.put(id, o);
                    }
                    JSONArray p = o.optJSONArray("p");
                    if (p == null) continue;
                    boolean dup = false;
                    for (int i = 0; i < p.length(); i++) {
                        if (J.same(p.optString(i), num)) dup = true;
                    }
                    if (!dup) p.put(num);
                }
            }
        } catch (Exception ignored) {
        }
        for (JSONObject o : map.values()) arr.put(o);
        return arr;
    }

    private JSONArray callsJson() {
        JSONArray arr = new JSONArray();
        if (!granted(Manifest.permission.READ_CALL_LOG)) return arr;
        String[] proj = {CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
                CallLog.Calls.DATE, CallLog.Calls.DURATION};
        try (Cursor c = getContentResolver().query(CallLog.Calls.CONTENT_URI, proj, null, null,
                CallLog.Calls.DATE + " DESC")) {
            int n = 0;
            if (c != null) {
                while (c.moveToNext() && n++ < 200) {
                    arr.put(J.obj("p", c.getString(0), "n", c.getString(1), "type", c.getInt(2),
                            "d", c.getLong(3), "dur", c.getLong(4)));
                }
            }
        } catch (Exception ignored) {
        }
        return arr;
    }

    private String lookupName(String num) {
        if (num == null || num.isEmpty() || !granted(Manifest.permission.READ_CONTACTS)) return null;
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num));
        try (Cursor c = getContentResolver().query(uri,
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ powiadomienia

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!isConnected()) return;
        JSONObject j = notifJson(sbn, false);
        if (j != null) send(j);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        replyActions.remove(sbn.getKey());
        if (!isConnected()) return;
        send(J.obj("t", "notif_rm", "key", sbn.getKey()));
    }

    private JSONObject notifJson(StatusBarNotification sbn, boolean old) {
        try {
            String pkg = sbn.getPackageName();
            if (pkg.equals(getPackageName())) return null;
            if (sbn.isOngoing()) return null;
            Notification n = sbn.getNotification();
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return null;
            String cat = n.category;
            if (Notification.CATEGORY_CALL.equals(cat) || Notification.CATEGORY_PROGRESS.equals(cat)
                    || Notification.CATEGORY_SERVICE.equals(cat) || Notification.CATEGORY_TRANSPORT.equals(cat)
                    || Notification.CATEGORY_SYSTEM.equals(cat)) return null;
            Bundle ex = n.extras;
            if (ex == null) return null;
            CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (text == null) text = ex.getCharSequence(Notification.EXTRA_TEXT);
            if (title == null && text == null) return null;
            Notification.Action ra = findReply(n);
            if (ra != null) replyActions.put(sbn.getKey(), ra);
            return J.obj("t", "notif",
                    "reply", ra != null,
                    "key", sbn.getKey(),
                    "pkg", pkg,
                    "app", appLabel(pkg),
                    "title", title == null ? "" : title.toString(),
                    "text", text == null ? "" : text.toString(),
                    "time", sbn.getPostTime(),
                    "old", old);
        } catch (Exception e) {
            return null;
        }
    }

    private String appLabel(String pkg) {
        if (pkg.contains("messaging") || pkg.contains(".mms")) return "SMS";
        if (pkg.equals("com.whatsapp")) return "WhatsApp";
        if (pkg.equals("com.facebook.orca")) return "Messenger";
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    // ------------------------------------------------------------------ rozmowy

    @SuppressWarnings("deprecation")
    private void registerPhoneReceiver() {
        phoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                final String st = i.getStringExtra(TelephonyManager.EXTRA_STATE);
                final String num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                sendExec.execute(() -> {
                    Link l = link;
                    if (l == null) return;
                    String state = TelephonyManager.EXTRA_STATE_RINGING.equals(st) ? "ringing"
                            : TelephonyManager.EXTRA_STATE_OFFHOOK.equals(st) ? "offhook" : "idle";
                    l.send(J.obj("t", "call", "state", state, "num", num, "name", lookupName(num)));
                });
                if (TelephonyManager.EXTRA_STATE_IDLE.equals(st)) {
                    // po rozmowie odśwież historię połączeń w radiu
                    main.postDelayed(() -> sendExec.execute(() -> {
                        Link l2 = link;
                        if (l2 != null) l2.send(J.obj("t", "calls", "list", callsJson()));
                    }), 2500);
                }
            }
        };
        IntentFilter f = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(phoneReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(phoneReceiver, f);
        }
    }
}
