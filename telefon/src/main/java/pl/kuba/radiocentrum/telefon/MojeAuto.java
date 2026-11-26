package pl.kuba.radiocentrum.telefon;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * „Moje auto” na telefonie: ostatni stan auta z radia, gdzie zaparkowałem,
 * statystyki miesięczne, tankowania, postoje na silniku, przegląd i OC.
 */
public class MojeAuto extends Activity {

    static final String PREFS = "rc_car";
    private LinearLayout col;

    // ------------------------------------------------------------------ dane z radia

    /** Wołane z PhoneService po odebraniu „car_state” z radia. */
    static void onState(Context c, JSONObject m) {
        SharedPreferences.Editor ed = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        ed.putString("state", m.toString());
        if (m.optBoolean("parked") && m.has("lat")) {
            ed.putString("park", J.obj("lat", m.optDouble("lat"), "lon", m.optDouble("lon"),
                    "t", m.optLong("ts")).toString());
        }
        ed.apply();
        checkDates(c);
    }

    private static JSONObject state(Context c) {
        try {
            return new JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("state", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ------------------------------------------------------------------ przypomnienia: przegląd i OC

    /** Raz dziennie: powiadomienie, gdy do przeglądu / końca OC zostało ≤ 14 dni. */
    static void checkDates(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        if (today.equals(p.getString("notified", ""))) return;
        StringBuilder sb = new StringBuilder();
        for (String[] k : new String[][]{{"przeglad", "Przegląd techniczny"}, {"oc", "Ubezpieczenie OC"}}) {
            long d = p.getLong(k[0], 0);
            if (d == 0) continue;
            long days = daysLeft(d);
            if (days > 14) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(k[1]).append(days >= 0 ? ": za " + days + " dni (" + fmtDay(d) + ")"
                    : ": PO TERMINIE od " + (-days) + " dni (" + fmtDay(d) + ")");
        }
        if (sb.length() == 0) return;
        p.edit().putString("notified", today).apply();
        notify(c, "🚗 Termin w aucie", sb.toString());
    }

    private static void notify(Context c, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel("rc_car", "Moje auto – terminy",
                        NotificationManager.IMPORTANCE_DEFAULT));
            }
            Intent i = new Intent(c, MojeAuto.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, 7, i, PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, "rc_car") : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(4711, b.build());
        } catch (Exception ignored) {
        }
    }

    private static long daysLeft(long when) {
        Calendar now = Calendar.getInstance();
        now.set(Calendar.HOUR_OF_DAY, 0);
        now.set(Calendar.MINUTE, 0);
        now.set(Calendar.SECOND, 0);
        now.set(Calendar.MILLISECOND, 0);
        return Math.round((when - now.getTimeInMillis()) / 86400000.0);
    }

    private static String fmtDay(long t) {
        return new SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).format(new Date(t));
    }

    private static String fmtTime(long t) {
        return new SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(new Date(t));
    }

    // ------------------------------------------------------------------ ekran

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF101418);
        sv.setFillViewport(true);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        col.setPadding(p, dp(28), p, dp(28));
        sv.addView(col);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        col.removeAllViews();
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        JSONObject s = state(this);
        col.addView(text("🚗 Moje auto", 26, 0xFFFFFFFF, true));
        long ts = s.optLong("ts");
        col.addView(text(ts == 0 ? "Brak danych – połącz telefon z radiem i włącz „Dane z CAN” w radiu."
                : "Dane z radia z " + fmtTime(ts), 14, 0xFF90A4AE, false), lp(4));

        // --- stan
        StringBuilder st = new StringBuilder();
        st.append("⛽ Paliwo: ").append(s.optInt("fuel", -1) < 0 ? "–" : s.optInt("fuel") + " L")
                .append("   ·   zasięg ").append(s.optString("range", "–")).append("\n");
        st.append("📏 Przebieg: ").append(s.optInt("km", -1) <= 0 ? "–" : s.optInt("km") + " km").append("\n");
        st.append(String.format(Locale.US, "📈 Spalanie: %.1f l/100 km", s.optDouble("cons", 0)))
                .append(s.optBoolean("consLearned") ? "" : " (domyślne)").append("\n");
        st.append("🔋 Akumulator: ").append(s.optString("batt", "–")).append("\n");
        if (s.has("temp")) st.append(String.format(Locale.US, "🌡 Na zewnątrz: %.1f°C\n", s.optDouble("temp")));
        st.append("🔧 Olej: ").append(s.optString("service", "–"));
        col.addView(card("Stan auta", st.toString(), 0xFF1E88E5), lp(14));

        // --- gdzie zaparkowałem
        JSONObject park = null;
        try {
            park = new JSONObject(sp.getString("park", "{}"));
        } catch (Exception ignored) {
        }
        if (park != null && park.has("lat")) {
            final double lat = park.optDouble("lat"), lon = park.optDouble("lon");
            col.addView(card("📍 Gdzie zaparkowałem", "Zaparkowano " + fmtTime(park.optLong("t"))
                    + String.format(Locale.US, "\n%.5f, %.5f", lat, lon), 0xFF43A047), lp(14));
            LinearLayout row = row();
            row.addView(button("🗺 Pokaż na mapie", 0xFF2E7D32, v -> open("geo:" + lat + "," + lon
                    + "?q=" + lat + "," + lon + "(" + Uri.encode("Moje auto") + ")")), weight());
            row.addView(button("🚶 Prowadź pieszo", 0xFF00897B, v -> open("google.navigation:q=" + lat + "," + lon
                    + "&mode=w")), weight());
            col.addView(row, lp(8));
        } else {
            col.addView(card("📍 Gdzie zaparkowałem", "Jeszcze brak – zapisze się przy najbliższym zgaszeniu silnika.",
                    0xFF43A047), lp(14));
        }

        // --- przegląd i OC
        col.addView(card("📅 Przegląd i OC", dateLine(sp, "przeglad", "Przegląd") + "\n" + dateLine(sp, "oc", "OC")
                + "\nPrzypomnienie na telefonie 14 dni przed terminem.", 0xFFFB8C00), lp(14));
        LinearLayout r2 = row();
        r2.addView(button("Ustaw przegląd", 0xFFEF6C00, v -> pickDate("przeglad")), weight());
        r2.addView(button("Ustaw OC", 0xFFF4511E, v -> pickDate("oc")), weight());
        col.addView(r2, lp(8));

        // --- statystyki miesięczne
        col.addView(card("📊 Statystyki miesięczne", stats(s, sp), 0xFF8E24AA), lp(14));
        LinearLayout r3 = row();
        r3.addView(button("💰 Cena paliwa: " + price(sp), 0xFF6A1B9A, v -> askPrice()), weight());
        col.addView(r3, lp(8));

        // --- tankowania
        col.addView(card("⛽ Tankowania", refuels(s), 0xFF00838F), lp(14));

        // --- postoje na silniku
        col.addView(card("⏱ Postoje na pracującym silniku (powyżej 5 min)", idles(s), 0xFF546E7A), lp(14));

        col.addView(button("🔄 Odśwież", 0xFF3949AB, v -> build()), lp(18));
    }

    private String dateLine(SharedPreferences sp, String key, String name) {
        long d = sp.getLong(key, 0);
        if (d == 0) return name + ": nie ustawiono";
        long left = daysLeft(d);
        return name + ": " + fmtDay(d) + (left >= 0 ? " (za " + left + " dni)" : " (PO TERMINIE)");
    }

    private void pickDate(final String key) {
        Calendar c = Calendar.getInstance();
        long cur = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(key, 0);
        if (cur > 0) c.setTimeInMillis(cur);
        new DatePickerDialog(this, (v, y, m, d) -> {
            Calendar n = Calendar.getInstance();
            n.set(y, m, d, 0, 0, 0);
            n.set(Calendar.MILLISECOND, 0);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(key, n.getTimeInMillis())
                    .remove("notified").apply();
            checkDates(this);
            build();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private static String price(SharedPreferences sp) {
        float p = sp.getFloat("price", 0);
        return p <= 0 ? "nie ustawiono" : String.format(Locale.US, "%.2f zł/l", p);
    }

    private void askPrice() {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setHint("np. 6.49");
        float cur = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat("price", 0);
        if (cur > 0) e.setText(String.format(Locale.US, "%.2f", cur));
        new android.app.AlertDialog.Builder(this)
                .setTitle("Cena paliwa (zł za litr)")
                .setView(e)
                .setPositiveButton("Zapisz", (d, w) -> {
                    try {
                        float v = Float.parseFloat(e.getText().toString().replace(',', '.'));
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat("price", v).apply();
                        build();
                    } catch (Exception ex) {
                        Toast.makeText(this, "Wpisz liczbę, np. 6.49", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Anuluj", null)
                .show();
    }

    private static String stats(JSONObject s, SharedPreferences sp) {
        JSONObject months = s.optJSONObject("months");
        JSONArray ref = s.optJSONArray("refuels");
        TreeMap<String, double[]> m = new TreeMap<>(Collections.reverseOrder()); // km, litry
        if (months != null) {
            Iterator<String> it = months.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONArray v = months.optJSONArray(k);
                if (v != null) m.put(k, new double[]{v.optInt(1) - v.optInt(0), 0});
            }
        }
        SimpleDateFormat mf = new SimpleDateFormat("yyyy-MM", Locale.ROOT);
        if (ref != null) {
            for (int i = 0; i < ref.length(); i++) {
                JSONObject e = ref.optJSONObject(i);
                if (e == null) continue;
                String k = mf.format(new Date(e.optLong("t")));
                double[] v = m.get(k);
                if (v == null) m.put(k, v = new double[]{0, 0});
                v[1] += e.optInt("after") - e.optInt("before");
            }
        }
        if (m.isEmpty()) return "Brak danych – zbierają się od teraz.";
        float price = sp.getFloat("price", 0);
        StringBuilder sb = new StringBuilder();
        String[] names = {"styczeń", "luty", "marzec", "kwiecień", "maj", "czerwiec", "lipiec", "sierpień",
                "wrzesień", "październik", "listopad", "grudzień"};
        int n = 0;
        for (java.util.Map.Entry<String, double[]> e : m.entrySet()) {
            if (n++ >= 12) break;
            String k = e.getKey();
            int mo = Integer.parseInt(k.substring(5)) - 1;
            double[] v = e.getValue();
            sb.append(names[mo]).append(" ").append(k, 0, 4).append(":  ")
                    .append((int) v[0]).append(" km");
            if (v[1] > 0) {
                sb.append(" · zatankowano ").append((int) v[1]).append(" L");
                if (price > 0) sb.append(String.format(Locale.US, " · %.0f zł", v[1] * price));
            }
            sb.append("\n");
        }
        sb.append("(spalanie z każdego tankowania – w sekcji Tankowania)");
        return sb.toString();
    }

    private static String refuels(JSONObject s) {
        JSONArray a = s.optJSONArray("refuels");
        if (a == null || a.length() == 0) return "Brak – zapisze się samo przy tankowaniu (min. 5 L).";
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e == null) continue;
            String r = fmtTime(e.optLong("t")) + "  +" + (e.optInt("after") - e.optInt("before")) + " L  ("
                    + e.optInt("before") + "→" + e.optInt("after") + " L)  " + e.optInt("km") + " km";
            if (e.has("cons")) r += String.format(Locale.US, "  ·  %.1f l/100", e.optDouble("cons"));
            rows.add(r);
        }
        Collections.reverse(rows);
        return android.text.TextUtils.join("\n", rows.subList(0, Math.min(20, rows.size())));
    }

    private static String idles(JSONObject s) {
        JSONArray a = s.optJSONArray("idles");
        if (a == null || a.length() == 0) return "Brak.";
        List<String> rows = new ArrayList<>();
        double sum = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e == null) continue;
            sum += e.optDouble("l");
            rows.add(fmtTime(e.optLong("t")) + "  ·  " + e.optInt("min") + " min  ·  ok. "
                    + String.format(Locale.US, "%.2f L", e.optDouble("l")));
        }
        Collections.reverse(rows);
        return android.text.TextUtils.join("\n", rows.subList(0, Math.min(20, rows.size())))
                + String.format(Locale.US, "\nRazem: ok. %.1f L (wolne obroty ≈ 0,9 l/h)", sum);
    }

    private void open(String uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
        } catch (Exception e) {
            Toast.makeText(this, "Brak aplikacji map", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ wygląd

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private View card(String title, String body, int accent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        c.setPadding(p, p, p, p);
        GradientDrawable g = round(0xFF1E2A33, 16);
        g.setStroke(dp(2), accent);
        c.setBackground(g);
        c.addView(text(title, 18, 0xFFFFFFFF, true));
        TextView t = text(body, 15, 0xFFE0E0E0, false);
        t.setLineSpacing(0, 1.25f);
        c.addView(t, lp(6));
        return c;
    }

    private Button button(String s, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(color, 14));
        b.setStateListAnimator(null);
        b.setMinHeight(dp(52));
        b.setPadding(dp(10), dp(8), dp(10), dp(8));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        p.leftMargin = dp(4);
        p.rightMargin = dp(4);
        return p;
    }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }
}
