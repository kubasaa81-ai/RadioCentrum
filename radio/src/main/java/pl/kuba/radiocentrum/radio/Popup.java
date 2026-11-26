package pl.kuba.radiocentrum.radio;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** Okienka wyświetlane nad innymi aplikacjami (nawigacją, muzyką…). */
final class Popup {
    private Popup() {
    }

    private static Context app;
    private static View notifView, callView, alertView;
    private static String lastNum, lastName;
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static final Runnable autoHide = () -> hideNotif();
    private static final Runnable alertHide = () -> hideAlert();

    static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    private static WindowManager wm() {
        return (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
    }

    @SuppressWarnings("deprecation")
    private static WindowManager.LayoutParams params(int yDp) {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int w = app.getResources().getDisplayMetrics().widthPixels;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                Math.min((int) (w * 0.75f), Ui.dp(app, 760)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        p.y = Ui.dp(app, yDp);
        return p;
    }

    private static LinearLayout card(int accent) {
        LinearLayout card = new LinearLayout(app);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(app, 16);
        card.setPadding(p, Ui.dp(app, 12), p, Ui.dp(app, 14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xF51B2430);
        g.setCornerRadius(Ui.dp(app, 20));
        g.setStroke(Ui.dp(app, 3), accent);
        card.setBackground(g);
        return card;
    }

    // ------------------------------------------------------------------ powiadomienie

    static void showNotif(Context c, JSONObject m) {
        app = c.getApplicationContext();
        if (!canDraw(app)) return;
        hideNotif();
        String appName = m.optString("app");
        int accent = Ui.appColor(appName);
        LinearLayout card = card(accent);

        LinearLayout head = new LinearLayout(app);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        int bs = Ui.dp(app, 40);
        head.addView(Ui.badge(app, appName, 40), new LinearLayout.LayoutParams(bs, bs));
        TextView a = Ui.text(app, Ui.appKind(appName), 18, accent, true);
        a.setPadding(Ui.dp(app, 10), 0, 0, 0);
        head.addView(a);
        card.addView(head);
        String title = m.optString("title");
        if (!title.isEmpty()) {
            TextView t = Ui.text(app, title, 22, Ui.WHITE, true);
            card.addView(t);
        }
        String text = m.optString("text");
        if (!text.isEmpty()) {
            TextView x = Ui.text(app, text, 19, 0xFFE0E0E0, false);
            x.setMaxLines(4);
            x.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(x);
        }
        final String key = m.optString("key");
        final String speech = speech(m);
        final boolean isCam = "Fotoradar".equals(appName) || "Policja".equals(appName) || "Uwaga".equals(appName)
                || "Paliwo".equals(appName) || "Serwis".equals(appName);
        LinearLayout row = new LinearLayout(app);
        row.setOrientation(LinearLayout.HORIZONTAL);
        if (!isCam) row.addView(Ui.buttonFit(app, "🔊 Przeczytaj", Ui.BLUE, 18, v -> {
            if (!Mowa.speak(app, speech)) {
                android.widget.Toast.makeText(app, "Brak połączenia z telefonem", android.widget.Toast.LENGTH_SHORT).show();
            }
            h.removeCallbacks(autoHide);
            h.postDelayed(autoHide, 15000);
        }), Ui.lp(0, Ui.dp(app, 54), 1, app, 5));
        if (m.optBoolean("reply")) {
            row.addView(Ui.buttonFit(app, "✉ Odpowiedz", Ui.GREEN, 18, v -> {
                hideNotif();
                Intent i = new Intent(app, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                i.putExtra(MainActivity.EXTRA_REPLY, key);
                app.startActivity(i);
            }), Ui.lp(0, Ui.dp(app, 54), 1, app, 5));
        }
        row.addView(Ui.buttonFit(app, "✖ Zamknij", Ui.SLATE, 18, v -> hideNotif()),
                Ui.lp(0, Ui.dp(app, 54), 1, app, 5));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = Ui.dp(app, 6);
        card.addView(row, rp);
        card.setOnClickListener(v -> {
            hideNotif();
            if (isCam) return;
            Intent i = new Intent(app, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra(MainActivity.EXTRA_OPEN, key);
            app.startActivity(i);
        });
        try {
            wm().addView(card, params(callView != null ? 200 : 14));
            notifView = card;
        } catch (Exception e) {
            notifView = null;
            return;
        }
        h.removeCallbacks(autoHide);
        h.postDelayed(autoHide, 15000);
    }

    /** Tekst do przeczytania na głos. */
    static String speech(JSONObject m) {
        String app = m.optString("app");
        String title = m.optString("title");
        String text = m.optString("text");
        StringBuilder sb = new StringBuilder();
        sb.append("SMS".equals(app) ? "SMS" : "Wiadomość " + app);
        if (!title.isEmpty()) sb.append(" od ").append(title);
        sb.append(". ").append(text);
        return sb.toString();
    }

    static void hideNotif() {
        h.removeCallbacks(autoHide);
        if (notifView != null) {
            try {
                wm().removeView(notifView);
            } catch (Exception ignored) {
            }
            notifView = null;
        }
    }

    // ------------------------------------------------------------------ ostrzeżenie (znak na środku)

    /**
     * Duży znak na środku ekranu: co to jest (kontrola policji, fotoradar, zagrożenie),
     * jakie jest ograniczenie i z jaką prędkością jedziesz. Do tego krótki sygnał – bez czytania na głos.
     *
     * @param kind   police / cam / danger
     * @param text   tekst z Yanosika (z niego bierzemy szczegóły i ograniczenie, jeśli jest)
     * @param distM  odległość w metrach (fotoradar z bazy radia) albo -1
     */
    static void showAlert(Context c, String kind, String text, int distM) {
        app = c.getApplicationContext();
        h.post(() -> {
            beep(kind);
            if (!canDraw(app)) return;
            hideAlert();
            String low = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
            String icon, label;
            int accent;
            if ("ice".equals(kind)) {
                icon = "❄";
                label = "MOŻLIWA ŚLISKOŚĆ";
                accent = 0xFF29B6F6;
            } else if (kind.startsWith("batt")) {
                icon = "🔋";
                label = "batt_low".equals(kind) ? "SŁABE ŁADOWANIE AKUMULATORA" : "ZA WYSOKIE NAPIĘCIE ŁADOWANIA";
                accent = Ui.RED;
            } else if ("hand".equals(kind)) {
                icon = "🅿";
                label = "ZACIĄGNIĘTY RĘCZNY!";
                accent = Ui.RED;
            } else if ("police".equals(kind)) {
                icon = "🚓";
                label = low.matches(".*(nieoznakow).*") ? "NIEOZNAKOWANA POLICJA"
                        : low.matches(".*(patrol|radiowóz|radiowoz).*") && !low.contains("kontrol") ? "PATROL POLICJI"
                        : "KONTROLA POLICJI";
                accent = Ui.BLUE;
            } else if ("cam".equals(kind)) {
                icon = "📷";
                label = low.matches(".*(odcinkow).*") ? "ODCINKOWY POMIAR"
                        : low.matches(".*(suszark|laser|ręczn|reczn|mobiln).*") ? "POMIAR PRĘDKOŚCI"
                        : "FOTORADAR";
                accent = Ui.ORANGE;
            } else {
                icon = "⚠";
                label = low.matches(".*(wypadek|kolizj).*") ? "WYPADEK"
                        : low.matches(".*(roboty).*") ? "ROBOTY DROGOWE"
                        : low.matches(".*(korek|utrudnien).*") ? "KOREK"
                        : low.matches(".*(zwierz).*") ? "ZWIERZĘ NA DRODZE"
                        : low.matches(".*(zatrzyman|pobocz|awari|unieruchom|stoi na).*") ? "POJAZD NA DRODZE"
                        : low.matches(".*(przeszkod).*") ? "PRZESZKODA"
                        : "UWAGA NA DRODZE";
                accent = Ui.RED;
            }
            boolean road = !"hand".equals(kind) && !kind.startsWith("batt") && !"ice".equals(kind);
            int lim = road ? limitFrom(low) : 0;
            if (lim <= 0 && "cam".equals(kind) && Fotoradary.nearestM >= 0 && Fotoradary.nearestM < 1500) {
                lim = Fotoradary.nearestLim;
            }
            int speed = kind.startsWith("batt") || "ice".equals(kind) ? 0 : Math.max(0, Auto.speed());

            LinearLayout card = card(accent);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            int p = Ui.dp(app, 20);
            card.setPadding(p, Ui.dp(app, 14), p, Ui.dp(app, 16));

            TextView ic = Ui.text(app, icon, 52, Ui.WHITE, false);
            ic.setGravity(Gravity.CENTER);
            card.addView(ic, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView lb = Ui.text(app, label, 30, accent == Ui.BLUE ? 0xFF64B5F6 : accent, true);
            lb.setGravity(Gravity.CENTER);
            lb.setMaxLines(2);
            card.addView(lb, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            if ("ice".equals(kind)) {
                TextView d = Ui.text(app, "Na zewnątrz " + text + " – na mostach i w cieniu może być ślisko",
                        20, Ui.WHITE, false);
                d.setGravity(Gravity.CENTER);
                card.addView(d, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            if (kind.startsWith("batt")) {
                TextView d = Ui.text(app, "Napięcie " + text + " przy pracującym silniku – sprawdź alternator / akumulator",
                        20, Ui.WHITE, false);
                d.setGravity(Gravity.CENTER);
                card.addView(d, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            if (distM > 0) {
                TextView d = Ui.text(app, "za " + distM + " m", 22, Ui.WHITE, true);
                d.setGravity(Gravity.CENTER);
                card.addView(d, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }

            LinearLayout row = new LinearLayout(app);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            if (lim > 0) {
                int sz = Ui.dp(app, 100);
                TextView sign = Ui.text(app, String.valueOf(lim), lim >= 100 ? 34 : 42, 0xFF111111, true);
                sign.setGravity(Gravity.CENTER);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(Ui.WHITE);
                g.setStroke(Ui.dp(app, 10), Ui.RED);
                sign.setBackground(g);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(sz, sz);
                sp.rightMargin = Ui.dp(app, 18);
                row.addView(sign, sp);
            }
            if (speed > 0) {
                boolean tooFast = lim > 0 && speed > lim + 3;
                LinearLayout col = new LinearLayout(app);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER_HORIZONTAL);
                col.addView(Ui.text(app, "jedziesz", 18, Ui.GREY, false));
                col.addView(Ui.text(app, speed + " km/h", 34, tooFast ? 0xFFFF5252 : 0xFF81C784, true));
                if (tooFast) col.addView(Ui.text(app, "ZWOLNIJ!", 22, 0xFFFF5252, true));
                row.addView(col);
            } else if (lim <= 0) {
                row.setVisibility(View.GONE);
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rp.topMargin = Ui.dp(app, 10);
            card.addView(row, rp);
            card.setOnClickListener(v -> hideAlert());

            WindowManager.LayoutParams wp = params(0);
            int w = app.getResources().getDisplayMetrics().widthPixels;
            wp.width = Math.min((int) (w * 0.62f), Ui.dp(app, 600));
            wp.gravity = Gravity.CENTER;
            wp.y = 0;
            try {
                wm().addView(card, wp);
                alertView = card;
                alertKind = kind;
            } catch (Exception e) {
                alertView = null;
                return;
            }
            h.removeCallbacks(alertHide);
            h.postDelayed(alertHide, 10000);
        });
    }

    /** Duży znak „Mało paliwa” na środku z przyciskiem do najbliższej stacji. */
    static void showFuel(Context c, int liters, String range, boolean urgent) {
        app = c.getApplicationContext();
        h.post(() -> {
            beep(urgent ? "police" : "fuel");
            if (!canDraw(app)) return;
            hideAlert();
            int accent = urgent ? Ui.RED : Ui.ORANGE;
            LinearLayout card = card(accent);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            int p = Ui.dp(app, 20);
            card.setPadding(p, Ui.dp(app, 14), p, Ui.dp(app, 16));
            LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            TextView ic = Ui.text(app, "⛽", 52, Ui.WHITE, false);
            ic.setGravity(Gravity.CENTER);
            card.addView(ic, full);
            TextView lb = Ui.text(app, urgent ? "PALIWO NA WYCZERPANIU" : "MAŁO PALIWA", 30, accent, true);
            lb.setGravity(Gravity.CENTER);
            lb.setMaxLines(2);
            card.addView(lb, new LinearLayout.LayoutParams(full));
            TextView d = Ui.text(app, "W baku " + liters + " L · zasięg " + range, 22, Ui.WHITE, true);
            d.setGravity(Gravity.CENTER);
            d.setMaxLines(2);
            card.addView(d, new LinearLayout.LayoutParams(full));
            LinearLayout row = new LinearLayout(app);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(Ui.buttonFit(app, "⛽ Najbliższa stacja", Ui.GREEN, 20, v -> {
                hideAlert();
                Paliwo.stations(app);
            }), Ui.lp(0, Ui.dp(app, 60), 1, app, 6));
            row.addView(Ui.buttonFit(app, "✖ Zamknij", Ui.SLATE, 20, v -> hideAlert()),
                    Ui.lp(0, Ui.dp(app, 60), 1, app, 6));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(full);
            rp.topMargin = Ui.dp(app, 12);
            card.addView(row, rp);

            WindowManager.LayoutParams wp = params(0);
            int w = app.getResources().getDisplayMetrics().widthPixels;
            wp.width = Math.min((int) (w * 0.62f), Ui.dp(app, 600));
            wp.gravity = Gravity.CENTER;
            wp.y = 0;
            try {
                wm().addView(card, wp);
                alertView = card;
            } catch (Exception e) {
                alertView = null;
                return;
            }
            h.removeCallbacks(alertHide);
            h.postDelayed(alertHide, 30000);
        });
    }

    /** Ograniczenie z tekstu Yanosika, np. „50 km/h” albo „ograniczenie 70”. 0 = brak. */
    private static int limitFrom(String low) {
        java.util.regex.Matcher mt = java.util.regex.Pattern
                .compile("(?:ograniczeni\\S*|limit|max\\.?|maks\\S*)\\s*(?:do\\s*)?(\\d{2,3})|(\\d{2,3})\\s*km\\s*/\\s*h")
                .matcher(low);
        while (mt.find()) {
            String g = mt.group(1) != null ? mt.group(1) : mt.group(2);
            try {
                int v = Integer.parseInt(g);
                if (v >= 20 && v <= 140 && v % 10 == 0) return v;
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    /**
     * Głośny sygnał ostrzegawczy: pełna głośność dźwięku (nie cichy ton systemowy), muzyka na chwilę ściszona.
     * Policja: 3 szybkie wysokie piknięcia. Fotoradar i reszta: 2 dłuższe dwutonowe.
     */
    @SuppressWarnings("deprecation")
    private static void beep(String kind) {
        new Thread(() -> {
            android.media.AudioManager am = (android.media.AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            android.media.AudioManager.OnAudioFocusChangeListener fl = f -> {
            };
            try {
                if (am != null) am.requestAudioFocus(fl, android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                int rate = 44100;
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                if ("police".equals(kind)) {
                    for (int i = 0; i < 3; i++) {
                        tone(out, rate, 1400, 180);
                        tone(out, rate, 0, 90);
                    }
                } else {
                    for (int i = 0; i < 2; i++) {
                        tone(out, rate, 1000, 250);
                        tone(out, rate, 1500, 250);
                        tone(out, rate, 0, 120);
                    }
                }
                byte[] pcm = out.toByteArray();
                android.media.AudioTrack t = new android.media.AudioTrack(android.media.AudioManager.STREAM_MUSIC,
                        rate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT,
                        pcm.length, android.media.AudioTrack.MODE_STATIC);
                t.write(pcm, 0, pcm.length);
                t.setStereoVolume(1f, 1f);
                t.play();
                Thread.sleep(pcm.length * 1000L / (rate * 2) + 200);
                t.release();
            } catch (Throwable ignored) {
            } finally {
                if (am != null) am.abandonAudioFocus(fl);
            }
        }, "rc-beep").start();
    }

    /** Ton prostokątny z łagodnym brzegiem – najgłośniejszy przy tej samej głośności radia. freq 0 = cisza. */
    private static void tone(java.io.ByteArrayOutputStream out, int rate, int freq, int ms) {
        int n = rate * ms / 1000, fade = rate / 200;
        for (int i = 0; i < n; i++) {
            double v = 0;
            if (freq > 0) {
                double ph = Math.sin(2 * Math.PI * freq * i / rate);
                v = Math.max(-1, Math.min(1, ph * 3)); // prawie prostokąt – głośniej niż czysty sinus
                double env = Math.min(1, Math.min(i, n - 1 - i) / (double) fade);
                v *= env * 0.97;
            }
            short x = (short) (v * 32767);
            out.write(x & 0xff);
            out.write((x >> 8) & 0xff);
        }
    }

    private static String alertKind = "";

    /** Schowaj znak, jeśli to ostrzeżenie danego rodzaju (np. ręczny został opuszczony). */
    static void hideAlertIf(String kind) {
        h.post(() -> {
            if (alertView != null && kind.equals(alertKind)) hideAlert();
        });
    }

    static void hideAlert() {
        h.removeCallbacks(alertHide);
        alertKind = "";
        if (alertView != null) {
            try {
                wm().removeView(alertView);
            } catch (Exception ignored) {
            }
            alertView = null;
        }
    }

    // ------------------------------------------------------------------ rozmowa

    /** Zapamiętaj numer wybierany z radia (telefon nie podaje go przy rozmowie wychodzącej). */
    static void expect(String num, String name) {
        lastNum = num;
        lastName = name;
    }

    static void showCall(Context c, JSONObject m) {
        app = c.getApplicationContext();
        String state = m.optString("state");
        if ("idle".equals(state)) {
            hideCall();
            return;
        }
        if (!canDraw(app)) return;
        String num = m.optString("num");
        String name = m.optString("name");
        if (num.isEmpty()) num = lastNum == null ? "" : lastNum;
        else if (!num.equals(lastNum)) lastName = null;
        if (name.isEmpty()) name = lastName != null ? lastName : Store.nameFor(num);
        lastNum = num;
        lastName = name;

        boolean ringing = "ringing".equals(state);
        removeCallView();
        LinearLayout card = card(ringing ? Ui.GREEN : Ui.BLUE);
        card.addView(Ui.text(app, ringing ? "📞 Połączenie przychodzące" : "📞 Rozmowa", 17,
                ringing ? 0xFF81C784 : 0xFF64B5F6, true));
        String big = (name != null && !name.isEmpty()) ? name : (num.isEmpty() ? "Nieznany numer" : num);
        card.addView(Ui.text(app, big, 28, Ui.WHITE, true));
        if (name != null && !name.isEmpty() && !num.isEmpty()) {
            card.addView(Ui.text(app, num, 19, Ui.GREY, false));
        }

        LinearLayout row = new LinearLayout(app);
        row.setOrientation(LinearLayout.HORIZONTAL);
        if (ringing) {
            row.addView(Ui.buttonFit(app, "✅ Odbierz", Ui.GREEN, 20,
                            v -> RadioService.sendToPhone(J.obj("t", "answer"))),
                    Ui.lp(0, Ui.dp(app, 60), 1, app, 6));
            row.addView(Ui.buttonFit(app, "⛔ Odrzuć", Ui.RED, 20, v -> {
                        RadioService.sendToPhone(J.obj("t", "reject"));
                        hideCall();
                    }),
                    Ui.lp(0, Ui.dp(app, 60), 1, app, 6));
        } else {
            row.addView(Ui.buttonFit(app, "⛔ Rozłącz", Ui.RED, 20, v -> {
                        RadioService.sendToPhone(J.obj("t", "reject"));
                        hideCall();
                    }),
                    Ui.lp(0, Ui.dp(app, 60), 1, app, 6));
            row.addView(Ui.buttonFit(app, "Schowaj", Ui.SLATE, 20, v -> removeCallView()),
                    Ui.lp(0, Ui.dp(app, 60), 1, app, 6));
        }
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = Ui.dp(app, 8);
        card.addView(row, rp);

        try {
            wm().addView(card, params(14));
            callView = card;
        } catch (Exception e) {
            callView = null;
        }
    }

    private static void removeCallView() {
        if (callView != null) {
            try {
                wm().removeView(callView);
            } catch (Exception ignored) {
            }
            callView = null;
        }
    }

    static void hideCall() {
        if (app == null) return;
        removeCallView();
        lastNum = null;
        lastName = null;
    }
}
