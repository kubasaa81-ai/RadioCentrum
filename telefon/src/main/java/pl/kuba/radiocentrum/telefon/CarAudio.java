package pl.kuba.radiocentrum.telefon;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

/** Przełączanie dźwięku na zestaw w aucie (kanał rozmowy Bluetooth): mikrofon i głośniki auta. */
final class CarAudio {
    private CarAudio() {
    }

    /** @return true gdy udało się przełączyć na zestaw w aucie. */
    @SuppressWarnings("deprecation")
    static boolean route(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        boolean routed = false;
        try {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            if (Build.VERSION.SDK_INT >= 31) {
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        routed = am.setCommunicationDevice(d);
                        break;
                    }
                }
            } else if (am.isBluetoothScoAvailableOffCall()) {
                am.startBluetoothSco();
                am.setBluetoothScoOn(true);
                routed = true;
            }
            if (!routed) am.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception ignored) {
        }
        return routed;
    }

    @SuppressWarnings("deprecation")
    static void release(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice();
            } else {
                am.setBluetoothScoOn(false);
                am.stopBluetoothSco();
            }
            am.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception ignored) {
        }
    }
}
