package pl.kuba.radiocentrum.telefon;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/** Słucha mowy na telefonie (na zlecenie radia) i odsyła tekst do radia. */
public class DictateActivity extends Activity {

    private SpeechRecognizer sr;
    private TextView live;
    private boolean done;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xB0000000);
        root.setOnClickListener(v -> finishWith(null, "Przerwano dyktowanie"));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dp(22);
        card.setPadding(p, p, p, p);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1B2430);
        g.setCornerRadius(dp(26));
        g.setStroke(dp(3), 0xFF8E24AA);
        card.setBackground(g);
        TextView t = new TextView(this);
        t.setText("🎤 Mów teraz…");
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        card.addView(t);
        live = new TextView(this);
        live.setText("Radio Centrum słucha – treść trafi na ekran radia");
        live.setTextColor(0xFFB0BEC5);
        live.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        live.setGravity(Gravity.CENTER);
        live.setPadding(0, dp(12), 0, 0);
        card.addView(live);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        lp.setMargins(dp(20), 0, dp(20), 0);
        root.addView(card, lp);
        setContentView(root);

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finishWith(null, "Telefon nie ma uprawnienia do mikrofonu – w aplikacji na telefonie kliknij krok 1");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            finishWith(null, "Telefon nie ma usługi rozpoznawania mowy");
            return;
        }
        sr = SpeechRecognizer.createSpeechRecognizer(this);
        sr.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
            }

            @Override
            public void onBeginningOfSpeech() {
            }

            @Override
            public void onRmsChanged(float rms) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
                live.setText("Rozpoznaję…");
            }

            @Override
            public void onError(int error) {
                boolean silence = error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                finishWith(null, silence ? "Nic nie usłyszałem – kliknij 🎤 i spróbuj jeszcze raz"
                        : "Błąd rozpoznawania mowy (" + error + ")");
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r == null || r.isEmpty()) finishWith(null, "Nic nie usłyszałem");
                else finishWith(r.get(0), null);
            }

            @Override
            public void onPartialResults(Bundle partial) {
                ArrayList<String> r = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty() && !r.get(0).isEmpty()) live.setText(r.get(0));
            }

            @Override
            public void onEvent(int type, Bundle params) {
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        try {
            sr.startListening(i);
        } catch (Exception e) {
            finishWith(null, "Nie udało się włączyć mikrofonu w telefonie");
        }
    }

    private void finishWith(String text, String err) {
        if (done) return;
        done = true;
        PhoneService s = PhoneService.instance;
        if (s != null) s.deliverDictation(text, err);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (sr != null) {
            try {
                sr.destroy();
            } catch (Exception ignored) {
            }
        }
        if (!done) {
            done = true;
            PhoneService s = PhoneService.instance;
            if (s != null) s.deliverDictation(null, "Przerwano dyktowanie");
        }
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
