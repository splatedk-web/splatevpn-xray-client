# Approved Splate VPN icon

User-approved direction: blue circular badge, white shield outline and a
clear white S. `splate-icon-approved.png` preserves the selected imagegen
proposal. The shipped white mark is traced from the approved shield/S
contours with `scripts/trace_approved_launcher.cjs`, rather than using a
hand-drawn replacement glyph. It has no white outer background, bevels
or photographic texture.
The shield contours have an additional 0.7 viewport-unit white stroke
for stronger readability at launcher sizes; the traced S is unchanged.

Android sources:

- `app/src/main/res/drawable/ic_launcher_mark.xml`: white shield and S;
  both remain inside the adaptive icon safe area.
- `app/src/main/res/drawable/ic_launcher_background.xml`: blue gradient.
- `mipmap-anydpi-v26`: adaptive launcher and round icon.
- `mipmap-anydpi-v33`: same artwork with an explicit monochrome layer.
- The splash screen uses the updated mark.

`scripts/generate_launcher_icons.cjs` generates ten density-specific WebP
fallbacks, these SVG/PNG previews and `app/src/main/ic_launcher-playstore.png`
from the Android vector path geometry. The repository README uses the round
`splate-launcher-preview.png`; the distribution PNG uses the same shield/S mark.
It requires Node.js and sharp, available through normal module resolution
or NODE_PATH. No app build dependency on Node.js is introduced.

The app version, application ID and signing configuration are unchanged.
The Android app label is `SplateVPN`. The home and startup headings retain
the spaced uppercase brand `SPLATE VPN`.

Validation: `:app:assembleDebug` passed. The x86_64 debug APK was installed
on the dedicated Android QA emulator without clearing app data. The new
blue shield/S icon was visually checked in the launcher app drawer and
the app launched successfully. Screenshot:
`build/ui-review/app-icon-launcher.png`. The monochrome resource is
packaged for Android 13+; themed-icon mode was not visually exercised.
