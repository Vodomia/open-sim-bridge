# Architecture

## Principle

The registered Android donor remains the only cellular endpoint. The iPhone is a local client. No cloud service is required for core operation.

## M0/M1 topology

```
Carrier network
    |
    v
Samsung Galaxy J7 Prime
- SIM + cellular modem
- Android SMS receiver
- Local bridge service
- Wi-Fi hotspot / local network
    |
    | encrypted local session
    v
iPhone 15
- Open SIM Bridge iOS client
- local notifications
- message viewer
```

## Trust boundaries

1. **Carrier -> donor phone:** outside project control.
2. **Android OS -> bridge service:** trusted only as far as the donor device is trusted.
3. **Bridge service -> iPhone:** project-controlled; must be authenticated and encrypted.
4. **Internet:** must not be required for SMS transport.

## Initial transport

For the first prototype, use the donor phone's local Wi-Fi network. Bluetooth LE may later be used for discovery/wake signaling, but message content should remain on an authenticated encrypted channel.

## Message flow

1. Android receives an SMS through the normal telephony stack.
2. Bridge service receives the SMS event.
3. Service normalizes metadata into a small local protocol object.
4. Payload is encrypted for the paired iPhone.
5. iPhone receives it over the local connection and posts a local notification.
6. Sensitive payloads are not written to verbose logs.

## Later call architecture

Call signaling and call audio are separate problems.

- Signaling: incoming caller, ringing, answer/reject/dial state.
- Audio: bidirectional low-latency voice path.

Do not design the audio bridge until SMS + pairing are stable.
