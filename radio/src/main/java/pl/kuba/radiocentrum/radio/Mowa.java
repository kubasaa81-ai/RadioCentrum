package pl.kuba.radiocentrum.radio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * Czytanie na głos. Tryby (Ustawienia → „Kto czyta”):
 * 0 = automatycznie, 1 = radio, 2 = telefon przez kanał rozmowy, 3 = telefon jako muzyka Bluetooth.
 */
final class Mowa {
    private Mowa() {
    }

    static final String[] MODES = {
            "Automatycznie",
            "Radio (głos radia, jak nawigacja)",
            "Telefon – kanał rozmowy",
            "Telefon – muzyka Bluetooth"};

    private static TextToSpeech tts;
    private static boolean ready;
    private static boolean polish;
    private static String engine = "";
    private static AudioManager am;
    private static final AudioManager.OnAudioFocusChangeListener focus = f -> {
    };

    static void init(Context c) {
        if (tts != null) return;
        final Context app = c.getApplicationContext();
        am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        try {
            tts = new TextToSpeech(app, st -> {
                if (st == TextToSpeech.SUCCESS && tts != null) {
                    int r = tts.setLanguage(new Locale("pl", "PL"));
                    polish = r >= TextToSpeech.LANG_AVAILABLE;
                    try {
                        engine = tts.getDefaultEngine();
                    } catch (Exception ignored) {
                    }
                    // jak komunikaty nawigacji – radia z Androidem miksują je z każdym źródłem
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String id) {
                        }

                        @Override
                        @SuppressWarnings("deprecation")
                        public void onDone(String id) {
                            if (am != null) am.abandonAudioFocus(focus);
                        }

                        @Override
                        @SuppressWarnings("deprecation")
                        public void onError(String id) {
                            if (am != null) am.abandonAudioFocus(focus);
                        }
                    });
                    ready = true;
                }
            });
        } catch (Exception ignored) {
        }
    }

    static boolean radioCanSpeak() {
        return ready && polish && tts != null;
    }

    static String info(Context c) {
        String radio = radioCanSpeak() ? "radio ma polski głos ✅ (" + engine + ")"
                : ready ? "radio NIE ma polskiego głosu (" + engine + ")"
                : "radio nie ma syntezatora mowy";
        return "🔊 Kto czyta: " + MODES[Prefs.voiceMode(c)] + " – " + radio;
    }

    /** @return false gdy nie było jak przeczytać. */
    @SuppressWarnings("deprecation")
    static boolean speak(Context c, String text) {
        if (text == null || text.isEmpty()) return true;
        int mode = Prefs.voiceMode(c);
        boolean useRadio = mode == 1 || (mode == 0 && radioCanSpeak());
        if (useRadio) {
            if (!ready || tts == null) return false;
            if (am != null) {
                am.requestAudioFocus(focus, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rc");
            return true;
        }
        String route = mode == 3 ? "media" : "call";
        return RadioService.sendToPhone(J.obj("t", "speak", "text", text, "route", route));
    }
}
