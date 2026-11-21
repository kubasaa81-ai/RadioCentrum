package pl.kuba.radiocentrum.radio;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Dane z telefonu trzymane w radiu. Wszystko wywoływane w wątku głównym. */
final class Store {
    private Store() {
    }

    static final List<JSONObject> contacts = new ArrayList<>();
    static final List<JSONObject> calls = new ArrayList<>();
    static final List<JSONObject> notifs = new ArrayList<>();
    static String phoneName = "";
    static String linkKind = null; // null = brak połączenia
    static int phoneBat = -1;
    static boolean phoneChg;

    private static final List<Runnable> listeners = new ArrayList<>();
    private static boolean loaded;

    static void addListener(Runnable r) {
        if (!listeners.contains(r)) listeners.add(r);
    }

    static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    static void changed() {
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    static void load(Context c) {
        if (loaded) return;
        loaded = true;
        fill(contacts, readFile(c, "contacts.json"));
        fill(calls, readFile(c, "calls.json"));
    }

    static void setContacts(Context c, JSONArray a) {
        if (a == null) return;
        fill(contacts, a);
        writeFile(c, "contacts.json", a);
    }

    static void setCalls(Context c, JSONArray a) {
        if (a == null) return;
        fill(calls, a);
        writeFile(c, "calls.json", a);
    }

    /** @return true gdy to nowe lub zmienione powiadomienie. */
    static boolean putNotif(JSONObject m) {
        String key = m.optString("key");
        for (int i = 0; i < notifs.size(); i++) {
            JSONObject o = notifs.get(i);
            if (o.optString("key").equals(key)) {
                boolean same = o.optString("text").equals(m.optString("text"))
                        && o.optString("title").equals(m.optString("title"));
                notifs.set(i, m);
                sortNotifs();
                return !same;
            }
        }
        notifs.add(m);
        sortNotifs();
        while (notifs.size() > 100) notifs.remove(notifs.size() - 1);
        return true;
    }

    private static void sortNotifs() {
        Collections.sort(notifs, (a, b) -> Long.compare(b.optLong("time"), a.optLong("time")));
    }

    static JSONObject findNotif(String key) {
        if (key == null) return null;
        for (JSONObject o : notifs) {
            if (key.equals(o.optString("key"))) return o;
        }
        return null;
    }

    /** Kontakt o dokładnie takiej nazwie (np. nadawca SMS-a) – pierwszy numer albo null. */
    static String numberForName(String name) {
        if (name == null || name.isEmpty()) return null;
        for (JSONObject c : contacts) {
            if (name.equalsIgnoreCase(c.optString("n"))) {
                JSONArray p = c.optJSONArray("p");
                if (p != null && p.length() > 0) return p.optString(0);
            }
        }
        return null;
    }

    static void removeNotif(String key) {
        for (int i = notifs.size() - 1; i >= 0; i--) {
            if (notifs.get(i).optString("key").equals(key)) notifs.remove(i);
        }
    }

    /** Nazwa kontaktu dla numeru albo null. */
    static String nameFor(String num) {
        if (num == null || num.isEmpty()) return null;
        for (JSONObject c : contacts) {
            JSONArray p = c.optJSONArray("p");
            if (p == null) continue;
            for (int i = 0; i < p.length(); i++) {
                if (J.same(p.optString(i), num)) return c.optString("n");
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ pliki

    private static void fill(List<JSONObject> list, JSONArray a) {
        list.clear();
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) list.add(o);
        }
    }

    private static JSONArray readFile(Context c, String name) {
        File f = new File(c.getFilesDir(), name);
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new JSONArray(new String(bo.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeFile(Context c, String name, JSONArray a) {
        try (OutputStream out = new FileOutputStream(new File(c.getFilesDir(), name))) {
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
