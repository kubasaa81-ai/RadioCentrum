package pl.kuba.radiocentrum.radio;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

final class Ui {
    private Ui() {
    }

    static final int BG = 0xFF0D1117;
    static final int CARD = 0xFF1B2430;
    static final int WHITE = 0xFFFFFFFF;
    static final int GREY = 0xFFB0BEC5;
    static final int GREEN = 0xFF43A047;
    static final int RED = 0xFFE53935;
    static final int BLUE = 0xFF1E88E5;
    static final int ORANGE = 0xFFFB8C00;
    static final int PURPLE = 0xFF8E24AA;
    static final int TEAL = 0xFF00897B;
    static final int SLATE = 0xFF546E7A;

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static int darker(int c) {
        int a = (c >>> 24) & 0xFF;
        int r = (int) (((c >> 16) & 0xFF) * 0.7f);
        int g = (int) (((c >> 8) & 0xFF) * 0.7f);
        int b = (int) ((c & 0xFF) * 0.7f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static Button button(Context c, String s, int color, float sp, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        setColor(c, b, color);
        b.setStateListAnimator(null);
        b.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        b.setMinHeight(0);
        b.setMinimumHeight(dp(c, 52));
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    /** Przycisk, którego tekst sam się zmniejsza, gdy brakuje miejsca. */
    static Button buttonFit(Context c, String s, int color, float sp, View.OnClickListener l) {
        Button b = button(c, s, color, sp, l);
        b.setMaxLines(2);
        autoSize(b, 11, Math.round(sp));
        return b;
    }

    static void setColor(Context c, View v, int color) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, round(c, darker(color), 14));
        sl.addState(new int[]{}, round(c, color, 14));
        v.setBackground(sl);
    }

    /** Tekst na przycisku zmniejsza się sam, żeby nic nie było ucięte. */
    static void autoSize(TextView t, int minSp, int maxSp) {
        if (Build.VERSION.SDK_INT >= 26) {
            t.setAutoSizeTextTypeUniformWithConfiguration(minSp, maxSp, 1, TypedValue.COMPLEX_UNIT_SP);
        }
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight, Context c, int marginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        int m = dp(c, marginDp);
        p.setMargins(m, m, m, m);
        return p;
    }

    static int appColor(String app) {
        if (app == null) return ORANGE;
        switch (app) {
            case "SMS":
                return 0xFF66BB6A;
            case "WhatsApp":
                return 0xFF25D366;
            case "Messenger":
                return 0xFF448AFF;
            case "Test":
                return 0xFFBA68C8;
            default:
                return 0xFFFFA726;
        }
    }

    static String time(long ms) {
        if (ms <= 0) return "";
        Calendar now = Calendar.getInstance();
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(ms);
        boolean today = now.get(Calendar.YEAR) == t.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR);
        String f = today ? "'dziś' HH:mm" : "dd.MM HH:mm";
        return new SimpleDateFormat(f, new Locale("pl", "PL")).format(new Date(ms));
    }
}
