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
 * Usługa ma DWIE listy słuchaczy i dane dostaje ostatni z każdej listy. Fabryczna aplikacja CAN
 * jest na pierwszej (setCanbusInterface = 3), my dopisujemy się do drugiej (setCanbusInterface_other = 5),
 * więc obie aplikacje dostają dane naraz. Nic nie wysyłamy do auta.
 * Ramka 0x41 (informacje o aucie): paliwo [12] w litrach, przebieg [9..11], temperatura [7..8].
 */
final class CanBus {
    private CanBus() {
    }

    private static final String SVC = "android.os.ICanbusService";
    private static final String CB = "android.os.ICanbusInterface";
    private static final int TX_SET_OTHER = 5;       // setCanbusInterface_other (druga lista)
    private static final int TX_REMOVE_OTHER = 6;    // removeCanbusInterface_other
    private static final int TX_SET_VALUE = 1;       // setValue(byte[]) – wysyłka do modułu CAN
    /**
     * Zapytanie „podaj dane auta” (paliwo, obroty, przebieg, temp.) – dokładnie to samo, co fabryczna
     * aplikacja wysyła co 1,5 s przy otwartym ekranie „car information”: 2E 90 02 41 02 + suma.
     * To tylko prośba o dane do modułu CAN, nic nie zmienia w aucie.
     */
    private static final byte[] REQ_CARINFO = {0x2E, (byte) 0x90, 0x02, 0x41, 0x02, 0x2A};
    static volatile int requests;
    private static int pollN;
    private static final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!wanted || !registered) return;
            // gdy fabryczny ekran jest otwarty, on już pyta – nie dublujemy
            if (System.currentTimeMillis() - at > 300) request(); // obroty na bieżąco (ok. 2–3 razy na sekundę)
            // stany (ręczny, pasy, spryskiwacze) – po aktualizacji modułu przychodzą tylko na prośbę
            if (++pollN % 5 == 0) ask(0x41, 0x01);
            h.postDelayed(this, rpm > 0 ? 400 : 2000); // przy pracującym silniku częściej – do podpowiedzi biegu
        }
    };

    private static void request() {
        send(REQ_CARINFO);
    }

    private static void send(byte[] q) {
        IBinder svc = svcRef;
        if (svc == null) return;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(SVC);
            d.writeByteArray(q);
            svc.transact(TX_SET_VALUE, d, r, 0);
            r.readException();
            requests++;
        } catch (Throwable e) {
            status = "zapytanie o dane nie poszło: " + e.getClass().getSimpleName();
        } finally {
            d.recycle();
            r.recycle();
        }
    }
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
    private static IBinder svcRef;
    private static volatile boolean wanted;
    // podgląd surowych danych
    static volatile int chunks, frames;
    static volatile String lastChunk = "–";
    private static final java.util.TreeMap<Integer, String> lastByCmd = new java.util.TreeMap<>();

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
        Auto.init(app);
        wanted = true;
        h.post(CanBus::register);
    }

    /** Odłącz się od usługi CAN (fabryczna aplikacja nadal działa). */
    static void stop() {
        wanted = false;
        h.removeCallbacksAndMessages(null);
        IBinder svc = svcRef;
        if (svc != null && registered) {
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            try {
                d.writeInterfaceToken(SVC);
                d.writeStrongBinder(callback);
                svc.transact(TX_REMOVE_OTHER, d, r, 0);
                r.readException();
            } catch (Throwable ignored) {
            } finally {
                d.recycle();
                r.recycle();
            }
        }
        registered = false;
        status = "wyłączone";
    }

    private static void register() {
        if (registered || !wanted) return;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder svc = (IBinder) sm.getMethod("getService", String.class).invoke(null, "canbus_service");
            if (svc == null) throw new IllegalStateException("brak usługi");
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            try {
                d.writeInterfaceToken(SVC);
                d.writeStrongBinder(callback);
                svc.transact(TX_SET_OTHER, d, r, 0);
                r.readException();
            } finally {
                d.recycle();
                r.recycle();
            }
            registered = true;
            svcRef = svc;
            status = "połączono z CAN (druga lista – obok fabrycznej)";
            h.removeCallbacks(poll);
            h.postDelayed(poll, 1500);
            try {
                svc.linkToDeath(() -> {
                    registered = false;
                    status = "usługa CAN zrestartowana";
                    if (wanted) h.postDelayed(CanBus::register, 5000);
                }, 0);
            } catch (Exception ignored) {
            }
        } catch (Throwable t) {
            status = "błąd: " + t.getClass().getSimpleName();
            if (wanted) h.postDelayed(CanBus::register, 30000);
        }
    }

    /** Surowe bajty z modułu – składamy ramki Raise: 0x2E, cmd, len, dane…, suma. */
    private static synchronized void feed(byte[] b, int n) {
        chunks++;
        lastChunk = hex(b, n);
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

    static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n && i < 64; i++) sb.append(String.format("%02X ", b[i] & 0xff));
        return sb.toString().trim();
    }

    /** Podgląd do porównania z fabrycznym ekranem „Informacje o aucie”. */
    private static final java.util.TreeMap<Integer, Integer> countByCmd = new java.util.TreeMap<>();
    private static final java.util.HashMap<Integer, String> lastLogged = new java.util.HashMap<>();
    private static final java.util.ArrayDeque<String> frameLog = new java.util.ArrayDeque<>();

    /**
     * Zapis podglądu do pliku w Pobranych: stan, wszystkie rodzaje ramek z licznikami
     * i dziennik ostatnich zmian ramek (do 3000 wpisów, z godziną).
     */
    static java.io.File saveToFile(Context c) throws java.io.IOException {
        String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new java.util.Date());
        java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
        dir.mkdirs();
        java.io.File f = new java.io.File(dir, "CAN-podglad-" + ts + ".txt");
        StringBuilder sb = new StringBuilder();
        sb.append("Radio Centrum – podgląd CAN ").append(ts).append("\n\n").append(dump()).append("\n");
        synchronized (CanBus.class) {
            sb.append("=== Rodzaje ramek (ile razy przyszły) ===\n");
            for (java.util.Map.Entry<Integer, Integer> e : countByCmd.entrySet()) {
                sb.append(keyName(e.getKey())).append("  ×").append(e.getValue()).append("\n");
            }
            sb.append("\n=== Dziennik zmian ramek (najstarsze na górze) ===\n");
            for (String l : frameLog) sb.append(l).append("\n");
        }
        java.io.FileOutputStream out = new java.io.FileOutputStream(f);
        try {
            out.write(sb.toString().getBytes("UTF-8"));
        } finally {
            out.close();
        }
        return f;
    }

    // ---------------------------------------------------------------- szukanie zmian (np. klima)

    private static java.util.Map<Integer, String> snap;
    private static final java.util.TreeMap<Integer, String> newKinds = new java.util.TreeMap<>();
    private static final java.util.TreeMap<Integer, Integer> changedCnt = new java.util.TreeMap<>();
    private static final java.util.TreeMap<Integer, String> changedLast = new java.util.TreeMap<>();
    private static long snapAt;

    /** Zapamiętaj wszystkie ramki – potem dump() pokaże, co się zmieniło albo pojawiło nowego. */
    static synchronized void startSearch() {
        snap = new java.util.HashMap<>(lastByCmd);
        newKinds.clear();
        changedCnt.clear();
        changedLast.clear();
        snapAt = System.currentTimeMillis();
    }

    static synchronized void stopSearch() {
        snap = null;
    }

    static boolean searching() {
        return snap != null;
    }

    private static String keyName(int k) {
        return k > 0xff ? String.format("0x%02X/%02X", k >> 8, k & 0xff) : String.format("0x%02X", k);
    }

    /** Zapytanie modułu CAN o dany rodzaj danych (2E 90 02 cmd sub + suma) – tylko prośba o dane. */
    static void ask(final int cmd, final int sub) {
        h.post(() -> {
            byte[] q = {0x2E, (byte) 0x90, 0x02, (byte) cmd, (byte) sub, 0};
            q[5] = (byte) ((0x90 + 0x02 + cmd + sub) & 0xff ^ 0xff);
            send(q);
        });
    }

    static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("Stan: ").append(status).append("\n");
        sb.append("Odebrane paczki: ").append(chunks).append(", ramki: ").append(frames)
                .append(", zapytania o dane: ").append(requests).append("\n");
        long age = at == 0 ? -1 : (System.currentTimeMillis() - at) / 1000;
        sb.append("Dane auta (paliwo, obroty): ").append(age < 0 ? "jeszcze nie przyszły" : "sprzed " + age + " s").append("\n");
        sb.append("Ostatnia paczka: ").append(lastChunk).append("\n");
        if (app != null) sb.append(Auto.shiftInfo(app)).append("\n");
        sb.append("\n");
        sb.append("Odczytane: paliwo ").append(fuelText()).append(", przebieg ")
                .append(mileageKm < 0 ? "–" : mileageKm + " km").append(", temp. ")
                .append(Double.isNaN(outTemp) ? "–" : String.format(Locale.US, "%.1f°C", outTemp))
                .append(", obroty ").append(rpm < 0 ? "–" : rpm + "/min").append(", akumulator ")
                .append(Double.isNaN(volts) ? "–" : String.format(Locale.US, "%.1f V", volts))
                .append(", prędkość z auta ").append(Auto.canKmh < 0 ? "–" : String.format(Locale.US, "%.1f km/h", Auto.canKmh))
                .append(" [skala ").append(Auto.scaleText()).append("]")
                .append(" (GPS ").append(Fotoradary.last != null ? Fotoradary.speedKmh + " km/h" : "–").append(")")
                .append(", ręczny ").append(handbrakeUp == null ? "–" : handbrakeUp ? "ZACIĄGNIĘTY" : "opuszczony")
                .append("\n\n");
        if (snap != null) {
            sb.append("🔍 SZUKANIE ZMIAN – od ").append((System.currentTimeMillis() - snapAt) / 1000).append(" s\n");
            sb.append("NOWE rodzaje ramek:\n");
            if (newKinds.isEmpty()) sb.append("  brak\n");
            for (java.util.Map.Entry<Integer, String> e : newKinds.entrySet()) {
                sb.append("  ★ ").append(keyName(e.getKey())).append(": ").append(e.getValue()).append("\n");
            }
            sb.append("ZMIENIONE (bez paliwa/obrotów 0x41/02):\n");
            int shown = 0;
            for (java.util.Map.Entry<Integer, Integer> e : changedCnt.entrySet()) {
                int k = e.getKey();
                if (k == 0x4102) continue;
                shown++;
                sb.append("  ").append(keyName(k)).append(" ×").append(e.getValue()).append("\n")
                        .append("     było: ").append(snap.get(k)).append("\n")
                        .append("     jest: ").append(changedLast.get(k)).append("\n");
            }
            if (shown == 0) sb.append("  brak\n");
            sb.append("\n");
        }
        sb.append("Ostatnia ramka każdego rodzaju (rodzaj: bajty):\n");
        if (lastByCmd.isEmpty()) sb.append("  brak – nic nie przyszło\n");
        for (java.util.Map.Entry<Integer, String> e : lastByCmd.entrySet()) {
            int k = e.getKey();
            sb.append(k > 0xff ? String.format("0x%02X/%02X: ", k >> 8, k & 0xff) : String.format("0x%02X: ", k))
                    .append(e.getValue()).append("\n");
        }
        return sb.toString();
    }

    static volatile int rpm = -1;
    static volatile Boolean handbrakeUp;
    static volatile int speedRaw = -1;
    private static int hbCount;
    private static boolean hbWarned;

    /** Ruszasz z zaciągniętym ręcznym → duży znak i sygnał (raz, do opuszczenia ręcznego). */
    private static void checkHandbrake() {
        if (!Boolean.TRUE.equals(handbrakeUp)) {
            hbCount = 0;
            hbWarned = false;
            Popup.hideAlertIf("hand");
            return;
        }
        boolean moving = Auto.speed() >= 6;
        hbCount = moving ? hbCount + 1 : 0;
        if (hbCount >= 2 && !hbWarned && app != null) {
            hbWarned = true;
            Popup.showAlert(app, "hand", "", -1);
        }
    }
    static volatile double volts = Double.NaN;

    /**
     * Ramka 0x41, podtyp 02 (sprawdzone z fabrycznym ekranem „car information”):
     * 2E 41 0D | 02 | obroty[2] | prędkość[2] | napięcie×100[2] | temp×10[2, ze znakiem] | przebieg[3] | paliwo L | suma
     */
    private static void frame(byte[] f) {
        int cmd = f[1] & 0xff;
        frames++;
        synchronized (CanBus.class) {
            // 0x41 ma podtypy (01 = stany: ręczny itd., 02 = silnik/paliwo) – trzymamy osobno
            int key = cmd == 0x41 && f.length > 3 ? 0x4100 | (f[3] & 0xff) : cmd;
            String hx = hex(f, f.length);
            lastByCmd.put(key, hx);
            Integer cn = countByCmd.get(key);
            countByCmd.put(key, cn == null ? 1 : cn + 1);
            // dziennik ramek do pliku – pomijamy powtórki identycznej ramki (oszczędza miejsce)
            if (!hx.equals(lastLogged.get(key))) {
                lastLogged.put(key, hx);
                frameLog.add(new java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(new java.util.Date())
                        + "  " + hx);
                while (frameLog.size() > 3000) frameLog.removeFirst();
            }
            if (snap != null) {
                String was = snap.get(key);
                if (was == null) newKinds.put(key, hx);
                else if (!was.equals(hx)) {
                    Integer n = changedCnt.get(key);
                    changedCnt.put(key, n == null ? 1 : n + 1);
                    changedLast.put(key, hx);
                }
            }
        }
        if (cmd == 0x41 && f.length >= 6 && (f[3] & 0xff) == 0x01) {
            // sprawdzone w aucie: bit 0x20 = 1 → ręczny opuszczony, 0 → zaciągnięty
            final boolean up = (f[4] & 0x20) == 0;
            handbrakeUp = up;
            h.post(CanBus::checkHandbrake);
            return;
        }
        if (cmd != 0x41 || f.length < 17 || (f[3] & 0xff) != 0x02) return;
        speedRaw = ((f[6] & 0xff) << 8) | (f[7] & 0xff);
        Auto.onSpeedRaw(speedRaw);
        int r = ((f[4] & 0xff) << 8) | (f[5] & 0xff);
        int v = ((f[8] & 0xff) << 8) | (f[9] & 0xff);
        int raw = ((f[10] & 0xff) << 8) | (f[11] & 0xff);
        int km = ((f[12] & 0xff) << 16) | ((f[13] & 0xff) << 8) | (f[14] & 0xff);
        int fuel = f[15] & 0xff;
        double t = (raw >= 0x8000 ? raw - 0x10000 : raw) / 10.0;
        if (r < 10000) rpm = r;
        // zaraz po włączeniu moduł przez chwilę podaje zera (paliwo 0 L, przebieg 0) – takie ramki pomijamy
        boolean valid = fuel > 0 && fuel < 120 && km > 0 && km < 2000000;
        if (!valid) {
            final int r0 = r < 10000 ? r : -1;
            h.post(() -> AccWatch.onRpm(r0));
            return;
        }
        if (v > 500 && v < 2000) volts = v / 100.0;
        fuelL = fuel;
        mileageKm = km;
        if (t > -45 && t < 65) {
            outTemp = t;
            Pogoda.fromCan(t);
        }
        at = System.currentTimeMillis();
        learn(fuelL, mileageKm);
        final int rr = r < 10000 ? r : -1, ff = fuelL, kk = mileageKm;
        h.post(CanBus::checkHandbrake);
        h.post(() -> {
            AccWatch.onRpm(rr);              // silnik pracuje / zgaszony → Yanosik
            Paliwo.onFuel(app, ff, kk, rr);  // rezerwa, tankowania
            Auto.onEngine(app, rr, volts);   // akumulator, przypomnienie o serwisie
            Auto.onShift(app, rr);           // „zmień bieg na wyższy”
        });
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
