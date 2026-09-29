# App localization

DiAuto includes English, Simplified Chinese (`zh-CN`), Arabic, Russian and Spanish,
alongside its existing community translations. Change the language in Settings →
App language; System default follows the device. Arabic uses right-to-left layouts.
Simplified Chinese starts from the existing Traditional Chinese translation,
converted to Simplified Chinese, with the newer screens translated separately.

On Android 13 and newer the in-app picker and Android's App languages settings
use the same platform preference. An existing saved choice migrates once, without
overwriting a choice already made in Android settings. Older Android versions
continue to use the saved context override. Settings export, import and reset also
include the language preference.

Connection setup, diagnostic export and newer connection/audio settings use
translated resources rather than hardcoded English. Spanish (Spain) inherits
completed generic Spanish text where it previously contained English placeholders.
Protocol names, product names and explicitly non-translatable developer controls
remain unchanged.

Run `python3 scripts/check_localization.py` to check coverage and format arguments
for the five core languages and to check the system language configuration.
