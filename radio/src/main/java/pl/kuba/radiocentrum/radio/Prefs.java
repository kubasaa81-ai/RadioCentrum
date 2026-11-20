package pl.kuba.radiocentrum.radio;

import android.content.Context;

final class Prefs {
    private Prefs() {
    }

    static String code(Context c) {
        return c.getSharedPreferences("rc", Context.MODE_PRIVATE).getString("code", "");
    }

    static boolean autoRead(Context c) {
        return c.getSharedPreferences("rc", Context.MODE_PRIVATE).getBoolean("autoRead", false);
    }

    static void setAutoRead(Context c, boolean v) {
        c.getSharedPreferences("rc", Context.MODE_PRIVATE).edit().putBoolean("autoRead", v).apply();
    }

    static int voiceMode(Context c) {
        int v = c.getSharedPreferences("rc", Context.MODE_PRIVATE).getInt("voice", 0);
        return v < 0 || v > 3 ? 0 : v;
    }

    static void setVoiceMode(Context c, int v) {
        c.getSharedPreferences("rc", Context.MODE_PRIVATE).edit().putInt("voice", v).apply();
    }

    static void setCode(Context c, String code) {
        c.getSharedPreferences("rc", Context.MODE_PRIVATE).edit().putString("code", code).apply();
    }
}
