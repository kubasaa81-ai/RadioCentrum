package pl.kuba.radiocentrum.radio;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Nowoczesne okno: ciemna karta na przyciemnionym tle, duże kolorowe przyciski. */
final class Okno {

    final Activity a;
    final Dialog d;
    final LinearLayout body;

    Okno(Activity a, String title, String subtitle) {
        this.a = a;
        d = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(0xC0000000);
        root.setOnClickListener(v -> d.dismiss());

        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(false);
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(a, 20);
        body.setPadding(p, Ui.dp(a, 16), p, Ui.dp(a, 16));
        android.graphics.drawable.GradientDrawable bg = Ui.round(a, 0xFF1B2430, 26);
        bg.setStroke(Ui.dp(a, 2), 0xFF2F3D50);
        body.setBackground(bg);
        body.setClickable(true); // klik w kartę nie zamyka okna
        sv.addView(body);

        int w = a.getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.min((int) (w * 0.82f), Ui.dp(a, 860)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        int m = Ui.dp(a, 12);
        lp.setMargins(m, m, m, m);
        root.addView(sv, lp);

        if (title != null && !title.isEmpty()) {
            TextView t = Ui.text(a, title, 26, Ui.WHITE, true);
            body.addView(t);
        }
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = Ui.text(a, subtitle, 19, Ui.GREY, false);
            body.addView(s);
        }

        d.setContentView(root);
        Window win = d.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(0));
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    /** Tekst wiadomości w ramce. */
    TextView message(String text, float sp) {
        TextView t = Ui.text(a, text, sp, 0xFFECEFF1, false);
        int p = Ui.dp(a, 14);
        t.setPadding(p, p, p, p);
        t.setBackground(Ui.round(a, 0xFF111821, 16));
        t.setLineSpacing(0, 1.15f);
        body.addView(t, lpFull(10));
        return t;
    }

    void view(View v) {
        body.addView(v, lpFull(8));
    }

    private LinearLayout.LayoutParams lpFull(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(a, topDp);
        return p;
    }

    /** Duży przycisk. close = czy zamknąć okno po kliknięciu. */
    Button btn(String label, int color, boolean close, Runnable r) {
        Button b = Ui.buttonFit(a, label, color, 22, v -> {
            if (close) d.dismiss();
            if (r != null) r.run();
        });
        b.setEllipsize(TextUtils.TruncateAt.END);
        return b;
    }

    /** Rząd przycisków o równej szerokości. */
    void row(Button... bs) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (Button b : bs) {
            if (b == null) continue;
            r.addView(b, Ui.lp(0, Ui.dp(a, 72), 1, a, 5));
        }
        LinearLayout.LayoutParams p = lpFull(4);
        body.addView(r, p);
    }

    void cancel(String label) {
        row(btn(label, Ui.SLATE, true, null));
    }

    void show() {
        try {
            d.show();
        } catch (Exception ignored) {
        }
    }

    void dismiss() {
        try {
            d.dismiss();
        } catch (Exception ignored) {
        }
    }
}
