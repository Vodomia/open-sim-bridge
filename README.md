# Open SIM Bridge

Open SIM Bridge is an experimental, privacy-first bridge that lets a registered Android phone act as a local cellular gateway for an iPhone.

The initial prototype uses a Samsung Galaxy J7 Prime as the cellular endpoint. The long-term goal is to reduce the Android donor phone to the minimum required hardware while preserving its original, lawfully assigned device identity.

## Goals

- Keep the SIM and cellular radio on the registered Android donor device.
- Forward SMS locally to an iPhone with no cloud dependency.
- Support outgoing SMS from the iPhone through the donor device.
- Add incoming/outgoing call control in a later milestone.
- Bridge call audio locally in a later milestone.
- Keep all sensitive data local by default.
- Make the protocol and client/server code auditable and open source.
- Never modify, clone, spoof, or transplant IMEI/device identity.

## Non-goals

- Bypassing carrier or device-registration rules.
- Modifying or cloning IMEI values.
- Relaying banking OTPs through third-party cloud services.
- Shipping a production-ready telephony product before the security model is validated.

## Prototype hardware

- Samsung Galaxy J7 Prime — registered donor device, SIM, modem, hotspot.
- iPhone 15 — primary user interface.
- Optional later-stage bridge hardware for call audio/control.

## Milestones

1. **M0 — Local SMS receive:** Incoming SMS appears on the iPhone over the donor phone's local network, without cloud services.
2. **M1 — Secure pairing:** Mutual device pairing, encryption, replay protection, and local-only authentication.
3. **M2 — Two-way SMS:** Read, reply, and send SMS from the iPhone.
4. **M3 — Call signaling:** Detect incoming calls and expose answer/reject/dial controls on iPhone.
5. **M4 — Call audio:** Full-duplex local audio bridge with acceptable latency and echo handling.
6. **M5 — Hardware reduction:** Determine the minimum J7 Prime hardware needed for reliable headless operation and package it safely.

## Security rule

Real phone numbers, SMS contents, OTPs, bank names, credentials, private keys, provisioning profiles, and production secrets must never be committed to this repository.

See `docs/security.md` for the threat model.
