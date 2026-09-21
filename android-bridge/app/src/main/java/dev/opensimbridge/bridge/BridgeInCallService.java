package dev.opensimbridge.bridge;

import android.telecom.Call;
import android.telecom.InCallService;

public final class BridgeInCallService extends InCallService {
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
}
