package pl.kuba.radiocentrum.radio;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Zbiera wszystko, co radio udostępnia aplikacjom – do pliku tekstowego. */
final class Diagnostyka {
    private Diagnostyka() {
    }

    static String build(Context c) {
        StringBuilder sb = new StringBuilder(200000);
        sb.append("=== RADIO CENTRUM – DIAGNOSTYKA RADIA ===\n");
        sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n\n");

        sec(sb, "SYSTEM (Build)");
        sb.append("MODEL=").append(Build.MODEL).append('\n')
                .append("BRAND=").append(Build.BRAND).append('\n')
                .append("MANUFACTURER=").append(Build.MANUFACTURER).append('\n')
                .append("DEVICE=").append(Build.DEVICE).append('\n')
                .append("PRODUCT=").append(Build.PRODUCT).append('\n')
                .append("BOARD=").append(Build.BOARD).append('\n')
                .append("HARDWARE=").append(Build.HARDWARE).append('\n')
                .append("DISPLAY=").append(Build.DISPLAY).append('\n')
                .append("FINGERPRINT=").append(Build.FINGERPRINT).append('\n')
                .append("SDK_INT=").append(Build.VERSION.SDK_INT).append('\n')
                .append("RELEASE=").append(Build.VERSION.RELEASE).append('\n');

        sec(sb, "PROCESOR (/proc/cpuinfo, /proc/version)");
        file(sb, "/proc/version", 50);
        file(sb, "/proc/cpuinfo", 80);

        sec(sb, "PARAMETRY SYSTEMU (getprop)");
        cmd(sb, "getprop");

        ContentResolver cr = c.getContentResolver();
        sec(sb, "USTAWIENIA: System");
        table(sb, cr, Settings.System.CONTENT_URI);
        sec(sb, "USTAWIENIA: Global");
        table(sb, cr, Settings.Global.CONTENT_URI);
        sec(sb, "USTAWIENIA: Secure");
        table(sb, cr, Settings.Secure.CONTENT_URI);

        sec(sb, "DŹWIĘK");
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            sb.append("mode=").append(am.getMode()).append('\n');
            sb.append("micMute=").append(am.isMicrophoneMute()).append('\n');
            sb.append("btScoAvailableOffCall=").append(am.isBluetoothScoAvailableOffCall()).append('\n');
            int[] streams = {AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_RING,
                    AudioManager.STREAM_MUSIC, AudioManager.STREAM_ALARM, AudioManager.STREAM_NOTIFICATION};
            String[] names = {"VOICE_CALL", "SYSTEM", "RING", "MUSIC", "ALARM", "NOTIFICATION"};
            for (int i = 0; i < streams.length; i++) {
                sb.append("volume ").append(names[i]).append('=').append(am.getStreamVolume(streams[i]))
                        .append('/').append(am.getStreamMaxVolume(streams[i])).append('\n');
            }
            if (Build.VERSION.SDK_INT >= 23) {
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_ALL)) {
                    sb.append(d.isSource() ? "WEJŚCIE " : "WYJŚCIE ").append("type=").append(d.getType())
                            .append(" name=").append(d.getProductName());
                    if (Build.VERSION.SDK_INT >= 28) sb.append(" addr=").append(d.getAddress());
                    sb.append('\n');
                }
            }
            for (String k : new String[]{"mic_gain", "MicGain", "bt_mic_gain", "BT_MIC_GAIN", "mic_volume",
                    "noise_suppression", "ec_supported", "bt_headset_nrec", "audio_source", "mic_select"}) {
                String v = am.getParameters(k);
                if (v != null && !v.isEmpty()) sb.append("param ").append(k).append(" -> ").append(v).append('\n');
            }
        } catch (Exception e) {
            sb.append("błąd: ").append(e).append('\n');
        }

        sec(sb, "BLUETOOTH (Android radia)");
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null) sb.append("brak\n");
            else {
                sb.append("name=").append(ad.getName()).append(" enabled=").append(ad.isEnabled()).append('\n');
                for (BluetoothDevice d : ad.getBondedDevices()) {
                    sb.append("sparowane: ").append(d.getName()).append(" ").append(d.getAddress())
                            .append(" class=").append(d.getBluetoothClass()).append('\n');
                }
            }
        } catch (Exception e) {
            sb.append("błąd: ").append(e).append('\n');
        }

        sec(sb, "SIEĆ (interfejsy)");
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                sb.append(ni.getName()).append(" up=").append(ni.isUp());
                for (InetAddress a : Collections.list(ni.getInetAddresses())) sb.append(' ').append(a.getHostAddress());
                sb.append('\n');
            }
        } catch (Exception e) {
            sb.append("błąd: ").append(e).append('\n');
        }

        sec(sb, "USB – podłączone urządzenia (szukamy modemu 4G)");
        File usb = new File("/sys/bus/usb/devices");
        String[] devs = usb.list();
        if (devs == null) sb.append("brak dostępu do /sys/bus/usb/devices\n");
        else {
            java.util.Arrays.sort(devs);
            for (String d : devs) {
                String base = "/sys/bus/usb/devices/" + d + "/";
                String vid = one(base + "idVendor"), pid = one(base + "idProduct");
                if (vid == null && pid == null) continue;
                sb.append(d).append("  VID:PID=").append(vid).append(':').append(pid)
                        .append("  producent=").append(one(base + "manufacturer"))
                        .append("  produkt=").append(one(base + "product"))
                        .append("  klasa=").append(one(base + "bDeviceClass")).append('\n');
            }
        }
        sb.append("\nInterfejsy USB (sterowniki):\n");
        if (devs != null) {
            for (String d : devs) {
                if (!d.contains(":")) continue;
                String base = "/sys/bus/usb/devices/" + d + "/";
                String drv = null;
                try {
                    drv = new File(base + "driver").getCanonicalFile().getName();
                } catch (Exception ignored) {
                }
                sb.append(d).append("  klasa=").append(one(base + "bInterfaceClass"))
                        .append(" pod=").append(one(base + "bInterfaceSubClass"))
                        .append(" protokół=").append(one(base + "bInterfaceProtocol"))
                        .append("  sterownik=").append(drv).append('\n');
            }
        }
        sec(sb, "USB – sieć i obsługiwane modemy");
        file(sb, "/proc/net/dev", 40);
        file(sb, "/system/etc/dongle_supported_list.txt", 400);
        file(sb, "/system/etc/dongleselect.conf", 200);
        file(sb, "/system/etc/custom.conf", 200);
        file(sb, "/system/etc/factory.ini", 200);
        file(sb, "/system/etc/xyautoverno.ini", 100);
        String[] ms = new File("/system/etc/usb_modeswitch.d").list();
        sb.append("\n[katalog /system/etc/usb_modeswitch.d] ");
        if (ms == null) sb.append("brak dostępu\n");
        else {
            java.util.Arrays.sort(ms);
            for (String m : ms) sb.append(m).append("  ");
            sb.append('\n');
        }
        String[] xy = new File("/system/etc/xy").list();
        sb.append("\n[katalog /system/etc/xy] ");
        if (xy == null) sb.append("brak dostępu\n");
        else {
            for (String m : xy) sb.append(m).append("  ");
            sb.append('\n');
        }
        sb.append("\nModuły jądra (/proc/modules):\n");
        file(sb, "/proc/modules", 200);

        sec(sb, "APLIKACJE (wszystkie)");
        PackageManager pm = c.getPackageManager();
        List<PackageInfo> pkgs = pm.getInstalledPackages(0);
        Collections.sort(pkgs, (a, b) -> a.packageName.compareTo(b.packageName));
        for (PackageInfo p : pkgs) {
            ApplicationInfo ai = p.applicationInfo;
            boolean sys = ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            sb.append(sys ? "[sys] " : "[usr] ").append(p.packageName).append("  „")
                    .append(ai == null ? "" : pm.getApplicationLabel(ai)).append("”  v")
                    .append(p.versionName).append('\n');
        }

        sec(sb, "APLIKACJE PRODUCENTA – ekrany i usługi (tu szukamy menu fabrycznego, mikrofonu, CAN)");
        for (PackageInfo p : pkgs) {
            String n = p.packageName;
            if (n.startsWith("com.google.") || n.startsWith("com.android.providers") || n.equals("android")
                    || n.startsWith("com.android.inputmethod") || n.equals(c.getPackageName())) continue;
            ApplicationInfo ai = p.applicationInfo;
            boolean sys = ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (!sys) continue;
            try {
                PackageInfo full = pm.getPackageInfo(n, PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES);
                if ((full.activities == null || full.activities.length == 0)
                        && (full.services == null || full.services.length == 0)) continue;
                sb.append("\n# ").append(n).append('\n');
                if (full.activities != null) {
                    for (ActivityInfo a : full.activities) {
                        sb.append("  ekran: ").append(a.name).append(a.exported ? " (dostępny)" : "").append('\n');
                    }
                }
                if (full.services != null) {
                    for (ServiceInfo s : full.services) sb.append("  usługa: ").append(s.name).append('\n');
                }
            } catch (Exception ignored) {
            }
        }

        sec(sb, "PLIKI KONFIGURACYJNE (jeśli czytelne)");
        for (String f : new String[]{"/system/build.prop", "/vendor/build.prop", "/odm/build.prop",
                "/system/etc/audio_policy_configuration.xml", "/vendor/etc/audio_policy_configuration.xml"}) {
            file(sb, f, 400);
        }
        for (String dir : new String[]{"/system/etc", "/vendor/etc", "/oem", "/system/app", "/system/priv-app"}) {
            File d = new File(dir);
            String[] list = d.list();
            sb.append("\n[katalog ").append(dir).append("] ");
            if (list == null) sb.append("brak dostępu\n");
            else {
                java.util.Arrays.sort(list);
                for (String s : list) sb.append(s).append("  ");
                sb.append('\n');
            }
        }

        sb.append("\n=== KONIEC ===\n");
        return sb.toString();
    }

    private static String one(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String l = br.readLine();
            return l == null ? null : l.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static void sec(StringBuilder sb, String t) {
        sb.append("\n\n########## ").append(t).append(" ##########\n");
    }

    private static void table(StringBuilder sb, ContentResolver cr, Uri u) {
        try (Cursor cu = cr.query(u, new String[]{"name", "value"}, null, null, "name")) {
            if (cu == null) {
                sb.append("brak dostępu\n");
                return;
            }
            while (cu.moveToNext()) sb.append(cu.getString(0)).append('=').append(cu.getString(1)).append('\n');
        } catch (Exception e) {
            sb.append("brak dostępu: ").append(e.getMessage()).append('\n');
        }
    }

    private static void cmd(StringBuilder sb, String command) {
        try {
            Process p = Runtime.getRuntime().exec(command);
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l;
            while ((l = br.readLine()) != null) sb.append(l).append('\n');
            br.close();
        } catch (Exception e) {
            sb.append("błąd: ").append(e).append('\n');
        }
    }

    private static void file(StringBuilder sb, String path, int maxLines) {
        sb.append("\n[plik ").append(path).append("]\n");
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String l;
            int n = 0;
            while ((l = br.readLine()) != null && n++ < maxLines) sb.append(l).append('\n');
        } catch (Exception e) {
            sb.append("brak dostępu\n");
        }
    }
}
