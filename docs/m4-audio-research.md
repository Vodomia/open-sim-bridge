# M4 — software-only call audio research

Status: active investigation on Samsung Galaxy J7 Prime (on7xelte), stock Android 8.1 / API 27.

## What the device exposes

A stock Android 8.1 firmware dump for on7xelte contains an audio policy with:

- a `Voice Call Mic` device using `AUDIO_DEVICE_IN_VOICE_CALL`
- input channel masks for `AUDIO_CHANNEL_IN_VOICE_UPLINK` and `AUDIO_CHANNEL_IN_VOICE_DNLINK`
- explicit CP/baseband in-call mixer routes in `mixer_paths.xml`

This is good news at the hardware/HAL level: the modem call path is visible to the Android audio stack.

Reference firmware tree:
- https://github.com/Exynos7870-labs/samsung_on7xelte_dump
- `system/vendor/etc/audio_policy_configuration.xml`
- `system/etc/mixer_paths.xml`

## Framework restriction

The normal third-party Android APIs protect `VOICE_UPLINK`, `VOICE_DOWNLINK`, and `VOICE_CALL` capture with `android.permission.CAPTURE_AUDIO_OUTPUT`.

On Android 8.1 that permission is `signature|privileged`, so becoming the default dialer does not by itself grant raw call PCM access.

AOSP references:
- https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r1/core/res/AndroidManifest.xml
- https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-28/+/refs/heads/androidx-media-release/android/media/MediaRecorder.java

## InCallService

`InCallService` gives us the control plane (call state, answer, reject, disconnect, audio route and mute), but it does not expose a raw PCM stream for the cellular call.

This is still useful because we can force speaker/earpiece routing while testing alternative capture paths.

## Stock J7-specific observation

The stock on7xelte audio policy exposes the call input route, but the inspected stock policy does **not** expose an `AUDIO_OUTPUT_FLAG_INCALL_MUSIC` mix port.

That makes a clean public-API "play PCM directly into the cellular uplink" path unlikely on this firmware.

## Software-only test ladder

We will test in this order:

1. During an active cellular call, try ordinary `AudioRecord` sources:
   - MIC
   - VOICE_COMMUNICATION
   - VOICE_RECOGNITION
   - UNPROCESSED
2. Confirm that protected sources behave as expected:
   - VOICE_CALL
   - VOICE_DOWNLINK
   - VOICE_UPLINK
3. Force the cellular call to speaker using `InCallService.setAudioRoute()` and repeat capture tests.
4. Play a low-level test tone from the app during the call and check whether the remote party hears it.
5. If ordinary concurrent capture is silent, test a minimal AccessibilityService because Android's audio-input policy has special concurrent-capture handling for accessibility services.
6. Only if all stock-API paths fail, evaluate privileged/system-app or custom-ROM routes before moving to external audio hardware.

## Security position

Because this donor will receive banking OTP SMS, root/system modification is **not** the preferred final design. A privileged/custom-system path may be useful as a research proof, but it increases the trust surface and weakens the clean security model compared with an unmodified stock donor.

## Current experiment

Branch: `experiment/m4-audio-bridge`

The M4 probe adds:
- current audio route/status
- speaker/earpiece route control
- 0.9 s RMS/peak capture probes for multiple AudioRecord sources
- a low-level 700 Hz output tone on MUSIC or VOICE_CALL stream

The immediate question is not audio quality yet. It is:

> Can we get any useful bidirectional signal path on stock API 27 without external hardware?
