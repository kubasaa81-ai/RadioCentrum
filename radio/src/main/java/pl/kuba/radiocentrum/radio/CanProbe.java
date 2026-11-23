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
            // plik systemu z opisem usługi
            byte[] key = "ICanbusService".getBytes();
            String[] jars = {"/system/framework/framework.jar", "/system/framework/framework-ext.jar",
                    "/system/framework/mediatek-framework.jar", "/system/framework/services.jar"};
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            boolean found = false;
            for (String j : jars) {
                if (!new File(j).exists()) continue;
                try (ZipFile z = new ZipFile(j)) {
                    Enumeration<? extends ZipEntry> en = z.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry e = en.nextElement();
                        if (!e.getName().endsWith(".dex")) continue;
                        byte[] data;
                        try (InputStream in = z.getInputStream(e)) {
                            ByteArrayOutputStream bo = new ByteArrayOutputStream();
                            byte[] buf = new byte[65536];
                            int r;
                            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                            data = bo.toByteArray();
                        }
                        if (indexOf(data, key) >= 0) {
                            String name = "CAN-system-" + new File(j).getName().replace(".jar", "") + "-" + e.getName();
                            try (FileOutputStream os = new FileOutputStream(new File(dir, name))) {
                                os.write(data);
                            }
                            sb.append("✔ zapisano Pobrane/").append(name).append(" (").append(data.length / 1024).append(" KB)\n");
                            found = true;
                        }
                    }
                } catch (Throwable t) {
                    sb.append("✖ ").append(j).append(": ").append(t.getMessage()).append("\n");
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

    private static int indexOf(byte[] d, byte[] k) {
        outer:
        for (int i = 0; i <= d.length - k.length; i++) {
            for (int j = 0; j < k.length; j++) if (d[i + j] != k[j]) continue outer;
            return i;
        }
        return -1;
    }
}
