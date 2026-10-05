# Buffered music (CarPlay main buffered audio)

Apple calls it "Enhanced buffering" ([WWDC23, Optimize CarPlay for vehicle systems](https://developer.apple.com/videos/play/wwdc2023/10150/)): for apps that support it, the iPhone sends music ahead of time and the car keeps the timing, so short wireless gaps are not heard. DiPlay implements the receiving side. It is off by default: **Settings → Display and performance → Buffered music (experimental)**, which reconnects CarPlay.

## What changes

- **Which apps:** Apple Music uses it; the SETUP names the client (`clientID=com.apple.Music`). Spotify kept the normal stream in my tests. Apps that do not use it are unaffected.
- **How much ahead:** the iPhone sent about 1 minute 43 seconds of music within 8 seconds, then topped it up as playback went on. During a Wi-Fi Direct reconnection the queue fell from 25 to 14 seconds and nothing was heard.
- **Playback:** the frames go to DiPlay's normal media renderer about one second ahead of playback, so audio focus, ducking under navigation and Siri, and the audio channel settings apply as for any music.

## How it works

Names come from the strings of Xcode's CarPlay Simulator and its CarPlaySDK; the behaviour was observed between DiPlay and an iPhone.

- **Offer:** `/info` carries `mainBufferedInfo` (an empty dictionary is accepted) and an `audioFormats` entry `{type 103, audioType media, audioOutputFormats AAC-LC}`. The iPhone proposes the session feature `mainBuffered` in its SETUP, and DiPlay enables it. Enabling the feature without `mainBufferedInfo`, or the reverse, makes the iPhone drop the session.
- **Stream SETUP (type 103):** `ct 4` (AAC), `audioFormat`, `spf 1024`, `isMedia`, `clientID`, `streamConnectionID` and `shk` (the stream key). DiPlay answers `{type 103, dataPort, audioBufferSize}` and listens on TCP.
- **Data:** each frame is a 2-byte length (including itself), a 12-byte RTP header (sequence +1, timestamp +1024) and the payload sealed with ChaCha20-Poly1305 under `shk` (the header's timestamp and SSRC as associated data), followed by an 8-byte nonce. The payload is a raw AAC-LC access unit.
- **Control:**

  | Request | Body | Meaning |
  |---|---|---|
  | `SETRATE` | `{rtpTime, rate 1}` | start at that sample; the car answers with the anchor |
  | `GETANCHOR` | `{rate}` | the iPhone polls the anchor until it gets one |
  | `SETRATEANCHORTIME` | `{rate 0}` | pause |
  | `FLUSHBUFFERED` | `{flushUntilSeq, flushUntilTS}` | drop what was sent before (track change, seek) |

- **Anchor:** `{rtpTime, networkTimeSecs, networkTimeFrac, rate}`: sample `rtpTime` plays at that time on the session's timing clock. `networkTimeSecs` counts from the 1970 epoch (NTP seconds minus 2208988800) and `networkTimeFrac` is a 64-bit fraction. With NTP's 1900 seconds the iPhone sent only about 0.6 s and waited.
- **/feedback** reports the buffered stream's position like the other audio streams.

## Lossless

Apple Music's lossless setting does not reach the car this way. With only PCM 48 kHz 16-bit, PCM 48 kHz 24-bit or ALAC 48 kHz 24-bit offered for type 103, the iPhone opened no music stream at all and played on its own speaker; offered together with AAC, it chose AAC. So over CarPlay the music arrives as AAC-LC (about 260 kbit/s measured). Whether another iOS version, or an offer we did not find, would allow a lossless buffered stream is not known.

## Tested

2024 BYD Tang (DiLink 5.0), iPhone on iOS 27, wireless CarPlay (Wi-Fi Direct and the car's hotspot), Apple Music:

- music played from the car, with pause, resume and track changes;
- calls and Siri worked as before; Spotify used the normal stream;
- unit tests cover the offer, frame opening, the anchor format, wrap-safe timestamps and the start/pause/flush flow.

## Limits

- Experimental and off by default; only AAC-LC is accepted.
- The anchor latency is a fixed estimate (about 400 ms), which only shifts the iPhone's progress bar slightly.
- Spatial audio (APAC formats in the simulator's list) is not offered.
