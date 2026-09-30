package dev.opensimbridge.media;

import android.util.Log;

import java.lang.reflect.Method;

final class VendorAudioControl {
    private static final String TAG = "OSB-M5";

    private VendorAudioControl() {}

    static boolean setCallForwarding(boolean enabled) {
        // Samsung universal7870 HAL compares this parameter to the literal strings
        // "on"/"off". Numeric 1/0 is accepted by AudioSystem.setParameters() but
        // does not switch the vendor call-forwarding route.
        String value = enabled ? "on" : "off";
        try {
            Class<?> audioSystem = Class.forName("android.media.AudioSystem");
            Method setParameters = audioSystem.getDeclaredMethod("setParameters", String.class);
            setParameters.setAccessible(true);
            Object rc = setParameters.invoke(null, "call_forwarding=" + value);

            String current = "";
            try {
                Method getParameters = audioSystem.getDeclaredMethod("getParameters", String.class);
                getParameters.setAccessible(true);
                Object got = getParameters.invoke(null, "call_forwarding");
                current = String.valueOf(got);
            } catch (Throwable ignored) {
            }

            Log.i(TAG, "HAL call_forwarding=" + value + " rc=" + rc + " get=" + current);
            MediaBridgeService.setStatus(
                    "HAL call_forwarding=" + value + " rc=" + rc
                            + (current.isEmpty() ? "" : " get=" + current)
            );
            // setParameters completed without throwing. Keep track of the requested
            // state so stopMedia() always sends the matching "off" request later.
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "HAL call_forwarding reflection failed", t);
            MediaBridgeService.setStatus("HAL call_forwarding failed: " + t.getClass().getSimpleName());
            return false;
        }
    }
}
