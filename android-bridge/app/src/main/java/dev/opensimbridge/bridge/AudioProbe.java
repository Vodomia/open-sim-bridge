package dev.opensimbridge.bridge;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
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
    private static final int SAMPLE_RATE = 16000;

    private AudioProbe() {}

    static String status(Context context) {
        JSONObject obj = new JSONObject();
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            obj.put("sdk_int", Build.VERSION.SDK_INT);
            obj.put("record_audio",
                    Build.VERSION.SDK_INT < 23
                            || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED);

            if (am != null) {
                obj.put("mode", am.getMode());
                obj.put("mode_name", modeName(am.getMode()));
                obj.put("speakerphone", am.isSpeakerphoneOn());
                obj.put("microphone_mute", am.isMicrophoneMute());

                if (Build.VERSION.SDK_INT >= 23) {
                    JSONArray inputs = new JSONArray();
                    for (AudioDeviceInfo device : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                        JSONObject d = new JSONObject();
                        d.put("id", device.getId());
                        d.put("type", device.getType());
                        d.put("type_name", deviceTypeName(device.getType()));
                        d.put("product", String.valueOf(device.getProductName()));
                        inputs.put(d);
                    }
                    obj.put("inputs", inputs);

                    JSONArray outputs = new JSONArray();
                    for (AudioDeviceInfo device : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                        JSONObject d = new JSONObject();
                        d.put("id", device.getId());
                        d.put("type", device.getType());
                        d.put("type_name", deviceTypeName(device.getType()));
                        d.put("product", String.valueOf(device.getProductName()));
                        outputs.put(d);
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
        if (Build.VERSION.SDK_INT >= 23
                && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return simpleError("permission_denied", "RECORD_AUDIO permission is not granted");
        }

        final int source = sourceFromName(sourceName);
        if (source < 0) {
            return simpleError("unknown_source", "Unknown audio source: " + sourceName);
        }

        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBuffer <= 0) {
            return simpleError("bad_min_buffer", "AudioRecord min buffer: " + minBuffer);
        }

        AudioRecord record = null;
        try {
            int bufferBytes = Math.max(minBuffer * 2, 4096);
            record = new AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
            );

            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                return simpleError("not_initialized", "AudioRecord did not initialize");
            }

            record.startRecording();

            short[] buffer = new short[1024];
            long sumSquares = 0L;
            int peak = 0;
            int samples = 0;
            int nonZero = 0;
            long deadline = System.currentTimeMillis() + 900L;

            while (System.currentTimeMillis() < deadline) {
                int n = record.read(buffer, 0, buffer.length);
                if (n < 0) {
                    return simpleError("read_failed", "AudioRecord read returned " + n);
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

            double rms = samples > 0 ? Math.sqrt((double) sumSquares / (double) samples) : 0.0;
            double rmsDbfs = rms > 0.0 ? 20.0 * Math.log10(rms / 32768.0) : -120.0;

            JSONObject obj = new JSONObject();
            obj.put("ok", true);
            obj.put("source", sourceName);
            obj.put("source_id", source);
            obj.put("samples", samples);
            obj.put("nonzero_samples", nonZero);
            obj.put("peak", peak);
            obj.put("rms", Math.round(rms * 10.0) / 10.0);
            obj.put("rms_dbfs", Math.round(rmsDbfs * 10.0) / 10.0);
            obj.put("looks_silent", peak < 64 || nonZero < Math.max(1, samples / 100));
            return obj.toString();
        } catch (SecurityException e) {
            return simpleError("security_exception", safeMessage(e));
        } catch (Exception e) {
            return error("capture_failed", e);
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

    static String playTone(String routeName) {
        final int streamType;
        if ("voice_call".equalsIgnoreCase(routeName)) {
            streamType = AudioManager.STREAM_VOICE_CALL;
        } else {
            streamType = AudioManager.STREAM_MUSIC;
        }

        final int samples = (int) (SAMPLE_RATE * 0.75);
        final short[] pcm = new short[samples];
        final double frequency = 700.0;
        final double amplitude = 0.07 * 32767.0;

        for (int i = 0; i < samples; i++) {
            double fade = 1.0;
            int fadeSamples = SAMPLE_RATE / 50;
            if (i < fadeSamples) fade = (double) i / fadeSamples;
            if (i > samples - fadeSamples) fade = (double) (samples - i) / fadeSamples;
            pcm[i] = (short) (Math.sin(2.0 * Math.PI * frequency * i / SAMPLE_RATE)
                    * amplitude * Math.max(0.0, Math.min(1.0, fade)));
        }

        try {
            final AudioTrack track = new AudioTrack(
                    streamType,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    pcm.length * 2,
                    AudioTrack.MODE_STATIC
            );

            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                return simpleError("tone_init_failed", "AudioTrack did not initialize");
            }

            track.write(pcm, 0, pcm.length);
            track.setVolume(0.35f);

            new Thread(() -> {
                try {
                    track.play();
                    Thread.sleep(1100L);
                } catch (Exception ignored) {
                } finally {
                    try {
                        track.stop();
                    } catch (Exception ignored) {
                    }
                    track.release();
                }
            }, "osb-audio-tone").start();

            JSONObject obj = new JSONObject();
            obj.put("ok", true);
            obj.put("stream", routeName);
            obj.put("frequency_hz", 700);
            obj.put("duration_ms", 750);
            obj.put("note", "Low-level test tone started on the J7 audio output");
            return obj.toString();
        } catch (Exception e) {
            return error("tone_failed", e);
        }
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
