# Release review — 27 September 2026

# DiAuto 0.3.3 — BYD HUD navigation

- Standalone windshield arrows, next-turn distance and street names on verified DiLink5.1 firmware, without ADB, root or a computer helper.
- Add opt-in BYD navigation settings and background vendor output with route-end, disconnect and stale-guidance cleanup. Force-stop cleanup occurs on the next launch.
- Recover the current Wi-Fi Direct interface/BSSID when it appears late; allow a bounded grace period before using a plausible cached fallback. Preserve Static BSSID overrides.
- Add a best-effort Bluetooth auto-start notification fallback and useful recovery diagnostics.
- Thanks to @STUkh for PR #2 and the Wi-Fi recovery/auto-start improvements.

## Validation scope

The user physically confirmed live standalone HUD guidance and street names in both apps. The latest DiPlay test also confirmed Car hotspot startup and substantially improved Wi-Fi Direct performance. Occasional audio cutouts remain; the user explicitly deferred them to another version and authorized pushing, merging and releasing these changes. The release packages now permit the verified standalone backend while retaining the exact firmware/stock-receiver guard. See [BYD navigation](BYD_NAVIGATION.md).

Earlier local review blockers for the standalone HUD path are resolved by physical tests. No claim is made that every vehicle, map app, force-stop sequence or USB failure mode was physically tested. Release checks include unit tests, build/lint, package/signature inspection and public-source credential checks. Android signing secrets and accessory private assets remain outside the repository/source archives.

The earlier plausible-fallback race is corrected: an unresolved current interface keeps a three-second grace period even when a cached address looks valid. The existing overall15-attempt IP/masked-address bound remains. Unit coverage includes an interface that appears after a valid-looking fallback, expiry, static overrides and client groups.
