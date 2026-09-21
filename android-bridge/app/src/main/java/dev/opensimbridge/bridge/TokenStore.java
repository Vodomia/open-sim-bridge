package dev.opensimbridge.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;

final class TokenStore {
    private static final String PREFS = "open_sim_bridge";
    private static final String KEY = "local_access_token";

    private TokenStore() {}

    static String getOrCreate(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String current = prefs.getString(KEY, null);
        if (current != null && !current.isEmpty()) {
            return current;
        }

        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.encodeToString(
                bytes,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING
        );
        prefs.edit().putString(KEY, token).apply();
        return token;
    }

    static boolean matches(Context context, String candidate) {
        if (candidate == null) return false;

        byte[] expected = getOrCreate(context).getBytes(StandardCharsets.UTF_8);
        byte[] actual = candidate.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
