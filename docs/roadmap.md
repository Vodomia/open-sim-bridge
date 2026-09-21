# Product roadmap

The goal is not a browser SMS viewer. The goal is a compact, headless, privacy-first cellular bridge that lets an iPhone use the donor device's registered SIM/radio for SMS, calls and data without carrying a second full phone.

## End state

- SIM remains in the registered donor cellular board.
- iPhone is the primary user interface.
- Donor hardware can operate headless.
- Internet access is provided through the donor's cellular connection.
- Incoming SMS appears on iPhone.
- Outgoing SMS can be sent from iPhone.
- Incoming calls ring on iPhone.
- Calls can be answered/rejected from iPhone.
- Outgoing calls can be initiated from iPhone.
- Bidirectional call audio is bridged with usable latency/quality.
- Bridge auto-starts after power loss/reboot.
- Pairing works without requiring a donor display.
- Core operation does not depend on a third-party cloud.
- Sensitive content is not logged.
- Original donor IMEI/device identity is preserved.

## Priority order

### Phase A — Prove telephony control
1. Reliable inbound SMS event capture.
2. Outbound SMS from local API.
3. Detect incoming GSM call state.
4. Answer/reject/end calls programmatically if the donor Android permits it.
5. Initiate outgoing calls.

### Phase B — Prove voice path
1. Identify a stable call-audio extraction/injection path.
2. Prototype full-duplex audio bridge.
3. Measure latency, echo, clipping and reconnect behavior.
4. Decide whether the final bridge uses:
   - Android audio APIs,
   - wired headset/TRRS path,
   - USB audio path,
   - Bluetooth HFP intermediary,
   - or a small Linux/audio coprocessor.

### Phase C — Native iPhone experience
1. Native iOS client.
2. Local discovery.
3. CallKit integration.
4. Local notifications for SMS/call state.
5. Background reconnection.
6. Contacts/name resolution locally on iPhone where possible.

### Phase D — Security
1. Screen-independent pairing.
2. Authenticated key exchange.
3. Encrypted local transport.
4. Replay protection.
5. Key revocation/reset.
6. No cloud relay required.

### Phase E — Headless donor
1. Auto-start bridge after boot.
2. Recover after hotspot/cellular failure.
3. Determine minimum J7 hardware required.
4. Boot without display.
5. Power/thermal measurements.
6. Physical status/reset controls if required.

### Phase F — Hardware reduction
1. Remove unnecessary assemblies only after bench validation.
2. Preserve required antennas and RF layout.
3. Safe battery/power design.
4. Compact enclosure.
5. Optional MagSafe mechanical attachment.
6. Serviceable SIM access.

## Headless pairing ideas (deferred)

The final device must not depend on a screen. Candidate approaches include:

- physical pairing/reset button + LED state
- BLE discovery with physical-button confirmation
- factory/static public-key QR or NFC label on enclosure
- temporary hotspot with one-time bootstrap secret
- iPhone-generated challenge confirmed by physical action on the bridge

No final approach is chosen yet. This is intentionally deferred until the core telephony bridge works.

## Definition of success

A successful prototype lets the user leave the donor module in a pocket/bag or attached to the iPhone and perform normal daily SMS and voice-call tasks from the iPhone without interacting with the donor device.
