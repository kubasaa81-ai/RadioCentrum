package pl.kuba.radiocentrum.telefon;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Słucha mowy na telefonie (na zlecenie radia) – przez mikrofon w aucie, jeśli się da.
 * Słucha ciągle: po każdej pauzie dopisuje fragment i słucha dalej.
 * Kończy, gdy zamilkniesz na ok. 3 s, po 2 minutach albo po kliknięciu „Gotowe”.
 */
public class DictateActivity extends Activity {

    private static final long MAX_MS = 120000;

    private SpeechRecognizer sr;
    private TextView live;
    private boolean done;
    private boolean carMic;
    private long startedAt;
    private final StringBuilder acc = new StringBuilder();
    private final Handler h = new Handler(Looper.getMainLooper());

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
        t.setText("🎤 Słucham…");
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        card.addView(t);
        live = new TextView(this);
        live.setText("Radio Centrum – treść trafi na ekran radia");
        live.setTextColor(0xFFB0BEC5);
        live.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        live.setGravity(Gravity.CENTER);
        live.setPadding(0, dp(12), 0, dp(12));
        card.addView(live);
        Button ok = new Button(this);
        ok.setText("✅ Gotowe");
        ok.setAllCaps(false);
        ok.setTextColor(0xFFFFFFFF);
        ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(0xFF43A047);
        gb.setCornerRadius(dp(14));
        ok.setBackground(gb);
        ok.setOnClickListener(v -> finishWith(acc.toString(), null));
        card.addView(ok, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));
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
        startedAt = SystemClock.elapsedRealtime();
        // mikrofon w aucie: otwórz kanał rozmowy Bluetooth (jak przy telefonowaniu)
        carMic = CarAudio.route(this);
        live.setText(carMic ? "Mów do mikrofonu w aucie" : "Mów do telefonu (brak zestawu w aucie)");
        h.postDelayed(this::listen, carMic ? 1600 : 0);
    }

    /** Jeden fragment słuchania. Po nim – kolejny, aż do ciszy. */
    private void listen() {
        if (done) return;
        if (SystemClock.elapsedRealtime() - startedAt > MAX_MS) {
            finishWith(acc.toString(), null);
            return;
        }
        destroyRecognizer();
        try {
            sr = SpeechRecognizer.createSpeechRecognizer(this);
        } catch (Exception e) {
            finishWith(acc.toString(), acc.length() == 0 ? "Nie udało się włączyć rozpoznawania mowy" : null);
            return;
        }
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
            }

            @Override
            public void onError(int error) {
                if (done) return;
                boolean silence = error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                if (acc.length() > 0 && silence) {
                    // po tym, co już powiedziałeś, zapadła cisza – koniec
                    finishWith(acc.toString(), null);
                } else if (silence) {
                    finishWith(null, "Nic nie usłyszałem – kliknij 🎤 i spróbuj jeszcze raz");
                } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                    h.postDelayed(DictateActivity.this::listen, 300);
                } else {
                    finishWith(acc.toString(), acc.length() == 0 ? "Błąd rozpoznawania mowy (" + error + ")" : null);
                }
            }

            @Override
            public void onResults(Bundle results) {
                if (done) return;
                ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty() && !r.get(0).trim().isEmpty()) {
                    if (acc.length() > 0) acc.append(' ');
                    acc.append(r.get(0).trim());
                    show(acc.toString());
                }
                // słuchaj dalej – następny fragment
                h.postDelayed(DictateActivity.this::listen, 150);
            }

            @Override
            public void onPartialResults(Bundle partial) {
                ArrayList<String> r = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty() && !r.get(0).isEmpty()) {
                    show(acc.length() == 0 ? r.get(0) : acc + " " + r.get(0));
                }
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
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10000L);
        try {
            sr.startListening(i);
        } catch (Exception e) {
            finishWith(acc.toString(), acc.length() == 0 ? "Nie udało się włączyć mikrofonu w telefonie" : null);
        }
    }

    /** Pokaż tekst na telefonie i od razu na radiu. */
    private void show(String text) {
        live.setText(text);
        PhoneService s = PhoneService.instance;
        if (s != null) s.sendLiveDictation(text);
    }

    private void destroyRecognizer() {
        if (sr != null) {
            try {
                sr.destroy();
            } catch (Exception ignored) {
            }
            sr = null;
        }
    }

    private void finishWith(String text, String err) {
        if (done) return;
        done = true;
        h.removeCallbacksAndMessages(null);
        destroyRecognizer();
        if (carMic) {
            carMic = false;
            CarAudio.release(this);
        }
        if (text != null && text.trim().isEmpty()) text = null;
        if (text == null && err == null) err = "Nic nie usłyszałem";
        PhoneService s = PhoneService.instance;
        if (s != null) s.deliverDictation(text, err);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (!done) finishWith(acc.toString(), acc.length() == 0 ? "Przerwano dyktowanie" : null);
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
