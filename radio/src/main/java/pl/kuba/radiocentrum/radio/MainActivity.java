package pl.kuba.radiocentrum.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    static final String EXTRA_REPLY = "reply_key";
    static final String EXTRA_OPEN = "open_key";

    private static final String[] QUICK_REPLIES = {
            "Prowadzę, odezwę się później.",
            "Jestem w drodze, będę niedługo.",
            "Oddzwonię, jak tylko zaparkuję.",
            "Nie mogę teraz rozmawiać.",
            "OK 👍",
            "Tak.",
            "Nie."};
    private static final int[] QUICK_COLORS = {Ui.BLUE, Ui.TEAL, Ui.PURPLE, Ui.ORANGE, Ui.GREEN, 0xFF2E7D32, 0xFFC62828};

    private static final int T_HOME = 0, T_NOTIF = 1, T_CALLS = 2, T_CONTACTS = 3, T_KEYPAD = 4, T_SETTINGS = 5, T_APPS = 6;
    /** Górny pasek: Pulpit, Połączenia, Kontakty, Radio, Więcej (rozwijane menu). */
    private static final String[] TAB_NAMES = {"🏠 Pulpit", "📞 Połączenia", "📒 Kontakty", "📻 Radio", "☰ Więcej"};
    private static final int[] TAB_COLORS = {Ui.TEAL, Ui.GREEN, Ui.BLUE, 0xFFE65100, Ui.SLATE};

    private LinearLayout top;
    private TextView screenTitle;
    private FrameLayout content;
    private TextView status;
    private int tab = T_NOTIF;

    private RowAdapter notifAdapter, callAdapter, contactAdapter;
    private final List<JSONObject> shownContacts = new ArrayList<>();
    private String query = "";
    private ListView contactList;
    private LinearLayout letterCol;
    private int contactsCountShown = -1;

    private TextView dialDisplay, dialName;
    private TextView settingsInfo;
    private CarView carView;
    private android.webkit.WebView mapView;
    private android.location.LocationListener locListener;
    private int shownCams = -1;
    private TextView carText, homeStatus, homeLast;
    private TextView[] dataVals;
    private final Runnable lightPoll = new Runnable() {
        @Override
        public void run() {
            if (carView != null) {
                boolean on = LightSensor.isOn(MainActivity.this);
                carView.setLights(on);
                carText.setText(on ? "💡 Światła włączone" : "Światła wyłączone");
                carText.setTextColor(on ? 0xFFFFE57F : Ui.GREY);
            }
            tickH.postDelayed(this, 2000);
        }
    };
    private EditText dictateTarget;
    private String dictateBase = "";
    private static final int REQ_SPEECH = 42;

    private final Runnable onChange = this::refresh;
    private static MainActivity active;
    private final android.os.Handler tickH = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            refreshHomeData();
            tickH.postDelayed(this, 1500);
        }
    };

    // ------------------------------------------------------------------ start

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        RadioService.start(this);
        Store.load(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // na Pulpicie paska nie ma; na innych ekranach: „🏠 Pulpit” + nazwa ekranu
        top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button homeBtn = Ui.button(this, "🏠 Pulpit", Ui.TEAL, 20, v -> show(T_HOME));
        top.addView(homeBtn, Ui.lp(Ui.dp(this, 190), ViewGroup.LayoutParams.MATCH_PARENT, 0, this, 4));
        screenTitle = Ui.text(this, "", 24, Ui.WHITE, true);
        screenTitle.setPadding(Ui.dp(this, 12), 0, 0, 0);
        top.addView(screenTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button moreBtn = Ui.button(this, "☰ Więcej", Ui.SLATE, 18, v -> moreMenu());
        top.addView(moreBtn, Ui.lp(Ui.dp(this, 160), ViewGroup.LayoutParams.MATCH_PARENT, 0, this, 4));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 60)));

        status = Ui.text(this, "", 16, Ui.WHITE, true);
        status.setPadding(Ui.dp(this, 12), Ui.dp(this, 2), Ui.dp(this, 12), Ui.dp(this, 4));
        root.addView(status);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        show(Popup.canDraw(this) ? T_HOME : T_SETTINGS);
        handleIntent(getIntent());
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
        } else if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 8);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (mapView != null) startLocation();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        String key = i.getStringExtra(EXTRA_REPLY);
        boolean reply = key != null;
        if (key == null) key = i.getStringExtra(EXTRA_OPEN);
        if (key == null) return;
        i.removeExtra(EXTRA_REPLY);
        i.removeExtra(EXTRA_OPEN);
        if (tab != T_NOTIF) show(T_NOTIF);
        JSONObject m = Store.findNotif(key);
        if (m != null && reply) openReply(m);
        else if (m != null) notifActions(m);
        else toast("Ta wiadomość została już zamknięta w telefonie");
    }

    @Override
    protected void onResume() {
        super.onResume();
        RadioService.start(this);
        active = this;
        Store.addListener(onChange);
        refresh();
        tickH.post(tick);
        tickH.post(lightPoll);
        if (mapView != null) startLocation();
    }

    @Override
    public void onBackPressed() {
        // jako pulpit: „wstecz” wraca na Pulpit, nie zamyka aplikacji
        if (tab != T_HOME) show(T_HOME);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (active == this) active = null;
        Store.removeListener(onChange);
        tickH.removeCallbacks(tick);
        tickH.removeCallbacks(lightPoll);
        stopLocation();
    }

    private void show(int idx) {
        tab = idx;
        top.setVisibility(idx == T_HOME ? View.GONE : View.VISIBLE);
        String[] titles = {"", "🔔 Powiadomienia", "📞 Połączenia", "📒 Kontakty", "⌨ Klawiatura", "⚙ Ustawienia", "📱 Aplikacje"};
        if (idx >= 0 && idx < titles.length) screenTitle.setText(titles[idx]);
        content.removeAllViews();
        notifAdapter = callAdapter = contactAdapter = null;
        contactList = null;
        letterCol = null;
        dialDisplay = dialName = settingsInfo = null;
        carView = null;
        carText = homeStatus = homeLast = null;
        dataVals = null;
        stopLocation();
        shownCams = -1;
        if (mapView != null) {
            try {
                mapView.destroy();
            } catch (Exception ignored) {
            }
            mapView = null;
        }
        View v;
        switch (idx) {
            case T_CALLS:
                v = buildCalls();
                break;
            case T_CONTACTS:
                v = buildContacts();
                break;
            case T_KEYPAD:
                v = buildKeypad();
                break;
            case T_SETTINGS:
                v = buildSettings();
                break;
            case T_HOME:
                v = buildHome();
                break;
            case T_APPS:
                v = buildApps();
                break;
            default:
                v = buildNotifs();
        }
        content.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        refresh();
    }

    private void refresh() {
        refreshStatus();
        if (notifAdapter != null) notifAdapter.notifyDataSetChanged();
        if (callAdapter != null) callAdapter.notifyDataSetChanged();
        if (contactAdapter != null) {
            filterContacts();
            contactAdapter.notifyDataSetChanged();
            if (contactsCountShown != Store.contacts.size()) fillLetters();
        }
    }

    /** Pasek stanu i opis w Ustawieniach – odświeżane co 1,5 s. */
    private void refreshStatus() {
        if (Store.linkKind != null) {
            String n = Store.phoneName == null || Store.phoneName.isEmpty() ? "telefonem" : Store.phoneName;
            status.setText("● Połączono z " + n + " (" + Store.linkKind + ")");
            status.setTextColor(0xFF81C784);
        } else {
            status.setText("● Brak połączenia z telefonem – " + RadioService.btInfo);
            status.setTextColor(0xFFEF9A9A);
        }
        if (!Popup.canDraw(this)) {
            status.append("   ⚠ Zezwól na okienka (⚙ Ustawienia)");
        }
        if (settingsInfo != null) settingsInfo.setText(settingsText());
        if (homeStatus != null) {
            homeStatus.setText(Store.linkKind != null ? "● Telefon połączony (" + Store.linkKind + ")" : "● Telefon niepołączony");
            homeStatus.setTextColor(Store.linkKind != null ? 0xFF81C784 : 0xFFEF9A9A);
            int missed = 0;
            long dayAgo = System.currentTimeMillis() - 24L * 3600 * 1000;
            for (JSONObject o : Store.calls) {
                if (o.optInt("type") == 3 && o.optLong("d") > dayAgo) missed++;
            }
            StringBuilder sb = new StringBuilder();
            if (missed > 0) sb.append("✖ Nieodebrane (24 h): ").append(missed).append("\n");
            if (!Store.notifs.isEmpty()) {
                JSONObject n = Store.notifs.get(0);
                sb.append(Ui.appIcon(n.optString("app"))).append(" ").append(n.optString("title"))
                        .append(": ").append(n.optString("text"));
            }
            homeLast.setText(sb.toString());
        }
    }

    // ------------------------------------------------------------------ listy

    private abstract class RowAdapter extends BaseAdapter {
        abstract List<JSONObject> data();

        abstract View row(JSONObject o);

        @Override
        public int getCount() {
            return data().size();
        }

        @Override
        public Object getItem(int i) {
            return data().get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View cv, ViewGroup parent) {
            return row(data().get(i));
        }
    }

    private ListView newList() {
        ListView lv = new ListView(this);
        lv.setDivider(new android.graphics.drawable.ColorDrawable(0xFF263241));
        lv.setDividerHeight(Ui.dp(this, 1));
        lv.setCacheColorHint(0);
        lv.setSelector(new android.graphics.drawable.ColorDrawable(0x331E88E5));
        return lv;
    }

    private FrameLayout withEmpty(ListView lv, String emptyText) {
        FrameLayout f = new FrameLayout(this);
        f.addView(lv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        TextView e = Ui.text(this, emptyText, 20, Ui.GREY, false);
        e.setGravity(Gravity.CENTER);
        e.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20));
        f.addView(e, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        lv.setEmptyView(e);
        return f;
    }

    private LinearLayout rowBox() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        return r;
    }

    // ------------------------------------------------------------------ powiadomienia

    private View buildNotifs() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        ListView lv = newList();
        notifAdapter = new RowAdapter() {
            @Override
            List<JSONObject> data() {
                return Store.notifs;
            }

            @Override
            View row(JSONObject o) {
                LinearLayout line = new LinearLayout(MainActivity.this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.setPadding(Ui.dp(MainActivity.this, 10), 0, 0, 0);
                String app = o.optString("app");
                int sz = Ui.dp(MainActivity.this, 54);
                line.addView(Ui.badge(MainActivity.this, app, 54), new LinearLayout.LayoutParams(sz, sz));
                LinearLayout r = rowBox();
                LinearLayout head = new LinearLayout(MainActivity.this);
                head.setOrientation(LinearLayout.HORIZONTAL);
                head.addView(Ui.text(MainActivity.this, Ui.appKind(app), 16, Ui.appColor(app), true),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                head.addView(Ui.text(MainActivity.this, Ui.time(o.optLong("time")), 16, Ui.GREY, false));
                r.addView(head);
                String title = o.optString("title");
                if (!title.isEmpty()) r.addView(Ui.text(MainActivity.this, title, 21, Ui.WHITE, true));
                String text = o.optString("text");
                if (!text.isEmpty()) r.addView(Ui.text(MainActivity.this, text, 19, 0xFFE0E0E0, false));
                line.addView(r, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                return line;
            }
        };
        lv.setAdapter(notifAdapter);
        lv.setOnItemClickListener((p, v, pos, id) -> notifActions(Store.notifs.get(pos)));
        col.addView(withEmpty(lv, "Brak powiadomień.\nNowe SMS-y i wiadomości pojawią się tutaj i w okienku na górze ekranu."),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        col.addView(Ui.button(this, "🗑 Wyczyść listę", 0xFFC62828, 18, v -> {
            Store.notifs.clear();
            Store.changed();
        }), Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56), 0, this, 6));
        return col;
    }

    // ------------------------------------------------------------------ historia połączeń

    private View buildCalls() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        ListView lv = newList();
        callAdapter = new RowAdapter() {
            @Override
            List<JSONObject> data() {
                return Store.calls;
            }

            @Override
            View row(JSONObject o) {
                LinearLayout r = new LinearLayout(MainActivity.this);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(Ui.dp(MainActivity.this, 10), Ui.dp(MainActivity.this, 8),
                        Ui.dp(MainActivity.this, 14), Ui.dp(MainActivity.this, 8));
                int type = o.optInt("type");
                String sym;
                String label;
                int color;
                switch (type) {
                    case 1:
                        sym = "↙";
                        label = "odebrane";
                        color = 0xFF66BB6A;
                        break;
                    case 2:
                        sym = "↗";
                        label = "wybierane";
                        color = 0xFF42A5F5;
                        break;
                    case 3:
                        sym = "✖";
                        label = "nieodebrane";
                        color = 0xFFEF5350;
                        break;
                    case 5:
                        sym = "⊘";
                        label = "odrzucone";
                        color = 0xFFEF5350;
                        break;
                    case 6:
                        sym = "⊘";
                        label = "zablokowane";
                        color = 0xFF9E9E9E;
                        break;
                    default:
                        sym = "•";
                        label = "";
                        color = Ui.GREY;
                }
                TextView s = Ui.text(MainActivity.this, sym, 30, color, true);
                s.setGravity(Gravity.CENTER);
                r.addView(s, new LinearLayout.LayoutParams(Ui.dp(MainActivity.this, 56),
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                LinearLayout txt = new LinearLayout(MainActivity.this);
                txt.setOrientation(LinearLayout.VERTICAL);
                String num = o.optString("p");
                String name = displayName(o.optString("n"), num);
                txt.addView(Ui.text(MainActivity.this, name.isEmpty() ? (num.isEmpty() ? "Nieznany numer" : num) : name,
                        22, type == 3 ? 0xFFEF9A9A : Ui.WHITE, true));
                String sub = (name.isEmpty() ? "" : num + " · ") + Ui.time(o.optLong("d"))
                        + (label.isEmpty() ? "" : " · " + label);
                txt.addView(Ui.text(MainActivity.this, sub, 16, Ui.GREY, false));
                r.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                return r;
            }
        };
        lv.setAdapter(callAdapter);
        lv.setOnItemClickListener((p, v, pos, id) -> {
            JSONObject o = Store.calls.get(pos);
            String num = o.optString("p");
            if (!num.isEmpty()) contactActions(displayName(o.optString("n"), num), num);
        });
        col.addView(withEmpty(lv, "Brak historii połączeń.\nPołącz telefon z radiem."),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        col.addView(Ui.button(this, "🔄 Pobierz ponownie z telefonu", Ui.TEAL, 18, v -> sync()),
                Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56), 0, this, 6));
        return col;
    }

    private String displayName(String cached, String num) {
        if (cached != null && !cached.isEmpty() && !"null".equals(cached)) return cached;
        String n = Store.nameFor(num);
        return n == null ? "" : n;
    }

    // ------------------------------------------------------------------ kontakty

    private View buildContacts() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        EditText search = new EditText(this);
        search.setHint("🔍 Szukaj po nazwie lub numerze…");
        search.setHintTextColor(0xFF78909C);
        search.setTextColor(Ui.WHITE);
        search.setTextSize(20);
        search.setSingleLine(true);
        search.setBackground(Ui.round(this, Ui.CARD, 12));
        search.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        search.setText(query);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                refresh();
            }
        });
        left.addView(search, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, this, 6));

        contactList = newList();
        contactAdapter = new RowAdapter() {
            @Override
            List<JSONObject> data() {
                return shownContacts;
            }

            @Override
            View row(JSONObject o) {
                LinearLayout r = rowBox();
                r.addView(Ui.text(MainActivity.this, o.optString("n"), 22, Ui.WHITE, true));
                r.addView(Ui.text(MainActivity.this, joinNums(o.optJSONArray("p")), 17, Ui.GREY, false));
                return r;
            }
        };
        contactList.setAdapter(contactAdapter);
        contactList.setOnItemClickListener((p, v, pos, id) -> pickNumber(shownContacts.get(pos)));
        left.addView(withEmpty(contactList, "Brak kontaktów.\nPołącz telefon z radiem – kontakty pobiorą się same i zostaną zapamiętane."),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        ScrollView sv = new ScrollView(this);
        letterCol = new LinearLayout(this);
        letterCol.setOrientation(LinearLayout.VERTICAL);
        sv.addView(letterCol);
        row.addView(sv, new LinearLayout.LayoutParams(Ui.dp(this, 60), ViewGroup.LayoutParams.MATCH_PARENT));
        contactsCountShown = -1;
        filterContacts();
        fillLetters();
        return row;
    }

    private static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return n.replace('ł', 'l');
    }

    private void filterContacts() {
        shownContacts.clear();
        String q = norm(query.trim());
        String qd = J.digits(query);
        for (JSONObject c : Store.contacts) {
            if (q.isEmpty() || norm(c.optString("n")).contains(q)
                    || (qd.length() >= 3 && J.digits(c.optString("p")).contains(qd))) {
                shownContacts.add(c);
            }
        }
    }

    private static String firstLetter(String name) {
        String n = norm(name).toUpperCase(Locale.ROOT);
        if (n.isEmpty() || !Character.isLetter(n.charAt(0))) return "#";
        return n.substring(0, 1);
    }

    private void fillLetters() {
        if (letterCol == null) return;
        letterCol.removeAllViews();
        contactsCountShown = Store.contacts.size();
        LinkedHashSet<String> letters = new LinkedHashSet<>();
        for (JSONObject c : Store.contacts) letters.add(firstLetter(c.optString("n")));
        for (final String L : letters) {
            TextView t = Ui.text(this, L, 21, 0xFF64B5F6, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            t.setOnClickListener(v -> {
                for (int i = 0; i < shownContacts.size(); i++) {
                    if (firstLetter(shownContacts.get(i).optString("n")).equals(L)) {
                        if (contactList != null) contactList.setSelection(i);
                        return;
                    }
                }
            });
            letterCol.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private static String joinNums(JSONArray p) {
        if (p == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.length(); i++) {
            if (i > 0) sb.append("   ");
            sb.append(p.optString(i));
        }
        return sb.toString();
    }

    private void pickNumber(JSONObject c) {
        final String name = c.optString("n");
        final JSONArray p = c.optJSONArray("p");
        if (p == null || p.length() == 0) return;
        if (p.length() == 1) {
            contactActions(name, p.optString(0));
            return;
        }
        Okno o = new Okno(this, name, "Który numer?");
        for (int i = 0; i < p.length(); i++) {
            final String num = p.optString(i);
            o.row(o.btn("☎ " + num, i % 2 == 0 ? Ui.BLUE : Ui.TEAL, true, () -> contactActions(name, num)));
        }
        o.cancel("✖ Anuluj");
        o.show();
    }

    /** Okno wiadomości: pełna treść + Odsłuchaj / Odpowiedz / Zadzwoń / Usuń. */
    private void notifActions(final JSONObject m) {
        final String title = m.optString("title");
        final String app = m.optString("app");
        String sub = Ui.appIcon(app) + "  " + Ui.appKind(app) + "  ·  " + Ui.time(m.optLong("time"));
        final Okno o = new Okno(this, title.isEmpty() ? app : title, sub);
        String text = m.optString("text");
        if (!text.isEmpty()) o.message(text, 22);

        Button reply = m.optBoolean("reply")
                ? o.btn("✉ Odpowiedz", Ui.GREEN, true, () -> openReply(m)) : null;
        o.row(o.btn("🔊 Odsłuchaj", Ui.BLUE, false, () -> speak(m)), reply);

        final String num = Store.numberForName(title);
        Button call = num != null ? o.btn("📞 Zadzwoń", Ui.TEAL, true, () -> dial(num, title)) : null;
        o.row(call, o.btn("🗑 Usuń", 0xFFC62828, true, () -> {
            Store.removeNotif(m.optString("key"));
            Store.changed();
        }));
        o.cancel("✖ Zamknij");
        o.show();
    }

    private void speak(JSONObject m) {
        if (!Mowa.speak(this, Popup.speech(m))) toast("Brak połączenia z telefonem");
    }

    private void openReply(final JSONObject m) {
        final String key = m.optString("key");
        String who = m.optString("title");
        composer("✉ Odpowiedz" + (who.isEmpty() ? "" : ": " + who), m.optString("text"), false, t -> {
            if (RadioService.sendToPhone(J.obj("t", "reply", "key", key, "text", t))) {
                toast("Wysyłam odpowiedź…");
                return true;
            }
            toast("Brak połączenia z telefonem");
            return false;
        });
    }

    /** Kontakt: zadzwoń albo SMS. */
    private void contactActions(final String name, final String num) {
        boolean hasName = name != null && !name.isEmpty();
        Okno o = new Okno(this, hasName ? name : num, hasName ? num : null);
        o.row(o.btn("📞 Zadzwoń", Ui.GREEN, true, () -> dial(num, name)),
                o.btn("✉ Napisz SMS", Ui.BLUE, true, () -> openSms(name, num)));
        o.cancel("✖ Anuluj");
        o.show();
    }

    private void openSms(final String name, final String num) {
        String who = (name == null || name.isEmpty()) ? num : name;
        composer("✉ SMS do: " + who, null, true, t -> {
            if (RadioService.sendToPhone(J.obj("t", "sms", "num", num, "text", t))) {
                toast("Wysyłam SMS…");
                return true;
            }
            toast("Brak połączenia z telefonem");
            return false;
        });
    }

    private interface Sender {
        boolean send(String text);
    }

    /** Wspólne okno pisania: dyktowanie, gotowe teksty, własny tekst. */
    private void composer(String title, String original, boolean dictateNow, final Sender sender) {
        final Okno o = new Okno(this, title, null);
        if (original != null && !original.isEmpty()) o.message("„" + original + "”", 19);

        final EditText et = new EditText(this);
        et.setHint("Kliknij 🎤 i mów albo wpisz…");
        et.setHintTextColor(0xFF78909C);
        et.setTextColor(Ui.WHITE);
        et.setTextSize(22);
        et.setMinLines(2);
        int p = Ui.dp(this, 14);
        et.setPadding(p, p, p, p);
        et.setBackground(Ui.round(this, 0xFF111821, 16));
        o.view(et);

        o.row(o.btn("🎤 Dyktuj", Ui.PURPLE, false, () -> dictate(et)),
                o.btn("⌫ Wyczyść", Ui.ORANGE, false, () -> et.setText("")),
                o.btn("📨 Wyślij", Ui.GREEN, false, () -> {
                    String t = et.getText().toString().trim();
                    if (t.isEmpty()) {
                        toast("Najpierw podyktuj, wpisz albo wybierz gotowy tekst");
                        return;
                    }
                    if (sender.send(t)) o.dismiss();
                }));

        o.view(Ui.text(this, "Gotowe teksty – kliknij, żeby wysłać od razu:", 17, Ui.GREY, false));
        for (int i = 0; i < QUICK_REPLIES.length; i += 2) {
            final String q1 = QUICK_REPLIES[i];
            Button b1 = o.btn(q1, QUICK_COLORS[i % QUICK_COLORS.length], false, () -> {
                if (sender.send(q1)) o.dismiss();
            });
            Button b2 = null;
            if (i + 1 < QUICK_REPLIES.length) {
                final String q2 = QUICK_REPLIES[i + 1];
                b2 = o.btn(q2, QUICK_COLORS[(i + 1) % QUICK_COLORS.length], false, () -> {
                    if (sender.send(q2)) o.dismiss();
                });
            }
            o.row(b1, b2);
        }
        o.cancel("✖ Anuluj");
        o.d.setOnDismissListener(d -> {
            if (dictateTarget == et) dictateTarget = null;
        });
        o.show();
        if (dictateNow) dictate(et);
    }

    /** Dyktowanie: słucha telefon (Samsung ma polskie rozpoznawanie mowy); bez połączenia – próbuje radio. */
    private void dictate(EditText target) {
        dictateTarget = target;
        dictateBase = target.getText().toString().trim();
        if (RadioService.sendToPhone(J.obj("t", "dictate"))) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL");
        try {
            startActivityForResult(i, REQ_SPEECH);
        } catch (Exception e) {
            toast("Brak połączenia z telefonem – nie ma kto słuchać. Wpisz tekst.");
        }
    }

    /** Tekst podyktowany do telefonu wraca tutaj. */
    static void deliverDictation(android.content.Context c, String text, String err) {
        MainActivity a = active;
        if (err != null && !err.isEmpty()) {
            Toast.makeText(c, err, Toast.LENGTH_LONG).show();
            return;
        }
        if (text == null || text.isEmpty()) return;
        if (a == null || a.dictateTarget == null) {
            Toast.makeText(c, "Podyktowano: " + text, Toast.LENGTH_LONG).show();
            return;
        }
        a.appendDictation(text);
    }

    /** Tekst pojawia się na bieżąco w trakcie mówienia. */
    static void liveDictation(String text) {
        MainActivity a = active;
        if (a != null) a.appendDictation(text);
    }

    private void appendDictation(String said) {
        if (dictateTarget == null || said == null || said.isEmpty()) return;
        said = said.substring(0, 1).toUpperCase(new Locale("pl", "PL")) + said.substring(1);
        dictateTarget.setText(dictateBase.isEmpty() ? said : dictateBase + " " + said);
        dictateTarget.setSelection(dictateTarget.getText().length());
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SPEECH || res != RESULT_OK || data == null) return;
        ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (r != null && !r.isEmpty()) appendDictation(r.get(0));
    }

    // ------------------------------------------------------------------ pulpit

    private static final class AppItem {
        String label;
        android.content.ComponentName cn;
        android.graphics.drawable.Drawable icon;
        boolean fav;
        String key;    // "rc:..." = funkcja Radio Centrum, inaczej aplikacja
        String emoji;
        int color;

        String id() {
            return key != null ? key : cn.flattenToString();
        }
    }

    /** Funkcje Radio Centrum, które można dodać na Pulpit jak aplikacje. */
    private static final String[][] RC_TILES = {
            {"rc:calls", "📞", "Połączenia"}, {"rc:contacts", "📒", "Kontakty"}, {"rc:notif", "🔔", "Powiadomienia"},
            {"rc:keypad", "⌨", "Klawiatura"}, {"rc:radio", "📻", "Radio"}, {"rc:aa", "🚗", "Android Auto"},
            {"rc:tools", "🛠", "Narzędzia radia"}, {"rc:settings", "⚙", "Ustawienia"}, {"rc:apps", "📱", "Wszystkie aplikacje"},
            {"rc:more", "☰", "Więcej"}};
    private static final int[] RC_COLORS = {Ui.GREEN, Ui.BLUE, Ui.ORANGE, Ui.PURPLE, 0xFFE65100, 0xFF1565C0,
            0xFF00695C, Ui.SLATE, 0xFF3949AB, 0xFF455A64};

    private List<AppItem> rcTiles(java.util.Set<String> favs) {
        List<AppItem> out = new ArrayList<>();
        for (int i = 0; i < RC_TILES.length; i++) {
            AppItem a = new AppItem();
            a.key = RC_TILES[i][0];
            a.emoji = RC_TILES[i][1];
            a.label = RC_TILES[i][2];
            a.color = RC_COLORS[i];
            a.fav = favs.contains(a.key);
            out.add(a);
        }
        return out;
    }

    private void openTile(AppItem a) {
        if (a.key == null) {
            launch(a.cn);
            return;
        }
        switch (a.key) {
            case "rc:calls":
                show(T_CALLS);
                break;
            case "rc:contacts":
                show(T_CONTACTS);
                break;
            case "rc:notif":
                show(T_NOTIF);
                break;
            case "rc:keypad":
                show(T_KEYPAD);
                break;
            case "rc:radio":
                launchRole("radio");
                break;
            case "rc:aa":
                openAA();
                break;
            case "rc:tools":
                tools();
                break;
            case "rc:settings":
                show(T_SETTINGS);
                break;
            case "rc:apps":
                show(T_APPS);
                break;
            default:
                moreMenu();
        }
    }

    /** Pierwsze uruchomienie: domyślne kafelki na Pulpicie. */
    private void ensureDefaultTiles() {
        android.content.SharedPreferences sp = getSharedPreferences("rc", MODE_PRIVATE);
        if (sp.getBoolean("tilesInit", false)) return;
        java.util.Set<String> f = Prefs.favs(this);
        java.util.Collections.addAll(f, "rc:calls", "rc:contacts", "rc:notif", "rc:radio", "rc:settings");
        Prefs.setFavs(this, f);
        sp.edit().putBoolean("tilesInit", true).apply();
    }

    private View buildHome() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 6));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = Ui.dp(this, 12);
        left.setPadding(p, Ui.dp(this, 6), p, p);
        left.setBackground(Ui.round(this, Ui.CARD, 20));

        android.widget.TextClock clock = new android.widget.TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextSize(58);
        clock.setTextColor(Ui.WHITE);
        clock.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        clock.setGravity(Gravity.CENTER);
        left.addView(clock);
        android.widget.TextClock date = new android.widget.TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTextSize(19);
        date.setTextColor(Ui.GREY);
        date.setGravity(Gravity.CENTER);
        left.addView(date);

        // dane jazdy zamiast rysunku auta
        boolean can = Prefs.canOn(this);
        String[] labels = {"Prędkość", can ? "Paliwo" : "Kierunek", "Temperatura", "Dystans trasy", "Czas jazdy", "Średnia",
                can ? "Zasięg" : "Maks.", "Światła", "Telefon"};
        dataVals = new TextView[labels.length];
        LinearLayout gridData = new LinearLayout(this);
        gridData.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < 3; r++) {
            LinearLayout rowD = new LinearLayout(this);
            rowD.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                int idx = r * 3 + c;
                LinearLayout tile = new LinearLayout(this);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER);
                tile.setBackground(Ui.round(this, 0xFF111821, 14));
                tile.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
                TextView lab = Ui.text(this, labels[idx], 13, Ui.GREY, false);
                lab.setGravity(Gravity.CENTER);
                lab.setMaxLines(1);
                Ui.autoSize(lab, 9, 13);
                tile.addView(lab, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 18)));
                TextView val = Ui.text(this, "–", 22, Ui.WHITE, true);
                val.setGravity(Gravity.CENTER);
                val.setMaxLines(1);
                Ui.autoSize(val, 11, 24);
                tile.addView(val, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
                dataVals[idx] = val;
                if (can && (idx == 1 || idx == 6)) {
                    tile.setOnClickListener(v -> Paliwo.show(this));
                    tile.setOnLongClickListener(v -> {
                        Paliwo.show(this);
                        return true;
                    });
                }
                if (idx == 2) {
                    tile.setOnLongClickListener(v -> {
                        Pogoda.refresh();
                        toast(Pogoda.info());
                        return true;
                    });
                }
                if (idx == 3) {
                    tile.setOnLongClickListener(v -> {
                        Fotoradary.resetTrip();
                        toast("Trasa wyzerowana");
                        return true;
                    });
                }
                rowD.addView(tile, Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1, this, 3));
            }
            gridData.addView(rowD, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        left.addView(gridData, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        TextView hintTrip = Ui.text(this, "Przytrzymaj „Dystans trasy”, aby wyzerować", 12, 0xFF78909C, false);
        hintTrip.setGravity(Gravity.CENTER);
        left.addView(hintTrip);
        homeStatus = Ui.text(this, "", 17, Ui.WHITE, true);
        homeStatus.setPadding(0, Ui.dp(this, 6), 0, 0);
        left.addView(homeStatus);
        homeLast = Ui.text(this, "", 16, 0xFFE0E0E0, false);
        homeLast.setMaxLines(3);
        homeLast.setEllipsize(android.text.TextUtils.TruncateAt.END);
        homeLast.setOnClickListener(v -> show(T_NOTIF));
        left.addView(homeLast);
        row.addView(left, Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f, this, 4));

        row.addView(buildHomeApps(), Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1, this, 4));
        refreshHomeData();
        return row;
    }

    /** Wszystkie aplikacje (☰ Więcej → 📱 Aplikacje). */
    private View buildApps() {
        final android.widget.GridView grid = new android.widget.GridView(this);
        grid.setNumColumns(android.widget.GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(this, 112));
        grid.setStretchMode(android.widget.GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(Ui.dp(this, 8));
        grid.setHorizontalSpacing(Ui.dp(this, 8));
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        final List<AppItem> apps = loadApps();
        final BaseAdapter ad = new BaseAdapter() {
            @Override
            public int getCount() {
                return apps.size();
            }

            @Override
            public Object getItem(int i) {
                return apps.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View cv, ViewGroup parent) {
                AppItem a = apps.get(i);
                LinearLayout t = new LinearLayout(MainActivity.this);
                t.setOrientation(LinearLayout.VERTICAL);
                t.setGravity(Gravity.CENTER);
                int pp = Ui.dp(MainActivity.this, 6);
                t.setPadding(pp, pp, pp, pp);
                t.setBackground(Ui.round(MainActivity.this, a.fav ? 0xFF24385A : Ui.CARD, 18));
                android.widget.ImageView iv = new android.widget.ImageView(MainActivity.this);
                iv.setImageDrawable(a.icon);
                int is = Ui.dp(MainActivity.this, 54);
                t.addView(iv, new LinearLayout.LayoutParams(is, is));
                TextView l = Ui.text(MainActivity.this, (a.fav ? "★ " : "") + a.label, 15, Ui.WHITE, false);
                l.setGravity(Gravity.CENTER);
                l.setMaxLines(2);
                l.setEllipsize(android.text.TextUtils.TruncateAt.END);
                t.addView(l);
                t.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(MainActivity.this, 118)));
                return t;
            }
        };
        grid.setAdapter(ad);
        grid.setOnItemClickListener((pa, v, pos, id) -> {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                i.setComponent(apps.get(pos).cn);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(i);
            } catch (Exception e) {
                toast("Nie udało się otworzyć");
            }
        });
        grid.setOnItemLongClickListener((pa, v, pos, id) -> {
            AppItem a = apps.get(pos);
            java.util.Set<String> f = Prefs.favs(this);
            String k = a.cn.flattenToString();
            if (f.contains(k)) f.remove(k);
            else f.add(k);
            Prefs.setFavs(this, f);
            toast(f.contains(k) ? "★ Przypięto na Pulpit: " + a.label : "Odpięto: " + a.label);
            apps.clear();
            apps.addAll(loadApps());
            ad.notifyDataSetChanged();
            return true;
        });
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        TextView hint = Ui.text(this, "Przytrzymaj kafelek, aby przypiąć go na Pulpit ★", 14, 0xFF78909C, false);
        hint.setPadding(Ui.dp(this, 6), 0, 0, Ui.dp(this, 4));
        right.addView(hint);
        right.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return right;
    }

    // ------------------------------------------------------------------ nawigacja / radio / menu

    private void topClick(int i) {
        switch (i) {
            case 0:
                show(T_HOME);
                break;
            case 1:
                show(T_CALLS);
                break;
            case 2:
                show(T_CONTACTS);
                break;
            case 3:
                launchRole("radio");
                break;
            default:
                moreMenu();
        }
    }

    private void moreMenu() {
        Okno o = new Okno(this, "☰ Więcej", null);
        int n = Store.notifs.size();
        o.row(o.btn("🔔 Powiadomienia" + (n > 0 ? " (" + n + ")" : ""), Ui.ORANGE, true, () -> show(T_NOTIF)),
                o.btn("⌨ Klawiatura", Ui.PURPLE, true, () -> show(T_KEYPAD)));
        o.row(o.btn("📱 Aplikacje", Ui.BLUE, true, () -> show(T_APPS)),
                o.btn("⚙ Ustawienia", Ui.SLATE, true, () -> show(T_SETTINGS)));
        o.row(o.btn("🛠 Narzędzia radia", 0xFF00695C, true, this::tools));
        o.cancel("✖ Zamknij");
        o.show();
    }

    private static final String[] NAV_WORDS = {"maps", "mapy", "nav", "yanosik", "automapa", "waze", "igo",
            "sygic", "here", "osmand", "mapa", "gps"};
    private static final String[] RADIO_WORDS = {"radio", "fm", "tuner", "am/fm"};

    private AppItem roleApp(String role) {
        String f = getSharedPreferences("rc", MODE_PRIVATE).getString("role_" + role, null);
        if (f == null) return null;
        android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(f);
        if (cn == null) return null;
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ActivityInfo ai = pm.getActivityInfo(cn, 0);
            AppItem a = new AppItem();
            a.cn = cn;
            a.label = String.valueOf(ai.loadLabel(pm));
            a.icon = ai.loadIcon(pm);
            return a;
        } catch (Exception e) {
            return null;
        }
    }

    private String shiftLabel() {
        int r = Prefs.shiftRpm(this);
        return r <= 0 ? "⬆⬇ Podpowiedź biegu: wyłączona"
                : "⬆⬇ Podpowiedź biegu: wyższy od " + r + ", niższy poniżej " + Math.max(1000, r - 800)
                + " obr/min (kliknij, by zmienić)";
    }

    /** Surowe dane z CAN – do porównania z fabrycznym ekranem i ustawienia właściwych bajtów. */
    private void canView() {
        Okno o = new Okno(this, "🔎 Podgląd CAN", CanBus.dump()
                + "\nZrób zdjęcie tego okna i wyślij w czacie.");
        o.row(o.btn("🔄 Odśwież", Ui.BLUE, true, this::canView),
                o.btn("🚙 Fabryczne Dane auta", 0xFF2E7D32, true, () -> openPkg("com.xygala.canbus")));
        o.row(CanBus.searching()
                        ? o.btn("⏹ Zakończ szukanie", Ui.RED, true, () -> {
                            CanBus.stopSearch();
                            canView();
                        })
                        : o.btn("🔍 Szukaj zmian (klima)", 0xFF6A1B9A, true, () -> {
                            CanBus.startSearch();
                            toast("Zapamiętano. Teraz pokręć klimą (temperatura, nawiew, AC), potem kliknij Odśwież.");
                            canView();
                        }),
                o.btn("❄ Zapytaj o klimę", 0xFF0277BD, true, () -> {
                    CanBus.ask(0x21, 0x00);
                    CanBus.ask(0x23, 0x00);
                    toast("Wysłano zapytanie o dane klimy – kliknij Odśwież za 2 s");
                }));
        o.cancel("✖ Zamknij");
        o.show();
    }

    /** Skróty do ukrytych / fabrycznych ekranów radia (znalezionych w diagnostyce). */
    private void tools() {
        Okno o = new Okno(this, "🛠 Narzędzia radia",
                "Skróty do ekranów producenta. W menu fabrycznym zmieniaj tylko to, co znasz – zrób najpierw zdjęcie.");
        o.row(o.btn("🔐 Menu fabryczne (8888) – mikrofon MIC", 0xFFC62828, true,
                () -> openComp("com.xygala.pvcanset", "com.xygala.pvcanset.PassDialogActivity")));
        o.row(o.btn("⚙ Ustawienia pojazdu (Głośność → mikrofon)", Ui.SLATE, true,
                        () -> openComp("com.xyauto.Settings", "com.xyauto.Settings.MainActivity")),
                o.btn("🎚 Efekt dźwiękowy / korektor", Ui.PURPLE, true,
                        () -> openComp("com.xy.tda7729", "com.xy.tda7729.MainActivity")));
        o.row(o.btn("🎤 Test mikrofonów (który słyszy?)", 0xFFAD1457, true, () -> MicTest.show(this)));
        boolean can = Prefs.canOn(this);
        o.row(o.btn(can ? "⛽ Dane z CAN: WŁĄCZONE (kliknij, by wyłączyć)" : "⛽ Dane z CAN: wyłączone (kliknij – test)",
                        can ? 0xFF2E7D32 : 0xFF00695C, true, () -> {
                            boolean nw = !Prefs.canOn(this);
                            Prefs.setCanOn(this, nw);
                            if (nw) CanBus.start(this);
                            else CanBus.stop();
                            toast(nw ? "Dane z CAN włączone – Paliwo i Zasięg na Pulpicie" : "Dane z CAN wyłączone");
                            recreate();
                        }),
                o.btn("🔎 Podgląd CAN", 0xFF00838F, true, this::canView));
        o.row(o.btn("🔬 Sonda CAN – bezpieczna", 0xFF455A64, true, () -> CanProbe.run(this)),
                o.btn("📻 Zapisz aplikację radia FM", 0xFFAD1457, true, this::exportRadioApks));
        o.row(o.btn("🎙 Test mikrofonu (nagraj i odsłuchaj)", Ui.GREEN, true,
                        () -> openComp("com.android.soundrecorder", "com.android.soundrecorder.SoundRecorder")),
                o.btn("📶 Ustawienia Bluetooth radia", Ui.BLUE, true,
                        () -> openComp("com.autochips.bluetooth", "com.autochips.bluetooth.BtSettingsActivity")));
        o.row(o.btn("📷 Kamera / wejście AV", 0xFF37474F, true,
                        () -> openComp("com.autochips.android.backcar", "com.autochips.android.backcar.AVInActivity")),
                o.btn("🎥 Rejestrator DVR", 0xFF37474F, true,
                        () -> openComp("com.autochips.android.backcar", "com.autochips.android.backcar.DVRActivity")));
        o.row(o.btn("🚙 Dane auta (CAN – Car Set)", 0xFF2E7D32, true, () -> openPkg("com.xygala.canbus")),
                o.btn("🛰 Test GPS", 0xFF1565C0, true,
                        () -> openComp("com.mediatek.ygps", "com.mediatek.ygps.YgpsActivity")));
        o.row(o.btn("ℹ Informacje o systemie", Ui.SLATE, true,
                        () -> openComp("com.xyauto.Settings", "com.xyauto.Settings.SystemInfoActivity")),
                o.btn("🧪 EngineerMode (zaawansowane!)", 0xFF6D4C41, true,
                        () -> openComp("com.mediatek.engineermode", "com.mediatek.engineermode.EngineerMode")));
        o.cancel("✖ Zamknij");
        o.show();
    }

    private void openComp(String pkg, String cls) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(new android.content.ComponentName(pkg, cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("Ten ekran nie chce się otworzyć na tym radiu");
        }
    }

    private void openPkg(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            toast("Brak aplikacji " + pkg);
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("Nie udało się otworzyć");
        }
    }

    private void openAA() {
        if (!AutoStart.launchAA(this)) pickRole("aa");
    }

    private void launchRole(String role) {
        AppItem a = roleApp(role);
        if (a == null) {
            pickRole(role);
            return;
        }
        launch(a.cn);
    }

    private void launch(android.content.ComponentName cn) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(cn);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception e) {
            toast("Nie udało się otworzyć");
        }
    }

    /** Wybór aplikacji do roli (nawigacja / radio). Pasujące aplikacje są na górze listy. */
    private void pickRole(final String role) {
        final boolean nav = "nav".equals(role);
        final boolean aa = "aa".equals(role);
        String[] words = nav ? NAV_WORDS : aa ? AutoStart.AA_WORDS : RADIO_WORDS;
        List<AppItem> all = loadApps();
        List<AppItem> match = new ArrayList<>(), rest = new ArrayList<>();
        for (AppItem a : all) {
            String t = (a.label + " " + a.cn.getPackageName()).toLowerCase(Locale.ROOT);
            boolean m = false;
            for (String w : words) if (t.contains(w)) m = true;
            if (m) match.add(a);
            else rest.add(a);
        }
        Okno o = new Okno(this, nav ? "🧭 Która aplikacja to nawigacja?"
                        : aa ? "🚗 Która aplikacja to Android Auto (np. ZLink5)?" : "📻 Która aplikacja to radio FM?",
                "Pasujące są na zielono, na górze listy");
        List<AppItem> ordered = new ArrayList<>(match);
        ordered.addAll(rest);
        for (int i = 0; i < ordered.size(); i += 2) {
            Button b1 = roleBtn(o, role, ordered.get(i), i < match.size());
            Button b2 = i + 1 < ordered.size() ? roleBtn(o, role, ordered.get(i + 1), i + 1 < match.size()) : null;
            o.row(b1, b2);
        }
        o.cancel("✖ Anuluj");
        o.show();
    }

    private Button roleBtn(Okno o, final String role, final AppItem a, boolean match) {
        Button b = o.btn(a.label, match ? 0xFF2E7D32 : 0xFF2E3B4E, true, () -> {
            getSharedPreferences("rc", MODE_PRIVATE).edit().putString("role_" + role, a.cn.flattenToString()).apply();
            toast("Zapisano: " + a.label);
            if (tab == T_HOME) show(T_HOME);
            if ("radio".equals(role)) launch(a.cn);
        });
        b.setTextSize(18);
        return b;
    }

    /** Duży kafel nawigacji na Pulpicie + przypięte aplikacje pod nim. */
    private View buildNavPanel() {
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        final AppItem nav = roleApp("nav");

        // żywa mapa z pozycją auta (GPS radia) + przycisk „Nawiguj”
        FrameLayout mapBox = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable g = Ui.round(this, 0xFF12352B, 22);
        g.setStroke(Ui.dp(this, 3), 0xFF43A047);
        mapBox.setBackground(g);
        int bp = Ui.dp(this, 3);
        mapBox.setPadding(bp, bp, bp, bp);
        mapView = new android.webkit.WebView(this);
        android.webkit.WebSettings ws = mapView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        mapView.setBackgroundColor(0xFF1B2430);
        mapView.setWebViewClient(new android.webkit.WebViewClient());
        mapView.loadDataWithBaseURL("https://radiocentrum.local/", MAP_HTML, "text/html", "utf-8", null);
        mapBox.addView(mapView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button go = Ui.buttonFit(this, nav != null ? "➤ Nawiguj – " + nav.label : "➤ Wybierz nawigację", 0xFF2E7D32, 20,
                v -> launchRole("nav"));
        go.setOnLongClickListener(v -> {
            pickRole("nav");
            return true;
        });
        bar.addView(go, Ui.lp(0, Ui.dp(this, 58), 1, this, 6));
        Button aaBtn = Ui.buttonFit(this, "🚗 Android Auto", 0xFF1565C0, 18, v -> openAA());
        aaBtn.setOnLongClickListener(v -> {
            pickRole("aa");
            return true;
        });
        bar.addView(aaBtn, Ui.lp(Ui.dp(this, 190), Ui.dp(this, 58), 0, this, 6));
        Button me = Ui.buttonFit(this, "◎ Moja pozycja", Ui.BLUE, 18, v -> {
            if (mapView != null) mapView.evaluateJavascript("center()", null);
        });
        bar.addView(me, Ui.lp(Ui.dp(this, 170), Ui.dp(this, 58), 0, this, 6));
        mapBox.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        right.addView(mapBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        startLocation();

        // przypięte aplikacje
        LinearLayout favRow = new LinearLayout(this);
        favRow.setOrientation(LinearLayout.HORIZONTAL);
        int shown = 0;
        for (final AppItem a : loadApps()) {
            if (!a.fav || shown >= 5) continue;
            shown++;
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setBackground(Ui.round(this, 0xFF24385A, 16));
            int pp = Ui.dp(this, 4);
            tile.setPadding(pp, pp, pp, pp);
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageDrawable(a.icon);
            int is = Ui.dp(this, 44);
            tile.addView(iv, new LinearLayout.LayoutParams(is, is));
            TextView l = Ui.text(this, a.label, 14, Ui.WHITE, false);
            l.setGravity(Gravity.CENTER);
            l.setMaxLines(1);
            l.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tile.addView(l);
            tile.setOnClickListener(v -> launch(a.cn));
            favRow.addView(tile, Ui.lp(0, Ui.dp(this, 92), 1, this, 4));
        }
        if (shown == 0) {
            TextView h = Ui.text(this, "Tu pojawią się przypięte aplikacje: ☰ Więcej → 📱 Aplikacje → przytrzymaj kafelek ★",
                    15, 0xFF78909C, false);
            h.setGravity(Gravity.CENTER);
            h.setPadding(0, Ui.dp(this, 8), 0, 0);
            favRow.addView(h, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        right.addView(favRow);
        return right;
    }

    private static final String MAP_HTML = "<!DOCTYPE html><html><head>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'>"
            + "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>"
            + "<style>html,body,#m{margin:0;height:100%;background:#1b2430}"
            + ".leaflet-control-attribution{font-size:9px}</style></head><body><div id='m'></div><script>"
            + "var map=L.map('m',{zoomControl:false}).setView([51.76,19.46],13);"
            + "L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}',"
            + "{maxZoom:19,attribution:'Mapa © Esri, HERE, Garmin, OpenStreetMap'}).addTo(map);"
            + "var me=L.circleMarker([0,0],{radius:11,color:'#fff',weight:3,fillColor:'#1E88E5',fillOpacity:1});"
            + "var follow=true;map.on('dragstart',function(){follow=false;});"
            + "function pos(a,b){me.setLatLng([a,b]);if(!map.hasLayer(me))me.addTo(map);"
            + "if(follow)map.setView([a,b],map.getZoom()<14?16:map.getZoom());}"
            + "function center(){follow=true;var p=me.getLatLng();if(p.lat)map.setView(p,16);}"
            + "var camL=L.layerGroup().addTo(map);"
            + "function cams(a){camL.clearLayers();a.forEach(function(c){"
            + "L.circleMarker([c[0],c[1]],{radius:9,color:'#fff',weight:2,fillColor:'#E53935',fillOpacity:1})"
            + ".bindTooltip('Fotoradar'+(c[2]>0?' '+c[2]+' km/h':'')).addTo(camL);});}"
            + "var sp=L.control({position:'topleft'});sp.onAdd=function(){var d=L.DomUtil.create('div');d.id='sp';"
            + "d.style.cssText='background:#1b2430;color:#fff;font:bold 28px sans-serif;padding:6px 14px;"
            + "border-radius:16px;border:3px solid #43A047';d.innerHTML='0 km/h';return d;};sp.addTo(map);"
            + "function spd(v){var d=document.getElementById('sp');if(d)d.innerHTML=v+' km/h';}"
            + "</script></body></html>";

    @android.annotation.SuppressLint("MissingPermission")
    private void startLocation() {
        if (mapView == null || locListener != null) return;
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        final android.location.LocationManager lm =
                (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return;
        locListener = new android.location.LocationListener() {
            @Override
            public void onLocationChanged(android.location.Location l) {
                if (mapView != null) {
                    mapView.evaluateJavascript("pos(" + l.getLatitude() + "," + l.getLongitude() + ")", null);
                    int kmh = l.hasSpeed() ? Math.round(l.getSpeed() * 3.6f) : 0;
                    mapView.evaluateJavascript("spd(" + kmh + ")", null);
                    if (shownCams != Fotoradary.version) {
                        shownCams = Fotoradary.version;
                        mapView.evaluateJavascript("cams(" + Fotoradary.json() + ")", null);
                    }
                }
            }

            @Override
            public void onStatusChanged(String p, int s, Bundle b) {
            }

            @Override
            public void onProviderEnabled(String p) {
            }

            @Override
            public void onProviderDisabled(String p) {
            }
        };
        for (String prov : new String[]{android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER}) {
            try {
                if (!lm.isProviderEnabled(prov)) continue;
                lm.requestLocationUpdates(prov, 2000, 3, locListener);
                final android.location.Location last = lm.getLastKnownLocation(prov);
                if (last != null) tickH.postDelayed(() -> {
                    if (locListener != null) locListener.onLocationChanged(last);
                }, 1500);
            } catch (Exception ignored) {
            }
        }
    }

    private void stopLocation() {
        if (locListener == null) return;
        try {
            android.location.LocationManager lm =
                    (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm != null) lm.removeUpdates(locListener);
        } catch (Exception ignored) {
        }
        locListener = null;
    }

    private static final String[] DIRS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final String[] DIRS_PL = {"płn.", "płn.-wsch.", "wsch.", "płd.-wsch.", "płd.", "płd.-zach.", "zach.", "płn.-zach."};

    /** Dane jazdy na Pulpicie (co 1,5 s). */
    private void refreshHomeData() {
        if (dataVals == null) return;
        boolean gps = Fotoradary.last != null;
        int spd = Auto.speed();
        boolean hand = Boolean.TRUE.equals(CanBus.handbrakeUp) && spd < 6;
        dataVals[0].setText(hand ? "🅿 RĘCZNY" : spd >= 0 ? spd + " km/h" : "brak GPS");
        dataVals[0].setTextColor(hand ? 0xFFEF5350 : Ui.WHITE);
        float b = Fotoradary.bearing;
        boolean can = Prefs.canOn(this);
        if (can) {
            dataVals[1].setText(CanBus.fuelText());
            dataVals[1].setTextColor(CanBus.fuelL >= 0 && CanBus.fuelL <= 8 ? 0xFFEF5350 : Ui.WHITE);
        } else {
            dataVals[1].setText(b < 0 ? "–" : "➤ " + DIRS[Math.round(b / 45f) % 8]);
        }
        Pogoda.tick(this);
        dataVals[2].setText(Pogoda.text());
        double tc = Pogoda.tempC;
        dataVals[2].setTextColor(Double.isNaN(tc) ? Ui.WHITE : tc <= 3 ? 0xFF81D4FA : tc >= 28 ? 0xFFFFAB40 : Ui.WHITE);
        double km = Fotoradary.tripM / 1000.0;
        dataVals[3].setText(km < 10 ? String.format(Locale.US, "%.1f km", km) : Math.round(km) + " km");
        long min = Fotoradary.movingMs / 60000;
        dataVals[4].setText(min >= 60 ? (min / 60) + " h " + (min % 60) + " min" : min + " min");
        double hrs = Fotoradary.movingMs / 3600000.0;
        dataVals[5].setText(hrs > 0.01 ? Math.round(km / hrs) + " km/h" : "–");
        dataVals[6].setText(can ? CanBus.rangeText() : Fotoradary.maxKmh > 0 ? Fotoradary.maxKmh + " km/h" : "–");
        boolean on = LightSensor.isOn(this);
        dataVals[7].setText(on ? "💡 WŁ" : "wył.");
        dataVals[7].setTextColor(on ? 0xFFFFE57F : Ui.WHITE);
        int bat = Store.phoneBat;
        dataVals[8].setText(Store.linkKind == null ? "brak" : bat < 0 ? "połączony"
                : "🔋 " + bat + "%" + (Store.phoneChg ? " ⚡" : ""));
        dataVals[8].setTextColor(bat >= 0 && bat <= 15 && !Store.phoneChg ? 0xFFEF5350 : Ui.WHITE);
    }

    /** Prawa strona Pulpitu: tylko wybrane aplikacje + „Dodaj / usuń aplikacje”. */
    private View buildHomeApps() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        ensureDefaultTiles();
        final List<AppItem> chosen = new ArrayList<>();
        java.util.Set<String> favsNow = Prefs.favs(this);
        for (AppItem a : rcTiles(favsNow)) if (a.fav) chosen.add(a);
        for (AppItem a : loadApps()) if (a.fav) chosen.add(a);

        if (chosen.isEmpty()) {
            TextView h = Ui.text(this, "Nie wybrano jeszcze aplikacji.\nKliknij przycisk poniżej i zaznacz, co ma być na Pulpicie.",
                    18, Ui.GREY, false);
            h.setGravity(Gravity.CENTER);
            col.addView(h, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            android.widget.GridView grid = new android.widget.GridView(this);
            grid.setNumColumns(android.widget.GridView.AUTO_FIT);
            grid.setColumnWidth(Ui.dp(this, 110));
            grid.setStretchMode(android.widget.GridView.STRETCH_COLUMN_WIDTH);
            grid.setVerticalSpacing(Ui.dp(this, 8));
            grid.setHorizontalSpacing(Ui.dp(this, 8));
            grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
            grid.setAdapter(new BaseAdapter() {
                @Override
                public int getCount() {
                    return chosen.size();
                }

                @Override
                public Object getItem(int i) {
                    return chosen.get(i);
                }

                @Override
                public long getItemId(int i) {
                    return i;
                }

                @Override
                public View getView(int i, View cv, ViewGroup parent) {
                    AppItem a = chosen.get(i);
                    LinearLayout t = new LinearLayout(MainActivity.this);
                    t.setOrientation(LinearLayout.VERTICAL);
                    t.setGravity(Gravity.CENTER);
                    int pp = Ui.dp(MainActivity.this, 6);
                    t.setPadding(pp, pp, pp, pp);
                    int is = Ui.dp(MainActivity.this, 56);
                    if (a.key != null) {
                        t.setBackground(Ui.round(MainActivity.this, a.color, 18));
                        TextView em = Ui.text(MainActivity.this, a.emoji, 34, Ui.WHITE, false);
                        em.setGravity(Gravity.CENTER);
                        t.addView(em, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, is));
                    } else {
                        t.setBackground(Ui.round(MainActivity.this, 0xFF24385A, 18));
                        android.widget.ImageView iv = new android.widget.ImageView(MainActivity.this);
                        iv.setImageDrawable(a.icon);
                        t.addView(iv, new LinearLayout.LayoutParams(is, is));
                    }
                    TextView l = Ui.text(MainActivity.this, a.label, 15, Ui.WHITE, false);
                    l.setGravity(Gravity.CENTER);
                    l.setMaxLines(2);
                    l.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    t.addView(l);
                    t.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(MainActivity.this, 118)));
                    return t;
                }
            });
            grid.setOnItemClickListener((pa, v, pos, id) -> openTile(chosen.get(pos)));
            grid.setOnItemLongClickListener((pa, v, pos, id) -> {
                AppItem a = chosen.get(pos);
                java.util.Set<String> f = Prefs.favs(this);
                f.remove(a.id());
                Prefs.setFavs(this, f);
                toast("Usunięto z Pulpitu: " + a.label);
                show(T_HOME);
                return true;
            });
            col.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.addView(Ui.buttonFit(this, "➕ Dodaj / usuń", Ui.TEAL, 18, v -> chooseHomeApps()),
                Ui.lp(0, Ui.dp(this, 56), 1, this, 4));
        bottom.addView(Ui.buttonFit(this, "☰ Więcej", Ui.SLATE, 18, v -> moreMenu()),
                Ui.lp(Ui.dp(this, 150), Ui.dp(this, 56), 0, this, 4));
        col.addView(bottom);
        return col;
    }

    /** Lista wszystkich aplikacji – kliknięcie zaznacza / odznacza. */
    private void chooseHomeApps() {
        final Okno o = new Okno(this, "Aplikacje na Pulpicie", "Kliknij, aby zaznaczyć ✓ lub odznaczyć");
        final java.util.Set<String> f = Prefs.favs(this);
        List<AppItem> all = rcTiles(f);
        all.addAll(loadApps());
        for (int i = 0; i < all.size(); i += 2) {
            Button b1 = favToggle(o, all.get(i), f);
            Button b2 = i + 1 < all.size() ? favToggle(o, all.get(i + 1), f) : null;
            o.row(b1, b2);
        }
        o.cancel("✅ Gotowe");
        o.d.setOnDismissListener(d -> {
            Prefs.setFavs(this, f);
            if (tab == T_HOME) show(T_HOME);
        });
        o.show();
    }

    private Button favToggle(Okno o, final AppItem a, final java.util.Set<String> f) {
        final String k = a.id();
        final String label = a.key != null ? a.emoji + " " + a.label : a.label;
        final Button[] ref = new Button[1];
        ref[0] = o.btn((f.contains(k) ? "✓ " : "") + label, f.contains(k) ? 0xFF2E7D32 : 0xFF2E3B4E, false, () -> {
            if (f.contains(k)) f.remove(k);
            else f.add(k);
            boolean on = f.contains(k);
            ref[0].setText((on ? "✓ " : "") + label);
            Ui.setColor(this, ref[0], on ? 0xFF2E7D32 : 0xFF2E3B4E);
        });
        ref[0].setTextSize(17);
        return ref[0];
    }

    private List<AppItem> loadApps() {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.Set<String> favs = Prefs.favs(this);
        List<AppItem> out = new ArrayList<>();
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)) continue;
            AppItem a = new AppItem();
            a.label = String.valueOf(ri.loadLabel(pm));
            a.icon = ri.loadIcon(pm);
            a.cn = new android.content.ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            a.fav = favs.contains(a.cn.flattenToString());
            out.add(a);
        }
        final java.text.Collator col = java.text.Collator.getInstance(new Locale("pl", "PL"));
        java.util.Collections.sort(out, (x, y) -> {
            if (x.fav != y.fav) return x.fav ? -1 : 1;
            return col.compare(x.label, y.label);
        });
        return out;
    }

    // ------------------------------------------------------------------ nauka świateł

    private void lightStepOff() {
        toast("Zapisuję… (chwila)");
        LightSensor.snapOff = LightSensor.snapshot(this);
        Okno o = new Okno(this, "✅ Zapisano – światła ZGASZONE", "Teraz ZAPAL światła (mijania) i kliknij krok 2.");
        o.cancel("OK");
        o.show();
    }

    private void lightStepOn() {
        if (LightSensor.snapOff == null) {
            toast("Najpierw krok 1 – przy zgaszonych światłach");
            return;
        }
        toast("Zapisuję… (chwila)");
        LightSensor.snapOn = LightSensor.snapshot(this);
        final List<String[]> d = LightSensor.diff(LightSensor.snapOff, LightSensor.snapOn);
        if (d.isEmpty()) {
            Okno o = new Okno(this, "Nie znalazłem różnicy",
                    "Radio nie zapisało nigdzie, że światła są zapalone. Spróbuj jeszcze raz (krok 1 i 2). "
                            + "Jeśli dalej nic – radio może nie mieć podłączonego przewodu ILL / świateł z CAN. "
                            + "Na razie rysunek auta pokazuje światła według trybu nocnego radia.");
            o.cancel("OK");
            o.show();
            return;
        }
        Okno o = new Okno(this, "Co się zmieniło po zapaleniu świateł?",
                "Wybierz pozycję (zwykle pierwsza jest dobra). Zgaszone → zapalone:");
        int n = Math.min(d.size(), 12);
        for (int i = 0; i < n; i++) {
            final String[] r = d.get(i);
            Button b = o.btn(r[0] + "\n" + r[1] + "  →  " + r[2], i == 0 ? Ui.GREEN : 0xFF2E3B4E, true, () -> {
                Prefs.setLight(this, r[0], r[2]);
                toast("Zapisano ✅ – auto na Pulpicie pokaże światła");
                refreshStatus();
            });
            b.setTextSize(16);
            o.row(b);
        }
        o.cancel("✖ Anuluj");
        o.show();
    }

    // ------------------------------------------------------------------ klawiatura

    private View buildKeypad() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 6));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"*", "0", "#"}};
        for (String[] line : keys) {
            LinearLayout lr = new LinearLayout(this);
            lr.setOrientation(LinearLayout.HORIZONTAL);
            for (final String k : line) {
                Button b = Ui.button(this, k, 0xFF2E3B4E, 30, v -> addDigit(k));
                Ui.autoSize(b, 16, 30);
                if (k.equals("0")) {
                    b.setText("0 +");
                    b.setOnLongClickListener(v -> {
                        addDigit("+");
                        return true;
                    });
                }
                lr.addView(b, Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1, this, 4));
            }
            grid.addView(lr, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        row.addView(grid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        dialDisplay = Ui.text(this, "", 36, Ui.WHITE, true);
        dialDisplay.setGravity(Gravity.CENTER);
        Ui.autoSize(dialDisplay, 18, 36);
        dialDisplay.setMaxLines(1);
        dialDisplay.setBackground(Ui.round(this, Ui.CARD, 14));
        right.addView(dialDisplay, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1, this, 4));
        dialName = Ui.text(this, "", 18, 0xFF81C784, true);
        dialName.setGravity(Gravity.CENTER);
        right.addView(dialName, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, this, 2));

        Button del = Ui.button(this, "⌫ Usuń (przytrzymaj = wszystko)", Ui.ORANGE, 18, v -> {
            CharSequence s = dialDisplay.getText();
            if (s.length() > 0) setDial(s.subSequence(0, s.length() - 1).toString());
        });
        Ui.autoSize(del, 12, 18);
        del.setOnLongClickListener(v -> {
            setDial("");
            return true;
        });
        right.addView(del, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f, this, 4));

        Button call = Ui.button(this, "📞 Zadzwoń", Ui.GREEN, 26, v -> {
            String num = dialDisplay.getText().toString();
            if (num.isEmpty()) {
                // pusty – wstaw ostatnio wybierany numer
                for (JSONObject o : Store.calls) {
                    if (o.optInt("type") == 2) {
                        setDial(o.optString("p"));
                        return;
                    }
                }
                return;
            }
            dial(num, Store.nameFor(num));
        });
        Ui.autoSize(call, 16, 26);
        right.addView(call, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.2f, this, 4));
        row.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return row;
    }

    private void addDigit(String d) {
        setDial(dialDisplay.getText().toString() + d);
    }

    private void setDial(String s) {
        if (dialDisplay == null) return;
        dialDisplay.setText(s);
        String n = s.length() >= 3 ? Store.nameFor(s) : null;
        dialName.setText(n == null ? "" : n);
    }

    // ------------------------------------------------------------------ ustawienia

    private View buildSettings() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 12);
        col.setPadding(p, p, p, p);
        sv.addView(col);

        settingsInfo = Ui.text(this, "", 18, Ui.WHITE, false);
        settingsInfo.setLineSpacing(0, 1.2f);
        settingsInfo.setPadding(p, p, p, p);
        settingsInfo.setBackground(Ui.round(this, Ui.CARD, 14));
        col.addView(settingsInfo);

        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.addView(Ui.button(this, "🪟 Zezwól na okienka nad aplikacjami", Ui.BLUE, 18, v -> openOverlay()),
                Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1, this, 6));
        r1.addView(Ui.button(this, "🧪 Pokaż testowe powiadomienie", Ui.PURPLE, 18, v -> {
            if (!Popup.canDraw(this)) {
                toast("Najpierw zezwól na okienka");
                return;
            }
            Popup.showNotif(this, J.obj("t", "notif", "key", "test", "app", "Test",
                    "title", "Radio Centrum", "text", "Tak będą wyglądać SMS-y i wiadomości z telefonu."));
        }), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1, this, 6));
        col.addView(r1);

        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.addView(Ui.button(this, "📡 Radio widoczne dla telefonu (parowanie)", Ui.TEAL, 18, v -> {
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            safeStart(i);
        }), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1, this, 6));
        r2.addView(Ui.button(this, "⚙ Ustawienia Bluetooth radia", Ui.SLATE, 18,
                        v -> safeStart(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))),
                Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1, this, 6));
        col.addView(r2);

        LinearLayout r3 = new LinearLayout(this);
        r3.setOrientation(LinearLayout.HORIZONTAL);
        r3.setGravity(Gravity.CENTER_VERTICAL);
        final EditText code = new EditText(this);
        code.setHint("Kod Wi-Fi z telefonu (6 cyfr)");
        code.setHintTextColor(0xFF78909C);
        code.setTextColor(Ui.WHITE);
        code.setTextSize(20);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setSingleLine(true);
        code.setText(Prefs.code(this));
        code.setBackground(Ui.round(this, Ui.CARD, 12));
        code.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 10));
        r3.addView(code, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1, this, 6));
        r3.addView(Ui.button(this, "💾 Zapisz kod", Ui.GREEN, 18, v -> {
            String c = code.getText().toString().replaceAll("[^0-9]", "");
            code.setText(c);
            Prefs.setCode(this, c);
            RadioService.kick();
            toast(c.length() == 6 ? "Zapisano kod " + c + " – łączę…" : "Kod musi mieć 6 cyfr");
            refresh();
        }), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1, this, 6));
        col.addView(r3);

        col.addView(Ui.text(this, "🚗 Nauka świateł auta (zrób raz, na postoju z włączonym silnikiem lub zapłonem):",
                17, Ui.WHITE, true), Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, this, 6));
        LinearLayout rl = new LinearLayout(this);
        rl.setOrientation(LinearLayout.HORIZONTAL);
        rl.addView(Ui.button(this, "1️⃣ Światła ZGASZONE → zapisz", Ui.SLATE, 18, v -> lightStepOff()),
                Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1, this, 6));
        rl.addView(Ui.button(this, "2️⃣ Światła ZAPALONE → zapisz", 0xFFF9A825, 18, v -> lightStepOn()),
                Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1, this, 6));
        col.addView(rl);

        final Button voice = Ui.button(this, voiceLabel(), 0xFF6D4C41, 18, null);
        voice.setOnClickListener(v -> {
            Prefs.setVoiceMode(this, (Prefs.voiceMode(this) + 1) % Mowa.MODES.length);
            voice.setText(voiceLabel());
            refreshStatus();
        });
        LinearLayout rv = new LinearLayout(this);
        rv.setOrientation(LinearLayout.HORIZONTAL);
        rv.addView(voice, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f, this, 6));
        rv.addView(Ui.button(this, "🔊 Test głosu", Ui.BLUE, 18, v -> {
            if (!Mowa.speak(this, "Test. Radio Centrum czyta wiadomości.")) toast("Brak połączenia z telefonem");
        }), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1, this, 6));
        col.addView(rv);

        final Button aaT = Ui.button(this, aaLabel(), 0xFF1565C0, 18, null);
        aaT.setOnClickListener(v -> {
            Prefs.setAaAuto(this, !Prefs.aaAuto(this));
            aaT.setText(aaLabel());
        });
        final Button aaD = Ui.button(this, aaDelayLabel(), 0xFF37474F, 18, null);
        aaD.setOnClickListener(v -> {
            int cur = Prefs.aaDelay(this), next = AutoStart.DELAYS[0];
            for (int i = 0; i < AutoStart.DELAYS.length; i++) {
                if (AutoStart.DELAYS[i] == cur) next = AutoStart.DELAYS[(i + 1) % AutoStart.DELAYS.length];
            }
            Prefs.setAaDelay(this, next);
            aaD.setText(aaDelayLabel());
        });
        final Button fmT = Ui.button(this, fmLabel(), 0xFFE65100, 18, null);
        fmT.setOnClickListener(v -> {
            Prefs.setFmAuto(this, !Prefs.fmAuto(this));
            fmT.setText(fmLabel());
        });
        col.addView(fmT);
        final Button shT = Ui.button(this, shiftLabel(), 0xFF00897B, 18, null);
        shT.setOnClickListener(v -> {
            int cur = Prefs.shiftRpm(this), next = Prefs.SHIFT_RPM[0];
            for (int i = 0; i < Prefs.SHIFT_RPM.length; i++) {
                if (Prefs.SHIFT_RPM[i] == cur) next = Prefs.SHIFT_RPM[(i + 1) % Prefs.SHIFT_RPM.length];
            }
            Prefs.setShiftRpm(this, next);
            shT.setText(shiftLabel());
        });
        col.addView(shT);
        col.addView(Ui.button(this, "🔊 Test: znak podpowiedzi biegu", 0xFF00695C, 18, v -> Auto.sayShift(this)));
        LinearLayout ra = new LinearLayout(this);
        ra.setOrientation(LinearLayout.HORIZONTAL);
        ra.addView(aaT, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1, this, 6));
        ra.addView(aaD, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1, this, 6));
        col.addView(ra);

        // wybór aplikacji (przeniesione z menu „Więcej”)
        LinearLayout rr = new LinearLayout(this);
        rr.setOrientation(LinearLayout.HORIZONTAL);
        rr.addView(Ui.buttonFit(this, "🚗 Aplikacja Android Auto", 0xFF37474F, 18, v -> pickRole("aa")),
                Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1, this, 6));
        rr.addView(Ui.buttonFit(this, "Nawigacja", 0xFF2E7D32, 18, v -> pickRole("nav")),
                Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1, this, 6));
        rr.addView(Ui.buttonFit(this, "📻 Aplikacja radia", 0xFFE65100, 18, v -> pickRole("radio")),
                Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1, this, 6));
        col.addView(rr);

        final Button cam = Ui.button(this, camLabel(), 0xFFC62828, 18, null);
        cam.setOnClickListener(v -> {
            Prefs.setCamAlerts(this, !Prefs.camAlerts(this));
            cam.setText(camLabel());
        });
        col.addView(cam, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, this, 6));

        final Button auto = Ui.button(this, autoLabel(), Ui.ORANGE, 18, null);
        auto.setOnClickListener(v -> {
            Prefs.setAutoRead(this, !Prefs.autoRead(this));
            auto.setText(autoLabel());
        });
        col.addView(auto, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, this, 6));

        col.addView(Ui.button(this, "📄 Zapisz diagnostykę radia (plik dla Claude)", 0xFF00695C, 18, v -> diagnose()),
                Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, this, 6));

        col.addView(Ui.button(this, "🔄 Pobierz wszystko ponownie z telefonu", 0xFF3949AB, 18, v -> sync()),
                Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, this, 6));
        return sv;
    }

    private String voiceLabel() {
        return "🗣 Kto czyta: " + Mowa.MODES[Prefs.voiceMode(this)] + "  (kliknij, by zmienić)";
    }

    private String aaLabel() {
        android.content.ComponentName cn = AutoStart.aaApp(this);
        return "🚀 Po starcie radia uruchom Android Auto" + (cn != null ? " (" + appName(cn) + ")" : "")
                + ": " + (Prefs.aaAuto(this) ? "WŁĄCZONE" : "wyłączone");
    }

    private String fmLabel() {
        android.content.ComponentName cn = AutoStart.radioApp(this);
        return "📻 Po starcie włącz radio w tle" + (cn != null ? " (" + appName(cn) + ")" : "")
                + ", potem Pulpit: " + (Prefs.fmAuto(this) ? "WŁĄCZONE" : "wyłączone");
    }

    private String aaDelayLabel() {
        return "⏱ Potem: " + AutoStart.delayLabel(Prefs.aaDelay(this)) + "  (kliknij, by zmienić)";
    }

    private String appName(android.content.ComponentName cn) {
        try {
            return String.valueOf(getPackageManager().getActivityInfo(cn, 0).loadLabel(getPackageManager()));
        } catch (Exception e) {
            return cn.getPackageName();
        }
    }

    /** Zbiera diagnostykę, zapisuje w radiu (Pobrane) i wysyła do telefonu (Pobrane w telefonie). */
    /** Kopiuje pliki APK aplikacji CAN do Pobranych – do analizy, jak odczytać paliwo / zasięg. */
    private void exportCanApks() {
        exportApks("CAN-", new String[]{"com.xygala.canbus", "com.example.tmedia", "com.xygala.pvcanset"});
    }

    /** Fabryczne aplikacje radia FM – do podejrzenia, jak sterują tunerem. */
    private void exportRadioApks() {
        java.util.List<String> l = new java.util.ArrayList<>(java.util.Arrays.asList(
                "com.acloud.stub.localradio", "com.android.fmradio", "com.acloud.stub.extradio"));
        android.content.ComponentName cn = AutoStart.radioApp(this);
        if (cn != null && !l.contains(cn.getPackageName())) l.add(0, cn.getPackageName());
        exportApks("FM-", l.toArray(new String[0]));
    }

    private void exportApks(final String prefix, final String[] pkgs) {
        toast("Zapisuję… chwilkę");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            dir.mkdirs();
            for (String p : pkgs) {
                try {
                    android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(p, 0);
                    java.util.List<String> src = new java.util.ArrayList<>();
                    src.add(ai.sourceDir);
                    if (ai.splitSourceDirs != null) java.util.Collections.addAll(src, ai.splitSourceDirs);
                    int n = 0;
                    for (String f : src) {
                        java.io.File out = new java.io.File(dir, prefix + p + (n == 0 ? "" : "-" + n) + ".apk");
                        try (java.io.InputStream in = new java.io.FileInputStream(f);
                             java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                            byte[] b = new byte[65536];
                            int r;
                            while ((r = in.read(b)) > 0) os.write(b, 0, r);
                        }
                        sb.append("✔ ").append(out.getName()).append(" (").append(out.length() / 1024).append(" KB)\n");
                        n++;
                    }
                } catch (Exception e) {
                    sb.append("✖ ").append(p).append(": ").append(e.getMessage()).append("\n");
                }
            }
            final String msg = sb.toString();
            runOnUiThread(() -> {
                Okno o = new Okno(this, "📦 Zapisano w Pobranych",
                        msg + "\nTeraz na telefonie: „📥 Pobierz pliki z radia” i wyślij te pliki w czacie.");
                o.cancel("OK");
                o.show();
            });
        }, "rc-apk").start();
    }

    private void diagnose() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 9);
        }
        toast("Zbieram diagnostykę… (kilka sekund)");
        final String name = "RadioCentrum-diagnostyka-"
                + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new java.util.Date()) + ".txt";
        new Thread(() -> {
            String text = Diagnostyka.build(this);
            String where = "";
            try {
                java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                dir.mkdirs();
                java.io.File f = new java.io.File(dir, name);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                    out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                where = "w radiu: Pobrane/" + name;
            } catch (Exception e) {
                where = "w radiu nie udało się zapisać";
            }
            boolean sent = RadioService.sendToPhone(J.obj("t", "diag", "name", name, "text", text));
            final String msg = "Diagnostyka zapisana " + where
                    + (sent ? "\nWysyłam też do telefonu (Pobrane w telefonie)…" : "\nTelefon niepołączony – nie wysłano do telefonu");
            runOnUiThread(() -> {
                Okno o = new Okno(this, "📄 Diagnostyka gotowa", msg
                        + "\n\nWyślij ten plik do Claude w czacie (spinacz → plik).");
                o.cancel("OK");
                o.show();
            });
        }, "rc-diag").start();
    }

    private String camLabel() {
        return "📷 Ostrzeżenia o fotoradarach: " + (Prefs.camAlerts(this) ? "WŁĄCZONE" : "wyłączone");
    }

    private String autoLabel() {
        return "🔊 Czytaj nowe wiadomości automatycznie: " + (Prefs.autoRead(this) ? "WŁĄCZONE" : "wyłączone");
    }

    private String settingsText() {
        return (Popup.canDraw(this) ? "✅ Okienka nad innymi aplikacjami: włączone"
                : "❌ Okienka nad innymi aplikacjami: WYŁĄCZONE – kliknij niebieski przycisk")
                + "\n" + Mowa.info(this)
                + "\n" + RadioService.btInfo
                + "\n" + RadioService.wifiInfo
                + "\nZapisany kod Wi-Fi: " + (Prefs.code(this).isEmpty() ? "brak" : Prefs.code(this))
                + "\n" + LightSensor.info(this)
                + "\n" + Fotoradary.info + "\n" + Pogoda.info()
                + "\nKontakty w radiu: " + Store.contacts.size()
                + "   Historia połączeń: " + Store.calls.size();
    }

    private void openOverlay() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    // ------------------------------------------------------------------ dzwonienie

    private void confirmDial(final String name, final String num) {
        contactActions(name, num);
    }

    private void dial(String num, String name) {
        if (RadioService.sendToPhone(J.obj("t", "dial", "num", num))) {
            Popup.expect(num, name);
            toast("Dzwonię: " + (name == null || name.isEmpty() ? num : name));
        } else {
            toast("Brak połączenia z telefonem");
        }
    }

    private void sync() {
        if (RadioService.sendToPhone(J.obj("t", "sync"))) toast("Pobieram z telefonu…");
        else toast("Brak połączenia z telefonem");
    }

    private void safeStart(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("Nie udało się otworzyć ustawień");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
