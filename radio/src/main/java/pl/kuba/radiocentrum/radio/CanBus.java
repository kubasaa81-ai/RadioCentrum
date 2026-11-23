package pl.kuba.radiocentrum.radio;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import java.util.Locale;

/**
 * Odbiór danych z modułu CAN (Raise VW PQ) przez systemową usługę radia „canbus_service”.
 * Rejestrujemy się jako DODATKOWY słuchacz (usługa trzyma listę) – nic nie wysyłamy do auta.
 * Ramka 0x41 (informacje o aucie): paliwo [12] w litrach, przebieg [9..11], temperatura [7..8].
 */
final class CanBus {
    private CanBus() {
    }

    private static final String SVC = "android.os.ICanbusService";
    private static final String CB = "android.os.ICanbusInterface";
    private static final int TX_SET_INTERFACE = 3;   // setCanbusInterface
    private static final int TX_ON_RESULT = 1;       // ICanbusInterface.onResult(byte[], int)

    static volatile int fuelL = -1;
    static volatile int mileageKm = -1;
    static volatile double outTemp = Double.NaN;
    static volatile long at;
    static volatile boolean registered;
    static volatile String status = "nie połączono";

    private static Context app;
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static final byte[] buf = new byte[512];
    private static int bufLen;
    private static long lastLog;

    private static final Binder callback = new Binder() {
        {
            attachInterface(null, CB);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == TX_ON_RESULT) {
                try {
                    data.enforceInterface(CB);
                    byte[] b = data.createByteArray();
                    int len = data.readInt();
                    if (b != null) feed(b, Math.min(Math.max(len, 0) == 0 ? b.length : len, b.length));
                } catch (Throwable ignored) {
                }
                if (reply != null) reply.writeNoException();
                return true;
            }
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (Throwable t) {
                return false;
            }
        }
    };

    static void start(Context c) {
        app = c.getApplicationContext();
        h.post(CanBus::register);
    }

    private static void register() {
        if (registered) return;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder svc = (IBinder) sm.getMethod("getService", String.class).invoke(null, "canbus_service");
            if (svc == null) throw new IllegalStateException("brak usługi");
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            try {
                d.writeInterfaceToken(SVC);
                d.writeStrongBinder(callback);
                svc.transact(TX_SET_INTERFACE, d, r, 0);
                r.readException();
            } finally {
                d.recycle();
                r.recycle();
            }
            registered = true;
            status = "połączono z CAN";
            try {
                svc.linkToDeath(() -> {
                    registered = false;
                    status = "usługa CAN zrestartowana";
                    h.postDelayed(CanBus::register, 5000);
                }, 0);
            } catch (Exception ignored) {
            }
        } catch (Throwable t) {
            status = "błąd: " + t.getClass().getSimpleName();
            h.postDelayed(CanBus::register, 30000);
        }
    }

    /** Surowe bajty z modułu – składamy ramki Raise: 0x2E, cmd, len, dane…, suma. */
    private static synchronized void feed(byte[] b, int n) {
        for (int i = 0; i < n; i++) {
            if (bufLen >= buf.length) bufLen = 0;
            buf[bufLen++] = b[i];
        }
        while (true) {
            int s = 0;
            while (s < bufLen && (buf[s] & 0xff) != 0x2E) s++;
            if (s > 0) {
                System.arraycopy(buf, s, buf, 0, bufLen - s);
                bufLen -= s;
            }
            if (bufLen < 3) return;
            int len = buf[2] & 0xff;
            int total = len + 4;
            if (total > buf.length) {
                // śmieci – przesuń o 1
                System.arraycopy(buf, 1, buf, 0, bufLen - 1);
                bufLen--;
                continue;
            }
            if (bufLen < total) return;
            byte[] f = new byte[total];
            System.arraycopy(buf, 0, f, 0, total);
            System.arraycopy(buf, total, buf, 0, bufLen - total);
            bufLen -= total;
            frame(f);
        }
    }

    private static void frame(byte[] f) {
        int cmd = f[1] & 0xff;
        if (cmd != 0x41 || f.length < 13) return;
        int fuel = f[12] & 0xff;
        int km = ((f[9] & 0xff) << 16) | ((f[10] & 0xff) << 8) | (f[11] & 0xff);
        int raw = ((f[7] & 0xff) << 8) | (f[8] & 0xff);
        double t = raw >= 0x8000 ? raw - 0x10000 : raw;
        if (Math.abs(t) > 70) t = t / 10.0;
        if (fuel > 0 && fuel < 120) fuelL = fuel;
        if (km > 0) mileageKm = km;
        if (t > -45 && t < 65) {
            outTemp = t;
            Pogoda.fromCan(t);
        }
        at = System.currentTimeMillis();
        learn(fuelL, mileageKm);
        long now = System.currentTimeMillis();
        if (now - lastLog > 120000) {
            lastLog = now;
            StringBuilder hex = new StringBuilder();
            for (byte x : f) hex.append(String.format("%02X ", x & 0xff));
            AccWatch.log("CAN 0x41: " + hex + "→ paliwo " + fuelL + " L, przebieg " + mileageKm + " km, temp " + t);
        }
    }

    // ---------------------------------------------------------------- spalanie i zasięg

    private static SharedPreferences p() {
        return app.getSharedPreferences("rc_can", Context.MODE_PRIVATE);
    }

    /** Uczy się średniego spalania z ubytku paliwa na przejechanych km (tankowanie zeruje pomiar). */
    private static void learn(int fuel, int km) {
        if (app == null || fuel < 0 || km < 0) return;
        SharedPreferences sp = p();
        int bKm = sp.getInt("baseKm", -1), bFuel = sp.getInt("baseFuel", -1);
        if (bKm < 0 || km < bKm || fuel > bFuel + 3) {
            sp.edit().putInt("baseKm", km).putInt("baseFuel", fuel).apply();
            return;
        }
        int dKm = km - bKm, dF = bFuel - fuel;
        if (dKm >= 60 && dF >= 3) {
            float c = dF * 100f / dKm;
            if (c > 3 && c < 20) {
                float old = sp.getFloat("cons", 0);
                float nw = old > 0 ? (old + c) / 2f : c;
                sp.edit().putFloat("cons", nw).putInt("baseKm", km).putInt("baseFuel", fuel).apply();
            }
        }
    }

    /** Średnie spalanie l/100 km: wyliczone z jazdy albo domyślne 7,5. */
    static float consumption() {
        if (app == null) return 7.5f;
        float c = p().getFloat("cons", 0);
        return c > 0 ? c : 7.5f;
    }

    static boolean consLearned() {
        return app != null && p().getFloat("cons", 0) > 0;
    }

    static boolean fresh() {
        return fuelL >= 0 && System.currentTimeMillis() - at < 5 * 60000L;
    }

    static String fuelText() {
        return fuelL < 0 ? "–" : fuelL + " L";
    }

    static String rangeText() {
        if (fuelL < 0) return "–";
        int r = Math.round(fuelL * 100f / consumption());
        return (consLearned() ? "" : "~") + r + " km";
    }

    static String info() {
        return "⛽ " + fuelText() + " · zasięg " + rangeText() + "\n"
                + String.format(Locale.US, "Spalanie: %.1f l/100 km", consumption())
                + (consLearned() ? " (wyliczone z Twojej jazdy)" : " (domyślne – wyliczy się samo po ok. 60 km)")
                + "\nPrzebieg: " + (mileageKm < 0 ? "–" : mileageKm + " km") + "\nCAN: " + status;
    }
}
