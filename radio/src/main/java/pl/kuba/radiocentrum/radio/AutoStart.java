package pl.kuba.radiocentrum.radio;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Locale;

/**
 * Po starcie / wybudzeniu radia: uruchom Android Auto (ZLink), a po kilkunastu sekundach wróć na Pulpit.
 * Android Auto zostaje połączone w tle.
 */
final class AutoStart {
    private AutoStart() {
    }

    static final String[] AA_WORDS = {"zlink", "android auto", "carplay", "autokit", "carlink", "auto link", "phonelink"};
    static final int[] DELAYS = {15, 25, 40, 60, 0}; // 0 = zostań w Android Auto

    private static long lastRun;
    private static final Handler h = new Handler(Looper.getMainLooper());

    /** Zapisana albo wykryta aplikacja Android Auto (np. ZLink5). */
    static ComponentName aaApp(Context c) {
        String f = c.getSharedPreferences("rc", Context.MODE_PRIVATE).getString("role_aa", null);
        if (f != null) {
            ComponentName cn = ComponentName.unflattenFromString(f);
            if (cn != null) return cn;
        }
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        for (String w : AA_WORDS) {
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                if (ri.activityInfo == null) continue;
                String t = (ri.loadLabel(pm) + " " + ri.activityInfo.packageName).toLowerCase(Locale.ROOT);
                if (t.contains(w)) return new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            }
        }
        return null;
    }

    static boolean launchAA(Context c) {
        ComponentName cn = aaApp(c);
        if (cn == null) return false;
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(cn);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void backToPulpit(Context c) {
        try {
            Intent i = new Intent(c, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            c.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    static final String[] FM_WORDS = {"fmradio", "localradio", "radio", "fm", "tuner"};

    /** Aplikacja radia FM: wybrana w Ustawieniach albo wykryta. */
    static ComponentName radioApp(Context c) {
        String f = c.getSharedPreferences("rc", Context.MODE_PRIVATE).getString("role_radio", null);
        if (f != null) {
            ComponentName cn = ComponentName.unflattenFromString(f);
            if (cn != null) return cn;
        }
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        for (String w : FM_WORDS) {
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(c.getPackageName())) continue; // nie my (Radio Centrum)
                String t = (ri.loadLabel(pm) + " " + pkg).toLowerCase(Locale.ROOT);
                if (t.contains(w)) return new ComponentName(pkg, ri.activityInfo.name);
            }
        }
        return null;
    }

    static boolean launch(Context c, ComponentName cn) {
        if (cn == null) return false;
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(cn);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Po starcie / wybudzeniu radia: radio FM w tle, ewentualnie Android Auto, na końcu zawsze Pulpit.
     * Pulpit jest pokazywany kilka razy, bo radio samo potrafi przywrócić ostatnią aplikację (np. Mapy).
     */
    static void run(Context c) {
        final Context app = c.getApplicationContext();
        long now = SystemClock.elapsedRealtime();
        if (lastRun != 0 && now - lastRun < 60 * 1000) return;
        lastRun = now;
        h.removeCallbacksAndMessages(null);
        backToPulpit(app);
        long t = 3000;
        if (Prefs.fmAuto(app)) {
            h.postDelayed(() -> launch(app, radioApp(app)), t);
            t += 2500;
        }
        long pulpitAt;
        if (Prefs.aaAuto(app) && aaApp(app) != null) {
            h.postDelayed(() -> launchAA(app), t);
            int d = Prefs.aaDelay(app);
            if (d == 0) return; // zostań w Android Auto
            pulpitAt = t + d * 1000L;
        } else {
            pulpitAt = t;
        }
        h.postDelayed(() -> backToPulpit(app), pulpitAt);
        h.postDelayed(() -> backToPulpit(app), pulpitAt + 6000);
        h.postDelayed(() -> backToPulpit(app), pulpitAt + 15000);
    }

    static String delayLabel(int d) {
        return d == 0 ? "zostań w Android Auto" : "po " + d + " s wróć na Pulpit";
    }
}
