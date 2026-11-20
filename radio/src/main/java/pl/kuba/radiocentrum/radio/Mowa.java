package pl.kuba.radiocentrum.radio;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/** Czytanie na głos. Najpierw głosem radia (gra przez głośniki auta), a gdy radio nie ma polskiego głosu – czyta telefon. */
final class Mowa {
    private Mowa() {
    }

    private static TextToSpeech tts;
    private static boolean ready;
    private static boolean polish;

    static void init(Context c) {
        if (tts != null) return;
        try {
            tts = new TextToSpeech(c.getApplicationContext(), st -> {
                if (st == TextToSpeech.SUCCESS && tts != null) {
                    int r = tts.setLanguage(new Locale("pl", "PL"));
                    polish = r >= TextToSpeech.LANG_AVAILABLE;
                    ready = true;
                }
            });
        } catch (Exception ignored) {
        }
    }

    static boolean radioCanSpeak() {
        return ready && polish && tts != null;
    }

    static String info() {
        if (radioCanSpeak()) return "🔊 Czytanie: głosem radia (polski) ✅";
        if (ready) return "🔊 Czytanie: radio nie ma polskiego głosu – czyta telefon przez kanał rozmowy";
        return "🔊 Czytanie: radio nie ma syntezatora mowy – czyta telefon przez kanał rozmowy";
    }

    /** @return false gdy nie było jak przeczytać (brak głosu w radiu i brak połączenia z telefonem). */
    static boolean speak(String text) {
        if (text == null || text.isEmpty()) return true;
        if (radioCanSpeak()) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rc");
            return true;
        }
        return RadioService.sendToPhone(J.obj("t", "speak", "text", text));
    }
}
