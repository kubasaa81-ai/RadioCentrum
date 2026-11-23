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
                try {
                    if (n.endsWith(".jar") || n.endsWith(".apk")) {
                        try (ZipFile z = new ZipFile(f)) {
                            Enumeration<? extends ZipEntry> en = z.entries();
                            while (en.hasMoreElements()) {
                                ZipEntry e = en.nextElement();
                                if (!e.getName().endsWith(".dex")) continue;
                                byte[] data = readAll(z.getInputStream(e));
                                if (indexOf(data, key, 0) >= 0) {
                                    found |= save(dir, "CAN-system-" + n + "-" + e.getName(), data, sb);
                                }
                            }
                        }
                    } else if (n.endsWith(".vdex") || n.endsWith(".odex") || n.endsWith(".dex")) {
                        if (f.length() > 200L * 1024 * 1024) continue;
                        byte[] data = readAll(new java.io.FileInputStream(f));
                        int k = indexOf(data, key, 0);
                        if (k < 0) continue;
                        // znajdź początek dex („dex\n0”) przed trafieniem i wytnij cały dex
                        byte[] magic = "dex\n0".getBytes();
                        int st = -1, pos = 0;
                        while (true) {
                            int m = indexOf(data, magic, pos);
                            if (m < 0 || m > k) break;
                            st = m;
                            pos = m + 1;
                        }
                        if (st >= 0 && st + 0x24 <= data.length) {
                            int size = (data[st + 0x20] & 0xff) | (data[st + 0x21] & 0xff) << 8
                                    | (data[st + 0x22] & 0xff) << 16 | (data[st + 0x23] & 0xff) << 24;
                            if (size > 0 && st + size <= data.length) {
                                byte[] dex = java.util.Arrays.copyOfRange(data, st, st + size);
                                found |= save(dir, "CAN-system-" + n + ".dex", dex, sb);
                                continue;
                            }
                        }
                        sb.append("trafienie w ").append(f.getPath()).append(" (bez wycięcia dex)\n");
                    }
                } catch (Throwable t) {
                    // pomiń nieczytelne pliki
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
