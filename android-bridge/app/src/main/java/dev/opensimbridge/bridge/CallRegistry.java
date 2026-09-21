package dev.opensimbridge.bridge;

import android.net.Uri;
import android.telecom.Call;

final class CallRegistry {
    private static volatile Call currentCall;

    private CallRegistry() {}

    static synchronized void set(Call call) {
        currentCall = call;
        syncState();
    }

    static synchronized void clear(Call call) {
        if (currentCall == call) {
            currentCall = null;
            CallStateStore.update(android.telephony.TelephonyManager.CALL_STATE_IDLE, "");
        }
    }

    static synchronized Call get() {
        return currentCall;
    }

    static synchronized void syncState() {
        Call call = currentCall;
        if (call == null) {
            CallStateStore.update(android.telephony.TelephonyManager.CALL_STATE_IDLE, "");
            return;
        }

        int mapped;
        switch (call.getState()) {
            case Call.STATE_RINGING:
                mapped = android.telephony.TelephonyManager.CALL_STATE_RINGING;
                break;
            case Call.STATE_ACTIVE:
            case Call.STATE_DIALING:
            case Call.STATE_CONNECTING:
            case Call.STATE_HOLDING:
                mapped = android.telephony.TelephonyManager.CALL_STATE_OFFHOOK;
                break;
            case Call.STATE_DISCONNECTED:
                mapped = android.telephony.TelephonyManager.CALL_STATE_IDLE;
                break;
            default:
                mapped = android.telephony.TelephonyManager.CALL_STATE_OFFHOOK;
                break;
        }

        String number = "";
        try {
            Uri handle = call.getDetails().getHandle();
            if (handle != null) number = handle.getSchemeSpecificPart();
        } catch (Exception ignored) {
        }

        CallStateStore.update(mapped, number);
    }
}
