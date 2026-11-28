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
        void on(String text);
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
            if (p != null) p.on("Przeglądam " + n + "/" + apps.size() + ": " + label);
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
                if (u.contains("?app=") || u.matches("(?i).*(checkupdate|check_update|getversion|version\\.json|update\\.json|ota.*check|appupdate).*")) {
                    toQuery.add(u);
                }
            }
            rep.append("\n");
        }

        rep.append("\n===== ODPOWIEDZI SERWERÓW (tylko odczyt) =====\n");
        int q = 0;
        for (String u : toQuery) {
            if (++q > 30) break;
            if (p != null) p.on("Pytam serwer " + q + "/" + Math.min(30, toQuery.size()) + "…");
            rep.append("\n>>> ").append(u).append("\n").append(get(u)).append("\n");
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

    private static String get(String u) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
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
            return "HTTP " + code + "\n" + body;
        } catch (Exception e) {
            return "błąd: " + e;
        }
    }

    /** Uruchom z ekranu radia: postęp na toastach, na końcu okno z nazwą pliku. */
    static void start(final MainActivity a) {
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final android.widget.Toast[] t = {null};
        new Thread(() -> {
            try {
                final long[] last = {0};
                File f = run(a, txt -> {
                    long now = System.currentTimeMillis();
                    if (now - last[0] < 2500) return;
                    last[0] = now;
                    h.post(() -> {
                        if (t[0] != null) t[0].cancel();
                        t[0] = android.widget.Toast.makeText(a, "🔎 " + txt, android.widget.Toast.LENGTH_SHORT);
                        t[0].show();
                    });
                });
                h.post(() -> a.toast("✅ Gotowe: Pobrane/" + f.getName() + " – pobierz telefonem i wyślij w czacie"));
            } catch (Throwable e) {
                h.post(() -> a.toast("Skan nie wyszedł: " + e));
            }
        }, "rc-updscan").start();
    }
}
