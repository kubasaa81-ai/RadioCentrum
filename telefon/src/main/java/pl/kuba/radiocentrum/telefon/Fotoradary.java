package pl.kuba.radiocentrum.telefon;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Telefon ma internet – pobiera bazę fotoradarów dla całej Polski (OpenStreetMap)
 * i wysyła ją do radia, które w trasie internetu nie ma.
 */
final class Fotoradary {
    private Fotoradary() {
    }

    private static final long WEEK = 7L * 24 * 3600 * 1000;

    /** Zwraca bazę [[lat,lon,limit,id],...] – z pliku, a raz w tygodniu odświeżoną z internetu. */
    static JSONArray get(Context c) {
        File f = new File(c.getFilesDir(), "cams.json");
        JSONArray cached = null;
        long at = 0;
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                JSONObject o = new JSONObject(read(in));
                cached = o.optJSONArray("list");
                at = o.optLong("at");
            } catch (Exception ignored) {
            }
        }
        if (cached != null && cached.length() > 0 && System.currentTimeMillis() - at < WEEK) return cached;
        JSONArray fresh = download();
        if (fresh != null && fresh.length() > 0) {
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(J.obj("at", System.currentTimeMillis(), "list", fresh).toString()
                        .getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
            return fresh;
        }
        return cached;
    }

    private static JSONArray download() {
        try {
            String q = "[out:json][timeout:90];area[\"ISO3166-1\"=\"PL\"][admin_level=2]->.a;"
                    + "node[\"highway\"=\"speed_camera\"](area.a);out;";
            URL u = new URL("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8"));
            HttpURLConnection con = (HttpURLConnection) u.openConnection();
            con.setConnectTimeout(20000);
            con.setReadTimeout(120000);
            con.setRequestProperty("User-Agent", "RadioCentrum/1.0 (car head unit)");
            String body;
            try (InputStream in = con.getInputStream()) {
                body = read(in);
            }
            JSONArray el = new JSONObject(body).optJSONArray("elements");
            JSONArray out = new JSONArray();
            if (el == null) return out;
            for (int i = 0; i < el.length(); i++) {
                JSONObject o = el.optJSONObject(i);
                if (o == null) continue;
                int lim = 0;
                JSONObject tags = o.optJSONObject("tags");
                if (tags != null) {
                    try {
                        lim = Integer.parseInt(tags.optString("maxspeed").replaceAll("[^0-9]", ""));
                    } catch (Exception ignored) {
                    }
                }
                JSONArray r = new JSONArray();
                r.put(o.optDouble("lat"));
                r.put(o.optDouble("lon"));
                r.put(lim);
                r.put(o.optLong("id"));
                out.put(r);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }
}
