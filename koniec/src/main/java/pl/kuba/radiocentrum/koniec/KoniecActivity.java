package pl.kuba.radiocentrum.koniec;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * „Koniec jazdy”: otwiera się na chwilę po zgaszeniu auta.
 * Procedura Samsunga „Jeśli otwarto Koniec jazdy → Zamknij aplikację Yanosik” zamyka wtedy Yanosika.
 */
public class KoniecActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        TextView t = new TextView(this);
        t.setText("🏁 Koniec jazdy\n\nZamykam Yanosika…");
        t.setTextSize(28);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(0xFFC62828);
        setContentView(t);
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 3000);
    }
}
