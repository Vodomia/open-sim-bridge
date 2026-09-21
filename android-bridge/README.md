# Android Bridge — M0 prototype

This is the first runnable prototype for the Samsung Galaxy J7 Prime donor phone.

## What it does

- receives incoming SMS using Android's normal telephony broadcast
- stores only the latest 50 messages locally
- runs a foreground local HTTP service on port `8787`
- exposes messages only to private/local-network clients
- requires a random local access token
- serves a tiny iPhone-friendly web viewer

## Important security limitation

**M0 is not encrypted. Do not use it for real banking OTPs or sensitive SMS.**

The access token prevents casual access, but HTTP traffic on the local network is still plaintext. M1 will replace this with authenticated encrypted pairing.

## Build

Open the `android-bridge` directory in Android Studio and build/install the `app` module on the J7 Prime.

The project intentionally uses only Android framework APIs and no third-party runtime libraries.

## First test

1. Install the app on the J7 Prime.
2. Grant the SMS permission.
3. Start the J7 Prime mobile hotspot.
4. Open Open SIM Bridge and tap **Start local bridge**.
5. Connect the iPhone to the J7 Prime hotspot.
6. On the iPhone, open one of the local URLs shown by the Android app.
7. Enter the local access token shown on the J7 Prime.
8. Send a harmless test SMS to the SIM in the J7 Prime.
9. The message should appear on the iPhone page within about one second.

Use a harmless test message. Do not test with a bank or account OTP yet.

## Current limitations

- browser viewer must be open to see updates
- no iPhone background notification yet
- no end-to-end encryption yet
- receive-only SMS
- no call signaling/audio yet
