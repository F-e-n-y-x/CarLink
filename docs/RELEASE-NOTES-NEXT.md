# DiPlay next release notes

Changes through DiPlay 0.2.15 are documented in [0.2.15 release notes](RELEASE-NOTES-0.2.15.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).

## Siri and calls

- Wireless calls and Siri send the head unit's microphone on Android 7.1–9 head units. CarPlay sends both as Opus, and Android provides a MediaCodec Opus encoder only from Android 10, so the microphone stopped with `stage=ENCODER` and the other side heard nothing. DiPlay now falls back to a bundled software Opus encoder ([Concentus](https://github.com/lostromb/concentus)) when the platform has none; the diagnostic report names the encoder for each microphone stream. Accepted on a BOS Mini A1 head unit (Android 9, MediaTek) with an iPhone 12 on iOS 27. Related: [#415](https://github.com/shihabal3amri/DiPlay/issues/415)
