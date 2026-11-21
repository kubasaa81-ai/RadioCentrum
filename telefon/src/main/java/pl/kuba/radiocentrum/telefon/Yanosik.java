package pl.kuba.radiocentrum.telefon;

import android.app.ActivityManager;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * Yanosik: włącza się w tle, gdy telefon połączy się z radiem, wyłącza po rozłączeniu,
 * a jego ostrzeżenia (policja, fotoradar, zagrożenia) idą na radio.
 */
final class Yanosik {
    private Yanosik() {
    }

    private static String pkgCache;
    private static long pkgAt;
    private static final LinkedList<String> log = new LinkedList<>();
    private static String lastAlert = "";
    private static long lastAlertAt;
    static volatile boolean startedByUs;

    /** Nazwa pakietu zainstalowanego Yanosika (albo null). */
    static String pkg(Context c) {
        if (pkgCache != null && System.currentTimeMillis() - pkgAt < 60000) return pkgCache;
        String found = null;
        try {
            PackageManager pm = c.getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                String p = ri.activityInfo.packageName;
                String low = p.toLowerCase(Locale.ROOT);
                String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
                if (low.contains("yanosik") || low.contains("neptis") || label.contains("yanosik")) {
                    found = p;
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        pkgCache = found;
        pkgAt = System.currentTimeMillis();
        return found;
    }

    static boolean isYanosik(Context c, String p) {
        String y = pkg(c);
        return p != null && (p.equals(y) || p.toLowerCase(Locale.ROOT).contains("yanosik")
                || p.toLowerCase(Locale.ROOT).contains("neptis"));
    }

    static boolean running(PhoneService s) {
        String y = pkg(s);
        if (y == null) return false;
        try {
            StatusBarNotification[] act = s.getActiveNotifications();
            if (act != null) for (StatusBarNotification n : act) if (y.equals(n.getPackageName())) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    // ------------------------------------------------------------------ włącz / wyłącz

    /** Telefon połączył się z radiem – uruchom Yanosika i schowaj go w tło. */
    static String start(final PhoneService s) {
        if (!Prefs.yanAuto(s)) return null;
        final String y = pkg(s);
        if (y == null) return null;
        if (running(s)) {
            add("Połączono z radiem – Yanosik już działa");
            return null;
        }
        if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(s)) {
            add("Nie mogę włączyć Yanosika – brak kroku 5 (wyświetlanie nad aplikacjami)");
            return "Yanosik: w aplikacji na telefonie zrób krok 5, wtedy włączy się sam";
        }
        try {
            Intent i = s.getPackageManager().getLaunchIntentForPackage(y);
            if (i == null) return null;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            s.startActivity(i);
            startedByUs = true;
            add("Połączono z radiem – włączam Yanosika");
            // po chwili schowaj go (ekran główny telefonu), Yanosik działa dalej w tle
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    s.startActivity(home);
                } catch (Exception ignored) {
                }
            }, 8000);
            return "🚓 Yanosik włączony w tle telefonu";
        } catch (Exception e) {
            add("Nie udało się włączyć Yanosika: " + e.getMessage());
            return null;
        }
    }

    /** Radio rozłączone na dłużej – wyłącz Yanosika. */
    static void stop(PhoneService s) {
        if (!Prefs.yanAuto(s)) return;
        String y = pkg(s);
        if (y == null) return;
        boolean done = false;
        try {
            StatusBarNotification[] act = s.getActiveNotifications();
            if (act != null) {
                for (StatusBarNotification sbn : act) {
                    if (!y.equals(sbn.getPackageName())) continue;
                    Notification.Action[] as = sbn.getNotification().actions;
                    if (as == null) continue;
                    for (Notification.Action a : as) {
                        String t = String.valueOf(a.title).toLowerCase(Locale.ROOT);
                        if (t.matches(".*(zako|wyłącz|wylacz|zamknij|wyjd|wyjś|wyjsc|koniec|exit|quit|stop|close).*")) {
                            try {
                                a.actionIntent.send();
                                done = true;
                                add("Rozłączono z radiem – wyłączam Yanosika („" + a.title + "”)");
                            } catch (Exception ignored) {
                            }
                            break;
                        }
                    }
                    if (done) break;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            ActivityManager am = (ActivityManager) s.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses(y);
        } catch (Exception ignored) {
        }
        if (!done) add("Rozłączono z radiem – Yanosik nie ma przycisku „Zakończ” w powiadomieniu, zamykam go w tle");
        startedByUs = false;
    }

    // ------------------------------------------------------------------ ostrzeżenia

    /**
     * Powiadomienie Yanosika → ostrzeżenie dla radia (albo null, gdy to nic ważnego).
     */
    static JSONObject alert(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle ex = n.extras;
        if (ex == null) return null;
        CharSequence ti = ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence tx = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (tx == null) tx = ex.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT);
        String title = ti == null ? "" : ti.toString().trim();
        String text = tx == null ? "" : tx.toString().trim();
        if (sub != null && !sub.toString().trim().isEmpty()) text = (text + " " + sub).trim();
        List<String> acts = new ArrayList<>();
        if (n.actions != null) for (Notification.Action a : n.actions) acts.add(String.valueOf(a.title));
        add((sbn.isOngoing() ? "[stałe] " : "") + title + " | " + text + (acts.isEmpty() ? "" : " | przyciski: " + acts));

        String all = (title + " " + text).toLowerCase(Locale.ROOT);
        String kind;
        if (all.matches(".*(policj|kontrol|patrol|nieoznakow|radiowóz|radiowoz|drogówk|drogowk).*")) {
            kind = "police";
        } else if (all.matches(".*(fotoradar|radar|odcinkow|pomiar prędk|pomiar predk|suszark|laser).*")) {
            kind = "cam";
        } else if (all.matches(".*(wypadek|kolizj|zagroż|zagroz|przeszkod|roboty|korek|utrudnien|uwaga|zwierz).*")) {
            kind = "danger";
        } else {
            return null;
        }
        String key = kind + "|" + title + "|" + text;
        long now = System.currentTimeMillis();
        if (key.equals(lastAlert) && now - lastAlertAt < 90000) return null; // to samo – nie powtarzaj
        lastAlert = key;
        lastAlertAt = now;
        return J.obj("t", "alert", "kind", kind, "title", title, "text", text);
    }

    // ------------------------------------------------------------------ podgląd

    static synchronized void add(String s) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        log.addFirst(t + "  " + s);
        while (log.size() > 40) log.removeLast();
    }

    static synchronized String logText() {
        if (log.isEmpty()) return "Na razie nic. Włącz Yanosika, przejedź się, a tu pojawi się wszystko, co Yanosik wysyła w powiadomieniach.";
        StringBuilder sb = new StringBuilder();
        for (String s : log) sb.append(s).append("\n\n");
        return sb.toString();
    }
}
