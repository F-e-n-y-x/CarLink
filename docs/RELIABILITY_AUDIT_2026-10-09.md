# Connection reliability audit, 2026-10-09

**Bench:** Redmi Pad Pro 5G (Android 16), iPhone 13 (iOS 26.5.2, `iPhone13,1`), Samsung S25 Ultra.
**Paths tested:** USB directly and through a USB-C dock, and wireless over Wi-Fi Direct.
**Evidence:** `.claude/device-logs/2026-10-09/`:

- `carlink.log`, 16:02:55 to 17:30:15;
- DiAuto journal, 12:30 to 17:30;
- DiPlay's last 8 runs, 16:59:21 to 17:26:26;
- `logcat.txt`, 17:29:48 to 17:31:05.

All times are device local time (IST). This is an analysis only: no code was changed.

**Conventions.** In this report:

- **TTFF** is CarLink's `start …` line to its `… connected on …` line. For both engines, "connected" is the first rendered video frame. This was checked against the engines' own first-frame lines: they agree within 7 ms (for example, 17:25:46.701 DiPlay `first-frame` and 17:25:46.708 CarLink `connected`).
- **"Inference"** marks every conclusion the logs do not prove directly.

---

## 0. Build timeline (derived from the logs)

Each install gets a new `/data/app/~~…` path, which DiAuto's `App.onCreate | native library dir …` line records. Logcat confirms the last install: 17:29:48 `Package [com.carlink.app.hudtest] reported as REPLACED`. The installs since 16:00 were:

| Build | Installed | Notes |
|---|---|---|
| b1–b2 | 16:01:56, 16:02:56 | |
| b3 | 16:05:17 | |
| b4, b5 | 16:11:02, 16:15:12 | |
| b6 | 16:23:04 | |
| b7–b9 | 16:34:01, 16:34:33, 16:37:50 | `Switcher` lines start in carlink.log |
| b10 | 16:42:58 | |
| b11 | 16:46:55 | |
| b12 | 16:52:59 | first build where wired CarPlay reaches "connected" |
| b13 | 16:59:19 | DiPlay pid 12161; first retained DiPlay run (with `WIRED_TIMING`) |
| b14, b15 | 17:09:48, 17:13:15 | |
| b16 | 17:18:23 | DiPlay pid 13604; `WIRELESS_TIMING` in both engines; S25 USB settle |
| b17 | 17:28:21 | |
| b18 | 17:29:50 | the "17:2x" build |

There were 18 installs, not the 8 recalled. Three things follow from this:

- **Installs explain some events.** Every install killed a running session, and several of the "USB attach at app start" events are CarLink enumerating the devices already plugged in, not a user plugging something in. These times are 16:05:16, 16:11:01, 16:15:11, 16:46:54, 16:52:58, 16:59:18, 17:28:20 and 17:29:49.
- **Two of those killed sessions left the S25 in stale accessory mode** (see K2).
- **The "17:4x" build is not covered.** The logs were pulled at 17:31:05.

---

## 1. Every reconstructable connection attempt

### 1a. CarPlay over USB

All of these went through the dock where the dock was enumerated: it is present at almost every app start, and was physically re-plugged at 16:07:55 and 16:45:23. Whether a given attempt was plugged directly or through the dock cannot be told from the logs.

| # | Start | Build | Result | TTFF | Where the time went |
|---|---|---|---|---|---|
| U1 | 16:03:25.7 | b2 | never "connected"; switched to AA by the driver at 16:04:13 | – | stuck at the last step for 47.8 s (K1) |
| U2 | 16:05:23.4 | b3 | never "connected"; driver Disconnect at 16:05:41 | – | 17.8 s stuck (K1) |
| U3 | 16:05:53.4 | b3 | never "connected"; dock re-plugged at 16:07:55 mid-session; driver Disconnect at 16:08:05.5 | – | 132 s stuck (K1) |
| U4 | 16:08:29.3 | b3 | never "connected"; switched to AA at 16:08:44 | – | 15 s stuck (K1) |
| U5 | 16:09:24.3 | b3 | never "connected"; killed by the b4 install at 16:11:01 | – | 97 s stuck (K1) |
| U6 | 16:11:01.4 | b4 | never "connected"; b5 install at 16:15:11 | – | 250 s stuck; iPhone re-enumeration pairs at 16:13:35 and 16:13:47 (K13) |
| U7 | 16:15:11.2 | b5 | never "connected"; unplugged at 16:21:28.8 | – | 377 s stuck; pairs at 16:18:57 and 16:19:09 (K13) |
| U8 | 16:45:25.3 | b10 | never "connected"; b11 install at 16:46:54 | – | 89 s in `Progress` with the CarPlay host in front |
| U9 | 16:46:54.4 | b11 | `Retrying` at 16:47:45; driver Disconnect at 16:47:48 | – | 54 s; failed (K12) |
| U10 | 16:48:04.2 | b11 | `Retrying` at 16:48:30, re-enumeration at 16:48:32.9, `Retrying` at 16:48:40, unplugged at 16:48:44 | – | 40 s; failed (K12) |
| U11 | 16:48:44.7 | b11 | `Retrying` at 16:48:57; unplugged at 16:49:01 | – | 16 s; failed (K12) |
| U12 | 16:59:03.2 | b12 | **connected** at 16:59:08.77 | 5.5 s | 2.1 s stopping the wireless CarPlay attempt; no DiPlay log kept |
| U13 | 16:59:20.9 | b13 | **connected** at 16:59:24.58 | 3.7 s | 1.9 s CarLink start to controller start (0.5 s debounce, K11); then plug+1677 ms to the frame, with no re-enumeration because the configuration was already 6 |
| U14 | 17:08:16.8 | b13 | **connected** at 17:08:22.13 | 5.3 s | 2.1 s stopping the wireless attempt, 0.88 s host start, then plug+2245 ms (re-enumeration 657 ms) |
| U15 | 17:19:15.1 | b16 | **connected** at 17:19:19.50 | 4.4 s | 1.7 s host start, then plug+2598 ms (re-enumeration 779 ms) |
| U16 | 17:19:51.3 | b16 | **connected** at 17:19:55.22 | 3.9 s | 0.5 s AA stop, 0.9 s host start, then plug+2412 ms (re-enumeration 753 ms) |

Every wired session that did connect ended by unplug (the DiPlay `USBMUX read request` failures at 17:09:06, 17:19:26 and 17:19:57), by a switch, or by an install. No wired session dropped on its own after b12.

### 1b. CarPlay wireless (Wi-Fi Direct)

| # | Start | Build | Result | TTFF | Where the time went |
|---|---|---|---|---|---|
| C1 | 16:24:49.2 | b6 | **hung** 57 s; driver Disconnect at 16:25:46 | – | unknown: no DiPlay or Switcher log kept |
| C2 | 16:25:48.5 | b6 | **connected** at 16:26:09.07 | 20.6 s | no split available |
| C3 | 16:35:08.1 | b8 | **connected** at 16:35:24.63 | 16.5 s | no split available |
| C4 | 16:37:59.6 | b9 | **connected** at 16:38:15.97 | 16.3 s | no split available |
| C5 | 16:43:20.2 | b10 | **connected** at 16:43:47.87 | 27.7 s | no split available |
| C6 | 16:58:53.3 | b12 | Home auto-start right after an AA unplug (K5); `Retrying` at 16:59:00.5; replaced by USB at 16:59:03 | – | about 7 s; matches one 5.15 s RFCOMM page failure (inference) |
| C7 | 17:08:06.2 | b13 | Home auto-start after an AA unplug; RFCOMM failed at 17:08:13.6; second dial cut by the USB switch at 17:08:16.8 | – | 10.6 s; RFCOMM failed after 5153 ms |
| C8 | 17:09:07.9 | b13 | Home auto-start after an iPhone unplug; 4 RFCOMM failures; driver Disconnect at 17:09:36.5 | – | 28.6 s of `fail 5.15 s → 1.5 s backoff → 0.6 s restart` loops |
| C9 | 17:21:50.3 | b16 | **connected** at 17:22:12.74 | **22.4 s** | see the timed split below |
| C10 | 17:25:33.9 | b16 | **connected** at 17:25:46.70 | **12.8 s** | see the timed split below |

Timed split for the two `WIRELESS_TIMING` runs. Values are milliseconds from DiPlay's controller start; CarLink's own overhead comes before that.

| Phase | C9 (17:21:52) | C10 (17:25:36) |
|---|---|---|
| CarLink start → controller start (AA stop + host + 0.5 s debounce) | 2.46 s (AA stop 1.56 s) | 2.51 s (AA stop 1.54 s) |
| RFCOMM dial → connected (paging the iPhone) | **6650** | **5846** |
| iAP2 identified → MFi auth → `0x5703` Wi-Fi config sent | 253 + 481 + 9 | 392 + 422 + 7 |
| `start-session` sent | +7658 | +6884 |
| iPhone associated with the P2P group | +763 after start-session | +771 |
| First AirPlay TCP | +1958 after start-session | +1123 |
| TCP → `control encryption enabled` (pair-verify) | **5063** | 460 |
| encryption → `SETUP` #1 | **3959** | 40 |
| SETUP → session active | 445 | 1257 |
| session active → first frame | 886 | 483 |
| **Total from controller start** | **19981** | **10245** |

### 1c. Android Auto over USB (S25)

| # | Start | Build | Result | TTFF | Where the time went |
|---|---|---|---|---|---|
| A1 | 16:04:13.5 | b2 | **connected** at 16:04:16.71 | 3.2 s | AOA switch, then the accessory at +0.6 s, then the frame at +2.3 s |
| A2 | 16:05:17.0 | b3 (app start) | stale accessory: SSL handshake failed at 16:05:19.8; driver chose CarPlay at 16:05:23 | – | K2 |
| A3 | 16:08:44.4 | b3 | **connected** at 16:08:47.82 | 3.4 s | |
| A4 | 16:49:02.8 | b11 | **failed** at 16:49:08.3: "did not switch" (DiAuto: `Cannot open device`) | – | K6, after phone re-enumeration at 16:49:03.5 and 16:49:05.0 |
| A5 | 16:49:13.1 | b11 | **connected** at 16:49:16.57 | 3.5 s | after a re-plug |
| A6 | 16:52:58.4 | b12 (app start) | stale accessory; **failed** `PHONE_DID_NOT_ANSWER` at 16:54:10.98 | – | **72.6 s; 60 s of it in `Retrying` after the engine had already failed (K2)** |
| A7 | 16:57:18.2 | b12 | **failed** at 16:57:22.8 (`Cannot open device`) | – | K6 |
| A8 | 16:58:46.0 | b12 | **connected** at 16:58:49.23 | 3.2 s | |
| A9 | 16:59:12.1 | b12 | **connected** at 16:59:15.15 | 3.1 s | after the "switch?" prompt |
| A10 | 16:59:18.5 | b13 (app start) | stale accessory; handshake failed at 16:59:20.87; driver chose CarPlay | – | K2 |
| A11 | 17:07:39.8 | b13 | **failed** at 17:07:44.4 (`Cannot open device`) | – | K6 |
| A12–A14 | 17:07:47.6, 17:07:51.8, 17:07:53.3 | b13 | "Try again" ×3: **failed in 0.10–0.25 s** each (`Cannot open device`) | – | K6: the retry reused the stale `UsbDevice` |
| A15 | 17:07:56.7 | b13 | **connected** at 17:08:00.05 | 3.4 s | after a re-attach |
| A16 | 17:19:36.1 | b16 | **connected** at 17:19:40.40 | 4.3 s | phone re-enumerated at 17:19:36.67; the settle step absorbed it (fix proven) |
| A17 | 17:20:59.8 | b16 | **connected** at 17:21:03.63 | 3.8 s | re-enumeration at 17:21:00.38 absorbed |
| A18 | 17:26:26.0 | b16 | **connected** at 17:26:33.36 | 7.4 s | **4.07 s stopping CarPlay wireless (K7)**, then `first-frame start+3122` |
| A19 | 17:28:20.5 | b17 (app start) | stale accessory; **failed** `PHONE_DID_NOT_ANSWER` at 17:29:25.99 | – | **65.5 s; handshake failed at +2.8 s, then `Retrying` for 60 s (K2)** |
| A20 | 17:29:43.3 | b17 | "Try again": stale again (handshake failed at 17:29:44.7); the engine then poked the phone **wirelessly** (K10); killed by the b18 install at 17:29:48 | – | |
| A21 | 17:29:49.3 | b18 (app start) | stale; handshake failed at 17:29:51.6; `Retrying` until the user unplugged (logcat removal at 17:30:03.0) | – | 17.2 s |
| A22 | 17:30:06.9 | b18 | **connected** at 17:30:10.84 | 3.9 s | after a re-plug: 04e8:6860 → AOA switch → 18d1:2d00 at +1.6 s |

Earlier in the day the DiAuto journal shows the same two failure types:

- **`Cannot open device`:** 15:45:20, 15:45:22, 15:46:48, 15:51:41 and 16:01:42.
- **Stale accessory:** 15:49:55, which hung until 15:50:59 (**64 s**), and 16:01:56.

At 13:34, a first plug raised **two** permission requests: one for the MTP device (7.0 s until the switch) and one for the re-enumerated accessory (3.0 s).

### 1d. Android Auto wireless (Wi-Fi Direct)

| # | Start | Build | Result | TTFF | Where the time went |
|---|---|---|---|---|---|
| W1 | 16:08:08.5 | b3 | cancelled by the driver after 3.3 s | – | |
| W2 | 16:08:14.4 | b3 | cancelled by the driver after 3.4 s | – | |
| W3 | 16:35:42.4 | b8 | **connected** at 16:35:53.60, right after CarPlay | 11.2 s | CarPlay stop 2.18 s; group +1.0 s; the poke waited for credentials until +3.3 s; poke connect 1.8 s; Type 3 at +6.6 s; TCP +7.0 s; frame +9.0 s |
| W4 | 16:39:08.7 | b9 | **connected** at 16:39:19.25, after CarPlay | 10.6 s | CarPlay stop 2.20 s; same shape as W3 |
| W5 | 17:09:40.7 | b13 | cancelled by the driver after 1.7 s | – | |
| W6 | 17:21:35.7 | b16 | **connected** at 17:21:40.91, **from idle** | **5.2 s** | group-created +624 ms; poke +860 ms; poke-connected +1673 ms; phone RFCOMM +1762 ms; start request +2089 ms; **phone joined +3108 ms**; Type 3 +3127 ms; TCP +3486 ms; frame +4937 ms |
| W7 | 17:22:20.6 | b16 | **connected** at 17:22:35.09, **after CarPlay** | **14.4 s** | **CarPlay stop 4.06 s**; group-created +594 ms; poke-connected +1706 ms; phone RFCOMM +1800 ms; start request +2125 ms; Type 3 +3169 ms; **phone joined +8492 ms (5.3 s after the credentials)**; TCP +8794 ms; frame +10255 ms |

Same Wi-Fi does not appear in the retained logs. The only trace is DiAuto arming `SoftApCredentials … 'wlan0'` at 13:34, during a USB session.

### 1e. Totals in the CarLink window (16:02 to 17:31)

| Engine and path | Attempts | Connected | Failed | Hung or stuck ≥ 10 s | Cancelled or replaced |
|---|---|---|---|---|---|
| CarPlay USB | 16 | 5 (all after b12) | 3 (b11) | 8 (b2–b10, "never connected") | |
| CarPlay wireless | 10 | 6 | | 2 (C1, C8) | 2 |
| AA USB | 22 | 10 | 8 (6 `Cannot open device`; 2 stale-accessory timeouts after 65–73 s) | 1 (A21, stale, until unplug) | 3 (A2, A10 replaced by the driver; A20 killed by an install) |
| AA wireless | 7 | 4 | | | 3 |

The median TTFF of successful attempts was:

| Engine and path | Median TTFF | Range |
|---|---|---|
| AA USB | 3.5 s | 3.1–7.4 s |
| CarPlay USB | 4.4 s | 3.7–5.5 s |
| AA wireless | 10.9 s | 5.2–14.4 s |
| CarPlay wireless | 18.5 s | 12.8–27.7 s |

---

## 2. Killers, ranked by impact

Impact is the seconds lost today multiplied by how often the killer happened, weighted up when it hangs or breaks a session.

| Rank | Killer | Times seen today | Seconds lost (approx.) | Breaks or hangs? | Status |
|---|---|---|---|---|---|
| K1 | Wired CarPlay never reports Connected ("stuck Picture") | 8 attempts (b2–b10) | about 1 030 s of stuck UI | hangs the UI | **fixed in b12, proven** |
| K2 | Stale AA accessory mode | 8 (3 full hangs) | about 235 s, and a re-plug every time | **hangs** for 60–72 s | **open**: the stale probe in the code cannot fire |
| K3 | Bluetooth RFCOMM paging to the iPhone | every wireless CarPlay start; 3 failure loops | about 49 s of loops, plus about 5 s per start | slows; the loops break the attempt | open (partly improved in b16) |
| K4 | Progress UI does not tell the truth (Retrying shown as "Phone answered", CarPlay sits on "Identified", "not answering" never shown) | every wireless CarPlay start and every retry | (makes K2, K3 and K8 look like hangs) | the user sees a stall | open |
| K5 | Home auto-starts wireless CarPlay right after a USB unplug | 3 (C6, C7, C8) | about 49 s (overlaps K3) | breaks: causes the K3 loops and a prompt | **fixed in b14–b16, proven** |
| K6 | S25 re-enumeration / `Cannot open device` | 11 failures in the day, 6 inside the window | about 40 s plus re-plugs | breaks: a re-plug is needed | **fixed in b14–b16, proven 4/4** |
| K7 | CarPlay stop when switching engines | 6 switches | 16.7 s (4.06 s ×2 on b16) | slows | open; **regressed in b16** |
| K8 | AirPlay handshake gap (pair-verify and SETUP) | 1 of 2 timed runs | 9 s | slows; no visible progress | open; cause unproven |
| K9 | Android Auto after CarPlay: Wi-Fi Direct group hand-over and phone re-join | 3 | about 21 s compared with starting from idle | slows | open |
| K10 | DiAuto arms wireless during USB sessions (P2P group plus HFP poke) | every AA USB start since b16 | – | risk of a race: it pulled the phone into the wireless flow at 17:29:45 | open |
| K11 | DiPlay start overhead (500 ms display debounce plus activity launch) | every CarPlay start (26) | about 0.5 s + 0.3–1.0 s each, so 20–35 s | slows | open |
| K12 | b11 wired CarPlay retry loops | 3 | 110 s | breaks | gone from b12 on (5/5 connect); cause not in the retained logs |
| K13 | Hidden DiPlay restarts during a wired session (b4/b5) | 4 (inference) | unknown | probable brief drops | not reproduced after b12; unproven |
| K14 | Same-engine USB plug during a wireless attempt asks the driver | 2 | 9.6 s | slows | open |
| K15 | Smaller items: `:aa` cold start, unplug detection while `Retrying`, permission prompts, re-plug needed after Disconnect | various | about 10 s | – | open |
| – | DHCP / first TCP | measured | at most 2 s per start | **not a killer today** | – |

### K1. Wired CarPlay never reports "connected": the stuck "Picture" step (fixed)

**Evidence**

- From 16:03 to 16:49 (b2–b11), all 11 CarPlay USB starts have **no** `CARPLAY connected on USB` line, even though sessions ran for up to 377 s.
  - U7 is the clearest: `16:15:11.343 CARPLAY starting on USB` … `16:21:28.779 USB phone for CARPLAY unplugged`.
  - While stuck, the Switcher shows the CarPlay host in front and CarLink still connecting: `16:45:27.396 phase=Progress projections=[CARPLAY]` … until `16:46:54`.
- Wireless CarPlay in the same builds did report "connected" (16:26:09, 16:35:24, 16:38:15, 16:43:47).
- From b12 on, every wired start reports "connected": 16:59:08.768, 16:59:24.584, 17:08:22.129, 17:19:19.500 and 17:19:55.219. Each matches DiPlay's `WIRED_TIMING first-frame`.

**Root cause in code**

- Wired CarPlay maps `RunningControl` to `STARTING_PROJECTION`, which is step 4, "Picture" (`CarPlaySessionEvents.kt:67`, `LinkStateMapping.kt:37`).
- The session becomes Connected only on `HostState.FirstFrame` (`CarPlaySessionEvents.kt:221-227`).
- The wired listener in DiPlay never forwarded `onVideoFrameRendered`. The fix is commented at `vendor/diplay/.../orchestration/CarPlayController.kt:402-417`: "the wired path used this listener without forwarding rendered frames, so the host never heard of the first wired frame".

**Fix status:** landed in b12. The logs prove it: 5 of 5 wired starts connected afterwards, against 0 of 11 before.

**Remaining risk:** "Picture" is still a dead end if the first frame never comes. Nothing times out on `STARTING_PROJECTION`. Fix plan item 2 adds a watchdog for this.

### K2. Stale Android Auto accessory mode (open; the biggest remaining hang)

**Evidence.** This is the 17:28 case. The other occurrences have the same shape: 16:52:58 → 16:54:10.98 (72.6 s), 15:49:55 → 15:50:59 (64 s), and 16:05:17, 16:59:18, 17:29:43 and 17:29:49.

```
17:26:33.355 [CarLink] ANDROID_AUTO connected on USB
~17:28:20    b17 install kills the session; the S25 stays at 18d1:2d00
17:28:20.491 [CarLink] start ANDROID_AUTO USB known=false              (app start found 18d1:2d00)
17:28:21.810 DiAuto checkAlreadyConnectedUsb | Found device already in accessory mode … 18D1 PID: 2D00
17:28:21.883 DiAuto AapService.onConnected                              (CommManager busy at once)
17:28:22.953 DiAuto Handshake: Drained 549115 bytes of stale USB data before version request
17:28:23.242 DiAuto Handshake failed with exception                     (SSL; 1.4 s after open)
17:28:23.260 DiAuto onHandshakeFailed … (failure #1)
17:28:25.527 [Switcher] phase=Retrying
17:29:25.989 [CarLink] ANDROID_AUTO failed: PHONE_DID_NOT_ANSWER (… unlock it and accept the prompt)
```

The engine knew the session was dead 2.8 s after start. CarLink then waited another 60 s, showed the wrong advice ("unlock it and accept the prompt"), and the user still had to re-plug (A22).

**Root cause** (from the code)

1. **How the phone gets stuck.** The S25 stays in AOA accessory mode after the host side of a session dies without a USB reset: a process death, a reinstall, or a driver Disconnect. The next open reads the old session's stream: "Drained 549115 bytes of stale USB data". The SSL handshake then fails.
2. **CarLink's probe cannot see it.** `AaCommandHandler.kt:114-126` only checks that `isBusy()` becomes true within 10 s (`STALE_ACCESSORY_MILLIS`). DiAuto turns busy as soon as it opens the accessory, 0.07–0.5 s after start (`onConnected` at 17:28:21.883). So `KIND_AOA_STALE` never fires for a phone that opens and then fails the handshake. The comment at `:120-121` names this exact case ("71 s on the tablet").
3. **The tracker turns the failure into a 60 s wait.**
   - The failed handshake reaches `AaSessionTracker` as a `Connecting` state followed by `Disconnected` (`:87-99`).
   - After the 2 s end debounce, `endSession` counts it as a USB "loss" with `losses < maxLosses` (`:239-255`) and goes back to waiting with `Retrying`.
   - `onTick` then fails only after `usbTimeoutMillis = 60 000` (`:38`, `:170-173`), with the "unlock the phone" message.
4. **DiAuto's own recovery never runs.** `AapService.onHandshakeFailed` (`vendor/diauto/.../aap/AapService.kt:2182-2212`) re-sends the AOA start to force a re-enumeration, but only on failure number 2 (`MAX_STALE_ACCESSORY_RETRIES = 1`, `:3433`). Nothing retries the handshake, so failure number 2 never happens.

**Fix status:** the probe is in the code but cannot fire for this failure. b17 and b18 (17:28 and 17:29) show the same 60 s hang, so the logs show it is not fixed.

**Proposed fix (plan item 1)**

- In USB sessions, treat "handshake failed on a device that was already in accessory mode" as stale, immediately.
  - Lower `MAX_STALE_ACCESSORY_RETRIES` to 0, or have `AaCommandHandler` call `switchToAccessory(device)` on an `18d1:2d00` device it did not switch itself. This is the same `connectAndSwitch` DiAuto already uses.
  - The phone re-enumerates as `04e8:6860`, then as `18d1:2d00`, and the normal path takes over.
- In `AaSessionTracker.endSession`, a USB session that ends **before any frame** and is followed by no new attach within about 5 s should fail at once with `KIND_AOA_STALE` ("unplug and plug in again"). It should not return to the 60 s wait.

**Expected effect:** 60–72 s becomes about 3–5 s, with automatic recovery. Where the phone ignores the re-switch, the "re-plug" message appears within about 5 s.

**Risk:** low to medium. Some phones do not re-enumerate on an AOA start while in accessory mode; the message is the fallback.

**Verify on the device:** connect AA over USB, kill CarLink from Recents or reinstall, reopen it with the phone still plugged in, and expect a picture or the re-plug message within 5 s.

### K3. Bluetooth RFCOMM paging time to the iPhone (open)

**Evidence**

- **Successful dials:**
  - `17:21:59.439 WIRELESS_TIMING rfcomm-connected start+6681ms … dialMs=6650 waitedMs=6331`
  - `17:25:42.331 … rfcomm-connected start+5868ms … dialMs=5846 waitedMs=5571`

  RFCOMM is 60–65% of the time from controller start to `start-session`. `waitedMs` shows that everything else (P2P group, listener, Bonjour) was ready after about 0.3 s and then waited for Bluetooth.
- **Failed dials:** each takes exactly the Bluetooth page timeout:
  - `17:08:13.596 Bluetooth connect failed elapsedMs=5153 failureClass=IOException`
  - 17:09:14.694 (5154), 17:09:22.377 (5151) and 17:09:30.019 (5149)
- **Each failure costs a full cycle:**
  1. `Failed … retrying in 1500ms` (`CarPlayHostActivity.kt:4654`);
  2. a Bonjour teardown of `elapsedMs=2003` (K7);
  3. a restart with `teardownWaitCompleted … elapsedMs=545–620`.

  The next dial starts about 2.5 s after the failure, so each cycle is about 7.7 s. C8 ran 4 cycles (28.6 s) before the driver gave up.

**Root cause**

- **Slow successful dials (inference).** `createRfcommSocketToServiceRecord().connect()` must page the iPhone, run SDP, then open RFCOMM. Dials of 5.8–6.7 s mean the page itself took most of the 5.12 s page window. The likely reasons are that the iPhone had no ACL link to the tablet (the tablet is not a hands-free car kit, DIPLAY.md §4) and that the iPhone page-scans slowly.
- **Failed dials (inference).** The iPhone was not page-reachable at 17:08–17:09: it had just been on wired CarPlay or was not near. The exact 5.15 s value is the controller's page timeout, not DiPlay's 15 s RFCOMM timeout (`CarPlayController.kt:2906`).
- **The loops.** DiPlay retries without limit, and CarLink accepts up to 5 "unbounded" retries (`CarPlaySessionEvents.kt:158`, `:244-245`; `WirelessStartupPolicy.kt:10`).
- **Dial start.** Before b16 the dial started only after hotspot + listener + Bonjour, about 0.44 s in. In b16 it starts at `start+21–28ms`, in parallel. That saved about 0.4 s, which the logs confirm.

**Proposed fixes**

- **Dial earlier (plan item 7).** Start the dial when CarLink decides on wireless CarPlay, in parallel with the AA stop (1.5 s) and the host launch (1–2 s). This hides 1.5–2.5 s of the page time. Risk: medium, because the controller must adopt an already-open socket.
- **Cap hopeless loops (plan item 3).** After 2 consecutive page timeouts (elapsed ≈ 5.1–5.2 s with `IOException` while `ConnectingBluetooth`), fail as `BLUETOOTH`, with "iPhone not reachable over Bluetooth: unlock it, turn on Bluetooth, or plug in a cable". This saves up to about 23 s per hopeless attempt. Risk: low.

### K4. The progress UI does not tell the truth (open)

**Evidence and root causes**

1. **`Retrying` is always drawn as step 2, "Phone answered" ("Identified" in older builds).** `LinkStateMapping.kt:21-22` hard-codes step `2` with `detail = "retrying"` and drops the reason.
   - In C8, the dial alternated `Progress` (step 1, "Reaching phone") and `Retrying` (step 2) every 7.7 s: 17:09:08.24 / 14.71 / 16.87 / 22.40 / 24.50 / 30.04 / 32.21. That told the driver the iPhone **had** answered when every dial had failed.
   - In K2 it shows step 2 · retrying for 60 s.
2. **"Phone not answering" can never appear during a retry loop.** `SessionManager.kt:380-384` cancels the 10 s `notAnswering` timer on every `Progress` and re-arms it. `CarPlaySessionTracker.onRetrying` clears `lastProgress` (`CarPlaySessionEvents.kt:236`), so each cycle re-emits `WAITING_FOR_PHONE`. A 7.7 s cycle never reaches 10 s. `has not answered` appears 0 times in carlink.log.
3. **Wireless CarPlay never shows "Linking" or "Picture" before the picture.**
   - `RunningWireless` maps to `HANDSHAKE` (step 2) (`CarPlaySessionEvents.kt:63`).
   - Nothing maps to `JOINING_WIFI`.
   - `WirelessActive` (step 4) arrives **after** the first frame: 17:22:12.954 against `first-frame` at 17:22:12.735.
   - So the UI holds step 2 from RFCOMM up to the picture: **13.3 s** in C9 (17:21:59.4 → 17:22:12.7) and 4.4 s in C10. This is the "Identified stall".
4. **USB Android Auto has no "not answering" hint.** The timer is wireless-only (`SessionManager.kt:382`), so a locked phone or a stale accessory shows a busy bar for 60 s.

**Proposed fix (plan item 2)**

- Map `Retrying` to the step the failure happened in, and carry the reason ("iPhone did not answer over Bluetooth", "Phone did not open Android Auto").
- Arm `notAnswering` once per attempt and cancel it only on real progress past `WAITING_FOR_PHONE`. Also arm it for USB.
- Publish two extra DiPlay statuses, or reuse the `WirelessTimeline` milestones:
  - `wifi-config` (0x5703 sent) → step 3, "Linking";
  - `tcp-accepted` → step 4, "Picture".
- Add a step-4 watchdog: 20 s on "Picture" without a frame → `Retrying` with a reason.

Risk: low (UI and mapping only).

### K5. Home auto-starts wireless CarPlay right after a USB unplug (fixed)

**Evidence**

```
16:58:52.902 USB phone for ANDROID_AUTO unplugged … 16:58:53.266 Home shown: connecting the last phone (CARPLAY)
17:08:06.116 … unplugged                         …  17:08:06.185 Home shown: connecting the last phone (CARPLAY)
17:09:07.840 USB phone for CARPLAY unplugged      …  17:09:07.938 Home shown: connecting the last phone (CARPLAY)
```

Each of these led into K3 loops (C6–C8), and two of them led to a "switch?" prompt (K14).

**Root cause:** `onHomeShown` treated Home reappearing after an unplug as "the car started".

**Fix status:** fixed. `usbGoneCheck` now sets `autoConnectPaused` (`SessionManager.kt:168-170`), and `onHomeShown` skips for 60 s after a session ends (`:278-279`). The logs prove it: the unplugs at 17:19:28, 17:20:02, 17:21:09 and 17:30:06 (b16/b18) are not followed by an auto-start.

### K6. S25 re-enumeration on plug-in / `Cannot open device` (fixed)

**Evidence**

- On every fresh plug the S25 attaches 2–3 times within about 1.5 s: 16:49:02.84 / 03.51 / 05.04, 16:57:18.45 / 19.01 and 17:07:39.94 / 40.37 / 41.74.
- The AOA switch then fails with DiAuto `UsbAccessoryMode.connectAndSwitch | Cannot open device` (16:49:08.283, 16:57:22.8, 17:07:44.342). CarLink reports "the phone did not switch to Android Auto over USB".
- "Try again" failed in 0.1 s three times, at 17:07:47.8, 17:07:51.9 and 17:07:53.5. `SessionManager.retry` re-sends the same request, including the stale `UsbDevice`, so the retry reused it.

**Root cause:** the switch and the permission act on a `UsbDevice` handle that the re-enumeration invalidated. Android permission is per device instance, so this is a permission loss on the new instance (inference).

**Fix status:** landed in b14–b16. `AaCommandHandler.settle()` waits for 600 ms of stability (2 s maximum) and re-resolves through `currentDevice()` (`AaCommandHandler.kt:135-155`, `:171-184`). The logs prove it: A16, A17, A18 and A22 all re-enumerated (17:19:36.67, 17:21:00.38, 17:26:25.0 / .4, 17:30:07.19) and all connected.

**Cost of the fix:** AA USB TTFF went from about 3.1–3.5 s to 3.8–4.3 s (+0.6 s settle). That is acceptable; it could start the settle timer from the first attach rather than from command arrival.

### K7. CarPlay stop time when switching engines (open; regressed in b16)

**Evidence.** CarLink `start <next>` → `<next> settings written` (CarLink starts the next engine only after the stop completes):

| Switch | Build | CarPlay stop |
|---|---|---|
| 16:35:42 CarPlay wireless → AA | b8 | 2.18 s |
| 16:39:08 CarPlay wireless → AA | b9 | 2.20 s |
| 16:59:03 CarPlay wireless (dialing) → CarPlay USB | b12 | 2.10 s |
| 17:08:16 CarPlay wireless (dialing) → CarPlay USB | b13 | 2.11 s |
| **17:22:20 CarPlay wireless → AA** | **b16** | **4.06 s** |
| **17:26:26 CarPlay wireless → AA USB** | **b16** | **4.07 s** |

For comparison, Android Auto stops take 1.54–1.56 s for wireless and 0.52 s for USB.

**Root cause** (strong inference; DiPlay's log closes at `Activity destroyed`, before the teardown finishes, so the stop itself is not logged)

1. **Bonjour close takes about 2 s per JmDNS instance.** Every logged wireless teardown shows `teardown resource=Bonjour completed=true elapsedMs=2003–2009` (17:08:15.614, 17:09:16.703, 17:09:24.392, 17:09:32.029). `CarPlayBonjour.close()` closes each JmDNS instance in turn and then joins the worker for up to `JOIN_TIMEOUT_MILLIS = 2_000` (`CarPlayBonjour.kt:329-357`, `:638`, `:657`).
2. **b16 publishes two families.** It shows `mdnsFamilies=IPv6,IPv4` (two JmDNS instances); b13 shows `mdnsFamilies=IPv4`.
3. **The close runs before the hotspot is removed.** `closeWirelessStack` closes Bonjour before the P2P hotspot (`CarPlayController.kt:2476-2482`).
4. **The host caps the whole teardown at 4 s.** `oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)` uses `CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000` (`CarPlayHostActivity.kt:4883`, `:5240`).

4.06 s and 4.07 s are exactly that cap plus about 60 ms. The b16 teardown most likely **timed out** (`clean=false`) rather than finished.

**Proposed fix (plan item 4)**

- Remove the P2P hotspot first.
- Close the JmDNS instances on a detached daemon thread, in parallel, without joining.
- Log `teardown end` before `Activity destroyed` closes the log.

**Expected effect:** CarPlay stop drops from 2.1–4.1 s to about 0.3–0.5 s. Each K3 retry cycle also gets about 0.6 s shorter.

**Risk:** low to medium. Goodbye packets may arrive late, and a quick restart rebinds mDNS on 5353 (JmDNS uses `SO_REUSEADDR`). Verify with 5 switches each way: the stop should take under 1 s, and the next CarPlay session should still be discovered.

### K8. AirPlay handshake gaps (open; cause unproven)

**Evidence.** Compare C9 and C10:

```
C9  17:22:02.381 airplay TCP accepted family=IPv6   (guess=true firstTcpMs=10000)
    17:22:07.444 airplay control encryption enabled          +5.06 s
    17:22:11.403 airplay SETUP keys=[deviceID, …]            +3.96 s
    17:22:11.848 WIRELESS_TIMING session-active … session+11432ms
C10 17:25:44.461 airplay TCP accepted family=IPv6   (guess=false firstTcpMs=30000)
    17:25:44.921 control encryption enabled                  +0.46 s
    17:25:44.961 SETUP                                       +0.04 s
    17:25:45.984 airplay event connection accepted            +1.02 s
```

**Root cause:** unknown. These gaps are the time the iPhone takes between its requests (pair-setup and pair-verify, fp-setup / auth-setup, SETUP). DiPlay does not log when each request arrives, so the delay cannot be attributed. The one difference the logs show is that C9 was the first run on IPv6, with a guessed address family: `guess=true`, then `wireless address family remembered=IPv6 proof=first_tcp` at 17:22:02.383. Candidate causes, all inference:

- the iPhone resolved the receiver's `_airplay._tcp` TXT over mDNS on the family it had just been told about;
- the iPhone was still finishing its Wi-Fi bring-up.

`CarPlayAdapter.install` (`CarPlayAdapter.kt:143-149`) now forgets the IPv6 guess so that IPv4 is used again. That build is not in the logs.

**Proposed fix (plan item 6):** first measure it. Add `WIRELESS_TIMING` marks for every RTSP/HTTP request (`pair-setup`, `pair-verify` 1 and 2, `fp-setup`, `auth-setup`, `GET /info`, each `SETUP`) and for the mDNS queries answered. Decide after 5 runs on each family. Risk: none (logging only).

### K9. Android Auto after CarPlay: 10 s against 5 s from idle (open)

**Evidence.** Compare W6 (from idle) and W7 (after CarPlay). The engine-side timeline is identical up to the credentials (Type 3 at +3127 ms against +3169 ms). Then:

| Milestone | W6, idle | W7, after CarPlay |
|---|---|---|
| phone joined the group | +3108 ms (before Type 3; the phone had the saved network) | **+8492 ms (5.3 s after Type 3)** |
| first frame | +4937 ms | +10255 ms |

The other 4.06 s of W7 is the CarPlay stop (K7). In b8/b9 (W3/W4) the join was fast, but the engine was 3–4 s slower before the poke (fixed in b16, where the poke goes at +0.8 s instead of +3.3 s).

**Root cause** (inference; there is one timed sample and logcat does not cover 17:22):

- The S25 left `DIRECT-Y1-CarLink` 34 s earlier, when AA stopped at 17:21:50.
- The same SSID came back on a new group, very likely with a new GO address, since Android randomizes the P2P interface per group.
- The phone's network selection either treated it as a new BSSID or deprioritized a network it had just left. It associated only after a scan, about 5 s later.

The radio swap from CarPlay's `DIRECT-dp…` group to AA's group happens on the same channel (5785 MHz both), so a channel change is not the cause.

**Proposed fix**

- **Measure first.** Log the GO BSSID per group in DiAuto (`WIRELESS_TIMING group-info bssid=<masked hash>`) and capture logcat (`wpa_supplicant` `AP-STA-CONNECTED`, `p2p0.DHCP`) across 3 switches.
- **If the BSSID changes**, keep a persistent group (`WifiP2pConfig` with the kept network and a fixed `deviceAddress`, or the `persistent` network ID) so the phone sees the same BSSID.
- **Independent of that**, the fixed 1 s delay before Type 3 (`delay(1000)` at `NativeAaHandshakeManager.kt:1046-1048`) costs 1 s on every AA wireless start. In W6 the phone had already joined before Type 3 was sent. Try 250 ms and verify with 10 starts.

**Risk:** medium; the delay was added as a "[FIX]" for slow phones.

### K10. DiAuto arms wireless during USB sessions (open)

**Evidence**

- Every b16+ USB start also creates the P2P group and pokes the phone over HFP. For A16: `17:19:37.207 group-create`, `17:19:37.984 poke-start profile=HFP-AG`, `17:19:38.051 USB accessory device attached`, then `17:19:51.327 phone-rfcomm` (the phone opened the AA Bluetooth channel during a USB session).
- In A20, after the stale USB handshake failed at 17:29:44.707:
  - `17:29:45.358 poke-start`
  - `17:29:46.945 Connection accepted from Ayush's S25 Ultra`
  - `17:29:47.305 wifi-start-request`

  The engine was pulling the plugged-in phone into wireless Android Auto.
- The group also shares airtime with the station network: "station 5785 MHz, group 5785 MHz: same channel".

**Root cause:** `AapService.onCreate` always runs `initWifiMode`, and the "USB/other session already active. Skipping auto-poke" guard only applies once the USB session has been accepted. `AaSessionTracker` ignores these events for USB (`AaSessionTracker.kt:111-112`, "the engine also arms wireless during USB sessions").

**Proposed fix (plan item 8):** add a host flag such as `HostIntegration.usbOnlySession`, set for `COMMAND_START_USB`, that skips `initWifiMode` and the pokes. **Expected effect:** no contention, and about 0.5 s less radio and Bluetooth work per USB start. **Risk:** medium (vendor start path). Verify that a USB start produces no `group-create` or `poke-start` lines.

### K11. DiPlay start overhead (open)

**Evidence.** In all 8 DiPlay runs, `Texture surface created` → `Display detected` takes **501–511 ms**:

- 17:19:16.244 → 16.750
- 17:21:52.232 → 52.737
- 17:25:35.937 → 36.437

The controller starts 10–75 ms later. CarLink `starting on …` → controller start is 1.0–1.9 s in total (activity launch 0.2–0.7 s, host init 0.2–0.3 s, surface 0.15–0.3 s, then the debounce).

**Root cause:** `scheduleDisplaySize` debounces even the **first** size by `DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500` (`CarPlayHostActivity.kt:4393-4394`, `:5233`). The debounce is for later size changes; the first one has nothing to settle.

**Proposed fix (plan item 5):** apply the size at once when `activeDisplaySize == null`. This saves 0.5 s on every CarPlay start, wired and wireless. **Risk:** very low.

### K12. b11 wired CarPlay retry loops (gone from b12)

**Evidence:** U9–U11 show `Retrying` at 16:47:45, 16:48:30, 16:48:40 and 16:48:57. Each retry is followed by an iPhone re-enumeration pair, which is DiPlay restarting the wired configuration: 16:48:32.891 / 33.753 after 16:48:30.21. There are no `connected` lines.

**Root cause:** not recoverable. DiPlay keeps only 8 runs, and the oldest kept is 16:59:21. From b12 on, 5 of 5 wired starts connected. Treat as fixed, unproven cause.

**Note:** keep more DiPlay history (plan item 6).

### K13. Hidden DiPlay restarts during a wired session (b4/b5; inference)

**Evidence:** during U6 and U7, iPhone re-attach pairs appeared at 16:13:35.7 / 36.6, 16:13:47.8 / 48.4, 16:18:57.5 / 58.4 and 16:19:09.5 / 10.2. CarLink logged each as "left to the engine". K12 shows that a pair is what DiPlay's wired restart looks like.

**Interpretation (inference):** two drop-and-reconnect cycles, 12 s apart, in each session. The CarLink UI could not show them (K1).

**Status:** no pairs in the shorter b13 and b16 wired sessions (24–45 s long). Not proven either way. Needs a session of 10 minutes or more on the current build.

### K14. Plugging the same engine's phone during a wireless attempt asks the driver (open)

**Evidence**

```
16:59:00.349 USB CARPLAY phone while a CARPLAY session runs; asking the driver   → 16:59:03.232 start CARPLAY USB  (2.9 s)
17:08:14.335 USB CARPLAY phone while a CARPLAY session runs; asking the driver   → 17:08:16.811 start CARPLAY USB  (2.5 s)
```

Each was followed by a 2.1 s wireless stop.

**Root cause:** `SessionManager.onUsbPhone` asks whenever any session is `Active` (`SessionManager.kt:204-207`). It does not consider that the running session is the **same phone and engine**, still **not connected**.

**Proposed fix:** if the running engine matches and the phase is not `Connected`, call `start(request)` directly; plugging in is deliberate, which the comment at `:187` already says. **Expected effect:** saves 2.5–3 s and one tap. **Risk:** low.

### K15. Smaller items

- **`:aa` cold start on USB at app start.** `ANDROID_AUTO settings written in 752 / 823 / 860 / 915 / 776 ms` when the engine process was not warm (16:05:17, 16:52:59, 16:59:19, 17:28:21, 17:29:50). Warm writes take 7–50 ms.
- **Unplug detection while `Retrying`.** At 17:19:57.9 DiPlay saw the iPhone unplug and went into `Retrying`. CarLink then used the 3.5 s not-connected grace instead of the 1.2 s connected grace (`SessionManager.kt:238`), and ended the session at 17:20:02.4, 4.5 s later. Fix: use the short grace once the session has ever been connected.
- **DiPlay keeps retrying after an unplug.** It logs `AirPlay session ended; retrying in 2000ms` (17:09:06.682, 17:19:27.023, 17:19:57.940) and showed `WaitingForIphone` until CarLink stopped it.
- **USB permission prompts.** Only in the 13:34 first-use case: two requests, one for MTP and one for the accessory. DiPlay reports `USB permission already granted` in every wired run, and no `UsbPermissionActivity` appears in the logcat window. Not a killer today.
- **A re-plug is needed after "Disconnect" on a USB session.** The re-enumeration after the stop is ignored by design (`USB CARPLAY attach right after the driver stopped; not restarting` at 16:05:44.9, 16:08:21.6 and 16:47:51.7), and the driver re-plugged 7.7–12.5 s later. Inference: Home offers no "Connect over USB" for a phone that is still plugged in.

### DHCP and first TCP: not a killer today

- **CarPlay:** `start-session` → peer associated 763 / 771 ms; → first TCP 1958 / 1123 ms.
- **Android Auto:** phone joined → TCP 378 ms (W6) and 302 ms (W7).

No run came near `FIRST_TCP` (10 s or 30 s). Logcat holds no DHCP lines for any CarPlay or AA wireless run; the one `qca6750 DHCP-R` at 17:30:06.6 is an unrelated broadcast. This topic needs logcat captured during a wireless run before it can be ruled out entirely.

---

## 3. Hangs: no progress for more than 10 s without the UI saying why

| # | When | Build | Duration | What the user saw | Real cause |
|---|---|---|---|---|---|
| H1 | U1–U8, 16:03–16:46 | b2–b10 | 15–377 s each (U7: 377 s) | Connecting, last step ("Picture"). From b8 on the Switcher pill was hidden (`show=false`, `phase=Progress`) while the CarPlay host was in front. | The wired first frame was never forwarded (K1). Fixed in b12. |
| H2 | C1, 16:24:49 → 16:25:46 | b6 | 57 s, cancelled | Connecting (no Switcher or DiPlay log for that build) | Unknown: the logs rotated out. A retry 2 s later connected in 20.6 s. |
| H3 | A6, 16:53:10.5 → 16:54:10.99 | b12 | 60.5 s after a 12 s lead-in | Step 2, "Phone answered · retrying", then "the phone did not start Android Auto over USB (unlock it and accept the prompt)" | Stale accessory; the handshake had failed at 16:53:07.687 (K2). |
| H4 | A19, 17:28:25.5 → 17:29:25.99 | b17 | 60.5 s | same as H3 | same; handshake failed at 17:28:23.242 |
| H5 | A21, 17:29:54.4 → 17:30:03 (unplug) | b18 | about 12 s, until the user gave up | Step 2 · retrying | same; handshake failed at 17:29:51.592 |
| H6 | 15:49:55 → 15:50:59 (DiAuto only) | pre-16:00 | 64 s | not logged | same; `Handshake failed` at 15:49:56.543 |
| H7 | C8, 17:09:07.9 → 17:09:36.5 | b13 | 28.6 s, cancelled | Flipping between step 1 and step 2 ("retrying") every 7.7 s; never "phone not answering" | 4 RFCOMM page timeouts (K3), shown as progress (K4) |
| H8 | C7, 17:08:06.2 → 17:08:16.8 | b13 | 10.6 s | same pattern | RFCOMM page timeout, plus the "switch?" prompt (K14) |
| H9 | C9, 17:21:59.4 → 17:22:12.7 | b16 | 13.3 s on one step, of which 9.0 s had no protocol progress | Step 2, "Phone answered / Identified" | Wi-Fi config, start-session, association, TCP (2 s), then the AirPlay gap of 5.1 + 4.0 s (K8); no step mapped (K4) |
| H10 | U9–U11, 16:46–16:49 | b11 | 54 / 40 / 16 s | Progress ↔ Retrying, no reason | DiPlay wired retries; cause lost (K12) |

**Near-hangs, under 10 s each:**

- the 4.06 s CarPlay stop shown as "Starting Android Auto", step 1 (W7, A18);
- the 5.3 s AA phone join on "Linking" (W7);
- the 5.8–6.7 s RFCOMM dial on "Reaching phone" (C9, C10).

---

## 4. Prioritized fix plan: top 8 by benefit and risk

Each item is small enough to build, install and verify on the tablet in one sitting.

| # | Fix | Where | Expected effect | Risk | Verify on the device |
|---|---|---|---|---|---|
| 1 | **Stale accessory: recover or fail within 5 s.** Re-send the AOA start on the first handshake failure of a pre-existing `18d1:2d00` (`MAX_STALE_ACCESSORY_RETRIES` 1 → 0, or a CarLink `switchToAccessory` on an accessory it did not create). In the tracker, a USB session that ends before any frame with no re-attach within about 5 s fails with `KIND_AOA_STALE` ("unplug and plug in again"), never the 60 s "unlock" wait. | `vendor/diauto/.../AapService.kt:2192,3433`; `AaCommandHandler.kt:114-126`; `AaSessionTracker.kt:239-255,170-173` | Removes H3–H6: 60–72 s becomes about 3–5 s, and stops the misleading advice | Low–medium | AA USB connected → force-stop or reinstall CarLink with the phone plugged in → reopen: a picture or the re-plug message within 5 s, 3 out of 3 |
| 2 | **Truthful progress.** Show `Retrying` at its real step, with the reason. Arm `notAnswering` once per attempt, for USB too. Map CarPlay `wifi-config` to "Linking" and `tcp-accepted` to "Picture". Add a 20 s "Picture without a frame" watchdog. | `LinkStateMapping.kt:21-22`; `SessionManager.kt:380-384`; `CarPlaySessionEvents.kt:60-68,236`; a DiPlay status hook next to the `WirelessTimeline` marks | Ends the "Identified stall" (H9) and "phone answered" during a dead dial (H7); any future K1-type bug becomes a visible retry | Low | Wireless CarPlay with the iPhone's Bluetooth off: "iPhone not answering" within 10 s. Normal start: the steps advance in about 6 s, 7 s, 9 s and 10 s |
| 3 | **Cap hopeless RFCOMM loops.** Two consecutive page timeouts (about 5.1 s `IOException` in `ConnectingBluetooth`) → `Failed(BLUETOOTH)` with "iPhone not reachable over Bluetooth: unlock it, check Bluetooth, or plug in a cable". | `CarPlaySessionEvents.kt:158,234-250` (a per-reason cap) | C8 goes from 28.6 s (and up to about 38 s) to about 13 s with a clear next action | Low | iPhone Bluetooth off → fails at about 13 s with that message. iPhone on → unchanged |
| 4 | **Non-blocking CarPlay teardown.** Remove the P2P hotspot before Bonjour. Close the JmDNS instances in parallel on a daemon thread, without joining. Log the teardown end before the log closes. | `vendor/diplay/.../CarPlayBonjour.kt:329-357`; `CarPlayController.kt:2476-2482` | CarPlay stop drops from 2.1–4.1 s to 0.5 s or less (W7 −3.5 s, A18 −3.5 s); each K3 cycle −0.6 s | Low–medium | 5 CarPlay → AA switches: the `start` → `settings written` gap is under 1 s; CarPlay is still discovered on the next start |
| 5 | **Drop DiPlay's first-size debounce.** Apply the first display size at once. | `CarPlayHostActivity.kt:4380-4394` (`activeDisplaySize == null` → delay 0) | −0.5 s on every CarPlay start (26 today) | Very low | `Texture surface created` → `Display detected` under 50 ms; picture correct, with no rotation regressions |
| 6 | **Instrument what the logs could not answer.** Log each AirPlay request arrival in `WIRELESS_TIMING` (K8). Log the GO BSSID per AA group and the join timing (K9). Keep DiPlay runs by size (for example 5 MB), not 8 runs. Add a session id plus the phase and Retrying reason to carlink.log. | DiPlay AirPlay server and `DiPlayLog` rotation; DiAuto `WirelessTiming`; `SessionManager.note` | Turns K8, K9, K12 and K13 from inference into fixes; nothing lost after 1 hour | None | One day of tests leaves every run on disk; the K8 gap can be attributed to one request |
| 7 | **Dial the iPhone earlier.** Start the RFCOMM dial, or at least an SDP page, when CarLink commits to wireless CarPlay, in parallel with the previous engine's stop and the host launch; hand the socket to the controller. | `CarPlayAdapter.start` → a DiPlay hook before `startCarPlay`; `CarPlayController.kt:~1209` | Hides 1.5–2.5 s of the 5.8–6.7 s page (more after a switch); C10 drops to about 10.5 s | Medium | 5 wireless starts: `rfcomm-connected` at controller start + 3–4 s instead of +5.9–6.7 s |
| 8 | **USB-only Android Auto sessions.** No P2P group, HFP poke or wireless server when CarLink starts AA over USB. Fold in K14 here: a same-engine USB plug during a not-yet-connected wireless attempt starts USB without asking. | DiAuto `AapService.onCreate` / `initWifiMode` behind a `HostIntegration` flag; `SessionManager.kt:204-207` | No wireless race while plugged in (A20); −2.5–3 s and one tap per plug-during-wireless | Medium (DiAuto) / low (SessionManager) | AA USB start: no `group-create` or `poke-start` lines. iPhone plugged in during a wireless dial: USB starts at once |

**Next after these:**

- **Shorter Type 3 delay.** Cut the 1 s delay before Type 3 to 250 ms (K9) and measure with 10 AA starts.
- **Persistent AA group.** Keep one GO address across groups, if item 6 shows the BSSID changes.
- **Short unplug grace.** Use the 1.2 s grace for any session that has been connected (K15).
- **Re-test K13** with a wired CarPlay session of 10 minutes or more.

**Already fixed and proven by today's logs:**

- K1, wired "connected" (b12);
- K5, no auto-start after an unplug (b14–b16);
- K6, settling on the S25's re-enumeration (b14–b16);
- the RFCOMM dial in parallel with the hotspot (b16, −0.4 s);
- the AA poke at +0.8 s instead of +3.3 s (b16).

**Landed in the code but not proven**, because the `17:4x` build is not in the logs:

- the IPv6 address-family reset (`CarPlayAdapter.kt:143-149`);
- the stale-accessory probe (`AaCommandHandler.kt:120-125`), which by design cannot catch the K2 failure.
