package dev.opensimbridge.bridge;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class AudioProbe {
    private static final int DEFAULT_SAMPLE_RATE = 16000;
    private static final int[] SAMPLE_RATES = new int[]{16000, 8000, 44100, 48000};
    private static final String[] CAPTURE_SOURCES = new String[]{
            "mic",
            "voice_communication",
            "voice_recognition",
            "unprocessed",
            "voice_uplink",
            "voice_downlink",
            "voice_call"
    };

    private AudioProbe() {}

    static String status(Context context) {
        JSONObject obj = new JSONObject();
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            obj.put("sdk_int", Build.VERSION.SDK_INT);
            obj.put("manufacturer", Build.MANUFACTURER);
            obj.put("model", Build.MODEL);
            obj.put("device", Build.DEVICE);
            obj.put("record_audio",
                    Build.VERSION.SDK_INT < 23
                            || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED);

            obj.put("capture_audio_output",
                    Build.VERSION.SDK_INT < 23
                            || context.checkSelfPermission("android.permission.CAPTURE_AUDIO_OUTPUT")
                            == PackageManager.PERMISSION_GRANTED);
            obj.put("modify_audio_routing",
                    Build.VERSION.SDK_INT < 23
                            || context.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING")
                            == PackageManager.PERMISSION_GRANTED);

            if (am != null) {
                obj.put("mode", am.getMode());
                obj.put("mode_name", modeName(am.getMode()));
                obj.put("speakerphone", am.isSpeakerphoneOn());
                obj.put("microphone_mute", am.isMicrophoneMute());

                if (Build.VERSION.SDK_INT >= 23) {
                    JSONArray inputs = new JSONArray();
                    for (AudioDeviceInfo device : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                        inputs.put(deviceJson(device));
                    }
                    obj.put("inputs", inputs);

                    JSONArray outputs = new JSONArray();
                    for (AudioDeviceInfo device : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                        outputs.put(deviceJson(device));
                    }
                    obj.put("outputs", outputs);
                }
            }

            obj.put("incall_service_active", BridgeInCallService.isActive());
            obj.put("incall_audio_state", BridgeInCallService.audioStateJson());
            return obj.toString();
        } catch (JSONException e) {
            return "{\"ok\":false,\"error\":\"json\"}";
        }
    }

    static String route(boolean speaker) {
        JSONObject obj = new JSONObject();
        try {
            boolean ok = BridgeInCallService.routeSpeaker(speaker);
            obj.put("ok", ok);
            obj.put("requested", speaker ? "speaker" : "earpiece");
            obj.put("audio_state", new JSONObject(BridgeInCallService.audioStateJson()));
            if (!ok) {
                obj.put("message", "InCallService is not active. Start/answer a call first.");
            }
            return obj.toString();
        } catch (Exception e) {
            return error("route_failed", e);
        }
    }

    static String capture(Context context, String sourceName) {
        if (!hasRecordPermission(context)) {
            return simpleError("permission_denied", "RECORD_AUDIO permission is not granted");
        }

        final int source = sourceFromName(sourceName);
        if (source < 0) {
            return simpleError("unknown_source", "Unknown audio source: " + sourceName);
        }

        return captureAtRate(sourceName, source, DEFAULT_SAMPLE_RATE, 900).toString();
    }

    static String fullCaptureScan(Context context) {
        if (!hasRecordPermission(context)) {
            return simpleError("permission_denied", "RECORD_AUDIO permission is not granted");
        }

        JSONObject root = new JSONObject();
        JSONArray results = new JSONArray();

        try {
            root.put("ok", true);
            root.put("manufacturer", Build.MANUFACTURER);
            root.put("model", Build.MODEL);
            root.put("device", Build.DEVICE);
            root.put("sdk_int", Build.VERSION.SDK_INT);
            root.put("note",
                    "No audio is stored. Each source is sampled briefly and only level/init metrics are returned.");

            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                root.put("mode", modeName(am.getMode()));
                root.put("speakerphone", am.isSpeakerphoneOn());
            }
            root.put("incall_service_active", BridgeInCallService.isActive());

            for (String sourceName : CAPTURE_SOURCES) {
                JSONObject sourceResult = new JSONObject();
                sourceResult.put("source", sourceName);

                int source = sourceFromName(sourceName);
                sourceResult.put("source_id", source);

                if (source < 0) {
                    sourceResult.put("ok", false);
                    sourceResult.put("code", "unsupported_source");
                    results.put(sourceResult);
                    continue;
                }

                JSONArray attempts = new JSONArray();
                JSONObject firstWorking = null;

                for (int sampleRate : SAMPLE_RATES) {
                    JSONObject attempt = captureAtRate(sourceName, source, sampleRate, 280);
                    attempts.put(attempt);

                    if (attempt.optBoolean("ok", false)) {
                        firstWorking = attempt;
                        break;
                    }

                    String code = attempt.optString("code", "");
                    if ("security_exception".equals(code)
                            || "permission_denied".equals(code)) {
                        break;
                    }
                }

                sourceResult.put("attempts", attempts);
                sourceResult.put("ok", firstWorking != null);
                if (firstWorking != null) {
                    sourceResult.put("working_sample_rate", firstWorking.optInt("sample_rate"));
                    sourceResult.put("peak", firstWorking.optInt("peak"));
                    sourceResult.put("rms_dbfs", firstWorking.optDouble("rms_dbfs"));
                    sourceResult.put("looks_silent", firstWorking.optBoolean("looks_silent"));
                }
                results.put(sourceResult);
            }

            root.put("results", results);
            return root.toString();
        } catch (JSONException e) {
            return simpleError("scan_json_failed", safeMessage(e));
        }
    }

    static String playTone(String routeName) {
        final int streamType =
                "voice_call".equalsIgnoreCase(routeName)
                        ? AudioManager.STREAM_VOICE_CALL
                        : AudioManager.STREAM_MUSIC;

        JSONArray attempts = new JSONArray();

        for (int sampleRate : SAMPLE_RATES) {
            int minBuffer = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );

            JSONObject attempt = new JSONObject();
            try {
                attempt.put("sample_rate", sampleRate);
                attempt.put("min_buffer", minBuffer);
            } catch (JSONException ignored) {
            }

            if (minBuffer <= 0) {
                try {
                    attempt.put("ok", false);
                    attempt.put("code", "bad_min_buffer");
                } catch (JSONException ignored) {
                }
                attempts.put(attempt);
                continue;
            }

            final int samples = (int) (sampleRate * 0.75);
            final short[] pcm = makeTone(sampleRate, samples);
            final int bufferBytes = Math.max(minBuffer * 2, 4096);

            AudioTrack track = null;
            try {
                track = new AudioTrack(
                        streamType,
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferBytes,
                        AudioTrack.MODE_STREAM
                );

                if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                    attempt.put("ok", false);
                    attempt.put("code", "not_initialized");
                    attempts.put(attempt);
                    track.release();
                    continue;
                }

                final AudioTrack liveTrack = track;
                track = null;

                attempt.put("ok", true);
                attempt.put("code", "started");
                attempts.put(attempt);

                new Thread(() -> {
                    try {
                        liveTrack.setVolume(0.25f);
                        liveTrack.play();
                        int offset = 0;
                        while (offset < pcm.length) {
                            int n = liveTrack.write(pcm, offset, pcm.length - offset);
                            if (n <= 0) break;
                            offset += n;
                        }
                        Thread.sleep(120L);
                    } catch (Exception ignored) {
                    } finally {
                        try {
                            liveTrack.stop();
                        } catch (Exception ignored) {
                        }
                        liveTrack.release();
                    }
                }, "osb-audio-tone").start();

                JSONObject result = new JSONObject();
                result.put("ok", true);
                result.put("stream", routeName);
                result.put("sample_rate", sampleRate);
                result.put("frequency_hz", 700);
                result.put("duration_ms", 750);
                result.put("attempts", attempts);
                result.put("note",
                        "Tone started on Android output stream. This does NOT prove cellular uplink injection; confirm on the remote phone.");
                return result.toString();
            } catch (Exception e) {
                try {
                    attempt.put("ok", false);
                    attempt.put("code", "exception");
                    attempt.put("message", safeMessage(e));
                } catch (JSONException ignored) {
                }
                attempts.put(attempt);
            } finally {
                if (track != null) {
                    try {
                        track.release();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        JSONObject result = new JSONObject();
        try {
            result.put("ok", false);
            result.put("code", "tone_init_failed");
            result.put("message", "No tested AudioTrack configuration initialized");
            result.put("stream", routeName);
            result.put("attempts", attempts);
            return result.toString();
        } catch (JSONException e) {
            return simpleError("tone_init_failed", "No tested AudioTrack configuration initialized");
        }
    }

    private static JSONObject captureAtRate(
            String sourceName,
            int source,
            int sampleRate,
            int durationMs
    ) {
        JSONObject obj = new JSONObject();
        AudioRecord record = null;

        try {
            obj.put("source", sourceName);
            obj.put("source_id", source);
            obj.put("sample_rate", sampleRate);

            int minBuffer = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            obj.put("min_buffer", minBuffer);

            if (minBuffer <= 0) {
                obj.put("ok", false);
                obj.put("code", "bad_min_buffer");
                return obj;
            }

            int bufferBytes = Math.max(minBuffer * 2, 4096);
            record = new AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
            );

            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                obj.put("ok", false);
                obj.put("code", "not_initialized");
                return obj;
            }

            record.startRecording();

            short[] buffer = new short[512];
            long sumSquares = 0L;
            int peak = 0;
            int samples = 0;
            int nonZero = 0;
            long deadline = System.currentTimeMillis() + durationMs;

            while (System.currentTimeMillis() < deadline) {
                int n = record.read(buffer, 0, buffer.length);
                if (n < 0) {
                    obj.put("ok", false);
                    obj.put("code", "read_failed");
                    obj.put("read_result", n);
                    return obj;
                }

                for (int i = 0; i < n; i++) {
                    int value = buffer[i];
                    int abs = Math.abs(value);
                    if (abs > peak) peak = abs;
                    if (value != 0) nonZero++;
                    sumSquares += (long) value * (long) value;
                }
                samples += n;
            }

            double rms = samples > 0
                    ? Math.sqrt((double) sumSquares / (double) samples)
                    : 0.0;
            double rmsDbfs = rms > 0.0
                    ? 20.0 * Math.log10(rms / 32768.0)
                    : -120.0;

            obj.put("ok", true);
            obj.put("samples", samples);
            obj.put("nonzero_samples", nonZero);
            obj.put("peak", peak);
            obj.put("rms", Math.round(rms * 10.0) / 10.0);
            obj.put("rms_dbfs", Math.round(rmsDbfs * 10.0) / 10.0);
            obj.put("looks_silent", peak < 64 || nonZero < Math.max(1, samples / 100));
            return obj;
        } catch (SecurityException e) {
            try {
                obj.put("ok", false);
                obj.put("code", "security_exception");
                obj.put("message", safeMessage(e));
            } catch (JSONException ignored) {
            }
            return obj;
        } catch (Exception e) {
            try {
                obj.put("ok", false);
                obj.put("code", "capture_failed");
                obj.put("message", safeMessage(e));
            } catch (JSONException ignored) {
            }
            return obj;
        } finally {
            if (record != null) {
                try {
                    if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop();
                    }
                } catch (Exception ignored) {
                }
                try {
                    record.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static short[] makeTone(int sampleRate, int samples) {
        short[] pcm = new short[samples];
        double frequency = 700.0;
        double amplitude = 0.06 * 32767.0;
        int fadeSamples = Math.max(1, sampleRate / 50);

        for (int i = 0; i < samples; i++) {
            double fade = 1.0;
            if (i < fadeSamples) fade = (double) i / fadeSamples;
            if (i > samples - fadeSamples) {
                fade = (double) (samples - i) / fadeSamples;
            }
            pcm[i] = (short) (
                    Math.sin(2.0 * Math.PI * frequency * i / sampleRate)
                            * amplitude
                            * Math.max(0.0, Math.min(1.0, fade))
            );
        }
        return pcm;
    }

    private static boolean hasRecordPermission(Context context) {
        return Build.VERSION.SDK_INT < 23
                || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static JSONObject deviceJson(AudioDeviceInfo device) throws JSONException {
        JSONObject d = new JSONObject();
        d.put("id", device.getId());
        d.put("type", device.getType());
        d.put("type_name", deviceTypeName(device.getType()));
        d.put("product", String.valueOf(device.getProductName()));
        return d;
    }

    private static int sourceFromName(String name) {
        if (name == null) return -1;
        switch (name.toLowerCase()) {
            case "mic": return MediaRecorder.AudioSource.MIC;
            case "voice_uplink": return MediaRecorder.AudioSource.VOICE_UPLINK;
            case "voice_downlink": return MediaRecorder.AudioSource.VOICE_DOWNLINK;
            case "voice_call": return MediaRecorder.AudioSource.VOICE_CALL;
            case "voice_recognition": return MediaRecorder.AudioSource.VOICE_RECOGNITION;
            case "voice_communication": return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
            case "unprocessed":
                return Build.VERSION.SDK_INT >= 24
                        ? MediaRecorder.AudioSource.UNPROCESSED
                        : -1;
            default: return -1;
        }
    }

    private static String modeName(int mode) {
        switch (mode) {
            case AudioManager.MODE_NORMAL: return "NORMAL";
            case AudioManager.MODE_RINGTONE: return "RINGTONE";
            case AudioManager.MODE_IN_CALL: return "IN_CALL";
            case AudioManager.MODE_IN_COMMUNICATION: return "IN_COMMUNICATION";
            default: return String.valueOf(mode);
        }
    }

    private static String deviceTypeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "BUILTIN_EARPIECE";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "BUILTIN_SPEAKER";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "WIRED_HEADSET";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "WIRED_HEADPHONES";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "BLUETOOTH_SCO";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "BUILTIN_MIC";
            case AudioDeviceInfo.TYPE_TELEPHONY: return "TELEPHONY";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB_DEVICE";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB_HEADSET";
            default: return String.valueOf(type);
        }
    }

    private static String simpleError(String code, String message) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("ok", false);
            obj.put("code", code);
            obj.put("message", message == null ? "" : message);
            return obj.toString();
        } catch (JSONException e) {
            return "{\"ok\":false}";
        }
    }

    private static String error(String code, Exception e) {
        return simpleError(code, safeMessage(e));
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }
}
