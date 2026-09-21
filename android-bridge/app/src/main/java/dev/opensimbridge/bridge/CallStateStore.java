package dev.opensimbridge.bridge;

import org.json.JSONException;
import org.json.JSONObject;

final class CallStateStore {
    private static volatile String state = "UNKNOWN";
    private static volatile String number = "";
    private static volatile long changedAtMs = System.currentTimeMillis();

    private CallStateStore() {}

    static void update(int phoneState, String incomingNumber) {
        switch (phoneState) {
            case android.telephony.TelephonyManager.CALL_STATE_RINGING:
                state = "RINGING";
                if (incomingNumber != null) number = incomingNumber;
                break;
            case android.telephony.TelephonyManager.CALL_STATE_OFFHOOK:
                state = "OFFHOOK";
                if (incomingNumber != null && !incomingNumber.isEmpty()) number = incomingNumber;
                break;
            case android.telephony.TelephonyManager.CALL_STATE_IDLE:
                state = "IDLE";
                number = "";
                break;
            default:
                state = "UNKNOWN";
        }
        changedAtMs = System.currentTimeMillis();
    }

    static String asJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("state", state);
            obj.put("number", number);
            obj.put("changed_at_ms", changedAtMs);
            return obj.toString();
        } catch (JSONException e) {
            return "{\"state\":\"UNKNOWN\",\"number\":\"\"}";
        }
    }
}
