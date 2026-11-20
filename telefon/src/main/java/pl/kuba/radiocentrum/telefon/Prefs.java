package pl.kuba.radiocentrum.telefon;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Locale;

final class Prefs {
    private Prefs() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("rc", Context.MODE_PRIVATE);
    }

    static String radioMac(Context c) {
        return p(c).getString("mac", null);
    }

    static String radioName(Context c) {
        return p(c).getString("name", null);
    }

    static void setRadio(Context c, String mac, String name) {
        p(c).edit().putString("mac", mac).putString("name", name).apply();
    }

    /** 6-cyfrowy kod do połączenia przez Wi-Fi (tworzony raz). */
    static String code(Context c) {
        String s = p(c).getString("code", null);
        if (s == null) {
            s = String.format(Locale.US, "%06d", new SecureRandom().nextInt(1000000));
            p(c).edit().putString("code", s).apply();
        }
        return s;
    }
}
