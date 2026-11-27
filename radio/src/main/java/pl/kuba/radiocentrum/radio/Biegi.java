package pl.kuba.radiocentrum.radio;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Rozpoznawanie biegu z prędkości (CAN) i obrotów: na każdym biegu km/h na 1000 obr/min jest stałe.
 * Podpowiedzi: „⬆ wrzuć N” przy wysokich obrotach (nie na najwyższym biegu)
 * i „⬇ redukuj na N” przy zbyt niskich. Przełożenia dopasowują się same podczas jazdy.
 */
final class Biegi {
    private Biegi() {
    }

    /** km/h przy 1000 obr/min dla biegów 1–6 (startowo typowe dla Tourana z 6-biegową skrzynią). */
    private static final double[] DEF = {7.9, 14.8, 23.6, 33.5, 43.6, 52.0};
    private static double[] ratio;
    private static Context ctx;
    private static long savedAt;

    static volatile int gear;      // aktualny bieg (0 = nie wiadomo / sprzęgło / luz)
    private static int candGear, candCnt;
    private static int hintedGear = -1;
    private static long lowSince, saidAt;
    static volatile int upCount, downCount;
    static volatile String last = "jeszcze nie było";

    private static void init(Context c) {
        if (ratio != null) return;
        ctx = c.getApplicationContext();
        SharedPreferences p = ctx.getSharedPreferences("rc_gears", Context.MODE_PRIVATE);
        ratio = new double[6];
        for (int i = 0; i < 6; i++) ratio[i] = p.getFloat("r" + i, (float) DEF[i]);
    }

    private static void save() {
        long t = System.currentTimeMillis();
        if (ctx == null || t - savedAt < 30000) return;
        savedAt = t;
        SharedPreferences.Editor ed = ctx.getSharedPreferences("rc_gears", Context.MODE_PRIVATE).edit();
        for (int i = 0; i < 6; i++) ed.putFloat("r" + i, (float) ratio[i]);
        ed.apply();
    }

    /** Wołane przy każdym odczycie obrotów (ok. 2–3 razy na sekundę). */
    static void onRpm(Context c, int rpm) {
        if (c == null) return;
        init(c);
        int up = Prefs.shiftRpm(c);
        int spd = Auto.speed();
        if (rpm < 700 || spd < 5 || !Auto.canSpeedFresh()) {
            gear = 0;
            candCnt = 0;
            lowSince = 0;
            return;
        }
        double r = Auto.canKmh / rpm * 1000.0;
        // najbliższy bieg w granicach ±12%
        int g = 0;
        double best = 0.12;
        for (int i = 0; i < 6; i++) {
            double d = Math.abs(r / ratio[i] - 1);
            if (d < best) {
                best = d;
                g = i + 1;
            }
        }
        if (g == candGear) candCnt++;
        else {
            candGear = g;
            candCnt = 1;
        }
        if (candCnt < 2) return;             // ten sam bieg 2 odczyty z rzędu
        if (g != gear) {
            gear = g;
            hintedGear = -1;                 // nowy bieg – można znowu podpowiedzieć
            lowSince = 0;
        }
        if (g == 0) return;
        if (candCnt >= 4) {                  // stabilna jazda – dopasuj przełożenie
            ratio[g - 1] = ratio[g - 1] * 0.97 + r * 0.03;
            save();
        }
        if (up <= 0 || hintedGear == g) return;
        long now = System.currentTimeMillis();
        if (now - saidAt < 3000) return;

        // ⬆ wyższy: obroty ≥ próg, nie najwyższy bieg, po zmianie obroty nie spadną poniżej 1250
        if (g < 6 && rpm >= up && rpm * ratio[g - 1] / ratio[g] >= 1250) {
            hint(c, true, g, g + 1, rpm);
            return;
        }
        // ⬇ niższy: obroty < próg redukcji przez 2 s, prędkość ≥ 15 km/h, po redukcji ≤ 3000 obr/min
        int down = Math.max(1000, up - 800);
        if (g > 1 && rpm < down && spd >= 15 && rpm * ratio[g - 1] / ratio[g - 2] <= 3000) {
            if (lowSince == 0) lowSince = now;
            else if (now - lowSince >= 2000) hint(c, false, g, g - 1, rpm);
        } else {
            lowSince = 0;
        }
    }

    private static void hint(Context c, boolean upShift, int from, int to, int rpm) {
        hintedGear = from;
        saidAt = System.currentTimeMillis();
        lowSince = 0;
        if (upShift) upCount++;
        else downCount++;
        last = (upShift ? "⬆ " : "⬇ ") + from + "→" + to + " przy " + rpm + " obr/min";
        Popup.showAlert(c, "shift", (upShift ? "UP|" : "DOWN|") + to + "|" + from + "|" + rpm, -1);
    }

    static String info() {
        StringBuilder sb = new StringBuilder("Bieg: ").append(gear == 0 ? "–" : String.valueOf(gear))
                .append(" · podpowiedzi ⬆").append(upCount).append(" ⬇").append(downCount)
                .append(" (ostatnio ").append(last).append(")\nPrzełożenia km/h na 1000 obr:");
        if (ratio != null) for (int i = 0; i < 6; i++) sb.append(String.format(Locale.US, " %d:%.1f", i + 1, ratio[i]));
        return sb.toString();
    }
}
