# Troubleshooting

What to do when CarLink waits, fails or behaves unexpectedly. Headings use the wording Home
shows, so you can search this page for the message on your screen. When Home offers a one-tap fix
(**Turn on**, **Allow**, **Use Wi-Fi Direct**, **Open settings**), try that first.

## While connecting

**"Taking longer than usual: …"** CarLink knows how long each phone usually takes. When a step
runs past that, it says what it is doing and recovers by itself, in this order: it calls the
phone again, restarts the USB link, tries again, reconnects Wi-Fi, tries the next way to connect
("Trying Wi-Fi Direct instead", "Trying the car hotspot instead"), and finally asks you to re-plug
or shows why it failed. You do not need to do anything unless it asks.

**"iPhone not reachable over Bluetooth" / "Your phone isn't answering"** (wireless). Unlock the
phone and check that its Bluetooth is on, or plug it in with a cable. For an Android phone, keep
it unlocked and near the screen; Android Auto starts by itself once it connects.

**"Your phone isn't answering" on a cable.** Unlock the phone and accept the prompt on its
screen ("Use CarPlay", or Android Auto's screens). If nothing happens, unplug it and plug it in
again. **"USB connection failed"**: try another cable.

**"Your phone didn't answer"** or **"Bluetooth connection failed".** Unlock the phone and make
sure its Bluetooth is on and connected to this screen. On the phone, turn Bluetooth off and on.
If it keeps failing, forget this screen in the phone's Bluetooth settings and pair again.

**"Pair your iPhone first" / "This phone isn't paired".** Wireless needs pairing once, started on
the phone (its Bluetooth settings, tap this screen's name). A cable works without pairing.

## Wi-Fi and networks

**"Wi-Fi is off" / "Location is off".** The picture travels over Wi-Fi, and Android needs
Location on to set up the wireless link. Turn Wi-Fi on here; it does not need to be connected
to a network.

**"Your iPhone couldn't reach this screen".** You are using Same Wi-Fi and the router blocks the
link CarPlay needs (mDNS/Bonjour between clients; many home and mobile routers do). Tap **Use
Wi-Fi Direct**, or plug in with a cable.

**"Your phone couldn't join the Wi-Fi".** Check the Wi-Fi password in **Settings › Connection**,
or switch the connection type to Wi-Fi Direct.

**"The car hotspot is off" / "Add the car hotspot".** Turn the hotspot on in the car's settings
and add its name and password once in Settings, or plug your phone in with a cable.

**"The wireless link was too weak".** Keep the phone close to the screen and try again, or use a
cable.

**The phone has no internet during wireless Android Auto.** Expected: the phone joins the
screen's Wi-Fi link, which has no internet, so its apps use mobile data. Allow Android Auto to use
mobile data on the phone.

## CarPlay

**"CarPlay can't sign in".** This build has no CarPlay authentication files: you are running a
build from source. Install the release APK from
[Releases](https://github.com/F-e-n-y-x/CarLink/releases/latest). Android Auto works either way.

**CarPlay stopped working after an iOS update.** Possible, and not something CarLink can promise
against: see the CarPlay notice in the [README](../README.md#carplay-notice). Android Auto is not
affected. Please report it with the iOS version.

## Other

**The phone does not connect by itself when I get in (tablet).** A tablet is not a Bluetooth car
kit, so the phone never connects to it on its own. CarLink starts the last phone when Home comes
to the front (the app opens, the screen wakes), at most once a minute. After **Disconnect** or
**Cancel**, automatic starts pause until you connect again or for 10 minutes.

**Plugging in a second phone did not take over.** By design: during a session, CarLink asks
"Switch to &lt;phone&gt;?" (**Switch** / **Not now**). The floating switcher over CarPlay or Android
Auto can also switch phones.

**Two CarLinks, or DiPlay or DiAuto, are installed.** Each answers USB plugs and Bluetooth. Run
one projection app at a time: uninstall or stop the others.

**"Couldn't reach GitHub" or "Update not installed".** Check the internet connection and try
**Settings › About › Check for updates** again. A download that fails a check is deleted and the
popup says why; only the official release (`com.carlink.app`, signed with CarLink's key) updates
itself.

## Reporting a problem

1. Reproduce it, then open **Settings › General › Diagnostics** and tap **Share log**. The log
   merges CarPlay, Android Auto and CarLink's own decisions, with addresses hidden.
2. [Open a bug report](https://github.com/F-e-n-y-x/CarLink/issues/new/choose) with: the screen
   (head unit or tablet model) and its Android version, the phone and its iOS or Android version,
   CarPlay or Android Auto, USB or wireless (and which wireless method), what you expected and
   what happened, and the log.
3. Do not post Wi-Fi or hotspot passwords. Security problems go to
   [SECURITY.md](../SECURITY.md), not to a public issue.
