package pl.kuba.radiocentrum.radio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Uruchamia usługę po starcie radia i gdy coś połączy się przez Bluetooth. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        RadioService.start(c);
    }
}
