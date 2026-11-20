package pl.kuba.radiocentrum.radio;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Stale działa w radiu: czeka na telefon (Bluetooth) i próbuje Wi-Fi jako zapas. */
public class RadioService extends Service implements Link.Handler {

    static final UUID APP_UUID = UUID.fromString("7a3c2e10-5b1f-4c8e-9d2a-6f4b8e1c3a51");
    static final int PORT = 47800;
    static final int BEACON_PORT = 47801;

    static volatile RadioService instance;
    static volatile String btInfo = "Bluetooth: uruchamianie…";
    static volatile String wifiInfo = "Wi-Fi: nie używane";

    private volatile boolean running;
    private volatile Link link;
    private volatile BluetoothServerSocket btServer;
    private final ExecutorService sendExec = Executors.newSingleThreadExecutor();
    private Handler main;

    static void start(Context c) {
        Intent i = new Intent(c, RadioService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    /** Wyślij polecenie do telefonu. false = brak połączenia. */
    static boolean sendToPhone(final JSONObject m) {
        RadioService s = instance;
        if (s == null) return false;
        final Link l = s.link;
        if (l == null || l.isClosed()) return false;
        s.sendExec.execute(() -> l.send(m));
        return true;
    }

    /** Po zapisaniu kodu: spróbuj połączyć od razu, nie czekaj. */
    static void kick() {
        final RadioService s = instance;
        if (s == null) return;
        wifiInfo = "Wi-Fi: łączę…";
        new Thread(() -> {
            String gw = s.gateway();
            if (gw != null && !s.tryTcp(gw)) {
                wifiInfo = "Wi-Fi: kod zapisany – czekam na telefon w tej samej sieci";
            }
        }, "rc-kick").start();
    }

    static boolean connected() {
        RadioService s = instance;
        return s != null && s.isConnected();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        running = true;
        main = new Handler(Looper.getMainLooper());
        startForeground(1, buildNotification());
        Store.load(this);
        Mowa.init(this);
        new Thread(this::btLoop, "rc-bt").start();
        new Thread(this::wifiLoop, "rc-wifi").start();
        new Thread(this::beaconLoop, "rc-beacon").start();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        Link l = link;
        if (l != null) l.close();
        try {
            BluetoothServerSocket s = btServer;
            if (s != null) s.close();
        } catch (IOException ignored) {
        }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("rc", "Radio Centrum",
                    NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, "rc");
        } else {
            b = new Notification.Builder(this);
        }
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
        return b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Radio Centrum")
                .setContentText("Odbiera powiadomienia z telefonu")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    boolean isConnected() {
        Link l = link;
        return l != null && !l.isClosed();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ------------------------------------------------------------------ Bluetooth (telefon łączy się do radia)

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void btLoop() {
        while (running) {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null) {
                btInfo = "Bluetooth: Android radia nie udostępnia Bluetootha – użyj Wi-Fi";
                return;
            }
            if (!ad.isEnabled()) {
                btInfo = "Bluetooth: wyłączony w ustawieniach Androida radia";
                sleep(5000);
                continue;
            }
            BluetoothServerSocket ss = null;
            try {
                ss = ad.listenUsingInsecureRfcommWithServiceRecord("RadioCentrum", APP_UUID);
                btServer = ss;
                btInfo = "Bluetooth: gotowe – nazwa radia „" + ad.getName() + "”";
                while (running) {
                    BluetoothSocket s = ss.accept();
                    startLink(new Link(s, s.getInputStream(), s.getOutputStream(), "Bluetooth"));
                }
            } catch (IOException e) {
                btInfo = "Bluetooth: ponawiam…";
                sleep(3000);
            } finally {
                try {
                    if (ss != null) ss.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ Wi-Fi (radio na hotspocie telefonu)

    /** Radio na hotspocie telefonu: telefon jest bramą sieci. */
    private void wifiLoop() {
        while (running) {
            if (!isConnected()) {
                String gw = gateway();
                if (Prefs.code(this).isEmpty()) {
                    wifiInfo = "Wi-Fi: nie wpisano kodu (⚙ Ustawienia)";
                } else if (gw == null) {
                    wifiInfo = "Wi-Fi: radio nie jest połączone z Wi-Fi";
                } else if (!tryTcp(gw)) {
                    wifiInfo = "Wi-Fi: kod zapisany – czekam na telefon w tej samej sieci (router w aucie lub hotspot)";
                }
            }
            sleep(8000);
        }
    }

    /** Router w aucie: telefon ogłasza się w sieci, radio łączy się pod jego adres. */
    private void beaconLoop() {
        WifiManager.MulticastLock lock = null;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("rc");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception ignored) {
        }
        while (running) {
            try (DatagramSocket ds = new DatagramSocket(BEACON_PORT)) {
                ds.setBroadcast(true);
                byte[] buf = new byte[256];
                while (running) {
                    DatagramPacket pk = new DatagramPacket(buf, buf.length);
                    ds.receive(pk);
                    String s = new String(pk.getData(), 0, pk.getLength(), java.nio.charset.StandardCharsets.UTF_8);
                    if (s.startsWith("RADIOCENTRUM") && !isConnected() && !Prefs.code(this).isEmpty()) {
                        tryTcp(pk.getAddress().getHostAddress());
                    }
                }
            } catch (Exception e) {
                sleep(5000);
            }
        }
        try {
            if (lock != null) lock.release();
        } catch (Exception ignored) {
        }
    }

    private synchronized boolean tryTcp(String host) {
        if (isConnected()) return true;
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, PORT), 3000);
            s.setSoTimeout(70000);
            Link l = new Link(s, s.getInputStream(), s.getOutputStream(), "Wi-Fi");
            l.send(J.obj("t", "auth", "code", Prefs.code(this)));
            wifiInfo = "Wi-Fi: połączono z telefonem " + host;
            startLink(l);
            return true;
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            return false;
        }
    }

    private String gateway() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return null;
            DhcpInfo d = wm.getDhcpInfo();
            if (d == null || d.gateway == 0) return null;
            int g = d.gateway;
            return (g & 0xff) + "." + ((g >> 8) & 0xff) + "." + ((g >> 16) & 0xff) + "." + ((g >> 24) & 0xff);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ obsługa połączenia

    private void startLink(final Link l) {
        Link old;
        synchronized (this) {
            old = link;
            link = l;
        }
        if (old != null) old.close();
        main.post(() -> {
            Store.linkKind = l.kind;
            Store.changed();
        });
        new Thread(() -> l.runReader(this), "rc-read").start();
    }

    @Override
    public void onClosed(Link l) {
        boolean current;
        synchronized (this) {
            current = link == l;
            if (current) link = null;
        }
        if (current) {
            main.post(() -> {
                Store.linkKind = null;
                Popup.hideCall();
                Store.changed();
            });
        }
    }

    @Override
    public void onMessage(Link l, final JSONObject m) {
        main.post(() -> handle(m));
    }

    private void handle(JSONObject m) {
        switch (m.optString("t")) {
            case "hello":
                Store.phoneName = m.optString("name");
                break;
            case "contacts":
                Store.setContacts(this, m.optJSONArray("list"));
                break;
            case "calls":
                Store.setCalls(this, m.optJSONArray("list"));
                break;
            case "notif":
                if (Store.putNotif(m) && !m.optBoolean("old")) {
                    Popup.showNotif(this, m);
                    if (Prefs.autoRead(this)) Mowa.speak(Popup.speech(m));
                }
                break;
            case "toast":
                Toast.makeText(this, m.optString("text"), Toast.LENGTH_LONG).show();
                return;
            case "notif_rm":
                Store.removeNotif(m.optString("key"));
                break;
            case "call":
                Popup.showCall(this, m);
                break;
            case "bad_code":
                wifiInfo = "Wi-Fi: zły kod – przepisz kod z aplikacji w telefonie";
                break;
            default:
                return;
        }
        Store.changed();
    }
}
