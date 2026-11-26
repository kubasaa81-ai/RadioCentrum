package pl.kuba.radiocentrum.radio;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.Toast;

/**
 * Sterowanie fabrycznym radiem FM (com.android.fmradio) bez otwierania jego ekranu.
 * Jego usługa FmService jest dostępna dla innych aplikacji i przyjmuje polecenia:
 * fmradio.seek.next / fmradio.seek.previous / fmradio.turnoff.
 * Gdy usługa nie przyjmie polecenia – zapas: przycisk multimediów (następna/poprzednia).
 */
final class FmRadio {
    private FmRadio() {
    }

    static final String PKG = "com.android.fmradio";
    private static final ComponentName SVC = new ComponentName(PKG, PKG + ".FmService");

    static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void next(Context c) {
        command(c, "fmradio.seek.next", KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    static void prev(Context c) {
        command(c, "fmradio.seek.previous", KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    static void off(Context c) {
        command(c, "fmradio.turnoff", KeyEvent.KEYCODE_MEDIA_STOP);
    }

    /** Otwórz ekran radia FM (włącza radio, jeśli było wyłączone). */
    static void open(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(PKG);
        if (i == null) {
            android.content.ComponentName cn = AutoStart.radioApp(c);
            if (cn != null) AutoStart.launch(c, cn);
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private static void command(Context c, String action, int key) {
        try {
            Intent i = new Intent(action).setComponent(SVC);
            if (c.startService(i) != null) return;
        } catch (Exception ignored) {
            // np. ograniczenia uruchamiania usług w tle – spróbuj przyciskiem multimediów
        }
        try {
            long t = SystemClock.uptimeMillis();
            for (int a : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                Intent m = new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(PKG);
                m.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(t, t, a, key, 0));
                c.sendBroadcast(m);
            }
        } catch (Exception e) {
            Toast.makeText(c, "Radio FM nie odpowiada", Toast.LENGTH_SHORT).show();
        }
    }
}
