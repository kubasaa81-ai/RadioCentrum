package pl.kuba.radiocentrum.radio;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Odczyt stałych (static final) wybranych klas z pliku dex – np. numerów funkcji usługi CAN. */
final class DexConst {
    private DexConst() {
    }

    private static ByteBuffer b;

    private static int u32(int o) {
        return b.getInt(o);
    }

    private static int[] uleb(int o) {
        int r = 0, s = 0;
        while (true) {
            int x = b.get(o++) & 0xff;
            r |= (x & 0x7f) << s;
            s += 7;
            if (x < 0x80) return new int[]{r, o};
        }
    }

    private static String str(int i) {
        int so = u32(u32(0x3c) + 4 * i);
        int[] l = uleb(so);
        int p = l[1], e = p;
        while (b.get(e) != 0) e++;
        byte[] by = new byte[e - p];
        for (int k = 0; k < by.length; k++) by[k] = b.get(p + k);
        return new String(by, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String type(int i) {
        return str(u32(u32(0x44) + 4 * i));
    }

    private static String fieldName(int i) {
        int o = u32(0x54) + 8 * i;
        return str(b.getInt(o + 4));
    }

    static String read(File f, String... classes) {
        StringBuilder sb = new StringBuilder();
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] all = new byte[(int) raf.length()];
            raf.readFully(all);
            b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
            int cdN = u32(0x60), cdO = u32(0x64);
            for (int i = 0; i < cdN; i++) {
                int o = cdO + 32 * i;
                String name = type(b.getInt(o));
                boolean want = false;
                for (String c : classes) if (name.equals(c)) want = true;
                if (!want) continue;
                int cdo = b.getInt(o + 24), sv = b.getInt(o + 28);
                sb.append(name.replace("Landroid/os/", "").replace(";", "")).append(":\n");
                if (cdo == 0) continue;
                int[] r = uleb(cdo);
                int sfn = r[0];
                r = uleb(r[1]);
                r = uleb(r[1]);
                r = uleb(r[1]);
                int p = r[1];
                String[] names = new String[sfn];
                int fi = 0;
                for (int k = 0; k < sfn; k++) {
                    r = uleb(p);
                    fi += r[0];
                    r = uleb(r[1]);
                    p = r[1];
                    names[k] = fieldName(fi);
                }
                if (sv == 0) continue;
                r = uleb(sv);
                int n = r[0];
                int q = r[1];
                for (int k = 0; k < n && k < sfn; k++) {
                    int hdr = b.get(q++) & 0xff;
                    int t = hdr & 0x1f, a = hdr >> 5;
                    String val;
                    if (t == 0x1e) val = "null";
                    else if (t == 0x1f) val = String.valueOf(a != 0);
                    else {
                        long v = 0;
                        for (int j = 0; j <= a; j++) v |= (long) (b.get(q + j) & 0xff) << (8 * j);
                        q += a + 1;
                        val = t == 0x17 ? str((int) v) : String.valueOf(v);
                    }
                    if (names[k].startsWith("TRANSACTION") || names[k].equals("DESCRIPTOR"))
                        sb.append("  ").append(names[k]).append(" = ").append(val).append("\n");
                }
            }
        } catch (Throwable t) {
            sb.append("błąd: ").append(t).append("\n");
        }
        return sb.toString();
    }
}
