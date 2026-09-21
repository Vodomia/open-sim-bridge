# Security and threat model

This project handles highly sensitive data, including banking OTP messages. Security is therefore a primary product requirement.

## Security goals

- No third-party cloud relay for message content.
- No analytics or advertising SDKs.
- No plaintext SMS logging.
- No default remote administration.
- Pair exactly one or a deliberately authorized set of clients.
- Encrypt application payloads in transit even on the local hotspot.
- Reject replayed or stale protocol messages.
- Keep long-term secrets in platform secure storage where available.
- Provide a clear way to revoke/re-pair a client.

## Threats considered

- A device on the same Wi-Fi network attempts to read messages.
- A malicious client attempts to pair without physical/user approval.
- Captured packets are replayed.
- Debug logs accidentally contain OTPs.
- Repository commits accidentally contain real user data or secrets.
- Lost/stolen bridge hardware remains paired to the iPhone.

## Out of scope for the first prototype

- Compromise of Android baseband firmware.
- A fully compromised/rooted donor phone.
- A fully compromised/jailbroken iPhone.
- Carrier-side interception.

## Repository hygiene

Never commit:
- real phone numbers
- SMS contents
- OTP values
- bank names tied to actual messages
- credentials or API keys
- certificates/private keys
- provisioning profiles
- IMEI/serial numbers

Use synthetic fixtures such as:
```
sender: TESTBANK
body: Your verification code is 123456
```

## Device identity

The project must not modify, clone, spoof, rewrite, or transplant IMEI/device identity. The donor device retains its original identity and cellular hardware.
