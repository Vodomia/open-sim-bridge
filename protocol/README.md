# Local protocol draft

Status: draft for M0/M1.

## Design goals

- minimal
- versioned
- authenticated
- encrypted
- replay-resistant
- easy to inspect

## Envelope

```json
{
  "version": 1,
  "type": "sms.received",
  "message_id": "uuid",
  "timestamp_ms": 0,
  "payload": {}
}
```

The envelope shown above is the logical representation before encryption.

## Initial message types

- `pair.request`
- `pair.confirm`
- `session.hello`
- `sms.received`
- `sms.send.request`
- `sms.send.result`
- `health.status`

## SMS payload

```json
{
  "sender": "TESTBANK",
  "body": "Your verification code is 123456",
  "received_at_ms": 0
}
```

Do not add device IMEI, IMSI, phone number, or unrelated device identifiers to routine protocol messages.

## Pairing

Pairing design is not finalized. Preferred direction:

1. User opens pairing screen on Android.
2. Android displays a short-lived QR code containing only bootstrap material.
3. iPhone scans it.
4. Both sides derive/store a long-term paired-device key.
5. Future sessions use authenticated key agreement and per-session encryption keys.
6. Re-pairing requires an explicit local action.

Cryptographic primitives should use well-reviewed platform/library implementations rather than custom crypto.
