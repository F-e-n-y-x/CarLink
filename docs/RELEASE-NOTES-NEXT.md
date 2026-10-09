# DiPlay next release notes

Changes through DiPlay 0.2.15 are documented in [0.2.15 release notes](RELEASE-NOTES-0.2.15.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).

## Siri and calls

- Wireless calls and Siri send the head unit's microphone on Android 7.1–9 head units. CarPlay sends both as Opus, and Android provides a MediaCodec Opus encoder only from Android 10, so the microphone stopped with `stage=ENCODER` and the other side heard nothing. DiPlay now falls back to a bundled software Opus encoder ([Concentus](https://github.com/lostromb/concentus)) when the platform has none; the diagnostic report names the encoder for each microphone stream. Accepted on a BOS Mini A1 head unit (Android 9, MediaTek) with an iPhone 12 on iOS 27. Related: [#415](https://github.com/shihabal3amri/DiPlay/issues/415)
- Keep the experimental call echo canceller's playback reference contiguous, and suppress noise after cancelling instead of before it. Not yet accepted in a car (#421).

## Connection setup and recovery

- Fix wired NCM receive framing when a transfer block ends on a USB packet boundary, and cap Android 8 read requests before Android rejects an oversized queue.
- Retry explicitly rejected large USB reads at smaller sizes down to 2 KiB, including capped or previously cached read sizes. Keep malformed framing and ambiguous requests fatal.
- Request Android 17 local-network permission for wireless CarPlay.
- Refresh hotspot addresses when the first AirPlay connection times out, and wait briefly for a preferred IPv6 address on the next attempt.
- Handle missing or blocked system VPN authorization screens without crashing. Firmware that cannot grant VPN authorization still needs a compatible connection path.
- Add WPA3 and WPA3 transition selection to car-hotspot setup and preserve the selected security when credentials are edited.
- Explain that built-in car hotspot sends its details over Bluetooth; users do not need to join it manually in iPhone Wi-Fi settings.
- Include unsupported message IDs in identification-rejection reports. This improves investigation of older-iPhone compatibility; it is not a confirmed compatibility repair.
- Keep the DiLink 3 Wi-Fi scan pause from outliving a disconnect that races wireless startup.

## Display and audio

- Keep a healthy session connected when returning to an unchanged window or cancelling unchanged in-session display settings.
- Pace experimental buffered-audio intake after its initial fill to reduce contention with realtime audio and video.
- Include communication-mode restoration and dropped audio-focus callbacks in diagnostic exports, with failure-safe diagnostic handling.
- Add experimental Low-latency decoding (Qualcomm decoder keys and a dedicated output thread) and Direct video output in Advanced, and an FPS counter in Diagnostics. All are off by default (#496).
- With Smooth video, try a hardware low-latency H.264 decoder first and ask Qualcomm decoders for decode-order output (#456).
- Ask before the Back gesture or the full-settings link discards staged in-session settings (#500).
- Add an Off option for the CarPlay swipe-down quick-menu gesture (#487).
- Show the reconnect bar when Auto yield changes (#482).
- Refine Settings spacing, alignment and the in-session menu width; Diagnostics and Advanced appear as Overview category rows (#486).

## Navigation and cluster

- Place the car marker with 1 % sliders in full-screen and small-window layouts, including DiLink 4 and the ADB cluster route. Earlier step positions migrate, and changes apply at the next connection (#494).
- The cluster's waiting screen follows DiPlay's theme with a small spinner (#481).
- Send the DiLink 3 simple-navigation arrival time in AMap's form so the cluster shows the whole time. Not yet confirmed in a car (#458).
- Add an experimental Platform 21 instrument task route for the 2023 Tang DM-i, off by default (#348).
- Add experimental wheel-key volume for spoken navigation guidance, off by default (#344).

## Vehicle

- Add an optional delayed pause of the car Bluetooth during CarPlay (needs ADB, off by default). While it is paused, CarPlay calls use the cabin speaker and microphone. Bluetooth turns back on when CarPlay ends or disconnects, when DiPlay reopens after an interruption, and before the next wireless handshake. Pending in-car acceptance (#307).
