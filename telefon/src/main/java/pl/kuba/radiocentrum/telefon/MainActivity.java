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

    private TextView status, checks, codeView;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            h.postDelayed(this, 1000);
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

        col.addView(button("1. Nadaj uprawnienia", 0xFF1E88E5, v -> askPermissions()), lp(14));
        col.addView(button("2. Dostęp do powiadomień", 0xFF8E24AA, v -> openNotifAccess()), lp(10));
        col.addView(button("3. Bateria bez ograniczeń", 0xFFFB8C00, v -> askBattery()), lp(10));
        col.addView(button("4. Wybierz radio (Bluetooth)", 0xFF43A047, v -> chooseRadio()), lp(10));
        col.addView(button("5. Wyświetlanie nad aplikacjami (dyktowanie)", 0xFFD81B60, v -> openOverlay()), lp(10));
        col.addView(button("Ustawienia Bluetooth (parowanie z radiem)", 0xFF00897B,
                v -> safeStart(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))), lp(10));
        col.addView(button("Wyślij ponownie wszystko do radia", 0xFF3949AB, v -> resend()), lp(10));
        col.addView(button("Ustawienia aplikacji (ograniczone ustawienia)", 0xFF546E7A, v -> openAppDetails()), lp(10));

        codeView = text("", 16, 0xFFFFD54F, false);
        codeView.setPadding(p, p, p, p);
        codeView.setBackground(round(0xFF1E2A33, 16));
        col.addView(codeView, lp(16));

        setContentView(sv);
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
