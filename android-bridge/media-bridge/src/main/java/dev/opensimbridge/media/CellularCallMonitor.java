package dev.opensimbridge.media;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

final class CellularCallMonitor {
    interface Listener {
        void onRinging(String incomingNumber);
        void onOffhook();
        void onIdle();
    }

    private final Context context;
    private final Listener callback;
    private TelephonyManager telephonyManager;
    private PhoneStateListener listener;

    CellularCallMonitor(Context context, Listener callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
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
                if (state == TelephonyManager.CALL_STATE_RINGING) {
                    callback.onRinging(incomingNumber == null ? "" : incomingNumber);
                } else if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                    callback.onOffhook();
                } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                    callback.onIdle();
                }
            }
        };

        try {
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
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
