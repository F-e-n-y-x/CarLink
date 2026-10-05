# Smooth wireless CarPlay

Two settings decide most of how smooth wireless CarPlay feels: the Wi-Fi channel and the size of the picture the iPhone sends. The numbers below were measured on a 2024 BYD Tang (DiLink 5.0, 2560×1440 screen) with an iPhone on iOS 27, parked, while scrolling the same Apple Music list. Other cars and phones will differ, but the reasons are the same.

## 1. Wi-Fi channel

**Settings → Connection setup → Wi-Fi Direct → Preferred channel**

The car has one Wi-Fi radio. If the car is also joined to a Wi-Fi network on 5 GHz (for example at home) and Wi-Fi Direct runs on a different 5 GHz channel, the radio keeps switching between the two channels. On the Tang this caused:

- the picture stopping for 0.6–2 s at a time;
- music stuttering.

A 2.4 GHz Wi-Fi Direct channel runs alongside a 5 GHz network without switching, and everything was smooth.

- **Auto** (the default) handles this. Next to a 5 GHz network, it first tries that network's own channel, so the radio never has to switch. If the car refuses that channel, as the Tang does, Auto picks 2.4 GHz. With no network joined, for example on the road, Auto tries 5 GHz first.
- If you choose a channel by hand and the car joins a 5 GHz network, choose a 2.4 GHz channel (1–11), or go back to Auto.

## 2. Picture size and screen rotation

**Settings → Display and performance → Turn CarPlay with the screen without reconnecting**

The iPhone encodes every frame it sends. The bigger the picture, the fewer frames per second it manages. DiPlay showed every frame it received in all three cases below, so the limit is on the iPhone's side.

| Setting | Picture | Pixels per frame | Frames per second while scrolling |
|---|---|---|---|
| Rotation **off** | 2560×1440, the screen's own size | 3.7 million | 45–57 |
| Rotation on, **Smoother (1920)** | 1920×1920 square | 3.7 million | 49–57 |
| Rotation on, **Sharper (the screen's size)** | 2560×2560 square | 6.6 million | 32–44 |

With rotation on, CarPlay gets a square picture that holds both a landscape and a portrait screen. Turning the screen then redraws CarPlay at once. A 1920 square has exactly as many pixels as a 2560×1440 screen, so it is as smooth. It is scaled up to fill the screen, though, so text is a little softer. A square the size of a 2560 screen has 1.8 times as many pixels, and the frame rate drops.

**What to choose:**

- **Your screen never turns, or you rarely turn it:** leave rotation **off**. You get the sharpest and smoothest picture. If you do turn the screen, CarPlay reconnects at the new size, which took about 10 s on the Tang.
- **You turn the screen often:** turn rotation on and keep **Smoother (1920)**, the default.
- **Sharper (the screen's size):** on a screen wider than 1920 pixels it costs frames. On a smaller screen it is the same as Smoother.

## 3. Checking it yourself

While CarPlay runs, DiPlay logs a `DiPlay-VideoStats` line every 5 s:

```
video stats rx=56.2fps shown=56.4fps maxGap=73ms kbps=28899 ...
```

- `rx` is the frames the iPhone sent; `shown` is the frames DiPlay displayed.
- If `shown` keeps up with `rx` but `rx` is low while you scroll, the picture size or the Wi-Fi link is the limit, not the car.
- `maxGap` also counts moments when nothing on the screen changed, because the iPhone sends no frames then. A single large value is not a stall by itself.
