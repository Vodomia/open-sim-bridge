package dev.opensimbridge.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class MediaCommandReceiver extends BroadcastReceiver {
    private static final String TAG = "OSB-CONTROL";
    public static final String ACTION_END_CELLULAR_CALL =
            "dev.opensimbridge.action.END_CELLULAR_CALL";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_END_CELLULAR_CALL.equals(intent.getAction())) {
            return;
        }

        String result = TelephonyController.end(context);
        Log.i(TAG, "media requested cellular hangup: " + result);
    }
}
