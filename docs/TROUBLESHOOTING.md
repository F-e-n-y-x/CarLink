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

**"Wireless isn't available on this stereo yet".** This stereo's Bluetooth is a separate module
run by the stereo's own firmware (common on FYT and ZLink-type units), and apps cannot use it, so
neither CarPlay nor Android Auto can start wirelessly. Re-pairing or turning Bluetooth off and on
does not help. Connect the phone with a USB cable (**How to connect** shows how); it works the
same. Diagnostics shows **Car stereo Bluetooth is a separate module** with what was found. To help
add wireless for your stereo, tap **Report on GitHub** in Diagnostics (or **Share log** and
[open an issue](https://github.com/F-e-n-y-x/CarLink/issues/new/choose)): the report says which kind
of module it is. Details: [design/EXTERNAL_BLUETOOTH.md](../design/EXTERNAL_BLUETOOTH.md).

**Trying wireless Android Auto through the stereo's module (experimental).** Settings ›
Connection › **Show advanced** › **Wireless through the stereo's Bluetooth module (experimental)**
(shown only on such stereos) lets Android Auto talk to the phone through the stereo's own module
instead. It is off by default and not yet tested on a real stereo. On an FYT stereo, first disable
the stereo's own **Car Link** app (`com.syu.carlink`) in Android Settings › Apps; CarLink does not
do it for you. Then pair the phone with the stereo as usual, tap **Connect**, and afterwards send
**Report on GitHub** from Diagnostics, whether it worked or not: the log shows the last step reached
([step by step](../design/EXTERNAL_BLUETOOTH.md#trying-it-on-a-stereo-step-by-step)). CarPlay is not
covered.

**"Couldn't reach the stereo's Bluetooth module".** The experimental setting above is on, but the
module did not answer: on an FYT stereo the stereo's own Car Link app is usually still enabled;
otherwise this stereo's module is not one CarLink can talk to yet. Turn the setting off and use the
USB cable, and send the report from Diagnostics so support can be added.

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

**CarPlay: sound but black screen (iOS 27).** The iPhone connects and plays music, touches work,
but no picture appears and Home stays on **Starting the picture**. On iOS 27 some iPhones open the
CarPlay screen but never send a frame
([DiPlay #249](https://github.com/shihabal3amri/DiPlay/issues/249),
[#975](https://github.com/shihabal3amri/DiPlay/issues/975)). When this happens CarLink shows
**"Sound but no picture? Turn on Direct video output"**: tap **Turn on and reconnect**. The same
switch is **Settings › Display › Direct video output** (with **Show advanced**) and applies at the
next connection. It fixed the picture for some drivers but not all, since the iPhone itself sends
nothing: if it stays black, try a USB cable, and on the iPhone forget this car in **Settings ›
General › CarPlay** and connect again. Please report it with the iOS version.

**The iPhone shows Personal Hotspot during USB CarPlay.** A car with built-in CarPlay switches its
USB port so that the iPhone becomes the USB host. An Android stereo's port cannot do that. So
CarLink asks the iPhone for its USB network connection instead, the same connection the iPhone
offers a computer for Personal Hotspot over USB, and iOS may show that as a Personal Hotspot
connection. Nothing else uses it. Only CarPlay's picture, sound and touches travel over the cable.
The link is local to the cable and never reaches the internet. The stereo gets no internet from the
iPhone through it, so no mobile data is spent on the stereo. Wi-Fi is not used while you are plugged
in, and CarLink no longer starts wireless CarPlay by itself while an iPhone is plugged in. The
iPhone charges from the cable as usual. Android also shows a key (VPN) icon on the stereo during USB
CarPlay. That is the same local link, not a VPN to the internet.

**CarPlay stopped working after an iOS update.** Possible, and not something CarLink can promise
against: see the CarPlay notice in the [README](../README.md#carplay-notice). Android Auto is not
affected. Please report it with the iOS version.

## Other

**The other person can't hear me on calls, or hears an echo.** On a stereo with its own
Bluetooth phone link (AutoChips and other head units with a built-in hands-free), the stereo's
phone app can take the microphone while CarPlay or Android Auto carries the call
([DiPlay #987](https://github.com/shihabal3amri/DiPlay/issues/987); echo on iOS 27:
[#985](https://github.com/shihabal3amri/DiPlay/issues/985)). When a call starts while that link
is connected or another app is recording, CarLink shows **"The stereo's own phone link may take
the microphone"**. Answer on the CarPlay or Android Auto screen, not with the stereo's own phone
popup, or disconnect the phone from the stereo's phone settings (or turn off its **Phone calls**
there) so only CarPlay or Android Auto handles calls. `carlink.log` has a `call: audioMode=…
recordings=[…] stereoHandsFree=…` line for each call (mode 2 is the stereo's own call, 3 a
CarPlay or Android Auto call; recordings are audio sources: 1 microphone, 6 voice recognition,
7 voice communication). If music and navigation stay silent after a CarPlay call, report it with
that log.

**"&lt;phone&gt; needs to be paired again".** Android 16 and later tell CarLink when this screen's
Bluetooth key for a phone is missing (Android 17 first tries to pair again by itself). The phone
then cannot connect wirelessly: on the phone, open Bluetooth settings, forget this screen, and
pair again (**Open Bluetooth settings** opens this screen's side). A USB cable still works.

**The phone does not connect by itself when I get in (tablet).** A tablet is not a Bluetooth car
kit, so the phone never connects to it on its own. CarLink starts the last phone when Home comes
to the front (the app opens, the screen wakes), at most once a minute. After **Disconnect** or
**Cancel**, automatic starts pause until you connect again or for 10 minutes.

**CarLink does not open when I start the car (head unit).** Turn on **Settings › Connection ›
Start with the car**. CarLink then opens when the stereo boots and when it wakes from sleep at
ignition (FYT, GLSX, MediaTek and Microntek/MTCD units that send an "ACC on" signal instead of
rebooting). Some stereo firmware stops every app that is not on its own list when the ignition
goes off, so nothing of CarLink is left to wake. The reliable fix is to make CarLink the stereo's
home screen: press Home, choose CarLink and **Always** (or set it under Android's **Settings ›
Apps › Default apps › Home app**).

**Android Auto drops after about 3 minutes on an aftermarket stereo.** A bug in the Android Auto
app on the phone, fixed by Google in Android Auto 17.4
([arenaev.com](https://www.arenaev.com/two_frustrating_android_auto_bugs_are_now_been_fixed-news-6171.php)):
update Android Auto from the Play Store on the phone.

**Android Auto shows no media controls, only a narrow left rail.** Android Auto draws that reduced layout when the screen gives it less than about 450 dp of height. With **Interface size** on Medium (or Advanced **Pixel density (DPI)** on Automatic) CarLink lowers the DPI to keep the full layout; a DPI you set yourself (or Small or Large) is sent as is, so pick Medium or a lower DPI.

**Black screen after the reverse or 360 camera closes.** It should not happen: while the camera
covers the projection, Android Auto and CarPlay keep their video decoders, and where the picture's
surface does go away they ask the phone for a new picture on return (CarPlay shows the last one
until it arrives). If the
screen stays black for more than a second, please [report it](#reporting-a-problem) with the
stereo model.

**A steering-wheel button cannot be learnt, or does nothing.** Learn sees only buttons the stereo
passes to apps; buttons its firmware keeps for itself never reach CarLink (or any app). Try the
stereo's own steering-wheel settings to give the button a standard media or voice function, then
learn it again. A learnt button applies from the next connection; in CarPlay it works while the
CarPlay screen is in front. See [Steering-wheel buttons](USER_GUIDE.md#steering-wheel-buttons).

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

**From the stereo, in one step: Report on GitHub.** Reproduce the problem, then tap **Report on
GitHub** on Home (it is there when Home says it couldn't connect) or in **Settings › General ›
Diagnostics**, next to Share log. A sheet shows what goes into the report; **Open GitHub** opens
the stereo's browser on a new bug report with everything filled in:

- the title (for example "Android Auto · wireless · couldn't connect: Your phone didn't answer");
- the CarLink version, the screen (maker, model, chip, Android version, size), the phone's name
  and whether it is an iPhone or an Android phone, CarPlay or Android Auto, the connection type
  and the car brand;
- what happened: Home's message and the technical detail (the problem and failure codes, the
  engine's own message, the session number, the time, and what the stereo Bluetooth check found);
- a log excerpt: the last session's CarLink lines first, then the engines' errors from the last
  10 minutes, the timing lines and the newest lines, as much as fits in a link (about 7,500
  characters). It ends with how many more lines only **Share log** has.

Addresses and Wi-Fi or hotspot passwords are removed before anything leaves the stereo. Add what
you did and what you expected, look it over, and tap **Submit**. The browser must be signed in to
GitHub (once; it stays signed in). If the stereo has no browser, CarLink shows a QR code instead:
scan it with your phone to open a shorter report (title, version, screen and the error) and attach
the file from **Share log**.

**By hand:**

1. Reproduce it, then open **Settings › General › Diagnostics** and tap **Share log**. The log
   merges CarPlay, Android Auto and CarLink's own decisions, with addresses hidden.
2. [Open a bug report](https://github.com/F-e-n-y-x/CarLink/issues/new/choose) with: the screen
   (head unit or tablet model) and its Android version, the phone and its iOS or Android version,
   CarPlay or Android Auto, USB or wireless (and which wireless method), what you expected and
   what happened, and the log.
3. Do not post Wi-Fi or hotspot passwords. Security problems go to
   [SECURITY.md](../SECURITY.md), not to a public issue.
