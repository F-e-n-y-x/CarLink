# Test checklist

Use the [installation guide](INSTALL.md). With the car parked, verify wired and wireless connection, picture, touch and music. Test disconnect/reconnect, then settings Apply/Cancel. Save a diagnostic report after reproducing an issue.

For channel memory, connect until authenticated CarPlay renders, disconnect and reconnect without changing the car's Wi-Fi association. Look for `remembered saved` followed by `remembered first`. Report absent events; creating a hotspot alone is insufficient.

Include head-unit model, DiLink/Android, iPhone/iOS, wired/wireless, app version and exact steps. Do not post credentials or unreviewed personal information. See [compatibility](COMPATIBILITY.md) for remaining limitations.

## Location reporting

With the car parked, open **Settings → Location → Report location to iPhone**.

- On a fresh installation, the switch is off. Enabling it requests precise location if needed; denying the request or granting only approximate location leaves it off.
- Grant precise location, enable the switch, then reopen Settings to confirm the saved state. With no connection running, the setting applies to the next connection.
- During wired and wireless CarPlay, enabling or disabling the switch reconnects the session. When enabled and requested by the iPhone, check for `start-location-information` and `location-information` in the DiPlay diagnostics; on wireless, also verify that reporting continues after the Bluetooth-to-Wi-Fi handoff.
- Disable the switch and confirm the next session does not advertise location reporting. These checks verify the accessory reporting path; they do not establish which inputs iOS uses in each fused location result.

## Advanced vehicle data

- Expand **Settings → Location → Advanced vehicle data**. Confirm a fresh install uses **Default mode · verified on DiLink 5.0 head units** and shows the battery, wheel-speed and parked-video switches without a field probe.
- In Default mode, tap **Check ADB access** and record the battery, speed and gear it shows. With CarPlay connected, turn on a switch whose data cannot be read: CarPlay must stay connected and the page must show what cannot be read.
- Select **Legacy head-unit detection · tested on controller 13 / DiLink 3.0**. Approve the key if the car asks; the same action must continue into the read-only field probe. With “Always allow” ticked, the page must not say the car allowed DiPlay only once. A failed probe must leave Default mode selected.
- Reopen Settings, restart DiPlay, change gear and reconnect CarPlay. The successful probe, resolved fields and enabled battery/wheel-speed/video switches must remain saved without another tap, even when the current firmware metadata differs.
- Switch back to Default mode and confirm the saved legacy probe remains available when Legacy mode is selected again.
- Turn ADB off temporarily. Saved functions and switches must remain visible; turning ADB back on allows automatic validation. Two READY-but-unreadable validations trigger one automatic re-probe, while an incomplete re-probe preserves the previous snapshot and shows manual retry.
- Press the first probe, authorization and retry controls after scrolling down the page. Progress and results must remain at the same scroll position rather than jumping to the top.
- Scroll down Settings, open CarPlay, then return to Settings (Back to DiPlay or the three-finger gesture). The page must keep its scroll position.
- When the BYD navigation card is available, confirm **Dashboard song** exists there exactly once and does not appear in Advanced vehicle data.
