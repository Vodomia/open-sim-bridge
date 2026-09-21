package dev.opensimbridge.bridge;

import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

import org.json.JSONException;
import org.json.JSONObject;

public final class BridgeInCallService extends InCallService {
    private static volatile BridgeInCallService instance;

    private final Call.Callback callback = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            CallRegistry.syncState();
        }

        @Override
        public void onDetailsChanged(Call call, Call.Details details) {
            CallRegistry.syncState();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        CallRegistry.set(call);
        try {
            call.registerCallback(callback);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        try {
            call.unregisterCallback(callback);
        } catch (Exception ignored) {
        }
        CallRegistry.clear(call);
        super.onCallRemoved(call);
    }

    static boolean isActive() {
        return instance != null;
    }

    static boolean routeSpeaker(boolean speaker) {
        BridgeInCallService service = instance;
        if (service == null) return false;
        try {
            service.setAudioRoute(
                    speaker
                            ? CallAudioState.ROUTE_SPEAKER
                            : CallAudioState.ROUTE_EARPIECE
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String audioStateJson() {
        JSONObject obj = new JSONObject();
        try {
            BridgeInCallService service = instance;
            if (service == null) {
                obj.put("active", false);
                return obj.toString();
            }

            obj.put("active", true);
            CallAudioState state = service.getCallAudioState();
            if (state == null) {
                obj.put("state", "null");
                return obj.toString();
            }

            obj.put("muted", state.isMuted());
            obj.put("route", state.getRoute());
            obj.put("supported_routes", state.getSupportedRouteMask());
            return obj.toString();
        } catch (JSONException e) {
            return "{\"active\":false}";
        }
    }
}
