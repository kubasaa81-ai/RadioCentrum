package pl.kuba.radiocentrum.telefon;

import org.json.JSONException;
import org.json.JSONObject;

final class J {
    private J() {
    }

    /** J.obj("klucz", wartość, "klucz2", wartość2...) – puste wartości są pomijane. */
    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] == null) continue;
            try {
                o.put((String) kv[i], kv[i + 1]);
            } catch (JSONException ignored) {
            }
        }
        return o;
    }

    static String digits(String s) {
        return s == null ? "" : s.replaceAll("[^0-9]", "");
    }

    /** Czy to ten sam numer (porównanie ostatnich 9 cyfr). */
    static boolean same(String a, String b) {
        String x = digits(a), y = digits(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.length() >= 9 && y.length() >= 9) {
            return x.substring(x.length() - 9).equals(y.substring(y.length() - 9));
        }
        return x.equals(y);
    }
}
