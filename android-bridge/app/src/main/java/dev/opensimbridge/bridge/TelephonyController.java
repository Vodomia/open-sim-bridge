package dev.opensimbridge.bridge;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telecom.Call;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.SmsManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

final class TelephonyController {
    private TelephonyController() {}

    static String sendSms(Context context, String to, String body) {
        if (!hasPermission(context, Manifest.permission.SEND_SMS)) {
            return result(false, "permission_denied", "SEND_SMS permission is not granted");
        }

        if (to == null || to.trim().isEmpty()) {
            return result(false, "invalid_number", "Destination number is empty");
        }
        if (body == null || body.isEmpty()) {
            return result(false, "empty_message", "Message body is empty");
        }
        if (body.length() > 4000) {
            return result(false, "message_too_long", "Prototype limit is 4000 characters");
        }

        try {
            SmsManager manager = SmsManager.getDefault();
            ArrayList<String> parts = manager.divideMessage(body);
            if (parts.size() > 1) {
                manager.sendMultipartTextMessage(to.trim(), null, parts, null, null);
            } else {
                manager.sendTextMessage(to.trim(), null, body, null, null);
            }
            return result(true, "queued", "SMS handed to Android telephony stack");
        } catch (Exception e) {
            return result(false, "send_failed", safeMessage(e));
        }
    }

    static String dial(Context context, String number) {
        if (!hasPermission(context, Manifest.permission.CALL_PHONE)) {
            return result(false, "permission_denied", "CALL_PHONE permission is not granted");
        }
        if (number == null || number.trim().isEmpty()) {
            return result(false, "invalid_number", "Number is empty");
        }

        try {
            Uri uri = Uri.fromParts("tel", number.trim(), null);
            TelecomManager telecom =
                    (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);

            if (telecom != null && Build.VERSION.SDK_INT >= 23) {
                telecom.placeCall(uri, new Bundle());
            } else {
                Intent intent = new Intent(Intent.ACTION_CALL, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
            return result(true, "dialing", "Outgoing call requested");
        } catch (Exception e) {
            return result(false, "dial_failed", safeMessage(e));
        }
    }

    static String answer(Context context) {
        Call call = CallRegistry.get();
        if (call != null && call.getState() == Call.STATE_RINGING) {
            try {
                call.answer(VideoProfile.STATE_AUDIO_ONLY);
                return result(true, "answered_via_incall",
                        "Answer requested through InCallService");
            } catch (Exception e) {
                return result(false, "answer_failed", safeMessage(e));
            }
        }

        if (Build.VERSION.SDK_INT < 26) {
            return result(false, "unsupported_android",
                    "No active InCallService call and public answer API needs API 26+");
        }
        if (!hasPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)) {
            return result(false, "permission_denied",
                    "ANSWER_PHONE_CALLS permission is not granted");
        }

        try {
            TelecomManager telecom =
                    (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            if (telecom == null) {
                return result(false, "telecom_unavailable", "TelecomManager is unavailable");
            }
            telecom.acceptRingingCall();
            return result(true, "answer_requested", "Answer call requested");
        } catch (Exception e) {
            return result(false, "answer_failed", safeMessage(e));
        }
    }

    static String end(Context context) {
        Call call = CallRegistry.get();
        if (call != null) {
            try {
                if (call.getState() == Call.STATE_RINGING) {
                    call.reject(false, null);
                    return result(true, "rejected_via_incall",
                            "Incoming call rejected through InCallService");
                }

                call.disconnect();
                return result(true, "disconnected_via_incall",
                        "Call disconnect requested through InCallService");
            } catch (Exception e) {
                return result(false, "incall_disconnect_failed", safeMessage(e));
            }
        }

        if (Build.VERSION.SDK_INT >= 28
                && hasPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)) {
            try {
                TelecomManager telecom =
                        (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
                if (telecom != null) {
                    boolean ended = telecom.endCall();
                    return result(ended, ended ? "ended" : "not_ended",
                            ended ? "End call requested" : "Android did not end a call");
                }
            } catch (Exception e) {
                return result(false, "end_failed", safeMessage(e));
            }
        }

        return result(false, "incall_service_not_active",
                "Set Open SIM Bridge as the default phone app, then retry");
    }

    static String capabilities(Context context) {
        JSONObject obj = new JSONObject();
        try {
            boolean defaultDialer = isDefaultDialer(context);
            obj.put("sdk_int", Build.VERSION.SDK_INT);
            obj.put("send_sms", hasPermission(context, Manifest.permission.SEND_SMS));
            obj.put("read_phone_state", hasPermission(context, Manifest.permission.READ_PHONE_STATE));
            obj.put("call_phone", hasPermission(context, Manifest.permission.CALL_PHONE));
            obj.put("answer_phone_calls",
                    Build.VERSION.SDK_INT >= 26
                            && hasPermission(context, Manifest.permission.ANSWER_PHONE_CALLS));
            obj.put("public_answer_api", Build.VERSION.SDK_INT >= 26);
            obj.put("public_end_api", Build.VERSION.SDK_INT >= 28);
            obj.put("default_dialer", defaultDialer);
            obj.put("incall_active", CallRegistry.get() != null);
            obj.put("incall_end_supported", defaultDialer && Build.VERSION.SDK_INT >= 23);
            return obj.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    static boolean isDefaultDialer(Context context) {
        if (Build.VERSION.SDK_INT < 23) return false;
        TelecomManager telecom =
                (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        return telecom != null
                && context.getPackageName().equals(telecom.getDefaultDialerPackage());
    }

    private static boolean hasPermission(Context context, String permission) {
        return Build.VERSION.SDK_INT < 23
                || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static String result(boolean ok, String code, String message) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("ok", ok);
            obj.put("code", code);
            obj.put("message", message == null ? "" : message);
            return obj.toString();
        } catch (JSONException e) {
            return "{\"ok\":" + ok + "}";
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }
}
