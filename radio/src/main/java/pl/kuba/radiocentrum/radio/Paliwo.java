package pl.kuba.radiocentrum.radio;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Paliwo z CAN: ostrzeżenie o rezerwie (z przyciskiem „Najbliższa stacja”)
 * oraz dziennik tankowań wykrywanych automatycznie (przybyło paliwa w baku).
 */
final class Paliwo {
    private Paliwo() {
    }

    /** Poniżej tylu litrów – ostrzeżenie o rezerwie; poniżej LOW2 – drugie, pilniejsze. */
    static final int LOW1 = 8, LOW2 = 4;
    /** Tyle litrów musi przybyć, żeby uznać to za tankowanie (wskaźnik w baku „pływa” o 1–2 L). */
    private static final int REFUEL_MIN = 5;

    private static Context app;
    private static int stableVal = -1, stableCnt;
    private static int warned; // 0 = nie, 1 = rezerwa, 2 = pilne

    private static SharedPreferences p() {
        return app.getSharedPreferences("rc_fuel", Context.MODE_PRIVATE);
    }

    /** Wołane przy każdej ramce z paliwem (z wątku usługi CAN). */
    static synchronized void onFuel(Context c, int fuel, int km, int rpm) {
        if (c == null || fuel < 0 || km <= 0) return;
        app = c.getApplicationContext();
        // poziom „stabilny” = ta sama wartość 3 razy z rzędu (paliwo chlupie w baku)
        if (fuel == stableVal) stableCnt++;
        else {
            stableVal = fuel;
            stableCnt = 1;
        }
        if (stableCnt < 3) return;

        SharedPreferences sp = p();
        int low = sp.getInt("low", -1);
        int lowKm = sp.getInt("lowKm", -1);
        if (low < 0 || km < lowKm) {
            sp.edit().putInt("low", fuel).putInt("lowKm", km).apply();
            low = fuel;
        } else if (fuel >= low + REFUEL_MIN) {
            refuel(sp, low, fuel, km);
            low = fuel;
        } else if (fuel < low) {
            sp.edit().putInt("low", fuel).putInt("lowKm", km).apply();
            low = fuel;
        }

        // rezerwa – tylko przy pracującym silniku (żeby nie straszyć na postoju)
        if (fuel > LOW1 + 1) warned = 0;
        if (rpm > 300) {
            if (fuel <= LOW2 && warned < 2) {
                warned = 2;
                Popup.showFuel(app, fuel, CanBus.rangeText(), true);
            } else if (fuel <= LOW1 && warned < 1) {
                warned = 1;
                Popup.showFuel(app, fuel, CanBus.rangeText(), false);
            }
        }
    }

    private static void refuel(SharedPreferences sp, int before, int after, int km) {
        JSONArray log = log();
        try {
            JSONObject e = new JSONObject();
            e.put("t", System.currentTimeMillis());
            e.put("km", km);
            e.put("before", before);
            e.put("after", after);
            // spalanie od poprzedniego tankowania: ile ubyło / ile przejechano
            if (log.length() > 0) {
                JSONObject prev = log.getJSONObject(log.length() - 1);
                int dKm = km - prev.optInt("km");
                int used = prev.optInt("after") - before;
                if (dKm >= 30 && used > 0) e.put("cons", Math.round(used * 1000.0 / dKm) / 10.0);
            }
            log.put(e);
            while (log.length() > 50) log.remove(0);
        } catch (Exception ignored) {
        }
        sp.edit().putString("log", log.toString()).putInt("low", after).putInt("lowKm", km).apply();
        warned = 0;
        AccWatch.log("tankowanie: " + before + " → " + after + " L przy " + km + " km");
        Popup.showNotif(app, J.obj("t", "notif", "key", "fuel", "app", "Paliwo",
                "title", "⛽ Zatankowano ok. " + (after - before) + " L",
                "text", "W baku " + after + " L · przebieg " + km + " km. Zapisane w dzienniku tankowań."));
    }

    static JSONArray log() {
        try {
            return new JSONArray(p().getString("log", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** Otwiera Mapy z wyszukiwaniem najbliższych stacji paliw. */
    static void stations(Context c) {
        Uri u = Uri.parse("geo:0,0?q=" + Uri.encode("stacja paliw"));
        Intent i = new Intent(Intent.ACTION_VIEW, u);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.setPackage("com.google.android.apps.maps");
        try {
            c.startActivity(i);
            return;
        } catch (Exception ignored) {
        }
        try {
            i.setPackage(null);
            c.startActivity(i);
        } catch (Exception e) {
            android.widget.Toast.makeText(c, "Brak aplikacji map", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** Okno „Paliwo i tankowania” (klik w kafelek Paliwo / Zasięg). */
    static void show(final Activity a) {
        app = a.getApplicationContext();
        StringBuilder sb = new StringBuilder();
        sb.append("W baku: ").append(CanBus.fuelText()).append("   ·   zasięg: ").append(CanBus.rangeText()).append("\n");
        sb.append(String.format(Locale.US, "Średnie spalanie: %.1f l/100 km", CanBus.consumption()))
                .append(CanBus.consLearned() ? " (z Twojej jazdy)" : " (domyślne – wyliczy się samo)").append("\n");
        sb.append("Przebieg: ").append(CanBus.mileageKm < 0 ? "–" : CanBus.mileageKm + " km");
        sb.append("\nAkumulator: ").append(Auto.battText());
        sb.append("\nSerwis / olej: ").append(Auto.serviceText(a));

        StringBuilder lg = new StringBuilder();
        JSONArray log = log();
        SimpleDateFormat df = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT);
        double sumC = 0;
        int nC = 0;
        for (int i = log.length() - 1; i >= 0 && i >= log.length() - 15; i--) {
            JSONObject e = log.optJSONObject(i);
            if (e == null) continue;
            lg.append(df.format(new Date(e.optLong("t")))).append("  ·  +")
                    .append(e.optInt("after") - e.optInt("before")).append(" L  (")
                    .append(e.optInt("before")).append(" → ").append(e.optInt("after")).append(" L)  ·  ")
                    .append(e.optInt("km")).append(" km");
            if (e.has("cons")) {
                lg.append("  ·  ").append(String.format(Locale.US, "%.1f l/100", e.optDouble("cons")));
                sumC += e.optDouble("cons");
                nC++;
            }
            lg.append("\n");
        }
        if (nC > 0) sb.append(String.format(Locale.US, "\nSpalanie wg tankowań: %.1f l/100 km", sumC / nC));

        Okno o = new Okno(a, "⛽ Paliwo i tankowania", null);
        o.message(sb.toString(), 21);
        o.message(lg.length() == 0
                ? "Dziennik tankowań jest pusty. Zapisze się sam po najbliższym tankowaniu (min. " + REFUEL_MIN + " L)."
                : "Ostatnie tankowania:\n" + lg.toString().trim(), 18);
        o.row(o.btn("⛽ Najbliższa stacja", Ui.ORANGE, true, () -> stations(a)),
                o.btn("🚙 Fabryczne Dane auta", 0xFF2E7D32, true, () -> {
                    Intent i = a.getPackageManager().getLaunchIntentForPackage("com.xygala.canbus");
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        a.startActivity(i);
                    }
                }));
        o.row(o.btn("🔧 Serwis / olej", Ui.BLUE, true, () -> Auto.serviceDialog(a)));
        if (log.length() > 0) {
            o.row(o.btn("🗑 Usuń ostatni wpis", Ui.RED, true, () -> {
                JSONArray l = log();
                if (l.length() > 0) l.remove(l.length() - 1);
                p().edit().putString("log", l.toString()).apply();
                show(a);
            }));
        }
        o.cancel("✖ Zamknij");
        o.show();
    }
}
