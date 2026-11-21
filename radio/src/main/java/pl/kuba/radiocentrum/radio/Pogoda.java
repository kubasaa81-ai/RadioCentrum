package pl.kuba.radiocentrum.radio;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.location.Location;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Temperatura na zewnątrz na Pulpicie.
 * 1) z auta (CAN), jeśli radio gdzieś ją zapisuje,
 * 2) z pogody (Open-Meteo) dla miejsca z GPS – przez internet radia albo przez telefon.
 */
final class Pogoda {
    private Pogoda() {
    }

    static volatile double tempC = Double.NaN;
    static volatile long at;
    static volatile boolean fromCar;
    private static volatile boolean fetching;
    private static long askedAt;
    private static double atLat = 999, atLon = 999;
    private static String carKey;
    private static long carScanAt;

    /** Tekst do kafelka. */
    static String text() {
        if (Double.isNaN(tempC)) return "–";
        long age = System.currentTimeMillis() - at;
        String t = Math.round(tempC) + "°C";
        if (!fromCar && age > 90 * 60000L) t = "~" + t;
        return t;
    }

    /** Wywoływane co 1,5 s z Pulpitu. */
    static void tick(Context c) {
        long now = System.currentTimeMillis();
        // 1) z auta
        if (now - carScanAt > 30000) {
            carScanAt = now;
            if (carKey == null) carKey = findCarKey(c);
            if (carKey != null) {
                Double v = num(LightSensor.read(c, carKey));
                if (v != null && v > -50 && v < 70) {
                    tempC = v;
                    at = now;
                    fromCar = true;
                    return;
                }
            }
        }
        if (fromCar && now - at < 120000) return;
        fromCar = false;
        // 2) z pogody
        Location l = Fotoradary.last;
        if (l == null || fetching) return;
        boolean moved = Fotoradary.dist(l.getLatitude(), l.getLongitude(), atLat, atLon) > 15000;
        long wait = Double.isNaN(tempC) ? 60000 : 15 * 60000L;
        if (!moved && now - askedAt < wait) return;
        askedAt = now;
        atLat = l.getLatitude();
        atLon = l.getLongitude();
        final double lat = atLat, lon = atLon;
        fetching = true;
        new Thread(() -> {
            try {
                if (!fetch(lat, lon)) {
                    RadioService.sendToPhone(J.obj("t", "weather", "lat", lat, "lon", lon));
                }
            } finally {
                fetching = false;
            }
        }, "rc-pogoda").start();
    }

    /** Wymuś odświeżenie (przytrzymanie kafelka). */
    static void refresh() {
        askedAt = 0;
        atLat = 999;
        carKey = null;
        carScanAt = 0;
    }

    /** Odpowiedź z telefonu. */
    static void fromPhone(double c) {
        if (Double.isNaN(c) || fromCar) return;
        tempC = c;
        at = System.currentTimeMillis();
    }

    private static boolean fetch(double lat, double lon) {
        try {
            URL u = new URL(String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m", lat, lon));
            HttpURLConnection con = (HttpURLConnection) u.openConnection();
            con.setConnectTimeout(6000);
            con.setReadTimeout(8000);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = con.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            }
            JSONObject cur = new JSONObject(new String(bo.toByteArray(), StandardCharsets.UTF_8)).optJSONObject("current");
            if (cur == null || !cur.has("temperature_2m")) return false;
            if (!fromCar) {
                tempC = cur.optDouble("temperature_2m");
                at = System.currentTimeMillis();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Szuka w ustawieniach radia klucza z temperaturą zewnętrzną (zapisywaną przez moduł CAN). */
    private static String findCarKey(Context c) {
        String[][] tables = {{"sys:", "system"}, {"glob:", "global"}};
        ContentResolver cr = c.getContentResolver();
        for (String[] t : tables) {
            try (Cursor cu = cr.query(Uri.parse("content://settings/" + t[1]), new String[]{"name", "value"}, null, null, null)) {
                if (cu == null) continue;
                while (cu.moveToNext()) {
                    String n = cu.getString(0);
                    if (n == null) continue;
                    String low = n.toLowerCase(Locale.ROOT);
                    boolean out = low.matches(".*(out|outside|outdoor|ambient|exterior|outer|env).*")
                            && low.contains("temp") && !low.contains("cpu") && !low.contains("bat");
                    if (out && num(cu.getString(1)) != null) return t[0] + n;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Double num(String s) {
        if (s == null) return null;
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (Exception e) {
            return null;
        }
    }

    static String info() {
        return "🌡 Temperatura: " + text() + (Double.isNaN(tempC) ? " (czekam na GPS i internet radia lub telefonu)"
                : fromCar ? " – z auta (" + carKey + ")" : " – z pogody dla Twojego miejsca");
    }
}
