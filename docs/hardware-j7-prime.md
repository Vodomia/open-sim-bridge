# Samsung Galaxy J7 Prime donor notes

## Current role

The J7 Prime is the registered cellular donor device. For early milestones it remains fully assembled.

## Do not disassemble yet

Before removing the display, cameras, speaker assemblies, battery, antennas, or buttons, software behavior must be proven on the intact phone.

## Hardware questions for later bench testing

The electronics phase should determine:

- Can the board boot reliably without the display attached?
- Which flex cables are required for boot?
- Which antennas are required for stable LTE/Wi-Fi/Bluetooth?
- Does the battery temperature/ID circuit need the original battery electronics?
- Can the device recover from power loss without touchscreen interaction?
- Can hotspot and the bridge service auto-start after boot?
- How much continuous power does the board draw under LTE + hotspot load?
- What thermal limits appear in a reduced enclosure?
- Can the microphone/headset path be accessed cleanly for a future audio bridge?

## Rules for hardware work

- Preserve the original cellular board and IMEI.
- Do not transfer identity data to another modem.
- Do not remove safety-critical battery protection.
- Test thermal behavior before designing a sealed case.
