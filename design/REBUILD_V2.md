# CarLink v2: rebuild plan

Status: approved direction (user, 2026-10-09: "first dissect both projects, understand them, then
make the app; start from scratch if you have to"). Built on `docs/dissection/DIPLAY.md`,
`docs/dissection/DIAUTO.md` and `design/RESEARCH_PLUG_AND_PLAY.md`. Section numbers like
DP§10 F13 / DA§10.6 point into those documents.

## 1. Goal and non-goals

Plug an iPhone or an Android phone into any Android car stereo or spare tablet, or pair it once,
and CarPlay or Android Auto starts. No fiddling, no second app's screens, no BYD assumptions.

Not goals: making a tablet a Bluetooth car kit, reading saved Wi-Fi passwords, keeping the
phone's internet on Wi-Fi during wireless Android Auto (impossible for apps: DA§11.6).

## 2. What is kept, what is rebuilt

| Area | Decision |
|---|---|
| Engines (`vendor/diplay`, `vendor/diauto`) | Kept: they are the proven part. Changed only through small, listed hooks (§5). |
| Process model (DiAuto in `:aa`, one engine at a time) | Kept (both references confirm it). |
| Detection (`PhoneClassifier`, USB watcher, KnownPhones) | Kept, extended with per-phone "last working path". |
| Connection layer (`EngineCoordinator`, `CarPlayEngine`, `AndroidAutoEngine`, command receiver, path selection, settings applier, refusals) | **Rebuilt** as `connection/` v2 (§3, §4). |
| Onboarding / pre-flight | **Rebuilt** (§6). |
| UI (Home, Settings, Diagnostics, waiting/failure screens) | **Rebuilt** on the v2 states, after the connection layer works on the tablet (§7). Theme tokens and components may be reused if they pass the new design review. |

## 3. Connection policy (defaults)

| | Android Auto | CarPlay |
|---|---|---|
| USB | Auto-start on attach; CarLink does the AOA switch; engine USB auto-switch off (DA§11.4) | Auto-start on attach; VPN consent collected in onboarding (DP F3) |
| Wireless, AUTOMATIC | **Wi-Fi Direct** (stable group name) → car hotspot only if its credentials are known/readable and the AP is up | **Wi-Fi Direct** on tablets (proven on the user's tablet 12:38); on head units **car hotspot 5 GHz** when credentials are known, else Wi-Fi Direct |
| Same Wi-Fi | Opt-in only (phone loses Wi-Fi internet; needs typed password + exact BSSID) | Opt-in; needs readable SSID (location on), password, WPA2 or mixed, a router that passes mDNS |
| Fallback | Phone refuses the network (WifiConnectStatus ≠ 0) twice → Wi-Fi Direct | Only on `FIRST_TCP_TIMEOUT` after DiPlay's own retries; never on Bluetooth-stage failures; refusals expire (DP F1) |
| Memory | Per phone: last path that connected; tried first next time | same |

Vehicle profile (`GENERIC` default, `BYD_DILINK` opt-in or auto-detected via
`BydOutputSettings.available()` / `BYD-AUTO/` fingerprint) gates every BYD item (DP§12, DA§12).

## 4. Connection layer v2 (`app/.../connection/`)

- `SessionManager` (main process, replaces EngineCoordinator): one state machine
  `Idle → Preflight → Starting(path) → Connecting(step) → Retrying(n, reason) → Connected →
  Ending → Idle | Blocked(problem) | Failed(problem)`. Exactly one engine. A new session starts
  only on the previous one's **stop completion** (DP F14).
- `CarPlayAdapter`: drives DiPlay through hooks: `hasSession()`, `stopSession(onDone)`, host
  states (retrying, permission/VPN blocked, retries exhausted) (DP F13, F15), maps
  `startupFailure` to fallback decisions. Never treats `CarPlayStatusBus.current == null` as
  "no session".
- `AndroidAutoAdapter`: writes the full session settings each time — mode 3,
  `static-bssid="0"` unless Same Wi-Fi (reset on stop), poke targets = the chosen phone, every
  engine auto-start flag off, `auto-connect-last-session=false`, `kill-on-disconnect=false`,
  `music-via-bluetooth` per profile (DA§11.1). Starts with `ACTION_START_WIRELESS` then
  `ACTION_NATIVE_AA_POKE`; restarts the service only when wedged, using `isBusy` (DA§10.5).
  Receives engine events through `HostIntegration.onEngineEvent` (DA§11.5.2).
- `Preflight`: per engine and path — Bluetooth on, phone bonded, Wi-Fi on, Location on,
  permissions (incl. `ACCESS_LOCAL_NETWORK` on SDK ≥ 37, DP F4), overlay, VPN consent (CarPlay
  USB), MFi present, hotspot/Same Wi-Fi details valid. Each failure is a `Blocked(problem)` with a
  one-tap fix; the engine is not opened.
- `Triggers`: USB attach (existing router), Bluetooth ACL of a known phone, Home open / screen
  wake on tablets (poke the last Android phone, DA§11.3), Connect button.

## 5. Vendor hooks (small, listed in docs/VENDOR_CHANGES.md)

DiPlay: `waitingView` (CarLink renders waiting/failure inside the host window), `hasSession`,
`stopSession(completion)`, host-state callback, `vehicleIntegrations` flag (gates
`BydNavigationOutputs.start`, wheel codes 304/312, `WifiScanPause`), brand names for iAP2 /
Bonjour / OEM label.

DiAuto: `stickyService` + `restartOnTaskRemoved` flags (no phantom engine, DA§10.6), `onEngineEvent`,
port open-headunit PR #1029 (keep the Bluetooth channel for the session), port PR #1006 (stable
Wi-Fi Direct name, no group removal on start), gate `BydFullscreen` by profile, export lockdown of
`AutomationActivity` / `AapProjectionActivity`.

## 6. First run and pre-flight UX

1. Welcome → one permission flow (Bluetooth, Nearby, Location + location switch, mic,
   notifications, overlay, battery, VPN consent, local network).
2. Car profile: Generic (default) / BYD; car name shown to phones (default "CarLink").
3. Pair a phone: the screen shows its Bluetooth name large and becomes discoverable; "On your
   phone open Bluetooth and tap <name>"; live detection of the bond; then "Accept Use CarPlay /
   Android Auto on your phone"; first connection runs and is confirmed.
4. Home thereafter: one primary action, live status in driver language, one-tap fixes.

## 7. UI

Rebuilt after §4 works on the tablet, from a fresh design review against the research (premium
in-car UIs; tablet 1458×911 dp as a primary target). The CarPlay waiting/failure UI is CarLink's
own through the DiPlay `waitingView` hook, identical to Android Auto's.

## 8. Verification gate (carlink-device skill)

Nothing is called working until this matrix passes on the user's tablet with logs:

| # | Case |
|---|---|
| 1 | S25 wireless, first connect after pairing |
| 2 | S25 wireless, reconnect (Home open / wake) |
| 3 | S25 USB |
| 4 | iPhone wireless, first connect |
| 5 | iPhone wireless, reconnect |
| 6 | iPhone USB (Trust prompt) |
| 7 | Switch iPhone ↔ Android without restarting the app |
| 8 | Disconnect / cancel during each stage; no phantom engine afterwards |
| 9 | Session stays up ≥ 5 minutes, ≥ 40 fps |

## 9. Milestones

1. Vendor hooks + adapters + SessionManager + settings policy (two agents: DiPlay side, DiAuto
   side; coordinator/pre-flight by the lead). Device matrix 1–9.
2. Onboarding and pairing flow. Device check.
3. UI rebuild (design review first). Device check on the tablet, screenshots in docs/.
4. Cleanup: remove v1 code, docs, README, skills updated.
