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
