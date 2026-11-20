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

    private static final String[] QUICK_REPLIES = {
            "Prowadzę, odezwę się później.",
            "Jestem w drodze, będę niedługo.",
            "Oddzwonię, jak tylko zaparkuję.",
            "Nie mogę teraz rozmawiać.",
            "OK 👍",
            "Tak.",
            "Nie."};
    private static final int[] QUICK_COLORS = {Ui.BLUE, Ui.TEAL, Ui.PURPLE, Ui.ORANGE, Ui.GREEN, 0xFF2E7D32, 0xFFC62828};

    private static final int T_NOTIF = 0, T_CALLS = 1, T_CONTACTS = 2, T_KEYPAD = 3, T_SETTINGS = 4;
    private static final String[] TAB_NAMES = {"🔔 Powiadomienia", "📞 Połączenia", "📒 Kontakty", "⌨ Klawiatura", "⚙ Ustawienia"};
    private static final int[] TAB_COLORS = {Ui.ORANGE, Ui.GREEN, Ui.BLUE, Ui.PURPLE, Ui.SLATE};

    private final Button[] tabs = new Button[5];
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
    private EditText dictateTarget;
    private static final int REQ_SPEECH = 42;

    private final Runnable onChange = this::refresh;

    // ------------------------------------------------------------------ start

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        RadioService.start(this);
        Store.load(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            Button t = Ui.button(this, TAB_NAMES[i], TAB_COLORS[i], 18, v -> show(idx));
            t.setMaxLines(2);
            Ui.autoSize(t, 11, 18);
            tabs[i] = t;
            top.addView(t, Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1, this, 4));
        }
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 66)));

        status = Ui.text(this, "", 16, Ui.WHITE, true);
        status.setPadding(Ui.dp(this, 12), Ui.dp(this, 2), Ui.dp(this, 12), Ui.dp(this, 4));
        root.addView(status);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        show(Popup.canDraw(this) ? T_NOTIF : T_SETTINGS);
        handleIntent(getIntent());
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
        if (key == null) return;
        i.removeExtra(EXTRA_REPLY);
        if (tab != T_NOTIF) show(T_NOTIF);
        JSONObject m = Store.findNotif(key);
        if (m != null) openReply(m);
        else toast("Ta wiadomość została już zamknięta w telefonie");
    }

    @Override
    protected void onResume() {
        super.onResume();
        RadioService.start(this);
        Store.addListener(onChange);
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Store.removeListener(onChange);
    }

    private void show(int idx) {
        tab = idx;
        for (int i = 0; i < 5; i++) {
            int c = TAB_COLORS[i];
            Ui.setColor(this, tabs[i], i == idx ? c : ((c & 0x00FFFFFF) | 0x55000000));
        }
        content.removeAllViews();
        notifAdapter = callAdapter = contactAdapter = null;
        contactList = null;
        letterCol = null;
        dialDisplay = dialName = settingsInfo = null;
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
            default:
                v = buildNotifs();
        }
        content.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        refresh();
    }

    private void refresh() {
        if (Store.linkKind != null) {
            String n = Store.phoneName == null || Store.phoneName.isEmpty() ? "telefonem" : Store.phoneName;
            status.setText("🟢 Połączono z " + n + " (" + Store.linkKind + ")");
            status.setTextColor(0xFF81C784);
        } else {
            status.setText("🔴 Brak połączenia z telefonem – " + RadioService.btInfo);
            status.setTextColor(0xFFEF9A9A);
        }
        if (!Popup.canDraw(this)) {
            status.append("   ⚠ Zezwól na okienka (⚙ Ustawienia)");
        }
        if (notifAdapter != null) notifAdapter.notifyDataSetChanged();
        if (callAdapter != null) callAdapter.notifyDataSetChanged();
        if (contactAdapter != null) {
            filterContacts();
            contactAdapter.notifyDataSetChanged();
            if (contactsCountShown != Store.contacts.size()) fillLetters();
        }
        if (settingsInfo != null) settingsInfo.setText(settingsText());
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
                LinearLayout r = rowBox();
                LinearLayout head = new LinearLayout(MainActivity.this);
                head.setOrientation(LinearLayout.HORIZONTAL);
                String app = o.optString("app");
                head.addView(Ui.text(MainActivity.this, app, 16, Ui.appColor(app), true),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                head.addView(Ui.text(MainActivity.this, Ui.time(o.optLong("time")), 16, Ui.GREY, false));
                r.addView(head);
                String title = o.optString("title");
                if (!title.isEmpty()) r.addView(Ui.text(MainActivity.this, title, 21, Ui.WHITE, true));
                String text = o.optString("text");
                if (!text.isEmpty()) r.addView(Ui.text(MainActivity.this, text, 19, 0xFFE0E0E0, false));
                return r;
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
        final String[] items = new String[p.length()];
        for (int i = 0; i < p.length(); i++) items[i] = "☎ " + p.optString(i);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(name + " – który numer?")
                .setItems(items, (d, w) -> contactActions(name, p.optString(w)))
                .setNegativeButton("Anuluj", null)
                .show();
    }

    private void notifActions(final JSONObject m) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add("🔊 Przeczytaj na głos");
        acts.add(() -> speak(m));
        if (m.optBoolean("reply")) {
            labels.add("✉ Odpowiedz");
            acts.add(() -> openReply(m));
        }
        final String title = m.optString("title");
        final String num = Store.numberForName(title);
        if (num != null) {
            labels.add("📞 Zadzwoń do: " + title);
            acts.add(() -> confirmDial(title, num));
        }
        labels.add("🗑 Usuń z listy");
        acts.add(() -> {
            Store.removeNotif(m.optString("key"));
            Store.changed();
        });
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title.isEmpty() ? m.optString("app") : title)
                .setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .setNegativeButton("Anuluj", null)
                .show();
    }

    private void speak(JSONObject m) {
        if (!RadioService.sendToPhone(J.obj("t", "speak", "text", Popup.speech(m)))) {
            toast("Brak połączenia z telefonem");
        }
    }

    /** Okno odpowiedzi: gotowe odpowiedzi jednym kliknięciem albo własny tekst. */
    private void openReply(final JSONObject m) {
        final String key = m.optString("key");
        final String who = m.optString("title");
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 12);
        col.setPadding(p, p, p, p);
        sv.addView(col);

        String text = m.optString("text");
        if (!text.isEmpty()) {
            TextView t = Ui.text(this, "„" + text + "”", 18, 0xFFE0E0E0, false);
            t.setPadding(p, p, p, p);
            t.setBackground(Ui.round(this, Ui.CARD, 12));
            col.addView(t, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, this, 4));
        }

        final AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("✉ Odpowiedz" + (who.isEmpty() ? "" : ": " + who))
                .setView(sv)
                .setNegativeButton("Anuluj", null)
                .create();

        LinearLayout grid = null;
        for (int i = 0; i < QUICK_REPLIES.length; i++) {
            if (i % 2 == 0) {
                grid = new LinearLayout(this);
                grid.setOrientation(LinearLayout.HORIZONTAL);
                col.addView(grid);
            }
            final String q = QUICK_REPLIES[i];
            grid.addView(Ui.button(this, q, QUICK_COLORS[i % QUICK_COLORS.length], 18, v -> {
                sendReply(key, q);
                dlg.dismiss();
            }), Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1, this, 4));
        }
        if (QUICK_REPLIES.length % 2 == 1 && grid != null) {
            View spacer = new View(this);
            grid.addView(spacer, Ui.lp(0, 1, 1, this, 4));
        }

        LinearLayout own = new LinearLayout(this);
        own.setOrientation(LinearLayout.HORIZONTAL);
        own.setGravity(Gravity.CENTER_VERTICAL);
        final EditText et = new EditText(this);
        et.setHint("Własna odpowiedź…");
        et.setHintTextColor(0xFF78909C);
        et.setTextColor(Ui.WHITE);
        et.setTextSize(20);
        et.setBackground(Ui.round(this, Ui.CARD, 12));
        et.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 10));
        own.addView(et, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1, this, 4));
        own.addView(Ui.button(this, "📨 Wyślij", Ui.GREEN, 20, v -> {
            String s = et.getText().toString().trim();
            if (s.isEmpty()) {
                toast("Wpisz tekst albo wybierz gotową odpowiedź");
                return;
            }
            sendReply(key, s);
            dlg.dismiss();
        }), Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, this, 4));
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        op.topMargin = Ui.dp(this, 8);
        col.addView(own, op);

        dlg.show();
    }

    private void sendReply(String key, String text) {
        if (RadioService.sendToPhone(J.obj("t", "reply", "key", key, "text", text))) {
            toast("Wysyłam: " + text);
        } else {
            toast("Brak połączenia z telefonem");
        }
    }

    /** Kontakt: zadzwoń albo napisz SMS. */
    private void contactActions(final String name, final String num) {
        String title = (name == null || name.isEmpty()) ? num : name + "\n" + num;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setItems(new String[]{"📞 Zadzwoń", "✉ Napisz SMS (możesz podyktować)"}, (d, w) -> {
                    if (w == 0) dial(num, name);
                    else openSms(name, num);
                })
                .setNegativeButton("Anuluj", null)
                .show();
    }

    /** Nowy SMS: dyktowanie głosem, gotowe teksty albo wpisanie. */
    private void openSms(final String name, final String num) {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 12);
        col.setPadding(p, p, p, p);
        sv.addView(col);

        final EditText et = new EditText(this);
        et.setHint("Treść SMS-a – kliknij 🎤 i mów");
        et.setHintTextColor(0xFF78909C);
        et.setTextColor(Ui.WHITE);
        et.setTextSize(21);
        et.setMinLines(2);
        et.setBackground(Ui.round(this, Ui.CARD, 12));
        et.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 10));
        col.addView(et, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, this, 4));

        final AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("✉ SMS do: " + ((name == null || name.isEmpty()) ? num : name))
                .setView(sv)
                .setNegativeButton("Anuluj", null)
                .create();

        LinearLayout act = new LinearLayout(this);
        act.setOrientation(LinearLayout.HORIZONTAL);
        act.addView(Ui.button(this, "🎤 Dyktuj", Ui.PURPLE, 22, v -> dictate(et)),
                Ui.lp(0, Ui.dp(this, 64), 1, this, 4));
        act.addView(Ui.button(this, "⌫ Wyczyść", Ui.ORANGE, 20, v -> et.setText("")),
                Ui.lp(0, Ui.dp(this, 64), 1, this, 4));
        act.addView(Ui.button(this, "📨 Wyślij", Ui.GREEN, 22, v -> {
            String t = et.getText().toString().trim();
            if (t.isEmpty()) {
                toast("Najpierw podyktuj albo wpisz treść");
                return;
            }
            if (RadioService.sendToPhone(J.obj("t", "sms", "num", num, "text", t))) {
                toast("Wysyłam SMS…");
                dlg.dismiss();
            } else {
                toast("Brak połączenia z telefonem");
            }
        }), Ui.lp(0, Ui.dp(this, 64), 1, this, 4));
        col.addView(act);

        col.addView(Ui.text(this, "Gotowe teksty (kliknij, żeby wstawić):", 16, Ui.GREY, false),
                Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, this, 4));
        LinearLayout grid = null;
        for (int i = 0; i < QUICK_REPLIES.length; i++) {
            if (i % 2 == 0) {
                grid = new LinearLayout(this);
                grid.setOrientation(LinearLayout.HORIZONTAL);
                col.addView(grid);
            }
            final String q = QUICK_REPLIES[i];
            grid.addView(Ui.button(this, q, 0xFF2E3B4E, 17, v -> et.setText(q)),
                    Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1, this, 4));
        }
        if (QUICK_REPLIES.length % 2 == 1 && grid != null) {
            grid.addView(new View(this), Ui.lp(0, 1, 1, this, 4));
        }
        dlg.setOnDismissListener(d -> {
            if (dictateTarget == et) dictateTarget = null;
        });
        dlg.show();
        dictate(et);
    }

    /** Rozpoznawanie mowy w radiu (jeśli radio ma usługę rozpoznawania mowy, np. aplikację Google). */
    private void dictate(EditText target) {
        dictateTarget = target;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Powiedz treść SMS-a");
        try {
            startActivityForResult(i, REQ_SPEECH);
        } catch (ActivityNotFoundException e) {
            toast("To radio nie ma rozpoznawania mowy – użyj mikrofonu na klawiaturze albo wpisz tekst");
        } catch (Exception e) {
            toast("Nie udało się włączyć dyktowania");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SPEECH || res != RESULT_OK || data == null || dictateTarget == null) return;
        ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (r == null || r.isEmpty()) return;
        String said = r.get(0);
        if (said.length() > 0) said = said.substring(0, 1).toUpperCase(new Locale("pl", "PL")) + said.substring(1);
        String cur = dictateTarget.getText().toString().trim();
        dictateTarget.setText(cur.isEmpty() ? said : cur + " " + said);
        dictateTarget.setSelection(dictateTarget.getText().length());
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
            Prefs.setCode(this, code.getText().toString().trim());
            toast("Zapisano");
            refresh();
        }), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1, this, 6));
        col.addView(r3);

        final Button auto = Ui.button(this, autoLabel(), Ui.ORANGE, 18, null);
        auto.setOnClickListener(v -> {
            Prefs.setAutoRead(this, !Prefs.autoRead(this));
            auto.setText(autoLabel());
        });
        col.addView(auto, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, this, 6));

        col.addView(Ui.button(this, "🔄 Pobierz wszystko ponownie z telefonu", 0xFF3949AB, 18, v -> sync()),
                Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, this, 6));
        return sv;
    }

    private String autoLabel() {
        return "🔊 Czytaj nowe wiadomości automatycznie: " + (Prefs.autoRead(this) ? "WŁĄCZONE" : "wyłączone");
    }

    private String settingsText() {
        return (Popup.canDraw(this) ? "✅ Okienka nad innymi aplikacjami: włączone"
                : "❌ Okienka nad innymi aplikacjami: WYŁĄCZONE – kliknij niebieski przycisk")
                + "\n" + RadioService.btInfo
                + "\n" + RadioService.wifiInfo
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
        String msg = (name == null || name.isEmpty()) ? num : name + "\n" + num;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Zadzwonić?")
                .setMessage(msg)
                .setPositiveButton("📞 Zadzwoń", (d, w) -> dial(num, name))
                .setNegativeButton("Anuluj", null)
                .show();
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
