package dev.opensimbridge.bridge;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

final class CallMonitor {
    private final Context context;
    private TelephonyManager telephonyManager;
    private PhoneStateListener listener;

    CallMonitor(Context context) {
        this.context = context.getApplicationContext();
    }

    void start() {
        if (Build.VERSION.SDK_INT >= 23
                && context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (telephonyManager == null) return;

        listener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                CallStateStore.update(state, incomingNumber);
            }
        };

        try {
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
            CallStateStore.update(telephonyManager.getCallState(), "");
        } catch (SecurityException ignored) {
        }
    }

    void stop() {
        if (telephonyManager != null && listener != null) {
            try {
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE);
            } catch (Exception ignored) {
            }
        }
    }
}
