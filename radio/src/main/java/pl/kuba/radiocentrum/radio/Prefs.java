package pl.kuba.radiocentrum.radio;

import android.content.Context;

final class Prefs {
    private Prefs() {
    }

    static String code(Context c) {
        return c.getSharedPreferences("rc", Context.MODE_PRIVATE).getString("code", "");
    }

    static void setCode(Context c, String code) {
        c.getSharedPreferences("rc", Context.MODE_PRIVATE).edit().putString("code", code).apply();
    }
}
