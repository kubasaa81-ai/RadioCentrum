package pl.kuba.radiocentrum.radio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fotoradary z OpenStreetMap (darmowe, otwarte dane) + GPS radia.
 * Działa w usłudze, więc ostrzega także wtedy, gdy na ekranie są Mapy Google.
 */
final class Fotoradary {
    private Fotoradary() {
    }

    /** lat, lon, ograniczenie (0 = brak), id */
    static final List<double[]> cams = new ArrayList<>();
    static volatile int version;
    static volatile int speedKmh;
    static volatile Location last;
    static volatile String info = "📷 Fotoradary: brak bazy – pobierze ją telefon";
    static volatile int nearestM = -1, nearestLim;
    static volatile long savedAt;

    private static final Set<Long> alerted = new HashSet<>();
    private static double fetchLat = 999, fetchLon = 999;
    private static volatile boolean fetching;
    private static LocationListener listener;

    @SuppressLint("MissingPermission")
    static void start(final Context c) {
        if (listener != null) return;
        if (Build.VERSION.SDK_INT >= 23
                && c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            info = "📷 Fotoradary: brak zgody na lokalizację (otwórz Radio Centrum i zezwól)";
            return;
        }
        final Context app = c.getApplicationContext();
        load(app);
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location l) {
                onLocation(app, l);
            }

            @Override
            public void onStatusChanged(String p, int s, Bundle b) {
            }

            @Override
            public void onProviderEnabled(String p) {
            }

            @Override
            public void onProviderDisabled(String p) {
            }
        };
        for (String prov : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (!lm.isProviderEnabled(prov)) continue;
                lm.requestLocationUpdates(prov, 1000, 0, listener, Looper.getMainLooper());
                Location l = lm.getLastKnownLocation(prov);
                if (l != null && last == null) onLocation(app, l);
            } catch (Exception ignored) {
            }
        }
    }

    static boolean started() {
        return listener != null;
    }

    private static void onLocation(Context c, Location l) {
        last = l;
        speedKmh = l.hasSpeed() ? Math.round(l.getSpeed() * 3.6f) : 0;
        if (!fetching && (cams.isEmpty() || System.currentTimeMillis() - savedAt > 7L * 24 * 3600 * 1000)
                && dist(l.getLatitude(), l.getLongitude(), fetchLat, fetchLon) > 20000) {
            fetch(c, l.getLatitude(), l.getLongitude()); // gdy radio ma internet
        }
        double best = 1e9;
        double[] bc = null;
        for (double[] cam : cams) {
            double d = dist(l.getLatitude(), l.getLongitude(), cam[0], cam[1]);
            long id = (long) cam[3];
            if (d > 1500) alerted.remove(id);
            if (d < best) {
                best = d;
                bc = cam;
            }
        }
        nearestM = bc == null ? -1 : (int) best;
        nearestLim = bc == null ? 0 : (int) bc[2];
        if (!Prefs.camAlerts(c)) return;
        if (bc != null && best < 600 && speedKmh >= 20 && !alerted.contains((long) bc[3])) {
            alerted.add((long) bc[3]);
            int m = (int) (Math.round(best / 50.0) * 50);
            int lim = (int) bc[2];
            String say = "Uwaga, fotoradar za " + m + " metrów" + (lim > 0 ? ". Ograniczenie " + lim + "." : ".");
            Mowa.speak(c, say);
            String text = (lim > 0 ? "Ograniczenie " + lim + " km/h · " : "") + "jedziesz " + speedKmh + " km/h";
            Popup.showNotif(c, J.obj("t", "notif", "key", "cam", "app", "Fotoradar",
                    "title", "📷 Fotoradar za " + m + " m", "text", text));
        }
    }

    private static void fetch(final Context c, final double lat, final double lon) {
        fetching = true;
        fetchLat = lat;
        fetchLon = lon;
        new Thread(() -> {
            try {
                String q = "[out:json][timeout:90];area[\"ISO3166-1\"=\"PL\"][admin_level=2]->.a;"
                        + "node[\"highway\"=\"speed_camera\"](area.a);out;";
                URL u = new URL("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8"));
                HttpURLConnection con = (HttpURLConnection) u.openConnection();
                con.setConnectTimeout(15000);
                con.setReadTimeout(30000);
                con.setRequestProperty("User-Agent", "RadioCentrum/1.0 (car head unit)");
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream in = con.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                }
                JSONArray el = new JSONObject(new String(bo.toByteArray(), StandardCharsets.UTF_8)).optJSONArray("elements");
                final List<double[]> got = new ArrayList<>();
                if (el != null) {
                    for (int i = 0; i < el.length(); i++) {
                        JSONObject o = el.optJSONObject(i);
                        if (o == null) continue;
                        JSONObject tags = o.optJSONObject("tags");
                        int lim = 0;
                        if (tags != null) {
                            try {
                                lim = Integer.parseInt(tags.optString("maxspeed").replaceAll("[^0-9]", ""));
                            } catch (Exception ignored) {
                            }
                        }
                        got.add(new double[]{o.optDouble("lat"), o.optDouble("lon"), lim, o.optLong("id")});
                    }
                }
                if (!got.isEmpty()) {
                    JSONArray arr = new JSONArray();
                    for (double[] d : got) {
                        JSONArray r = new JSONArray();
                        r.put(d[0]);
                        r.put(d[1]);
                        r.put((int) d[2]);
                        r.put((long) d[3]);
                        arr.put(r);
                    }
                    final JSONArray fa = arr;
                    new android.os.Handler(Looper.getMainLooper()).post(() -> setAll(c, fa));
                }
            } catch (Exception ignored) {
                // brak internetu w radiu – bazę dośle telefon
            } finally {
                fetching = false;
            }
        }, "rc-cams").start();
    }

    /** Baza z telefonu albo z internetu: [[lat,lon,limit,id],...] – zapisywana w radiu. */
    static void setAll(Context c, JSONArray a) {
        if (a == null || a.length() == 0) return;
        List<double[]> got = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONArray r = a.optJSONArray(i);
            if (r == null || r.length() < 4) continue;
            got.add(new double[]{r.optDouble(0), r.optDouble(1), r.optInt(2), r.optLong(3)});
        }
        if (got.isEmpty()) return;
        cams.clear();
        cams.addAll(got);
        version++;
        savedAt = System.currentTimeMillis();
        info = "📷 Fotoradary w bazie (cała Polska): " + cams.size();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(c.getFilesDir(), "cams.json"))) {
            out.write(J.obj("at", savedAt, "list", a).toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static boolean loaded;

    static void load(Context c) {
        if (loaded) return;
        loaded = true;
        java.io.File f = new java.io.File(c.getFilesDir(), "cams.json");
        if (!f.exists()) return;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            JSONObject o = new JSONObject(new String(bo.toByteArray(), StandardCharsets.UTF_8));
            JSONArray a = o.optJSONArray("list");
            if (a == null) return;
            for (int i = 0; i < a.length(); i++) {
                JSONArray r = a.optJSONArray(i);
                if (r != null && r.length() >= 4) cams.add(new double[]{r.optDouble(0), r.optDouble(1), r.optInt(2), r.optLong(3)});
            }
            savedAt = o.optLong("at");
            version++;
            info = "📷 Fotoradary w bazie (cała Polska): " + cams.size();
        } catch (Exception ignored) {
        }
    }

    static String json() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cams.size(); i++) {
            double[] c = cams.get(i);
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "[%.6f,%.6f,%d]", c[0], c[1], (int) c[2]));
        }
        return sb.append(']').toString();
    }

    static double dist(double la1, double lo1, double la2, double lo2) {
        if (la2 > 900) return 1e9;
        double r = 6371000, dLa = Math.toRadians(la2 - la1), dLo = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dLa / 2) * Math.sin(dLa / 2) + Math.cos(Math.toRadians(la1))
                * Math.cos(Math.toRadians(la2)) * Math.sin(dLo / 2) * Math.sin(dLo / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
