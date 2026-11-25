package pl.kuba.radiocentrum.radio;

import android.content.Context;

import java.util.HashSet;
import java.util.Set;

final class Prefs {
    private Prefs() {
    }

    static String code(Context c) {
        return c.getSharedPreferences("rc", Context.MODE_PRIVATE).getString("code", "");
    }

    static boolean autoRead(Context c) {
        return c.getSharedPreferences("rc", Context.MODE_PRIVATE).getBoolean("autoRead", true);
    }

    static void setAutoRead(Context c, boolean v) {
        c.getSharedPreferences("rc", Context.MODE_PRIVATE).edit().putBoolean("autoRead", v).apply();
    }

    static int voiceMode(Context c) {
        int v = c.getSharedPreferences("rc", Context.MODE_PRIVATE).getInt("voice", 2);
        return v < 0 || v > 3 ? 2 : v;
    }

    static void setVoiceMode(Context c, int v) {
        c.getSharedPreferences("rc", Context.MODE_PRIVATE).edit().putInt("voice", v).apply();
    }

    static String lightKey(Context c) {
        return c.getSharedPreferences("rc", Context.MODE_PRIVATE).getString("lightKey", null);
    }

    static String lightOn(Context c) {
        return c.getSharedPreferences("rc", Context.MODE_PRIVATE).getString("lightOn", null);
    }

    static void setLight(Context c, String key, String onValue) {
        c.getSharedPreferences("rc", Context.MODE_PRIVATE).edit()
                .putString("lightKey", key).putString("lightOn", onValue).apply();
    }

    static Set<String> favs(Context c) {
        return new HashSet<>(c.getSharedPreferences("rc", Context.MODE_PRIVATE)
                .getStringSet("favs", new HashSet<>()));
    }

    static void setFavs(Context c, Set<String> s) {
        c.getSharedPreferences("rc", Context.MODE_PRIVATE).edit().putStringSet("favs", s).apply();
    }

    static boolean camAlerts(Context c) {
        return c.getSharedPreferences("rc", Context.MODE_PRIVATE).getBoolean("camAlerts", true);
    }

    static void setCamAlerts(Context c, boolean v) {
        c.getSharedPreferences("rc", Context.MODE_PRIVATE).edit().putBoolean("camAlerts", v).apply();
    }

    static boolean aaAuto(Context c) {
        return c.getSharedPreferences("rc", Context.MODE_PRIVATE).getBoolean("aaAuto", true);
    }

    static void setAaAuto(Context c, boolean v) {
        c.getSharedPreferences("rc", Context.MODE_PRIVATE).edit().putBoolean("aaAuto", v).apply();
    }

    /** Po starcie radia włącz w tle radio FM. */
    static boolean fmAuto(Context c) {
        return c.getSharedPreferences("rc", Context.MODE_PRIVATE).getBoolean("fmAuto", true);
    }

    static void setFmAuto(Context c, boolean v) {
        c.getSharedPreferences("rc", Context.MODE_PRIVATE).edit().putBoolean("fmAuto", v).apply();
    }

    static int aaDelay(Context c) {
        return c.getSharedPreferences("rc", Context.MODE_PRIVATE).getInt("aaDelay", 25);
    }

    static void setAaDelay(Context c, int v) {
        c.getSharedPreferences("rc", Context.MODE_PRIVATE).edit().putInt("aaDelay", v).apply();
    }

    static void setCode(Context c, String code) {
        c.getSharedPreferences("rc", Context.MODE_PRIVATE).edit().putString("code", code).apply();
    }

    /** Odbiór danych z CAN (druga lista słuchaczy – obok fabrycznej aplikacji). */
    static volatile boolean canOnCached;

    static boolean canOn(Context c) {
        boolean v = c.getSharedPreferences("rc", Context.MODE_PRIVATE).getBoolean("canOn", false);
        canOnCached = v;
        return v;
    }

    static void setCanOn(Context c, boolean v) {
        canOnCached = v;
        c.getSharedPreferences("rc", Context.MODE_PRIVATE).edit().putBoolean("canOn", v).apply();
    }
}
