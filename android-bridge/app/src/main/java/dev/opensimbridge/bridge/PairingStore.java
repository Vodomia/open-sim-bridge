package dev.opensimbridge.bridge;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

final class PairingStore {
    private static final String PREFS = "open_sim_bridge";
    private static final String KEY_CODE = "pairing_code";
    private static final String KEY_EXPIRES = "pairing_code_expires";
    private static final String KEY_FAILS = "pairing_failed_attempts";
    private static final String KEY_LOCK_UNTIL = "pairing_lock_until";

    private static final long CODE_TTL_MS = 10L * 60L * 1000L;
    private static final long LOCK_MS = 30L * 1000L;
    private static final int MAX_FAILED_ATTEMPTS = 5;

    private PairingStore() {}

    static synchronized String currentCode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        String code = prefs.getString(KEY_CODE, null);
        long expires = prefs.getLong(KEY_EXPIRES, 0L);

        if (code == null || code.length() != 6 || expires <= now) {
            return regenerate(context);
        }
        return code;
    }

    static synchronized String regenerate(Context context) {
        int value = 100000 + new SecureRandom().nextInt(900000);
        String code = String.format(Locale.US, "%06d", value);

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CODE, code)
                .putLong(KEY_EXPIRES, System.currentTimeMillis() + CODE_TTL_MS)
                .putInt(KEY_FAILS, 0)
                .putLong(KEY_LOCK_UNTIL, 0L)
                .apply();

        return code;
    }

    static synchronized long expiresInMs(Context context) {
        currentCode(context);
        long expires = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_EXPIRES, 0L);
        return Math.max(0L, expires - System.currentTimeMillis());
    }

    static synchronized boolean isLocked(Context context) {
        long until = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LOCK_UNTIL, 0L);
        return until > System.currentTimeMillis();
    }

    static synchronized boolean verify(Context context, String candidate) {
        if (candidate == null || candidate.length() != 6 || isLocked(context)) {
            return false;
        }

        String expected = currentCode(context);
        boolean ok = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8)
        );

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        if (ok) {
            editor.putInt(KEY_FAILS, 0).putLong(KEY_LOCK_UNTIL, 0L).apply();
            return true;
        }

        int fails = prefs.getInt(KEY_FAILS, 0) + 1;
        if (fails >= MAX_FAILED_ATTEMPTS) {
            editor.putInt(KEY_FAILS, 0)
                    .putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + LOCK_MS)
                    .apply();
        } else {
            editor.putInt(KEY_FAILS, fails).apply();
        }

        return false;
    }
}
