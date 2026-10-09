# DiPlay next release notes

Changes through DiPlay 0.2.15 are documented in [0.2.15 release notes](RELEASE-NOTES-0.2.15.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).

## Siri and calls

- Wireless calls and Siri send the head unit's microphone on Android 7.1–9 head units. CarPlay sends both as Opus, and Android provides a MediaCodec Opus encoder only from Android 10, so the microphone stopped with `stage=ENCODER` and the other side heard nothing. DiPlay now falls back to a bundled software Opus encoder ([Concentus](https://github.com/lostromb/concentus)) when the platform has none; the diagnostic report names the encoder for each microphone stream. Accepted on a BOS Mini A1 head unit (Android 9, MediaTek) with an iPhone 12 on iOS 27. Related: [#415](https://github.com/shihabal3amri/DiPlay/issues/415)

## Connection setup and recovery

- Fix wired NCM receive framing when a transfer block ends on a USB packet boundary, and cap Android 8 read requests before Android rejects an oversized queue.
- Retry explicitly rejected large USB reads at smaller sizes down to 2 KiB, including capped or previously cached read sizes. Keep malformed framing and ambiguous requests fatal.
- Request Android 17 local-network permission for wireless CarPlay.
- Refresh hotspot addresses when the first AirPlay connection times out, and wait briefly for a preferred IPv6 address on the next attempt.
- Handle missing or blocked system VPN authorization screens without crashing. Firmware that cannot grant VPN authorization still needs a compatible connection path.
- Add WPA3 and WPA3 transition selection to car-hotspot setup and preserve the selected security when credentials are edited.
- Explain that built-in car hotspot sends its details over Bluetooth; users do not need to join it manually in iPhone Wi-Fi settings.
- Include unsupported message IDs in identification-rejection reports. This improves investigation of older-iPhone compatibility; it is not a confirmed compatibility repair.

## Display and audio

- Keep a healthy session connected when returning to an unchanged window or cancelling unchanged in-session display settings.
- Pace experimental buffered-audio intake after its initial fill to reduce contention with realtime audio and video.
- Include communication-mode restoration and dropped audio-focus callbacks in diagnostic exports, with failure-safe diagnostic handling.
