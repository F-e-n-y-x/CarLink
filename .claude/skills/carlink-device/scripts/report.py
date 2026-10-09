#!/usr/bin/env python3
"""Connection report for CarLink on a real device.

Pulls CarLink's logcat lines, DiAuto's journal (files/diagnostics/current.log) and DiPlay's log
(files/logs/diplay.log), puts them on one local-time timeline and prints the events that decide
a connection, then a verdict per attempt. Read-only: never changes the device.

usage: report.py [--serial S] [--package P] [--since HH:MM[:SS]] [--all]
"""
import argparse, datetime as dt, re, subprocess, sys

KEY_CARLINK = re.compile(r"CarLink-(Session|Connection|Engine|Settings|AAEngine|CarPlay|BT|AA)\b")
KEY_DIAUTO = re.compile(
    r"Connection accepted|\[RX\]|Handshake failed|join failure|WiFi session landed|SSL handshake complete"
    r"|First frame rendered|read timeout|Connection closed|isUserExit|sessions in a row|Group formed|createGroup"
    r"|poke|SoftApCredentials: (SUCCESS|No passphrase)|Stop action|AapService creating|Native AA on|Phone refused"
    r"|Initializing WiFi Mode|moving to Wi-Fi Direct|Byebye|disconnect", re.I)
KEY_DIPLAY = re.compile(
    r"CONNECTION_DIAGNOSTIC .*stage=|wireless hotspot backend|Existing Wi-Fi attached|RFCOMM connected"
    r"|iap2 authentication accepted|carplay-start-session|tcpAccepted=[1-9]|waitingFor=|No AirPlay TCP"
    r"|Failed|sessionActive=true|First frame|Host started|teardown begin", re.I)
MAC = re.compile(r"(?i)\b([0-9a-f]{2}:){5}[0-9a-f]{2}\b")


def adb(serial, *args):
    r = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, errors="replace")
    return r.stdout


def mask(s):
    return MAC.sub("[mac]", s)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="192.168.10.103:5555")
    ap.add_argument("--package", default="com.carlink.app.hudtest")
    ap.add_argument("--since", help="local device time HH:MM[:SS] (default: last 10 minutes)")
    ap.add_argument("--all", action="store_true", help="every engine line, not only key events")
    a = ap.parse_args()

    now = dt.datetime.strptime(adb(a.serial, "shell", "date '+%Y-%m-%d %H:%M:%S'").strip(), "%Y-%m-%d %H:%M:%S")
    if a.since:
        parts = [int(p) for p in a.since.split(":")] + [0]
        since = now.replace(hour=parts[0], minute=parts[1], second=parts[2], microsecond=0)
    else:
        since = now - dt.timedelta(minutes=10)
    tz = dt.datetime.now().astimezone().tzinfo
    offset = now - dt.datetime.now()  # device clock vs this PC, to place epoch stamps in device time

    events = []
    for line in adb(a.serial, "logcat", "-d", "-v", "time").splitlines():
        m = re.match(r"(\d\d-\d\d) (\d\d:\d\d:\d\d\.\d+) (\w)/(\S+?)\(\s*\d+\): (.*)", line)
        if not m or not KEY_CARLINK.search(m.group(4)) or "command=report" in line:
            continue
        t = dt.datetime.strptime(f"{now.year}-{m.group(1)} {m.group(2)[:8]}", "%Y-%m-%d %H:%M:%S")
        events.append((t, "carlink", f"{m.group(4)}: {m.group(5)}"))

    # CarLink's own file log (survives logcat rotation); when present, its session lines replace logcat's.
    carlink_file = adb(a.serial, "shell", "run-as", a.package, "cat", "files/logs/carlink.log")
    if carlink_file.strip():
        events = [e for e in events if not (e[1] == "carlink" and "CarLink-Session:" in e[2])]
    for line in carlink_file.splitlines():
        m = re.match(r"(\d{13}) \[([^\]]+)\] (.*)", line)
        if m:
            t = dt.datetime.fromtimestamp(int(m.group(1)) / 1000) + offset
            msg = m.group(3)
            # Same wording as the logcat lines the attempt parser looks for.
            events.append((t.replace(microsecond=0), "carlink", f"CarLink-Session: {msg}" if m.group(2) == "CarLink" else f"CarLink-{m.group(2)}: {msg}"))

    journal = adb(a.serial, "shell", "run-as", a.package, "cat", "files/diagnostics/current.log")
    for line in journal.splitlines():
        m = re.match(r"(\d{13}) \[(\d)\] (?:\[\d+\] )?(.*)", line)
        if not m:
            continue
        t = dt.datetime.fromtimestamp(int(m.group(1)) / 1000) + offset
        if a.all or KEY_DIAUTO.search(m.group(3)):
            events.append((t.replace(microsecond=0), "aa", m.group(3)))

    diplay = adb(a.serial, "shell", "run-as", a.package, "cat", "files/logs/diplay.log")
    header = re.search(r"DiPlay log started (\d{4}-\d\d-\d\d)", diplay)
    day = header.group(1) if header else now.strftime("%Y-%m-%d")
    for line in diplay.splitlines():
        m = re.match(r"(\d\d:\d\d:\d\d)\.\d+\s+(.*)", line)
        if not m:
            continue
        if a.all or KEY_DIPLAY.search(m.group(2)):
            events.append((dt.datetime.strptime(f"{day} {m.group(1)}", "%Y-%m-%d %H:%M:%S"), "carplay", m.group(2)))

    # USB attaches from the system, for "plugged in → session" timing.
    for line in adb(a.serial, "logcat", "-d", "-v", "time", "-s", "UsbHostManager:D").splitlines():
        m = re.match(r"(\d\d-\d\d) (\d\d:\d\d:\d\d\.\d+) \w/UsbHostManager\(\s*\d+\): USB device attached: vidpid (\S+)", line)
        if m:
            t = dt.datetime.strptime(f"{now.year}-{m.group(1)} {m.group(2)[:12]}", "%Y-%m-%d %H:%M:%S.%f")
            events.append((t, "usb", f"attached {m.group(3)}"))

    events = sorted((e for e in events if e[0] >= since), key=lambda e: e[0])
    if not events:
        print(f"no CarLink activity since {since:%H:%M:%S} (device time {now:%H:%M:%S})")
        return
    for t, src, msg in events:
        print(f"{t:%H:%M:%S} {src:<7} {mask(msg)[:200]}")

    # Verdict per attempt: an attempt starts at each "connect engine=" line.
    print("\n== attempts")
    attempts, cur = [], None
    for t, src, msg in events:
        m = re.search(r"connect engine=(\w+)|CarLink-Session: start (\w+)", msg)
        if m:
            cur = {"start": t, "engine": m.group(1) or m.group(2), "lines": []}
            attempts.append(cur)
        if cur:
            cur["lines"].append(msg)
    for at in attempts:
        text = "\n".join(at["lines"])
        path = re.search(r"session engine=\w+ path=(\w+)|starting on (\w+)", text)
        path_name = (path.group(1) or path.group(2)) if path else "?"
        connected = "-> CONNECTED" in text or " connected on " in text
        verdict = "connected" if connected else "failed" if ("-> FAILED" in text or "failed:" in text) else \
            "blocked" if " blocked on " in text else "no result yet"
        causes = [c for c in [
            "phone refused the Wi-Fi (wrong/missing password or network)" if "status=-3" in text else None,
            "video stalled (Wi-Fi read timeout)" if "read timeout" in text else None,
            "iPhone never reached the car over Wi-Fi (no AirPlay TCP)" if "No AirPlay TCP" in text else None,
            "ended by a user exit (back/exit menu)" if "isUserExit=true" in text else None,
            "driver pressed Disconnect/Cancel" if "driver disconnect" in text else None,
            "engine moved to Wi-Fi Direct after refusals" if "moving to Wi-Fi Direct" in text else None,
        ] if c]
        dropped = "CONNECTED -> WAITING" in text or "session ended" in text
        stays = "stayed connected" if verdict == "connected" and not dropped else \
            "dropped after connecting" if verdict == "connected" else ""
        connected_at = next((t for t, _, m in events if t >= at["start"] and " connected on " in m), None)
        took = f" in {(connected_at - at['start']).total_seconds():.1f}s" if connected_at else ""
        plugged = [t for t, src, m in events if src == "usb" and t <= at["start"] and (at["start"] - t).total_seconds() < 15]
        from_plug = f" ({(connected_at - plugged[0]).total_seconds():.1f}s from plug-in)" if connected_at and plugged else ""
        print(f"{at['start']:%H:%M:%S} {at['engine']:<12} path={path_name:<11} {verdict}{took}{from_plug} {stays}"
              + (f" — {'; '.join(causes)}" if causes else ""))


if __name__ == "__main__":
    sys.exit(main())
