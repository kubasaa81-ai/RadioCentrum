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

    static void setCode(Context c, String code) {
        c.getSharedPreferences("rc", Context.MODE_PRIVATE).edit().putString("code", code).apply();
    }
}
