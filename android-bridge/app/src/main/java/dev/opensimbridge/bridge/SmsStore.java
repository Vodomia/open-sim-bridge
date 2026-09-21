package dev.opensimbridge.bridge;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class SmsStore {
    private static final String PREFS = "open_sim_bridge";
    private static final String KEY_MESSAGES = "messages";
    private static final int MAX_MESSAGES = 50;

    private SmsStore() {}

    static synchronized void add(Context context, String sender, String body, long receivedAtMs) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray existing = readArray(prefs.getString(KEY_MESSAGES, "[]"));
        JSONArray next = new JSONArray();

        JSONObject item = new JSONObject();
        try {
            item.put("id", receivedAtMs + "-" + Math.abs((sender + body).hashCode()));
            item.put("sender", sender == null ? "" : sender);
            item.put("body", body == null ? "" : body);
            item.put("received_at_ms", receivedAtMs);
            next.put(item);

            for (int i = 0; i < existing.length() && next.length() < MAX_MESSAGES; i++) {
                next.put(existing.getJSONObject(i));
            }
        } catch (JSONException ignored) {
            return;
        }

        prefs.edit().putString(KEY_MESSAGES, next.toString()).apply();
    }

    static synchronized String asJson(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject response = new JSONObject();
        try {
            response.put("version", 1);
            response.put("messages", readArray(prefs.getString(KEY_MESSAGES, "[]")));
            return response.toString();
        } catch (JSONException e) {
            return "{\"version\":1,\"messages\":[]}";
        }
    }

    static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_MESSAGES)
                .apply();
    }

    private static JSONArray readArray(String raw) {
        try {
            return new JSONArray(raw == null ? "[]" : raw);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }
}
