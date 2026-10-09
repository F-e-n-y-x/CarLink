# How shipping receivers make wireless CarPlay / Android Auto "just work"

Research for CarLink's connection redesign, 2026-10-09. Sources are linked; vendor pages and
forum posts are practical guidance, not specifications.

## The common model

Every product looked at uses the same three-part model:

1. **The receiver hosts its own private Wi-Fi link.** Carlinkit/AutoKit, Ottocast, AAWireless,
   ZLink head units and Open Headunit's native mode all have the car side provide the network
   (Wi-Fi Direct or its own access point). Bluetooth starts the session; Wi-Fi carries it
   ([Carlinkit AutoKit FAQ](https://carlinkitcarplay.com/pages/faq-en-autokit-for-aftermarket-android-screen-cars-version1-0),
   [AAWireless review, 9to5Google](https://9to5google.com/web-stories/this-is-how-you-get-wireless-android-auto/),
   [Open Headunit wiki: Wireless](https://github.com/andreknieriem/open-headunit/wiki/Wireless)).
   Open Headunit (DiAuto's upstream) rates Wi-Fi Direct "Best for stability. No hotspot
   required". DiPlay's Existing Wi-Fi / Same LAN is validated on one BYD only and needs the
   router to pass multicast DNS (`vendor/diplay/docs/EXISTING_WIFI.md`).
2. **First pairing is done from the phone, once.** The car screen shows its Bluetooth name; the
   driver pairs to it in the phone's Bluetooth settings, then accepts the phone's own prompt
   (CarPlay: "Use CarPlay?"; Android Auto: its setup screens)
   ([Carlinkit first connection](https://carlinkitcarplay.com/blogs/carlinkit-5-0-2air-faq/carlinkit-2air-the-first-connection-video-tutorial),
   [AAWireless setup](https://www.aawireless.io/it/support/topics-faq-aawireless/setting-up-aawireless)).
   Reviewers report two to five minutes for a first-timer.
3. **After that, nothing to do.** With the phone's Bluetooth and Wi-Fi on, it reconnects when the
   car starts; reported times 15–30 s. The phone keeps using mobile data for internet; the
   car link is local only.

## Lessons for CarLink

- Default wireless path = the receiver's own link (Wi-Fi Direct, or the car's hotspot when the
  firmware exposes it). Same Wi-Fi is an advanced option, offered only where it is proven.
- The phone using mobile data during wireless projection is normal for every product; say so
  in the UI instead of trying to fight it.
- First-time setup must be a guided screen on the car: make the receiver discoverable, show its
  Bluetooth name large, tell the driver to pair from the phone and accept the phone's prompt,
  and detect each step live. Tablets often cannot initiate the car-kit Bluetooth role, which is
  exactly why the phone-initiated pairing is the norm.
- Common failure causes the products' FAQs name: stale Bluetooth pairings (fix: forget and
  re-pair), the phone already tied to another car/adapter, interference on the chosen channel,
  phone OS quirks (Samsung One UI), weak USB power. CarLink's recovery screens should map to
  these.
- Keep the phone unlocked during the first connection; later connections work locked.
