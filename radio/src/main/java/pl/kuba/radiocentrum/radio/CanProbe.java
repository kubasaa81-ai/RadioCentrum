package pl.kuba.radiocentrum.radio;

import android.app.Activity;
import android.os.Environment;
import android.os.IBinder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Bezpieczna sonda: czy aplikacja widzi systemową usługę „canbus_service”
 * i zapis pliku systemu z opisem tej usługi (do odczytania numerów funkcji).
 * Niczego nie wysyła do auta.
 */
final class CanProbe {
    private CanProbe() {
    }

    static void run(final Activity a) {
        android.widget.Toast.makeText(a, "Sprawdzam… chwilkę", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "canbus_service");
                if (b == null) sb.append("✖ usługa canbus_service: niedostępna\n");
                else {
                    sb.append("✔ usługa canbus_service: widoczna\n");
                    try {
                        sb.append("   opis: ").append(b.getInterfaceDescriptor()).append("\n");
                    } catch (Exception e) {
                        sb.append("   opis: błąd ").append(e.getMessage()).append("\n");
                    }
                }
            } catch (Throwable e) {
                sb.append("✖ usługa: ").append(e.getClass().getSimpleName()).append(" ").append(e.getMessage()).append("\n");
            }
            // pliki systemu z opisem usługi: wszystkie .jar/.apk (dex w środku) oraz .vdex/.odex/.dex
            byte[] key = "ICanbusService$Stub".getBytes();
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            boolean found = false;
            java.util.List<File> files = new java.util.ArrayList<>();
            for (String root : new String[]{"/system/framework", "/system_ext/framework", "/vendor/framework",
                    "/product/framework", "/apex/com.android.art/javalib"}) {
                collect(new File(root), files, 0);
            }
            sb.append("plików systemu: ").append(files.size()).append("\n");
            for (File f : files) {
                String n = f.getName();
                if (n.contains("services")) continue; // te już mamy
                try {
                    if (n.endsWith(".jar") || n.endsWith(".apk")) {
                        try (ZipFile z = new ZipFile(f)) {
                            Enumeration<? extends ZipEntry> en = z.entries();
                            while (en.hasMoreElements()) {
                                ZipEntry e = en.nextElement();
                                if (!e.getName().endsWith(".dex")) continue;
                                if (!streamHas(z.getInputStream(e), key)) continue;
                                byte[] data = readAll(z.getInputStream(e));
                                found |= save(dir, "CAN-system-" + n + "-" + e.getName(), data, sb);
                            }
                        }
                    } else if (n.endsWith(".vdex") || n.endsWith(".odex") || n.endsWith(".dex")) {
                        long hit = streamFind(f, key);
                        if (hit < 0) continue;
                        // początek dex przed trafieniem
                        long st = -1;
                        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
                            byte[] buf = new byte[1 << 20];
                            long pos = 0;
                            while (pos < hit) {
                                raf.seek(pos);
                                int r = raf.read(buf);
                                if (r <= 0) break;
                                for (int i = 0; i + 4 < r && pos + i < hit; i++) {
                                    if (buf[i] == 'd' && buf[i + 1] == 'e' && buf[i + 2] == 'x' && buf[i + 3] == '\n') st = pos + i;
                                }
                                pos += r - 8;
                            }
                            if (st >= 0) {
                                byte[] h = new byte[0x70];
                                raf.seek(st);
                                raf.readFully(h);
                                long size = (h[0x20] & 0xff) | (h[0x21] & 0xff) << 8 | (h[0x22] & 0xff) << 16 | (long) (h[0x23] & 0xff) << 24;
                                long dataEnd = ((h[0x68] & 0xff) | (h[0x69] & 0xff) << 8 | (h[0x6a] & 0xff) << 16 | (long) (h[0x6b] & 0xff) << 24)
                                        + ((h[0x6c] & 0xff) | (h[0x6d] & 0xff) << 8 | (h[0x6e] & 0xff) << 16 | (long) (h[0x6f] & 0xff) << 24);
                                long len = Math.min(Math.max(size, dataEnd), f.length() - st);
                                if (len > 0 && len < 60L * 1024 * 1024) {
                                    byte[] dex = new byte[(int) len];
                                    raf.seek(st);
                                    raf.readFully(dex);
                                    found |= save(dir, "CAN-system-" + n + ".dex", dex, sb);
                                    continue;
                                }
                            }
                        }
                        sb.append("trafienie w ").append(f.getPath()).append(" @").append(hit).append(" (bez wycięcia)\n");
                    }
                } catch (Throwable t) {
                    sb.append("✖ ").append(f.getPath()).append(": ").append(t.getClass().getSimpleName()).append("\n");
                }
            }
            if (!found) sb.append("✖ nie znalazłem opisu usługi w plikach systemu\n");
            final String msg = sb.toString();
            a.runOnUiThread(() -> {
                Okno o = new Okno(a, "🔬 Sonda CAN", msg
                        + "\nZrób zdjęcie tego okna. Plik CAN-system-… pobierz na telefon i wyślij w czacie.");
                o.cancel("OK");
                o.show();
            });
        }, "rc-probe").start();
    }

    private static void collect(File d, java.util.List<File> out, int depth) {
        File[] fs = d.listFiles();
        if (fs == null || depth > 3) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out, depth + 1);
            else out.add(f);
        }
    }

    /** Szuka ciągu w strumieniu kawałkami (bez wczytywania całości). */
    private static boolean streamHas(InputStream in, byte[] key) throws java.io.IOException {
        try {
            byte[] buf = new byte[1 << 20];
            int keep = 0, r;
            while ((r = in.read(buf, keep, buf.length - keep)) > 0) {
                int len = keep + r;
                if (indexOfLen(buf, len, key) >= 0) return true;
                keep = Math.min(key.length, len);
                System.arraycopy(buf, len - keep, buf, 0, keep);
            }
            return false;
        } finally {
            in.close();
        }
    }

    private static long streamFind(File f, byte[] key) throws java.io.IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[1 << 20];
            int keep = 0, r;
            long base = 0;
            while ((r = in.read(buf, keep, buf.length - keep)) > 0) {
                int len = keep + r;
                int i = indexOfLen(buf, len, key);
                if (i >= 0) return base + i;
                keep = Math.min(key.length, len);
                System.arraycopy(buf, len - keep, buf, 0, keep);
                base += len - keep;
            }
            return -1;
        }
    }

    private static int indexOfLen(byte[] d, int n, byte[] k) {
        outer:
        for (int i = 0; i <= n - k.length; i++) {
            if (d[i] != k[0]) continue;
            for (int j = 1; j < k.length; j++) if (d[i + j] != k[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    private static boolean save(File dir, String name, byte[] data, StringBuilder sb) {
        try (FileOutputStream os = new FileOutputStream(new File(dir, name.replace('/', '_')))) {
            os.write(data);
            sb.append("✔ zapisano Pobrane/").append(name.replace('/', '_')).append(" (").append(data.length / 1024).append(" KB)\n");
            return true;
        } catch (Exception e) {
            sb.append("✖ zapis ").append(name).append(": ").append(e.getMessage()).append("\n");
            return false;
        }
    }

    private static int indexOf(byte[] d, byte[] k, int from) {
        outer:
        for (int i = from; i <= d.length - k.length; i++) {
            for (int j = 0; j < k.length; j++) if (d[i + j] != k[j]) continue outer;
            return i;
        }
        return -1;
    }
}
