# PR 158 and PR 164 integration rationale

## Current preflight and publication context

The current combined candidate is `merge/reviewed-safety-20261003`, with integration compile corrections through `96cac13` over candidate `8776153`. It includes the corrected PR 158 head `1f8bab9`, accepted-probe publication guard `bc76616`, PR 164's reconciled hotspot settings, the other reviewed feature changes and local diagnostics. The combined full workflow stopped at two integration compile mismatches; corrections are committed and await fresh full validation. No final test count or completed preflight result is claimed here. Investigation of callbacks from a destroyed user probe remains pending and must not be treated as resolved by this document.

Under the user's publication authorization, PRs 155, 156, 157, 161, 162 and 168 have been merged on GitHub. Corrected contributor heads were pushed for PR 146 (`4f9f0dc`), PR 155 (`7cd60eb`) and PR 158 (`1f8bab9`). PR 155's corrected head passed GitHub CI before its native squash merge `b4056c5`; PRs 146 and 158 remain unmerged at this checkpoint. PR 169 is held, PR 170 is deferred at the user's request, and PRs 171/172 are outside the reviewed batch. TV knob X/Y keep their original absolute semantics and its source-only workflow contains no credential provisioning. Physical vehicle acceptance remains outstanding.

The rationale below originated in an **earlier isolated preflight snapshot** merging corrected PR 158 into safety branch `bb6dd37`. That candidate's first parent included other reviewed PRs and diagnostics, so it was deliberately not pushed to the contributor's PR. Its conflict-resolution rationale is retained and reused in the current combined candidate; the contributor received the narrow corrected head instead.

## Settings ownership

PR 158's **Location → Advanced vehicle data** owns the default/legacy mode, saved probe, supported-field controls, explicit replacement flow, automatic validation and vehicle-data reconnect preflight. Its Dashboard song control appears once, in the navigation card or the advanced section when navigation is unavailable.

PR 164's separate BYD ADB card owns the optional automatic car-hotspot setting. The default remains off. Its existing explicit authorization still checks actual own-package WRITE_SETTINGS/overlay permissions before saving the preference. Boot startup, manual/P2P eligibility, cancellation, fixed permission operations, preservation of hotspot credentials and the existing no-stop behavior are unchanged. The old second set of vehicle controls and its Apply button were removed from this card so they cannot bypass PR 158's mode/capability checks. The corresponding tests now exercise the unified flow.

The merged render function must not increment `adbCheckGeneration`: PR 158 renders its progress immediately after scheduling a check, so PR 164's old render-side invalidation would discard that check's completion. Destruction still invalidates pending checks. A late hotspot eligibility callback only updates its own card and cannot cancel a vehicle reconnect preflight.

Hotspot permission work and user vehicle ADB work do not start alongside one another. Controls disable while the other user operation is pending, and method guards also reject stale callbacks. Automatic saved-field validation is paused for explicit hotspot permission work and resumes after completion. No new permission, arbitrary command, firmware write or vehicle-data field was introduced by this reconciliation.

## Other conflict resolutions

- Resource files retain both feature sets and all diagnostic/settings-gesture/turn-card strings. For the two conflicting dashboard-map labels, PR 158's shorter label wins; the explanatory ADB requirement remains in the description. XML parsing and duplicate-name checks cover all six locales.
- `LocalAdb` retains PR 164's buffered input and bounded approval recheck, together with PR 158's bounded one-shot command read window (`MAX_COMMAND_TIMEOUT_MS`). Both constants remain; no authorization or timeout bound was broadened during reconciliation.
- `AndroidMediaSink` retains the safety branch version unchanged: API 28-compatible effective attributes, diagnostic failure stages, bounded logging and codec failure cleanup survive.
- PR 158's `CarPlayHostActivity` reconciliation adds four mode-aware active-setting substitutions over the safety parent. The current combined candidate also retains the reviewed TV input changes; theme/exit/rotation/VPN diagnostics and fixes remain present.
- Navigation/testing documentation keeps both the runtime Wi-Fi ownership/parked-video readiness guidance and PR 158's mode/probe/location acceptance checks. Raw issue logs are not imported.

## Change provenance and verification

PR 158 feature sources: `BydVehicleSettingsBackend`, `Byd13CatalogProbeMain`, `BydVehicleCapabilityProbe`, `BydVehicleFields`, mode-aware `BydOutputSettings`, supporting ADB/battery/parked-state/wheel-speed reads, their tests, the four host active-setting calls, vehicle/location UI and localized resources. Guard `bc76616` still publishes battery data only after a snapshot is persisted/accepted and only in the selected legacy mode; held candidates stay outside runtime data.

Integration-specific edits: `DiPlayActivity`, `BydAdbSettingsUiTest`, `BydSettingsReconnectTest`, `CarHotspotSwitchTest`, two additional cases in `BydVehicleDataSettingsTest`, the conflict resolutions in resources/`LocalAdb`/navigation/testing documents, and this rationale. Existing PR 164 grant/setup/controller/network sources and the safety parent's diagnostic sources are unchanged.

At the earlier isolated-preflight checkpoint, static checks completed: no unresolved markers, all localized strings parsed with no duplicate names, `git diff --check`, and `scripts/check_public_tree.py`. No Gradle run or GitHub mutation had occurred in that isolated worktree, and its new/updated tests were then unexecuted. Those statements are historical, not the current workflow/publication status above. No APK download, new binary, credential import, minSDK change, PR 169 or PR 170 source change was introduced by this reconciliation. Hardware acceptance remains necessary for supported head units, ADB denial/loss, mode changes, accepted/rejected legacy fields and actual hotspot startup.
