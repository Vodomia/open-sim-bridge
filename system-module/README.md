# M4 privileged audio experiment

This directory contains the Android 8.1 privileged-app allowlist needed for the next software-only audio experiment.

Goal:
- install Open SIM Bridge as a privileged system app on the dedicated J7 Prime
- grant `CAPTURE_AUDIO_OUTPUT`
- grant `MODIFY_AUDIO_ROUTING`
- re-run the existing M4 full audio scan
- check whether `VOICE_UPLINK`, `VOICE_DOWNLINK`, or `VOICE_CALL` now initialize

Target package:
`dev.opensimbridge.bridge`

Target Android:
Samsung SM-G610F / API 27 (Android 8.1)

Expected system locations on Android 8.1:
- app: `/system/priv-app/OpenSimBridge/OpenSimBridge.apk`
- allowlist: `/system/etc/permissions/privapp-permissions-open-sim-bridge.xml`

Do not install this by manually modifying the system partition yet. The install method should be chosen only after the donor device backup/root/boot state is understood.

This experiment preserves the original modem and IMEI and does not modify device identity.
