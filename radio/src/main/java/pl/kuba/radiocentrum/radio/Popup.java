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
    private static View notifView, callView;
    private static String lastNum, lastName;
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static final Runnable autoHide = () -> hideNotif();

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
        final boolean isCam = "Fotoradar".equals(appName) || "Policja".equals(appName) || "Uwaga".equals(appName);
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
