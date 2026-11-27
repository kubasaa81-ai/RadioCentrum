package pl.kuba.radiocentrum.telefon;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private TextView status, checks, codeView, dlText, ulText;
    private android.widget.ProgressBar dlBar, ulBar;
    private LinearLayout dlBox, ulBox;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            h.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF101418);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        col.setPadding(p, dp(28), p, p);
        sv.addView(col);

        col.addView(text("Radio Centrum – telefon", 24, 0xFFFFFFFF, true));
        col.addView(text("Działa w tle i wysyła do radia powiadomienia, połączenia i kontakty. "
                + "Kroki 1–5 zrób tylko raz.", 15, 0xFFB0BEC5, false), lp(8));

        status = text("", 18, 0xFFFFFFFF, true);
        status.setPadding(p, p, p, p);
        status.setBackground(round(0xFF1E2A33, 16));
        col.addView(status, lp(14));

        checks = text("", 17, 0xFFE0E0E0, false);
        checks.setLineSpacing(0, 1.25f);
        col.addView(checks, lp(12));

        col.addView(button("🚗 Moje auto (paliwo, parking, terminy)", 0xFF2E7D32,
                v -> startActivity(new Intent(this, MojeAuto.class))), lp(14));
        col.addView(button("1. Nadaj uprawnienia", 0xFF1E88E5, v -> askPermissions()), lp(10));
        col.addView(button("2. Dostęp do powiadomień", 0xFF8E24AA, v -> openNotifAccess()), lp(10));
        col.addView(button("3. Bateria bez ograniczeń", 0xFFFB8C00, v -> askBattery()), lp(10));
        col.addView(button("4. Wybierz radio (Bluetooth)", 0xFF43A047, v -> chooseRadio()), lp(10));
        col.addView(button("5. Wyświetlanie nad aplikacjami (dyktowanie)", 0xFFD81B60, v -> openOverlay()), lp(10));
        col.addView(button("Ustawienia Bluetooth (parowanie z radiem)", 0xFF00897B,
                v -> safeStart(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))), lp(10));
        col.addView(button("Wyślij ponownie wszystko do radia", 0xFF3949AB, v -> resend()), lp(10));
        col.addView(button("📥 Pobierz pliki z radia (Pobrane)", 0xFF6D4C41, v -> radioFiles()), lp(10));
        dlBox = new LinearLayout(this);
        dlBox.setOrientation(LinearLayout.VERTICAL);
        dlBox.setPadding(p, dp(10), p, dp(12));
        dlBox.setBackground(round(0xFF1E2A33, 14));
        dlText = text("", 16, 0xFFFFFFFF, true);
        dlBox.addView(dlText);
        dlBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        dlBar.setMax(100);
        dlBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF66BB6A));
        dlBox.addView(dlBar, lp(6));
        dlBox.setVisibility(View.GONE);
        col.addView(dlBox, lp(6));
        col.addView(button("📤 Wyślij plik do radia (Pobrane)", 0xFF00838F, v -> pickFileForRadio()), lp(10));
        ulBox = new LinearLayout(this);
        ulBox.setOrientation(LinearLayout.VERTICAL);
        ulBox.setPadding(dp(16), dp(10), dp(16), dp(12));
        ulBox.setBackground(round(0xFF1E2A33, 14));
        ulText = text("", 16, 0xFFFFFFFF, true);
        ulBox.addView(ulText);
        ulBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        ulBar.setMax(100);
        ulBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF4FC3F7));
        ulBox.addView(ulBar, lp(6));
        ulBox.setVisibility(View.GONE);
        col.addView(ulBox, lp(6));
        col.addView(button("Ustawienia aplikacji (ograniczone ustawienia)", 0xFF546E7A, v -> openAppDetails()), lp(10));
        yanButton = button("", 0xFFC62828, v -> {
            Prefs.setYanAuto(this, !Prefs.yanAuto(this));
            updateYanButton();
        });
        col.addView(yanButton, lp(10));
        updateYanButton();
        col.addView(button("👁 Podgląd powiadomień Yanosika", 0xFF5E35B1, v -> yanLog()), lp(10));
        col.addView(button("🏁 Test: „Koniec jazdy” (procedura Samsunga)", 0xFFC62828,
                v -> toast(Yanosik.koniec(this))), lp(10));
        col.addView(button("🛑 Test: wyłącz Yanosika teraz", 0xFF6A1B9A, v -> {
            PhoneService ps = PhoneService.instance;
            if (ps == null) toast("Usługa nie działa – zrób krok 2");
            else toast(ps.stopYanosikNow());
        }), lp(10));

        codeView = text("", 16, 0xFFFFD54F, false);
        codeView.setPadding(p, p, p, p);
        codeView.setBackground(round(0xFF1E2A33, 16));
        col.addView(codeView, lp(16));

        setContentView(sv);
        handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleShare(i);
    }

    /** „Udostępnij → Radio Centrum” z galerii, Moich plików itd. */
    @SuppressWarnings("deprecation")
    private void handleShare(Intent i) {
        if (i == null) return;
        PhoneService s = PhoneService.instance;
        java.util.List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) {
            ArrayList<Uri> list = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        if (uris.isEmpty()) return;
        i.setAction(Intent.ACTION_MAIN);
        if (s == null || !s.isConnected()) {
            toast("Brak połączenia z radiem – nie wysłano");
            return;
        }
        for (Uri u : uris) s.sendFileToRadio(u);
    }

    private void pickFileForRadio() {
        PhoneService s = PhoneService.instance;
        if (s == null || !s.isConnected()) {
            toast("Brak połączenia z radiem");
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, 77);
        } catch (Exception e) {
            toast("Nie udało się otworzyć wyboru plików");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 77 || res != RESULT_OK || data == null) return;
        PhoneService s = PhoneService.instance;
        if (s == null) return;
        if (data.getClipData() != null) {
            for (int k = 0; k < data.getClipData().getItemCount(); k++) {
                s.sendFileToRadio(data.getClipData().getItemAt(k).getUri());
            }
        } else if (data.getData() != null) {
            s.sendFileToRadio(data.getData());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (notifAccess() && PhoneService.instance == null) {
            try {
                NotificationListenerService.requestRebind(new ComponentName(this, PhoneService.class));
            } catch (Exception ignored) {
            }
        }
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    // ------------------------------------------------------------------ stan

    private void refresh() {
        int st0 = PhoneService.dlState;
        if (dlBox != null) {
            dlBox.setVisibility(st0 == 0 ? View.GONE : View.VISIBLE);
            if (st0 != 0) {
                long size = PhoneService.dlSize, got = PhoneService.dlGot;
                int pct = size > 0 ? (int) Math.min(100, got * 100 / size) : 0;
                dlBar.setIndeterminate(st0 == 1 && size <= 0);
                dlBar.setProgress(st0 == 2 ? 100 : pct);
                String sz = String.format(Locale.US, "%.1f / %.1f MB", got / 1048576.0, size / 1048576.0);
                dlText.setText(st0 == 1 ? "📥 Pobieram " + pct + "%  ·  " + sz + "\n" + PhoneService.dlName
                        : st0 == 2 ? "✅ Pobrano: " + PhoneService.dlName + " (w Pobranych)"
                        : "❌ Nie udało się: " + PhoneService.dlName + " (" + sz + ") – spróbuj ponownie");
                dlText.setTextColor(st0 == 3 ? 0xFFEF5350 : st0 == 2 ? 0xFF81C784 : 0xFFFFFFFF);
            }
        }
        int us = PhoneService.ulState;
        if (ulBox != null) {
            ulBox.setVisibility(us == 0 ? View.GONE : View.VISIBLE);
            if (us != 0) {
                long size = PhoneService.ulSize, sent = PhoneService.ulSent;
                int pct = size > 0 ? (int) Math.min(100, sent * 100 / size) : 0;
                ulBar.setProgress(us == 2 ? 100 : pct);
                String sz = String.format(Locale.US, "%.1f / %.1f MB", sent / 1048576.0, size / 1048576.0);
                ulText.setText(us == 1 ? "📤 Wysyłam do radia " + pct + "%  ·  " + sz + "\n" + PhoneService.ulName
                        : us == 4 ? "📤 Wysłano – czekam na potwierdzenie radia…\n" + PhoneService.ulName
                        : us == 2 ? "✅ Plik jest w radiu (Pobrane): " + PhoneService.ulName
                        : "❌ Nie udało się wysłać: " + PhoneService.ulName + " – spróbuj ponownie");
                ulText.setTextColor(us == 3 ? 0xFFEF5350 : us == 2 ? 0xFF81C784 : 0xFFFFFFFF);
            }
        }
        PhoneService s = PhoneService.instance;
        boolean conn = s != null && s.isConnected();
        String st = s == null ? "Usługa nie działa – zrób krok 2" : PhoneService.status;
        status.setText((conn ? "🟢 " : "🟠 ") + st);
        String name = Prefs.radioName(this);
        String mac = Prefs.radioMac(this);
        String radio = mac == null ? "nie wybrano" : ((name == null || name.isEmpty()) ? mac : name + " (" + mac + ")");
        checks.setText(mark(allPerms()) + "  Uprawnienia\n"
                + mark(notifAccess()) + "  Dostęp do powiadomień\n"
                + mark(battery()) + "  Bateria bez ograniczeń\n"
                + mark(mac != null) + "  Radio: " + radio + "\n"
                + mark(Settings.canDrawOverlays(this)) + "  Wyświetlanie nad aplikacjami (dyktowanie)");
        codeView.setText("Połączenie przez Wi-Fi (router w aucie albo hotspot telefonu)\nKod: " + Prefs.code(this)
                + "\nWpisz go raz w aplikacji na radiu → ⚙ Ustawienia → Zapisz kod.");
    }

    private static String mark(boolean ok) {
        return ok ? "✅" : "❌";
    }

    private String[] neededPerms() {
        List<String> l = new ArrayList<>(Arrays.asList(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ANSWER_PHONE_CALLS));
        if (Build.VERSION.SDK_INT >= 31) l.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS);
        return l.toArray(new String[0]);
    }

    private boolean allPerms() {
        for (String p : neededPerms()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private boolean notifAccess() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getPackageName());
    }

    private boolean battery() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    // ------------------------------------------------------------------ akcje

    private void askPermissions() {
        if (allPerms()) {
            toast("Uprawnienia już są ✅");
            return;
        }
        requestPermissions(neededPerms(), 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (!allPerms()) {
            toast("Brakuje uprawnień – włącz je w Uprawnieniach aplikacji");
            openAppDetails();
        } else {
            PhoneService s = PhoneService.instance;
            if (s != null) s.resendAll();
        }
    }

    private void openNotifAccess() {
        toast("Włącz „Radio Centrum”. Jeśli jest zablokowane – patrz przycisk „Ustawienia aplikacji”.");
        safeStart(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    @SuppressLint("BatteryLife")
    private void askBattery() {
        if (battery()) {
            toast("Już ustawione ✅");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            safeStart(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void openOverlay() {
        if (Settings.canDrawOverlays(this)) {
            toast("Już ustawione ✅");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            openAppDetails();
        }
    }

    private void openAppDetails() {
        safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    @SuppressLint("MissingPermission")
    private void chooseRadio() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            toast("Najpierw krok 1 – uprawnienia");
            askPermissions();
            return;
        }
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) {
            toast("Włącz Bluetooth w telefonie");
            return;
        }
        final List<BluetoothDevice> devs = new ArrayList<>(ad.getBondedDevices());
        String[] items = new String[devs.size() + 1];
        for (int i = 0; i < devs.size(); i++) {
            String n = devs.get(i).getName();
            items[i] = (n == null || n.isEmpty() ? "(bez nazwy)" : n) + "\n" + devs.get(i).getAddress();
        }
        items[devs.size()] = "✏️ Wpisz adres radia ręcznie…";
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Wybierz Bluetooth radia")
                .setItems(items, (dlg, w) -> {
                    if (w == devs.size()) {
                        manualMac();
                    } else {
                        BluetoothDevice d = devs.get(w);
                        Prefs.setRadio(this, d.getAddress(), d.getName());
                        reconnect();
                    }
                })
                .setNegativeButton("Anuluj", null)
                .show();
    }

    private void manualMac() {
        final EditText e = new EditText(this);
        e.setHint("np. 0C:89:10:E3:74:D7");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        String cur = Prefs.radioMac(this);
        if (cur != null) e.setText(cur);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Adres Bluetooth radia")
                .setView(box)
                .setPositiveButton("Zapisz", (d, w) -> {
                    String mac = e.getText().toString().trim().toUpperCase(Locale.US).replace('-', ':');
                    if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
                        toast("Zły adres – wpisz w formacie 0C:89:10:E3:74:D7");
                        return;
                    }
                    Prefs.setRadio(this, mac, "Radio");
                    reconnect();
                })
                .setNegativeButton("Anuluj", null)
                .show();
    }

    private void reconnect() {
        PhoneService s = PhoneService.instance;
        if (s != null) s.reconnect();
        toast("Zapisano – łączę z radiem…");
    }

    private void resend() {
        PhoneService s = PhoneService.instance;
        if (s != null && s.isConnected()) {
            s.resendAll();
            toast("Wysłano ✅");
        } else {
            toast("Brak połączenia z radiem");
        }
    }

    /** Lista plików z Pobranych w radiu → wybór → zapis w Pobranych telefonu. */
    private void radioFiles() {
        final PhoneService s = PhoneService.instance;
        if (s == null || !s.isConnected()) {
            toast("Brak połączenia z radiem");
            return;
        }
        PhoneService.filesCallback = (list, err) -> {
            PhoneService.filesCallback = null;
            if (err != null && !err.isEmpty()) {
                toast(err);
                return;
            }
            if (list.length() == 0) {
                toast("W Pobranych w radiu nie ma plików");
                return;
            }
            final String[] names = new String[list.length()];
            String[] labels = new String[list.length()];
            for (int i = 0; i < list.length(); i++) {
                org.json.JSONObject o = list.optJSONObject(i);
                names[i] = o == null ? "" : o.optString("n");
                long sz = o == null ? 0 : o.optLong("s");
                String size = sz >= 1024 * 1024 ? String.format(Locale.US, "%.1f MB", sz / 1048576.0)
                        : String.format(Locale.US, "%d kB", Math.max(1, sz / 1024));
                labels[i] = names[i] + "\n" + size;
            }
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Pliki w radiu (Pobrane)")
                    .setItems(labels, (d, w) -> {
                        PhoneService ps = PhoneService.instance;
                        if (ps != null) {
                            ps.requestFile(names[w]);
                            toast("Pobieram: " + names[w] + " – chwilę to potrwa");
                        }
                    })
                    .setNegativeButton("Zamknij", null)
                    .show();
        };
        s.requestFileList();
        toast("Pytam radio o pliki…");
    }

    private Button yanButton;

    private void updateYanButton() {
        String y = Yanosik.pkg(this);
        yanButton.setText(y == null ? "🚓 Yanosik: nie znalazłem go w telefonie"
                : Prefs.yanAuto(this) ? "🚓 Yanosik z radiem: WŁĄCZONE ✔\n(włącza się po połączeniu, wyłącza po rozłączeniu)"
                : "🚓 Yanosik z radiem: WYŁĄCZONE ✖");
    }

    /** Co Yanosik wysyła w powiadomieniach – do dopracowania rozpoznawania ostrzeżeń. */
    private void yanLog() {
        TextView t = text(Yanosik.logText(), 14, 0xFFE0E0E0, false);
        t.setTextIsSelectable(true);
        int p = dp(14);
        t.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Yanosik – ostatnie powiadomienia")
                .setView(sv)
                .setPositiveButton("Zamknij", null)
                .setNeutralButton("Kopiuj", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Yanosik", Yanosik.logText()));
                    toast("Skopiowano – możesz wkleić w czacie");
                })
                .show();
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

    private static int darker(int c) {
        int a = (c >>> 24) & 0xFF;
        int r = (int) (((c >> 16) & 0xFF) * 0.7f);
        int g = (int) (((c >> 8) & 0xFF) * 0.7f);
        int b = (int) ((c & 0xFF) * 0.7f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, round(darker(color), 14));
        sl.addState(new int[]{}, round(color, 14));
        b.setBackground(sl);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(56));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }
}
