package dev.opensimbridge.media;

import android.util.Log;

import java.lang.reflect.Method;

final class VendorAudioControl {
    private static final String TAG = "OSB-M5";

    private VendorAudioControl() {}

    static boolean setCallForwarding(boolean enabled) {
        // Samsung universal7870 HAL compares this parameter with the literal
        // values "on"/"off".
        String value = enabled ? "on" : "off";
        try {
            Class<?> audioSystem = Class.forName("android.media.AudioSystem");
            Method setParameters = audioSystem.getDeclaredMethod("setParameters", String.class);
            setParameters.setAccessible(true);
            Object rawRc = setParameters.invoke(null, "call_forwarding=" + value);
            int rc = rawRc instanceof Number ? ((Number) rawRc).intValue() : -1;

            String current = "";
            try {
                Method getParameters = audioSystem.getDeclaredMethod("getParameters", String.class);
                getParameters.setAccessible(true);
                Object got = getParameters.invoke(null, "call_forwarding");
                current = String.valueOf(got);
            } catch (Throwable ignored) {
            }

            boolean ok = rc == 0;
            Log.i(TAG, "HAL call_forwarding=" + value + " rc=" + rc
                    + " ok=" + ok + " get=" + current);
            MediaBridgeService.setStatus(
                    "HAL call_forwarding=" + value + " rc=" + rc + " ok=" + ok
                            + (current.isEmpty() ? "" : " get=" + current)
            );
            return ok;
        } catch (Throwable t) {
            Log.e(TAG, "HAL call_forwarding reflection failed", t);
            MediaBridgeService.setStatus("HAL call_forwarding failed: " + t.getClass().getSimpleName());
            return false;
        }
    }
}
