package pl.kuba.radiocentrum.radio;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.Locale;

/**
 * Dane auta z CAN poza paliwem:
 * – prędkość z auta (dokładniejsza niż GPS, działa w tunelu),
 * – akumulator: ostrzeżenie przy za słabym / za mocnym ładowaniu,
 * – przypomnienie o wymianie oleju / serwisie wg przebiegu.
 */
final class Auto {
    private Auto() {
    }

    // ------------------------------------------------------------------ prędkość

    /** Prędkość z auta w km/h (surowa wartość z CAN / 100), -1 = brak. */
    static volatile double canKmh = -1;
    static volatile long canKmhAt;

    static void onSpeedRaw(int raw) {
        if (raw < 0 || raw > 30000) return;
        canKmh = raw / 100.0;
        canKmhAt = System.currentTimeMillis();
    }

    /** Czy mamy świeżą prędkość z auta (ostatnie 5 s). */
    static boolean canSpeedFresh() {
        return canKmh >= 0 && System.currentTimeMillis() - canKmhAt < 5000;
    }

    /** Prędkość do pokazania i ostrzeżeń: z auta, a gdy jej brak – z GPS. -1 = nie wiadomo. */
    static int speed() {
        if (canSpeedFresh()) return (int) Math.round(canKmh);
        return Fotoradary.last != null ? Fotoradary.speedKmh : -1;
    }

    // ------------------------------------------------------------------ akumulator

    private static long lowSince, highSince, runSince;
    private static boolean battWarned;

    /** Napięcie przy pracującym silniku: < 12,8 V lub > 15,2 V przez 30 s → ostrzeżenie (raz na jazdę). */
    static void onEngine(Context c, int rpm, double volts) {
        long now = System.currentTimeMillis();
        if (rpm < 500) {
            runSince = 0;
            lowSince = highSince = 0;
            if (rpm == 0) battWarned = false; // silnik zgaszony – następna jazda może ostrzec znowu
            return;
        }
        if (runSince == 0) {
            runSince = now;
            serviceCheck(c);
        }
        if (Double.isNaN(volts) || now - runSince < 60000) return; // minuta po rozruchu na ustabilizowanie
        lowSince = volts < 12.8 ? (lowSince == 0 ? now : lowSince) : 0;
        highSince = volts > 15.2 ? (highSince == 0 ? now : highSince) : 0;
        if (battWarned) return;
        if (lowSince > 0 && now - lowSince > 30000) {
            battWarned = true;
            Popup.showAlert(c, "batt_low", String.format(Locale.US, "%.1f V", volts), -1);
        } else if (highSince > 0 && now - highSince > 30000) {
            battWarned = true;
            Popup.showAlert(c, "batt_high", String.format(Locale.US, "%.1f V", volts), -1);
        }
    }

    static String battText() {
        double v = CanBus.volts;
        if (Double.isNaN(v)) return "–";
        String s = String.format(Locale.US, "%.1f V", v);
        if (CanBus.rpm >= 500) {
            s += v < 12.8 ? " – słabe ładowanie!" : v > 15.2 ? " – za wysokie!" : " – ładowanie OK";
        } else {
            s += v < 12.2 ? " – akumulator słaby" : " – silnik zgaszony";
        }
        return s;
    }

    // ------------------------------------------------------------------ serwis / olej

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("rc_service", Context.MODE_PRIVATE);
    }

    /** Ile km zostało do serwisu (ujemne = po terminie), albo Integer.MIN_VALUE gdy nie ustawiono. */
    static int serviceLeft(Context c) {
        SharedPreferences p = sp(c);
        int last = p.getInt("lastKm", -1);
        int every = p.getInt("every", 15000);
        if (last < 0 || CanBus.mileageKm < 0) return Integer.MIN_VALUE;
        return last + every - CanBus.mileageKm;
    }

    static String serviceText(Context c) {
        SharedPreferences p = sp(c);
        int last = p.getInt("lastKm", -1);
        if (last < 0) return "nie ustawiono – kliknij „🔧 Serwis / olej”";
        int left = serviceLeft(c);
        String base = "ostatni przy " + last + " km, co " + p.getInt("every", 15000) + " km";
        if (left == Integer.MIN_VALUE) return base;
        return base + (left >= 0 ? " · zostało " + left + " km" : " · PO TERMINIE o " + (-left) + " km");
    }

    /** Przy uruchomieniu silnika: przypomnienie, gdy do serwisu zostało < 1000 km (raz na jazdę). */
    private static void serviceCheck(Context c) {
        if (c == null) return;
        int left = serviceLeft(c);
        if (left == Integer.MIN_VALUE || left > 1000) return;
        Popup.showNotif(c, J.obj("t", "notif", "key", "service", "app", "Serwis",
                "title", left >= 0 ? "🔧 Wymiana oleju za " + left + " km" : "🔧 Wymiana oleju – po terminie o " + (-left) + " km",
                "text", "Przebieg " + CanBus.mileageKm + " km. Po wymianie wpisz nowy przebieg: Pulpit → Paliwo → 🔧 Serwis / olej."));
    }

    /** Okno ustawień serwisu: przebieg ostatniej wymiany i co ile km. */
    static void serviceDialog(final Activity a) {
        SharedPreferences p = sp(a);
        Okno o = new Okno(a, "🔧 Serwis / wymiana oleju", "Stan: " + serviceText(a));
        final EditText last = num(a, "Przebieg przy ostatniej wymianie (km)", p.getInt("lastKm", -1));
        final EditText every = num(a, "Co ile km (np. 15000)", p.getInt("every", 15000));
        o.view(Ui.text(a, "Przebieg przy ostatniej wymianie oleju:", 19, Ui.GREY, false));
        o.view(last);
        o.view(Ui.text(a, "Wymiana co (km):", 19, Ui.GREY, false));
        o.view(every);
        o.row(o.btn("✅ Zapisz", Ui.GREEN, true, () -> save(a, last, every)),
                o.btn("🛢 Wymieniono teraz", Ui.ORANGE, true, () -> {
                    if (CanBus.mileageKm > 0) {
                        sp(a).edit().putInt("lastKm", CanBus.mileageKm).apply();
                        android.widget.Toast.makeText(a, "Zapisano wymianę przy " + CanBus.mileageKm + " km",
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                }));
        o.cancel("✖ Zamknij");
        o.show();
    }

    private static EditText num(Activity a, String hint, int val) {
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint(hint);
        e.setTextSize(22);
        e.setTextColor(Ui.WHITE);
        e.setHintTextColor(Ui.GREY);
        e.setSingleLine(true);
        if (val >= 0) e.setText(String.valueOf(val));
        int pd = Ui.dp(a, 12);
        e.setPadding(pd, pd, pd, pd);
        e.setBackground(Ui.round(a, 0xFF111821, 14));
        e.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private static void save(Activity a, EditText last, EditText every) {
        try {
            SharedPreferences.Editor ed = sp(a).edit();
            String l = last.getText().toString().trim();
            String e = every.getText().toString().trim();
            if (!l.isEmpty()) ed.putInt("lastKm", Integer.parseInt(l));
            if (!e.isEmpty()) ed.putInt("every", Math.max(1000, Integer.parseInt(e)));
            ed.apply();
            android.widget.Toast.makeText(a, "Zapisano: " + serviceText(a), android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception ex) {
            android.widget.Toast.makeText(a, "Wpisz same cyfry", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
