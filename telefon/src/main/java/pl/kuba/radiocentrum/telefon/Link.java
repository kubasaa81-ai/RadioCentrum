package pl.kuba.radiocentrum.telefon;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/** Jedno połączenie telefon <-> radio. Wiadomości to linie JSON. */
public class Link {

    public interface Handler {
        void onMessage(Link l, JSONObject m);
        void onClosed(Link l);
    }

    private final Closeable socket;
    private final BufferedReader in;
    private final Writer out;
    public final String kind;
    private volatile boolean closed;

    public Link(Closeable socket, InputStream is, OutputStream os, String kind) {
        this.socket = socket;
        this.kind = kind;
        this.in = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        this.out = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
    }

    public String readLine() throws IOException {
        return in.readLine();
    }

    public synchronized boolean send(JSONObject m) {
        if (closed) return false;
        try {
            out.write(m.toString());
            out.write('\n');
            out.flush();
            return true;
        } catch (IOException e) {
            close();
            return false;
        }
    }

    /** Blokuje wątek do końca połączenia. */
    public void runReader(Handler h) {
        try {
            String line;
            while (!closed && (line = in.readLine()) != null) {
                try {
                    h.onMessage(this, new JSONObject(line));
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        close();
        h.onClosed(this);
    }

    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    public boolean isClosed() {
        return closed;
    }
}
