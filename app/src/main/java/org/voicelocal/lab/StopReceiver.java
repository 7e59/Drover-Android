package org.voicelocal.lab;
import android.content.*;
public final class StopReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) { VoiceVpnService.requestStop(context); }
}
