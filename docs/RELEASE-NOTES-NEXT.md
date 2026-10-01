# DiPlay next release notes

Unreleased draft for the next announcement. This document records PRs #71, #88, and #89, merged on 2 October 2026 in Oman time. The release version and publication date will be set after the remaining PR reviews. A release has not been created or published.

## Announcement draft

- CarPlay now follows BYD head-unit day/night changes while it is on screen, including firmware that does not reliably send Android configuration callbacks.
- Media and navigation audio stream selection supports 0–20 again. Older saved navigation selections are preserved when no new selection exists. Use 0 for automatic routing; vendor-specific outputs depend on the head unit.
- CarPlay stays connected when the surround-view camera temporarily resizes its window during an existing full-screen session. Video keeps its proportions, and touch input follows the visible picture. If CarPlay connects while the camera window is already narrow, closing the camera triggers one reconnect to restore the full-screen canvas.

Thanks to @lpcheng1208 for these contributions.

## Merged changes

### BYD day and night appearance

[PR #71](https://github.com/shihabal3amri/DiPlay/pull/71) checks Android's resource configuration every two seconds while the host activity is visible, and when it regains window focus. This handles BYD firmware that changes its resources without reliably calling `onConfigurationChanged`. Explicit day/night changes are sent to the iPhone; an undefined night-mode value keeps the previous state.

### Extended audio stream selection and saved navigation settings

[PR #88](https://github.com/shihabal3amri/DiPlay/pull/88) restores the 0–20 selection range across media and navigation settings, persistence, and preview. The new navigation setting inherits the older saved `navigation_stream_type` value only when the new setting is absent. Explicit new selections, including 0, take precedence; fresh installs default to automatic routing.

Nonzero preview selections use the same legacy `AudioTrack` constructor as playback. A device that rejects the selected stream reports an unavailable preview. English and Simplified Chinese descriptions explain that vendor-specific outputs require head-unit support. Playback fallback and audio focus behavior are unchanged.

### CarPlay continuity during surround view

[PR #89](https://github.com/shihabal3amri/DiPlay/pull/89) preserves the existing CarPlay session and negotiated video canvas when a camera window shrinks the host view and then returns it to its original size. Video fits the available window without distortion. Touch coordinates follow the visible content, and touch sequences starting in the surrounding bars are ignored. Background-session adoption preserves the display state. Actual display rotation, explicit system-bar layout changes, and connection failures retain their reconnect paths.

The startup-window correction in [commit 7eb4a3f](https://github.com/shihabal3amri/DiPlay/commit/7eb4a3ffc6860b5a24c9a2f7e231fa330814b4d7) handles connecting or reconnecting while the camera window is already narrow. When the window later grows beyond its startup dimensions, CarPlay reconnects once to negotiate a canvas for the larger window. The comparison uses the original window dimensions rather than the scaled video resolution, so resolution scaling does not cause unnecessary reconnects during normal camera open/close cycles.

## Validation and remaining vehicle checks

The combined changes passed 394 local unit tests: 98 in `common` and 296 in `shared`, with zero failures, errors, or skipped tests. Mobile and automotive debug builds passed. Mobile lint completed with zero errors and 18 warnings. Public-source credential and whitespace checks passed. These results validate the reviewed source, not a signed release APK or physical vehicle behavior.

Before finalizing the announcement, record the results of these vehicle checks:

- Switch day/night mode with CarPlay visible and after returning from another car app.
- Verify channel 15 preview and navigation output on the affected head unit, including simultaneous media playback and persistence after reconnect.
- Open and close surround view repeatedly, verify uninterrupted media and accurate touches, and check background/foreground transitions and physical screen rotation.
- Connect with the camera window already narrow, then close it and confirm one reconnect restores full-screen CarPlay. Repeat with resolution scaling enabled.

GitHub PR checks were not green at merge time: #71 reported failure without available jobs or logs, and #88 and #89 reported `action_required`. The local validation above was completed independently. Capture the post-merge CI result before release publication.

## Merge references

| PR | Merge commit |
| --- | --- |
| [#71](https://github.com/shihabal3amri/DiPlay/pull/71) | [0429420](https://github.com/shihabal3amri/DiPlay/commit/04294209449de38df4bb8e3affb18793e88bb82f) |
| [#88](https://github.com/shihabal3amri/DiPlay/pull/88) | [0bcc777](https://github.com/shihabal3amri/DiPlay/commit/0bcc77715f0116e1b8faf12ecd25c10ff4099bb5) |
| [#89](https://github.com/shihabal3amri/DiPlay/pull/89) | [0d21434](https://github.com/shihabal3amri/DiPlay/commit/0d21434883306a6273470439cdbea077ca2ab9c0) |
