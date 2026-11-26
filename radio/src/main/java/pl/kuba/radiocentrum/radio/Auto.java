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

    /** Prędkość z auta w km/h (surowa wartość z CAN × skala), -1 = brak. */
    static volatile double canKmh = -1;
    static volatile long canKmhAt;
    /** km/h na jednostkę surową – startowo 0,01, potem sama się dopasowuje do przebiegu z licznika. */
    private static double scale = -1;
    private static long prevAt, lastMoveAt;
    private static int calKm0 = -1;
    private static double calM;
    private static Context ctx;

    static void init(Context c) {
        ctx = c.getApplicationContext();
        Fotoradary.loadTrip(ctx);
        if (scale < 0) scale = ctx.getSharedPreferences("rc_auto", Context.MODE_PRIVATE).getFloat("scale", 0.01f);
    }

    /**
     * Prędkość z CAN → komputer trasy (dystans, czas jazdy, maks.) liczony z auta, nie z GPS.
     * Co 10 km porównujemy policzony dystans z przebiegiem licznika i poprawiamy skalę.
     */
    static void onSpeedRaw(int raw) {
        if (raw < 0 || raw > 30000) return;
        if (scale < 0) scale = 0.01;
        long now = System.currentTimeMillis();
        double kmh = raw * scale;
        canKmh = kmh;
        canKmhAt = now;
        long dt = prevAt == 0 ? 0 : now - prevAt;
        prevAt = now;
        if (kmh >= 3) {
            lastMoveAt = now;
        }
        if (dt <= 0 || dt > 10000) return;
        double m = kmh / 3.6 * dt / 1000.0;
        if (kmh >= 3) {
            Fotoradary.tripM += m;
            Fotoradary.movingMs += dt;
            int k = (int) Math.round(kmh);
            if (k > Fotoradary.maxKmh && k < 250) Fotoradary.maxKmh = k;
        }
        calibrate(m);
    }

    private static void calibrate(double m) {
        int km = CanBus.mileageKm;
        if (km <= 0) return;
        if (calKm0 < 0 || km < calKm0) {
            calKm0 = km;
            calM = 0;
            return;
        }
        calM += m;
        int dKm = km - calKm0;
        if (dKm < 10) return;
        if (calM > 100) {
            double ratio = dKm * 1000.0 / calM;
            if (ratio > 0.2 && ratio < 5) {
                // mała różnica – dopasuj łagodnie; duża (zła jednostka) – od razu
                scale *= (ratio > 0.8 && ratio < 1.25) ? 1 + (ratio - 1) * 0.3 : ratio;
                if (ctx != null) ctx.getSharedPreferences("rc_auto", Context.MODE_PRIVATE)
                        .edit().putFloat("scale", (float) scale).apply();
            }
        }
        calKm0 = km;
        calM = 0;
    }

    static String scaleText() {
        return String.format(Locale.US, "%.4f", scale < 0 ? 0.01 : scale);
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
        month(c);
        if (rpm < 500) {
            if (runSince != 0 && rpm == 0) {
                sendState(c, true); // silnik zgaszony → miejsce postoju na telefon
                engineStopped(c);
            }
            idleEnd(c, now);
            runSince = 0;
            lowSince = highSince = 0;
            if (rpm == 0) {
                battWarned = false; // silnik zgaszony – następna jazda może ostrzec znowu
                iceWarned = false;
            }
            return;
        }
        if (runSince == 0) {
            runSince = now;
            serviceCheck(c);
            // pierwsze uruchomienie po zgaszeniu silnika → nowa trasa od zera
            if (c != null && c.getSharedPreferences("rc_trip", Context.MODE_PRIVATE).getBoolean("pending", false)) {
                c.getSharedPreferences("rc_trip", Context.MODE_PRIVATE).edit().putBoolean("pending", false).apply();
                Fotoradary.resetTrip();
            }
        }
        iceCheck(c);
        idleTick(c, now);
        if (now - sentAt > 60000) sendState(c, false);
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

    // ------------------------------------------------------------------ śliskość

    private static boolean iceWarned;

    /** Temperatura z czujnika auta ≤ +3°C → niebieski znak „możliwa śliskość” (raz na jazdę). */
    private static void iceCheck(Context c) {
        double t = CanBus.outTemp;
        if (iceWarned || Double.isNaN(t) || t > 3) return;
        iceWarned = true;
        Popup.showAlert(c, "ice", String.format(Locale.US, "%.1f°C", t), -1);
    }

    // ------------------------------------------------------------------ postój na pracującym silniku

    private static long idleSince;
    private static final double IDLE_LPH = 0.9; // ok. 0,9 l/h na wolnych obrotach

    private static void idleTick(Context c, long now) {
        if (speed() < 3) {
            if (idleSince == 0) idleSince = now;
        } else {
            idleEnd(c, now);
        }
    }

    private static void idleEnd(Context c, long now) {
        if (idleSince == 0) return;
        long ms = now - idleSince;
        idleSince = 0;
        if (ms < 5 * 60000L || c == null) return;
        try {
            SharedPreferences p = c.getSharedPreferences("rc_auto", Context.MODE_PRIVATE);
            org.json.JSONArray a = new org.json.JSONArray(p.getString("idles", "[]"));
            org.json.JSONObject e = new org.json.JSONObject();
            e.put("t", now - ms);
            e.put("min", Math.round(ms / 60000.0));
            e.put("l", Math.round(ms / 3600000.0 * IDLE_LPH * 100) / 100.0);
            a.put(e);
            while (a.length() > 40) a.remove(0);
            p.edit().putString("idles", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ kilometry w miesiącach

    private static void month(Context c) {
        int km = CanBus.mileageKm;
        if (c == null || km <= 0) return;
        String key = new java.text.SimpleDateFormat("yyyy-MM", Locale.ROOT).format(new java.util.Date());
        try {
            SharedPreferences p = c.getSharedPreferences("rc_auto", Context.MODE_PRIVATE);
            org.json.JSONObject m = new org.json.JSONObject(p.getString("months", "{}"));
            org.json.JSONArray v = m.optJSONArray(key);
            if (v == null) {
                v = new org.json.JSONArray();
                v.put(km);
                v.put(km);
            } else if (v.optInt(1) == km) {
                return;
            } else {
                v.put(1, km);
            }
            m.put(key, v);
            p.edit().putString("months", m.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ stan auta → telefon

    private static long sentAt;

    /** Wyślij na telefon stan auta (paliwo, przebieg, akumulator, serwis, tankowania, postoje, miejsce). */
    static void sendState(Context c, boolean parked) {
        if (c == null) return;
        sentAt = System.currentTimeMillis();
        try {
            SharedPreferences p = c.getSharedPreferences("rc_auto", Context.MODE_PRIVATE);
            org.json.JSONObject m = J.obj("t", "car_state", "ts", sentAt, "parked", parked,
                    "fuel", CanBus.fuelL, "range", CanBus.rangeText(), "km", CanBus.mileageKm,
                    "cons", Math.round(CanBus.consumption() * 10) / 10.0,
                    "consLearned", CanBus.consLearned(),
                    "batt", battText(), "temp", Double.isNaN(CanBus.outTemp) ? null : CanBus.outTemp,
                    "service", serviceText(c));
            int left = serviceLeft(c);
            if (left != Integer.MIN_VALUE) m.put("serviceLeft", left);
            m.put("refuels", Paliwo.log());
            m.put("idles", new org.json.JSONArray(p.getString("idles", "[]")));
            m.put("months", new org.json.JSONObject(p.getString("months", "{}")));
            android.location.Location l = Fotoradary.last;
            if (l != null) {
                m.put("lat", l.getLatitude());
                m.put("lon", l.getLongitude());
                m.put("locTs", l.getTime());
            }
            RadioService.sendToPhone(m);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ podpowiedź zmiany biegu

    private static int prevRpm = -1;
    private static long shiftSaidAt;
    private static boolean armed = true;

    /**
     * Obroty rosną i przekraczają próg (domyślnie 2000) podczas jazdy → głos „Zmień bieg na wyższy”.
     * Ponownie dopiero, gdy obroty spadną poniżej progu (zmiana biegu), najwyżej co 8 s.
     */
    static void onShift(Context c, int rpm) {
        if (c == null || rpm < 0) return;
        int lim = Prefs.shiftRpm(c);
        int prev = prevRpm;
        prevRpm = rpm;
        if (rpm > maxRpmSeen) maxRpmSeen = rpm;
        if (lim <= 0) return;
        if (rpm < lim - 200) armed = true; // zmieniono bieg / zwolniono – można znowu podpowiedzieć
        long now = System.currentTimeMillis();
        // przejście przez próg (także gdy między odczytami obroty skoczyły wysoko)
        if (armed && rpm >= lim && speed() >= 5 && now - shiftSaidAt > 8000) {
            armed = false;
            shiftSaidAt = now;
            shiftCount++;
            shiftLast = "przy " + rpm + " obr/min, " + speed() + " km/h (poprzedni odczyt " + prev + ")";
            sayShift(c);
        }
    }

    /** Silnik zgaszony (obroty 0 albo wyjęty kluczyk): trasa zostaje na ekranie, wyzeruje się przy następnym uruchomieniu. */
    static void engineStopped(Context c) {
        if (c == null) return;
        Fotoradary.saveTrip(true);
        c.getSharedPreferences("rc_trip", Context.MODE_PRIVATE).edit().putBoolean("pending", true).apply();
    }

    static volatile int shiftCount, maxRpmSeen;
    static volatile String shiftLast = "jeszcze nie było";

    /** Znak na środku ekranu + krótki sygnał (bez czytania na głos). */
    static void sayShift(Context c) {
        int r = prevRpm > 0 ? prevRpm : Prefs.shiftRpm(c);
        Popup.showAlert(c, "shift", String.valueOf(r), -1);
    }

    static String shiftInfo(Context c) {
        int lim = Prefs.shiftRpm(c);
        return "Podpowiedź biegu: " + (lim <= 0 ? "wyłączona" : "próg " + lim + " obr/min")
                + ", było razy: " + shiftCount + " (" + shiftLast + ")"
                + ", najwyższe obroty od startu: " + maxRpmSeen
                ;
    }
}
