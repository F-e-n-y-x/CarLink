# CarPlay view areas: dock position and split screen

A CarPlay accessory may declare several **view areas** inside the main screen's video stream and tell the iPhone which one to draw in. DiPlay uses this for two optional settings, both without reconnecting CarPlay:

- **CarPlay dock** (Settings → Display and performance, and the in-session menu): Automatic (default), Driver's side or Bottom.
- **CarPlay in the head unit's split screen** (Settings → Display and performance, off by default): when the head unit shows DiPlay in half of the screen, CarPlay redraws for that window instead of shrinking with black bars, and returns to full screen.

## How it works

- `/info` lists the main display's `viewAreas`: rectangles of the stream (`widthPixels`, `heightPixels`, `originXPixels`, `originYPixels`) with their safe areas. With more than one, DiPlay adds `viewAreaTransitionControl = true`. Apple's own widescreen profile in Xcode's CarPlay Simulator declares two (the whole 1920×720 and the right 1280×720).
- A view area may carry `viewAreaStatusBarEdge`, which places CarPlay's dock. CarPlay Simulator's StatusBarEdge enum has automatic, bottom and driver. On a Tang, `1` put the dock at the bottom and `2` on the driver's side.
- The car switches areas on the event channel with `updateViewArea {uuid, viewAreaIndex, animationDurationMillis, adjacentViewAreas}`. The key names sit next to `AirPlayReceiverSessionViewAreaUpdate` in CarPlaySDK's strings. The iPhone acted on it only with the animation duration and the adjacent areas.
- The iPhone keeps streaming the whole frame. DiPlay lays out the canvas so that the area in use fills its window, and touches follow the same rectangle.

DiPlay declares:

| Settings | Areas |
|---|---|
| Automatic dock, no split screen | the whole screen as one area (as before) |
| Fixed dock | the whole screen once per edge (driver's side, bottom) |
| Split screen | the above, plus an area the size of DiPlay's split-screen window, once per dock edge |

Moving the dock between the fixed edges switches to the same kind of area with the other edge. Entering or leaving the split screen switches between the whole-screen and split-screen areas with the same edge. Other window changes (rotation, camera windows, floating windows of another shape) keep their existing handling.

The split-screen window is remembered per screen orientation as a fraction of the full window. BYD shows its status and navigation bars in split screen, so DiPlay's window is smaller than half the screen (on a Tang 1270×1208 of 2560×1440, or 1440×1154 of 1440×2560 when split top and bottom). The first time, DiPlay uses half the screen and learns the real size; from the next connection the area matches exactly. A session that starts while DiPlay is already in split screen sizes its canvas to that window and keeps one area.

With any second area declared, CarPlay with an Automatic dock puts the dock at the bottom; choose Driver's side to keep it there.

## Tested

2024 BYD Tang (DiLink 5.0, 2560×1440 centre screen) with an iPhone on iOS 27, wireless CarPlay:

- **Dock:** Automatic → Bottom reconnected. Bottom ↔ Driver's side moved at once, from Settings and from the in-session menu.
- **Split screen:** with BYD's split screen (DiPlay left, BYD media right, and top/bottom on the portrait screen), CarPlay filled DiPlay's half at its proportions and returned to full screen, without reconnecting. Touches worked in both.
