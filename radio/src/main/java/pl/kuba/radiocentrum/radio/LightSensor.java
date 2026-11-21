package pl.kuba.radiocentrum.radio;

import android.app.UiModeManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wykrywanie świateł auta. Radio wie o światłach (przewód ILL / CAN – ściemnia ekran),
 * ale każdy producent zapisuje to gdzie indziej. Dlatego aplikacja „uczy się”:
 * zapisuje wszystkie ustawienia przy zgaszonych i przy zapalonych światłach i szuka różnicy.
 */
final class LightSensor {
    private LightSensor() {
    }

    static Map<String, String> snapOff, snapOn;

    // ------------------------------------------------------------------ zrzut wszystkiego

    static Map<String, String> snapshot(Context c) {
        Map<String, String> m = new TreeMap<>();
        ContentResolver cr = c.getContentResolver();
        table(cr, Settings.System.CONTENT_URI, "sys:", m);
        table(cr, Settings.Global.CONTENT_URI, "glob:", m);
        table(cr, Settings.Secure.CONTENT_URI, "sec:", m);
        try {
            Process p = Runtime.getRuntime().exec("getprop");
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            Pattern pat = Pattern.compile("\\[(.+?)\\]: \\[(.*)\\]");
            String line;
            while ((line = br.readLine()) != null) {
                Matcher mt = pat.matcher(line);
                if (mt.find()) m.put("prop:" + mt.group(1), mt.group(2));
            }
            br.close();
        } catch (Exception ignored) {
        }
        m.put("cfg:night", nightCfg(c));
        m.put("uimode:night", uiMode(c));
        return m;
    }

    private static void table(ContentResolver cr, Uri uri, String prefix, Map<String, String> m) {
        try (Cursor cu = cr.query(uri, new String[]{"name", "value"}, null, null, null)) {
            if (cu == null) return;
            while (cu.moveToNext()) {
                String n = cu.getString(0);
                if (n != null) m.put(prefix + n, String.valueOf(cu.getString(1)));
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ porównanie

    private static final String[] NOISE = {"time", "date", "boot", "uptime", "clock", "alarm", "battery",
            "random", "ntp", "location", "wifi", "cell", "signal", "net.", "dhcp", "gsm", "audio_safe",
            "volume", "usb", "uid", "pid", "session", "counter", "count", "last", "seq"};
    private static final String[] HINTS = {"ill", "light", "lamp", "night", "dim", "bright", "head",
            "small", "park", "backlight", "lcd", "day"};

    /** Klucze, które różnią się między zgaszonymi a zapalonymi światłami – najbardziej prawdopodobne pierwsze. */
    static List<String[]> diff(Map<String, String> off, Map<String, String> on) {
        List<String[]> hint = new ArrayList<>(), rest = new ArrayList<>();
        for (Map.Entry<String, String> e : on.entrySet()) {
            String k = e.getKey();
            String a = off.get(k), b = e.getValue();
            if (b == null || b.equals(a)) continue;
            String low = k.toLowerCase();
            boolean noise = false;
            for (String n : NOISE) if (low.contains(n)) noise = true;
            boolean h = false;
            for (String n : HINTS) if (low.contains(n)) h = true;
            if (noise && !h) continue;
            String[] row = {k, a == null ? "(brak)" : a, b};
            if (h) hint.add(row);
            else rest.add(row);
        }
        List<String[]> all = new ArrayList<>(hint);
        all.addAll(rest);
        return all;
    }

    // ------------------------------------------------------------------ odczyt bieżący

    static boolean isOn(Context c) {
        String key = Prefs.lightKey(c);
        if (key == null) {
            // to radio (XYAuto + CAN Raise) zapisuje stan świateł w ustawieniu „Headlamp”
            String h = read(c, "sys:Headlamp");
            if (h != null && !"null".equals(h)) return !"0".equals(h.trim());
            return "night".equals(nightCfg(c));
        }
        String v = read(c, key);
        return v != null && v.equals(Prefs.lightOn(c));
    }

    static String info(Context c) {
        String key = Prefs.lightKey(c);
        if (key == null) return "🚗 Światła: odczyt z radia (Headlamp = " + read(c, "sys:Headlamp")
                + "); jeśli się nie zgadza – zrób naukę poniżej";
        return "🚗 Światła: " + key + " (zapalone = „" + Prefs.lightOn(c) + "”)";
    }

    static String read(Context c, String key) {
        int i = key.indexOf(':');
        if (i < 0) return null;
        String type = key.substring(0, i), name = key.substring(i + 1);
        ContentResolver cr = c.getContentResolver();
        try {
            switch (type) {
                case "sys":
                    return String.valueOf(Settings.System.getString(cr, name));
                case "glob":
                    return String.valueOf(Settings.Global.getString(cr, name));
                case "sec":
                    return String.valueOf(Settings.Secure.getString(cr, name));
                case "prop":
                    return sysProp(name);
                case "cfg":
                    return nightCfg(c);
                case "uimode":
                    return uiMode(c);
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String sysProp(String name) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            return (String) get.invoke(null, name);
        } catch (Exception e) {
            return null;
        }
    }

    private static String nightCfg(Context c) {
        int ui = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return ui == Configuration.UI_MODE_NIGHT_YES ? "night" : "day";
    }

    private static String uiMode(Context c) {
        try {
            UiModeManager u = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
            return String.valueOf(u.getNightMode());
        } catch (Exception e) {
            return "?";
        }
    }

    static List<String> keys(Map<String, String> m) {
        List<String> l = new ArrayList<>(m.keySet());
        Collections.sort(l);
        return l;
    }
}
