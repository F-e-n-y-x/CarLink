# PR 158 and PR 164 integration rationale

This isolated preflight candidate merges corrected PR 158 (`1f8bab9`, including accepted-probe publication guard `bc76616`) into safety branch `bb6dd37`. It is not a narrowly scoped PR 158 publication head: its first parent includes other reviewed PRs and local diagnostics. Do not push this candidate to the contributor's PR. The publication owner will reuse the conflict resolution against updated main and the corrected PR 158 source.

## Settings ownership

PR 158's **Location → Advanced vehicle data** owns the default/legacy mode, saved probe, supported-field controls, explicit replacement flow, automatic validation and vehicle-data reconnect preflight. Its Dashboard song control appears once, in the navigation card or the advanced section when navigation is unavailable.

PR 164's separate BYD ADB card owns the optional automatic car-hotspot setting. The default remains off. Its existing explicit authorization still checks actual own-package WRITE_SETTINGS/overlay permissions before saving the preference. Boot startup, manual/P2P eligibility, cancellation, fixed permission operations, preservation of hotspot credentials and the existing no-stop behavior are unchanged. The old second set of vehicle controls and its Apply button were removed from this card so they cannot bypass PR 158's mode/capability checks. The corresponding tests now exercise the unified flow.

The merged render function must not increment `adbCheckGeneration`: PR 158 renders its progress immediately after scheduling a check, so PR 164's old render-side invalidation would discard that check's completion. Destruction still invalidates pending checks. A late hotspot eligibility callback only updates its own card and cannot cancel a vehicle reconnect preflight.

Hotspot permission work and user vehicle ADB work do not start alongside one another. Controls disable while the other user operation is pending, and method guards also reject stale callbacks. Automatic saved-field validation is paused for explicit hotspot permission work and resumes after completion. No new permission, arbitrary command, firmware write or vehicle-data field was introduced by this reconciliation.

## Other conflict resolutions

- Resource files retain both feature sets and all diagnostic/settings-gesture/turn-card strings. For the two conflicting dashboard-map labels, PR 158's shorter label wins; the explanatory ADB requirement remains in the description. XML parsing and duplicate-name checks cover all six locales.
- `LocalAdb` retains PR 164's buffered input and bounded approval recheck, together with PR 158's bounded one-shot command read window (`MAX_COMMAND_TIMEOUT_MS`). Both constants remain; no authorization or timeout bound was broadened during reconciliation.
- `AndroidMediaSink` retains the safety branch version unchanged: API 28-compatible effective attributes, diagnostic failure stages, bounded logging and codec failure cleanup survive.
- `CarPlayHostActivity` receives only PR 158's four mode-aware active-setting substitutions over the safety parent. Its theme/exit/rotation/VPN diagnostics and fixes remain present.
- Navigation/testing documentation keeps both the runtime Wi-Fi ownership/parked-video readiness guidance and PR 158's mode/probe/location acceptance checks. Raw issue logs are not imported.

## Change provenance and verification

PR 158 feature sources: `BydVehicleSettingsBackend`, `Byd13CatalogProbeMain`, `BydVehicleCapabilityProbe`, `BydVehicleFields`, mode-aware `BydOutputSettings`, supporting ADB/battery/parked-state/wheel-speed reads, their tests, the four host active-setting calls, vehicle/location UI and localized resources. Guard `bc76616` still publishes battery data only after a snapshot is persisted/accepted and only in the selected legacy mode; held candidates stay outside runtime data.

Integration-specific edits: `DiPlayActivity`, `BydAdbSettingsUiTest`, `BydSettingsReconnectTest`, `CarHotspotSwitchTest`, two additional cases in `BydVehicleDataSettingsTest`, the conflict resolutions in resources/`LocalAdb`/navigation/testing documents, and this rationale. Existing PR 164 grant/setup/controller/network sources and the safety parent's diagnostic sources are unchanged.

Static checks completed: no unresolved markers, all localized strings parse with no duplicate names, `git diff --check`, and `scripts/check_public_tree.py`. No Gradle, GitHub mutation, APK download, new binary, credential import, minSDK change, PR 169 or PR 170 change occurred in this worktree. The new/updated regression tests are unexecuted until the publication owner runs the combined workflow. Hardware acceptance remains necessary for supported head units, ADB denial/loss, mode changes, accepted/rejected legacy fields and actual hotspot startup.
