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
    /** null = nie wiadomo, true = radio obudzone, false = zgaszone. */
    private static volatile Boolean carOn;
    /** Telefon dostał polecenie „włącz Yanosika” (car_on). Wysyłamy je TYLKO przy światłach albo jeździe. */
    private static volatile boolean yanOn;
    /** Ostatnie „zgaszenie” wynikało ze zgaszenia świateł (wtedy GPS nie włącza z powrotem). */
    private static volatile boolean lampOff;
    private static int fastCount;
    private static Handler lampH;
    private static ContentResolver res;
    /** Światła: reaguj dopiero, gdy stan utrzyma się 3 s (przy kluczyku sygnał chwilę „mruga”). */
    private static final Runnable lampCheck = () -> {
        if (engineKnown()) return; // jest CAN – decyduje praca silnika, nie światła
        String v = snap.get("system:Headlamp");
        if ("1".equals(v)) yanOn("światła włączone");
        else if ("0".equals(v) && yanOn) off("światła wyłączone");
    };

    /** Prędkość z GPS: jeśli auto jedzie, a Yanosik nie działa (np. jazda bez świateł), włącz go. */
    static void onSpeed(int kmh) {
        if (yanOn || lampOff || engineKnown()) return;
        fastCount = kmh >= 8 ? fastCount + 1 : 0;
        if (fastCount >= 2) yanOn("jazda – GPS " + kmh + " km/h");
    }

    // ------------------------------------------------------------------ silnik (CAN – obroty)

    private static long runSince, zeroSince, rpmAt;
    private static volatile int lastRpm = -1;

    /** Mamy świeże obroty z CAN (ostatnie 15 s) – wtedy one decydują o Yanosiku. */
    static boolean engineKnown() {
        return Prefs.canOnCached && lastRpm >= 0 && System.currentTimeMillis() - rpmAt < 15000;
    }

    /** Obroty z CAN: silnik pracuje (≥ 300/min przez 2 s) → Yanosik; 0 przez 10 s → zamknij. */
    static void onRpm(int rpm) {
        if (rpm < 0) return;
        long now = System.currentTimeMillis();
        lastRpm = rpm;
        rpmAt = now;
        if (rpm >= 300) {
            zeroSince = 0;
            if (runSince == 0) runSince = now;
            if (!yanOn && now - runSince >= 2000) yanOn("silnik pracuje – " + rpm + " obr/min");
        } else if (rpm == 0) {
            runSince = 0;
            if (zeroSince == 0) zeroSince = now;
            if (yanOn && now - zeroSince >= 10000) off("silnik zgaszony (CAN)");
        }
    }

    /** Telefon właśnie się połączył: powiedz mu, czy Yanosik ma działać (sprawdź światła na nowo). */
    static void hello() {
        if (engineKnown()) {
            if (lastRpm >= 300) {
                if (yanOn) RadioService.sendToPhone(J.obj("t", "car_on"));
                else yanOn("silnik pracuje (po połączeniu)");
            } else if (yanOn) {
                off("silnik zgaszony (po połączeniu)");
            } else {
                log("telefon połączony – silnik zgaszony, Yanosik czeka");
            }
            return;
        }
        String v = lampNow();
        if ("0".equals(v)) {
            if (yanOn) off("światła wyłączone (po połączeniu)");
            return;
        }
        if (yanOn) {
            RadioService.sendToPhone(J.obj("t", "car_on"));
        } else if ("1".equals(v)) {
            yanOn("światła włączone (po połączeniu)");
        } else {
            log("telefon połączony – światła zgaszone, Yanosik czeka");
        }
    }

    /** Aktualny stan świateł czytany na nowo z ustawień (w czuwaniu radio mogło nie zgłosić zmiany). */
    private static String lampNow() {
        if (res != null) takeSnap(res, "system");
        return snap.get("system:Headlamp");
    }

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
                    wake("sygnał " + a);
                }
            }
        }, f);

        // zmiany w ustawieniach systemu (moduł CAN/MCU często zapisuje tam stan ACC)
        Handler h = new Handler(Looper.getMainLooper());
        lampH = h;
        final ContentResolver cr = s.getContentResolver();
        res = cr;
        takeSnap(cr, "system");
        takeSnap(cr, "global");
        takeSnap(cr, "secure");
        ContentObserver ob = new ContentObserver(h) {
            @Override
            public void onChange(boolean self, Uri uri) {
                String u = String.valueOf(uri);
                String table = u.contains("global") ? "global" : u.contains("secure") ? "secure" : "system";
                diff(cr, table);
            }
        };
        try {
            cr.registerContentObserver(Uri.parse("content://settings/system"), true, ob);
            cr.registerContentObserver(Uri.parse("content://settings/global"), true, ob);
            cr.registerContentObserver(Uri.parse("content://settings/secure"), true, ob);
        } catch (Exception e) {
            log("nie mogę obserwować ustawień: " + e.getMessage());
        }
        // radio uruchomione przy włączonych światłach
        if ("1".equals(snap.get("system:Headlamp"))) h.postDelayed(lampCheck, 3000);
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
                    if (k.equals("system:Headlamp") && lampH != null) {
                        lampH.removeCallbacks(lampCheck);
                        lampH.postDelayed(lampCheck, 3000);
                    }
                    if (k.equals("system:hasaccoff")) {
                        // ustawiane od razu po wyjęciu kluczyka (uśpienie radia jest dopiero ok. 44 s później)
                        if ("1".equals(v)) {
                            lampOff = false; // nowa jazda – GPS znów może włączyć Yanosika
                            off("wyjęty kluczyk – hasaccoff=1");
                        } else if ("0".equals(v)) wake("kluczyk – hasaccoff=0");
                    }
                    if (k.equals("global:airplane_mode_on")) {
                        // radio włącza tryb samolotowy przy zgaszeniu zapłonu
                        if ("1".equals(v)) off("tryb samolotowy");
                        else wake("tryb samolotowy wyłączony");
                    }
                    if (name.contains("acc") && !name.contains("hasacc") && !name.contains("access") && !name.contains("accel")
                            && !name.contains("account")) {
                        String lv = v.toLowerCase(Locale.ROOT);
                        if (lv.equals("0") || lv.equals("false") || lv.equals("off")) off("ustawienie " + k + "=" + v);
                        else if (lv.equals("1") || lv.equals("true") || lv.equals("on")) wake("ustawienie " + k + "=" + v);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Zgaszenie: zamknij Yanosika (każdy sygnał wystarczy). */
    private static void off(String why) {
        if (why.startsWith("światła")) lampOff = true;
        fastCount = 0;
        if (lampH != null && !why.startsWith("światła")) lampH.removeCallbacks(lampCheck);
        if (Boolean.FALSE.equals(carOn) && !yanOn) return; // już zgaszone – nie powtarzaj
        carOn = false;
        yanOn = false;
        log("➡ ZGASZENIE (" + why + ") – zamykam Yanosika");
        RadioService rs0 = RadioService.instance;
        if (rs0 != null && CanBus.mileageKm > 0) Auto.sendState(rs0, true); // miejsce postoju na telefon
        RadioService.sendToPhone(J.obj("t", "car_off"));
    }

    /**
     * Radio się obudziło (kluczyk, wyjście z czuwania). Yanosika NIE włączamy –
     * czekamy na światła albo jazdę. Sprawdzamy tylko, czy światła już się palą.
     */
    private static void wake(String why) {
        if (!Boolean.TRUE.equals(carOn)) {
            carOn = true;
            log("radio obudzone (" + why + ") – Yanosik czeka na światła");
            RadioService rs = RadioService.instance;
            if (rs != null) AutoStart.run(rs); // ekran: FM + Pulpit
        }
        if (lampH != null && !yanOn) {
            lampH.removeCallbacks(lampCheck);
            lampH.postDelayed(() -> {
                lampNow();
                lampCheck.run();
            }, 3000);
        }
    }

    /** Włącz Yanosika – tylko przy włączonych światłach albo podczas jazdy. */
    private static void yanOn(String why) {
        if (yanOn) return;
        carOn = true;
        yanOn = true;
        lampOff = false;
        log("➡ WŁĄCZENIE (" + why + ") – włączam Yanosika");
        RadioService.sendToPhone(J.obj("t", "car_on"));
    }

    static void log(String s) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        RadioService.sendToPhone(J.obj("t", "rlog", "text", "RADIO " + t + ": " + s));
    }
}
