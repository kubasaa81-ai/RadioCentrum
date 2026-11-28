package pl.kuba.radiocentrum.radio;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Environment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Skan „skąd aplikacje w radiu biorą aktualizacje”: przegląda pliki wszystkich zainstalowanych
 * aplikacji (także systemowych), wyszukuje w nich adresy serwerów aktualizacji, a adresy wyglądające
 * na sprawdzanie wersji odpytuje (tylko odczyt, GET). Wynik → Pobrane/AKTUALIZACJE-skan-….txt
 */
final class UpdScan {
    private UpdScan() {
    }

    interface Progress {
        /** pct 0–100, text – co teraz robi */
        void on(int pct, String text);
    }

    private static final Pattern URL = Pattern.compile("https?://[A-Za-z0-9._~:/?#@!$&'()*+,;=%-]{6,200}");
    private static final Pattern KEY = Pattern.compile("(?i)(update|upgrade|ota|version|check|plugin|firmware|download|apk|\\?app=)");
    private static final Pattern SKIP = Pattern.compile("(?i)(w3\\.org|apache\\.org|schemas|xmlpull|android\\.com/apk|example\\.|jetbrains|mozilla|json\\.org|ietf|ns\\.adobe|purl\\.org|xml\\.org|googleapis\\.com/auth|%s|\\{|\\$)");

    static File run(Context c, Progress p) throws Exception {
        PackageManager pm = c.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        StringBuilder rep = new StringBuilder();
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        rep.append("Radio Centrum – skan serwerów aktualizacji ").append(ts).append("\n");
        rep.append("Android ").append(android.os.Build.VERSION.RELEASE).append(" · ")
                .append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL)
                .append(" · kompilacja ").append(android.os.Build.DISPLAY).append("\n\n");
        Set<String> toQuery = new LinkedHashSet<>();
        int n = 0;
        for (ApplicationInfo ai : apps) {
            n++;
            if (ai.packageName.equals(c.getPackageName())) continue; // nasza aplikacja – pomijamy
            String label = String.valueOf(pm.getApplicationLabel(ai));
            if (p != null) p.on(n * 85 / Math.max(1, apps.size()),
                    "Aplikacja " + n + " z " + apps.size() + ":\n" + label);
            Set<String> urls = new LinkedHashSet<>();
            try (ZipFile z = new ZipFile(ai.sourceDir)) {
                java.util.Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String nm = e.getName();
                    boolean dex = nm.endsWith(".dex") || nm.endsWith(".so") || nm.endsWith(".json")
                            || nm.endsWith(".properties") || nm.endsWith(".xml") && nm.startsWith("assets");
                    if (!dex || e.getSize() > 60L * 1024 * 1024) continue;
                    try (InputStream in = z.getInputStream(e)) {
                        scan(in, urls);
                    }
                }
            } catch (Throwable ignored) {
                // uszkodzony / niedostępny plik albo brak pamięci – pomijamy tę aplikację
            }
            if (urls.isEmpty()) continue;
            String ver = "";
            try {
                PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
                ver = pi.versionName + " (" + pi.versionCode + ")";
            } catch (Exception ignored) {
            }
            boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            rep.append("=== ").append(label).append(" · ").append(ai.packageName).append(" · wersja ").append(ver)
                    .append(sys ? " · systemowa" : "").append("\n");
            for (String u : urls) {
                rep.append("  ").append(u).append("\n");
                if (u.contains("?app=") || u.contains("xygala.com/api.php") || u.endsWith("car_config.json")
                        || u.matches("(?i).*(checkupdate|check_update|getversion|version\\.json|update\\.json|ota.*check|appupdate|m=update).*")) {
                    toQuery.add(u);
                }
            }
            rep.append("\n");
        }

        rep.append("\n===== ODPOWIEDZI SERWERÓW (tylko odczyt) =====\n");
        if (p != null) p.on(86, "Sprawdzam, czy radio ma internet…");
        rep.append("\n>>> test internetu: http://connectivitycheck.gstatic.com/generate_204\n")
                .append(get("http://connectivitycheck.gstatic.com/generate_204", 8000)).append("\n");
        // serwery producenta radia – także w wersji https, gdyby port 80 był blokowany
        Set<String> extra = new LinkedHashSet<>();
        for (String u : toQuery) {
            extra.add(u);
            if (u.startsWith("http://") && u.contains("xygala.com")) extra.add("https://" + u.substring(7));
        }
        int q = 0, tot = Math.min(30, extra.size());
        for (String u : extra) {
            if (++q > 30) break;
            if (p != null) p.on(86 + q * 13 / Math.max(1, tot), "Pytam serwer " + q + " z " + tot + ":\n" + host(u));
            rep.append("\n>>> ").append(u).append("\n").append(get(u, 20000)).append("\n");
        }
        if (toQuery.isEmpty()) rep.append("(brak adresów sprawdzania wersji)\n");

        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        dir.mkdirs();
        File f = new File(dir, "AKTUALIZACJE-skan-" + ts + ".txt");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(rep.toString().getBytes("UTF-8"));
        }
        return f;
    }

    /**
     * Wyciąga z pliku binarnego ciągi znaków wyglądające na adresy serwerów aktualizacji.
     * Czyta po kawałku (64 KB z zakładką), więc nawet bardzo duże pliki nie zapychają pamięci.
     */
    private static void scan(InputStream in, Set<String> urls) throws Exception {
        byte[] b = new byte[65536];
        String tail = "";
        int r;
        while ((r = in.read(b)) > 0 && urls.size() < 60) {
            String s = tail + new String(b, 0, r, "ISO-8859-1");
            Matcher m = URL.matcher(s);
            while (m.find() && urls.size() < 60) {
                String u = m.group();
                if (m.end() >= s.length()) continue; // adres urwany na końcu kawałka – złapiemy go w następnym
                if (SKIP.matcher(u).find()) continue;
                if (!KEY.matcher(u).find() && !u.matches("https?://[^/]+/?")) continue;
                urls.add(u);
            }
            tail = s.length() > 300 ? s.substring(s.length() - 300) : s;
        }
    }

    private static String host(String u) {
        try {
            return new URL(u).getHost();
        } catch (Exception e) {
            return u;
        }
    }

    private static String get(String u, int timeout) {
        long t0 = android.os.SystemClock.elapsedRealtime();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = "";
            if (in != null) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int r, tot = 0;
                while ((r = in.read(b)) > 0 && tot < 6000) {
                    o.write(b, 0, r);
                    tot += r;
                }
                in.close();
                body = o.toString("UTF-8");
            }
            return "HTTP " + code + " (" + (android.os.SystemClock.elapsedRealtime() - t0) + " ms)\n" + body;
        } catch (Throwable e) {
            return "błąd po " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms: " + e;
        }
    }

    private static volatile boolean running;

    /** Uruchom z ekranu radia: okienko z paskiem postępu, na końcu nazwa pliku. */
    static void start(final MainActivity a) {
        if (running) {
            a.toast("Skan już trwa – poczekaj na „Gotowe”");
            return;
        }
        running = true;
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        float dp = a.getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);

        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF1E2A38);
        bg.setCornerRadius(16 * dp);
        box.setBackground(bg);

        android.widget.TextView title = new android.widget.TextView(a);
        title.setText("🔎 Szukam serwerów aktualizacji");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(title);

        final android.widget.TextView pctTv = new android.widget.TextView(a);
        pctTv.setText("0%");
        pctTv.setTextColor(0xFF64B5F6);
        pctTv.setTextSize(28);
        pctTv.setTypeface(null, android.graphics.Typeface.BOLD);
        pctTv.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        pctTv.setPadding(0, (int) (12 * dp), 0, (int) (6 * dp));
        box.addView(pctTv);

        final android.widget.ProgressBar bar = new android.widget.ProgressBar(a, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF42A5F5));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF455A64));
        box.addView(bar, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int) (14 * dp)));

        final android.widget.TextView info = new android.widget.TextView(a);
        info.setText("Przygotowuję listę aplikacji…");
        info.setTextColor(0xFFCFD8DC);
        info.setTextSize(16);
        info.setMinLines(2);
        info.setPadding(0, (int) (10 * dp), 0, (int) (14 * dp));
        box.addView(info);

        android.widget.TextView hint = new android.widget.TextView(a);
        hint.setText("Możesz schować to okno – skan pracuje dalej, a na końcu pojawi się „✅ Gotowe”.");
        hint.setTextColor(0xFF90A4AE);
        hint.setTextSize(13);
        box.addView(hint);

        android.widget.Button hide = new android.widget.Button(a);
        hide.setText("Schowaj okno");
        hide.setAllCaps(false);
        hide.setTextColor(0xFFFFFFFF);
        hide.setTextSize(16);
        android.graphics.drawable.GradientDrawable hb = new android.graphics.drawable.GradientDrawable();
        hb.setColor(0xFF00897B);
        hb.setCornerRadius(10 * dp);
        hide.setBackground(hb);
        android.widget.LinearLayout.LayoutParams hlp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = (int) (14 * dp);
        box.addView(hide, hlp);

        final android.app.Dialog d = new android.app.Dialog(a);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        d.setContentView(box);
        d.setCancelable(true);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            int w = Math.min(a.getResources().getDisplayMetrics().widthPixels * 9 / 10, (int) (520 * dp));
            d.getWindow().setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        hide.setOnClickListener(v -> d.dismiss());
        try {
            d.show();
            if (d.getWindow() != null) {
                int w = Math.min(a.getResources().getDisplayMetrics().widthPixels * 9 / 10, (int) (520 * dp));
                d.getWindow().setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable ignored) {
        }

        new Thread(() -> {
            try {
                final long[] last = {0};
                File f = run(a, (pct, txt) -> {
                    long now = System.currentTimeMillis();
                    if (now - last[0] < 300) return;
                    last[0] = now;
                    h.post(() -> {
                        bar.setProgress(pct);
                        pctTv.setText(pct + "%");
                        info.setText(txt);
                    });
                });
                h.post(() -> {
                    bar.setProgress(100);
                    pctTv.setText("100%");
                    pctTv.setTextColor(0xFF66BB6A);
                    title.setText("✅ Gotowe");
                    info.setText("Zapisano: Pobrane/" + f.getName() + "\nPobierz telefonem i wyślij w czacie.");
                    hide.setText("Zamknij");
                    a.toast("✅ Gotowe: Pobrane/" + f.getName());
                });
            } catch (Throwable e) {
                h.post(() -> {
                    title.setText("⚠ Skan nie wyszedł");
                    info.setText(String.valueOf(e));
                    hide.setText("Zamknij");
                    a.toast("Skan nie wyszedł: " + e);
                });
            } finally {
                running = false;
            }
        }, "rc-updscan").start();
    }
}
