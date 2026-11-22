package pl.kuba.radiocentrum.radio;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Wykrywanie zgaszenia / włączenia zapłonu.
 * Radio po zgaszeniu auta zwykle tylko gasi podświetlenie i jeszcze chwilę działa,
 * więc słuchamy wielu sygnałów naraz i każdy zapisujemy (podgląd na telefonie).
 */
final class AccWatch {
    private AccWatch() {
    }

    private static final String[] ACTIONS = {
            Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON, Intent.ACTION_SHUTDOWN,
            "android.intent.action.ACC_OFF", "android.intent.action.ACC_ON",
            "android.intent.action.ACTION_ACC_OFF", "android.intent.action.ACTION_ACC_ON",
            "com.autochips.intent.action.ACC_OFF", "com.autochips.intent.action.ACC_ON",
            "autochips.intent.action.QB_POWEROFF", "autochips.intent.action.QB_POWERON",
            "autochips.intent.action.PREQB_POWEROFF", "autochips.intent.action.PREQB_POWERON",
            "autochips.intent.action.QB_PREPOWEROFF", "com.autochips.quickboot.POWEROFF",
            "com.autochips.quickboot.POWERON",
            "com.xyauto.acc.off", "com.xyauto.acc.on", "com.xyauto.ACC_OFF", "com.xyauto.ACC_ON",
            "com.xy.acc.off", "com.xy.acc.on", "com.xygala.acc.off", "com.xygala.acc.on",
            "com.xygala.canbus.ACC_OFF", "com.xygala.canbus.ACC_ON",
            "android.intent.action.FAST_BOOT_START", "android.intent.action.FAST_BOOT_END",
            "com.android.action.ACC_OFF", "com.android.action.ACC_ON",
            "com.car.acc.off", "com.car.acc.on", "car.intent.action.ACC_OFF", "car.intent.action.ACC_ON",
            "android.intent.action.MCU_ACC_OFF", "android.intent.action.MCU_ACC_ON",
            "com.mediatek.intent.action.ACC_OFF", "com.mediatek.intent.action.ACC_ON",
            "android.intent.action.SLEEP", "android.intent.action.WAKEUP",
            "com.autochips.intent.action.SLEEP", "com.autochips.intent.action.WAKEUP",
    };

    private static final Map<String, String> snap = new HashMap<>();
    private static long lastOff, lastOn;

    static void start(final RadioService s) {
        IntentFilter f = new IntentFilter();
        for (String a : ACTIONS) f.addAction(a);
        s.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String a = String.valueOf(i.getAction());
                log("sygnał " + a);
                String up = a.toUpperCase(Locale.ROOT);
                if (a.equals(Intent.ACTION_SCREEN_OFF) || a.equals(Intent.ACTION_SHUTDOWN)
                        || up.contains("OFF") || up.endsWith("SLEEP") || up.contains("FAST_BOOT_START")) {
                    off("sygnał " + a);
                } else {
                    on("sygnał " + a);
                }
            }
        }, f);

        // zmiany w ustawieniach systemu (moduł CAN/MCU często zapisuje tam stan ACC)
        Handler h = new Handler(Looper.getMainLooper());
        final ContentResolver cr = s.getContentResolver();
        takeSnap(cr, "system");
        takeSnap(cr, "global");
        ContentObserver ob = new ContentObserver(h) {
            @Override
            public void onChange(boolean self, Uri uri) {
                String table = uri != null && String.valueOf(uri).contains("global") ? "global" : "system";
                diff(cr, table);
            }
        };
        try {
            cr.registerContentObserver(Uri.parse("content://settings/system"), true, ob);
            cr.registerContentObserver(Uri.parse("content://settings/global"), true, ob);
        } catch (Exception e) {
            log("nie mogę obserwować ustawień: " + e.getMessage());
        }
    }

    private static void takeSnap(ContentResolver cr, String table) {
        try (Cursor c = cr.query(Uri.parse("content://settings/" + table), new String[]{"name", "value"}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) snap.put(table + ":" + c.getString(0), String.valueOf(c.getString(1)));
        } catch (Exception ignored) {
        }
    }

    private static void diff(ContentResolver cr, String table) {
        try (Cursor c = cr.query(Uri.parse("content://settings/" + table), new String[]{"name", "value"}, null, null, null)) {
            if (c == null) return;
            int n = 0;
            while (c.moveToNext()) {
                String k = table + ":" + c.getString(0);
                String v = String.valueOf(c.getString(1));
                String old = snap.put(k, v);
                if (old != null && !old.equals(v)) {
                    String name = c.getString(0).toLowerCase(Locale.ROOT);
                    if (name.contains("brightness_float") || name.equals("next_alarm_formatted")) continue;
                    if (n++ < 6) log("ustawienie " + k + ": " + old + " → " + v);
                    if (name.contains("acc") && !name.contains("access") && !name.contains("accel")
                            && !name.contains("account")) {
                        String lv = v.toLowerCase(Locale.ROOT);
                        if (lv.equals("0") || lv.equals("false") || lv.equals("off")) off("ustawienie " + k + "=" + v);
                        else if (lv.equals("1") || lv.equals("true") || lv.equals("on")) on("ustawienie " + k + "=" + v);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void off(String why) {
        long now = System.currentTimeMillis();
        if (now - lastOff < 20000) return;
        lastOff = now;
        log("➡ ZGASZENIE (" + why + ") – zamykam Yanosika");
        RadioService.sendToPhone(J.obj("t", "car_off"));
    }

    private static void on(String why) {
        long now = System.currentTimeMillis();
        if (now - lastOn < 20000) return;
        lastOn = now;
        log("➡ WŁĄCZENIE (" + why + ")");
        RadioService.sendToPhone(J.obj("t", "car_on"));
    }

    static void log(String s) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        RadioService.sendToPhone(J.obj("t", "rlog", "text", "RADIO " + t + ": " + s));
    }
}
