# Effects research: MrStar, Raingel, Magic Home

Research notes for building out effect/mode support later. Extracted by pulling
each app's APK via `adb` and decompiling with `jadx`/`apktool`. Byte formats
below are as found in each app's decompiled source unless marked otherwise;
none of this has been implemented in `nexuslights` protocol code yet, and none
of it has been tested against real hardware except where noted.

---

## MrStar (Desk Strip, package `com.findn.mrstar`)

Frame shape: `[0xBC, cmd, sub, ...args, 0x55]` (matches the confirmed-working
power/color/brightness commands already in `protocols/mrstar.py`).

**Effect select command** (`ModeFragment.f0(int)`, confirmed from decompiled
source):

```
[0xBC, 0x06, 0x02, effect_id // 255, effect_id % 255, 0x55]
```

Note the divisor is `255`, not the more typical `256` -- copied verbatim from
the app, an apparent off-by-one in the original code that should be matched
exactly rather than "corrected".

**Effect names/IDs: not found.** `ModeFragment.java`'s method that maps UI
selections (multiple tabs/categories) to a final effect ID (`P()`) failed to
decompile to readable Java (jadx: "Method not decompiled"), and no effect name
strings exist in `strings.xml` or `arrays.xml` -- names are likely either
hardcoded in bytecode or generated programmatically (e.g. "Effect N"). The
partial smali analysis showed multiple category tabs, each adding a different
fixed offset (seen: `+0x14`, `+0x15`, and a special-cased `0x7`) to a
per-category index before calling `f0()`, implying effect IDs span multiple
numeric ranges per tab rather than one flat 1..N list.

**Practical path forward:** empirical sweep (send `effect_id` 1..N in sequence,
observe/record what each looks like) -- we started this during troubleshooting
and got through IDs 1-15 before moving on to other issues. IDs 1-5 were varied
animations (one was a fast strobe/flash). No static color effect was found;
static color is achieved via the regular `set_rgb()`/`set_brightness()`
commands instead (confirmed working).

---

## Raingel (VC-BLELIGHT, package `com.raingel.app`)

Much less obfuscated than MrStar -- clear class/method names throughout.
Commands go through `StripManager.write()`, format:
`[cmd_byte, ...args]` sent directly to characteristic `AE01` as
**write-with-response** -- no extra header, length prefix, or checksum
(confirmed by reading `StripManager.write()` directly).

### All commands found in `StripManager.java`

| Cmd (dec) | Method | Byte layout | Notes |
|---|---|---|---|
| 1 | `switchLight(on)` | `[1, on?1:0]` | matches our `vcblelight.py` power on/off |
| 2 | `updateBrightness(pct)` | `[2, pct]` | separate from color, like mrstar |
| 3 | `ColorAdjust(r,g,b,i4,i5,i6)` | `[3, r, g, b, i4, i5, i6]` | our `set_rgb()` sends `i4-i6` as `0,0,0`; unclear what non-zero values do here (a second/white channel?) -- worth testing |
| 4 | `deviceMicUpdate(a,b)` | `[4, a, b]` | music/mic reactive mode |
| 5 | `updateDelay(on, x, i)` | `[5, on?1:0, 0x01, i_lo, i_hi]` | |
| 7 | `ModeAdjust(...)` | `[7, ...]` | **effects, see below** |
| 8 | `updateDateTime(...)` | `[8, ...]` | sets device clock, for scheduling |
| 12 | `updateTimer(...)` | `[12, ...]` | |
| 13 | `updateAlarm(list)` | `[13, count, (hour\|slot<<5, min\|action_flag<<7, weekday_mask) * N]` | up to 6 alarms |
| 14 | `IcOrder(i)` | `[14, i]` | LED chip color-order config (RGB vs GRB etc, for addressable strips) |
| 15 | `IcLength(i)` | `[15, i_lo, i_hi]` | pixel count config |
| 16 | `recorderUpdate(...)` | `[16, ...]` | |
| 17 | `openPhoneMic()` | `[17, 4]` | |
| 18 | `setRgbOrder(a,b)` | `[18, a, b]` | |

### Effects (cmd 7, `ModeAdjust`)

Two overloads exist:

- **RGB gradient/chase modes**: `ModeAdjust(tabPosition+1, speed, brightness, colorCount, colorHexPattern)` →
  `[7, tabPosition+1, speed, brightness, colorCount, ...colorHexPatternBytes]`
- **"Magic" modes** (non-RGB-strip-specific, section/direction/alternate/etc. flags folded into one byte via `Mode.formatValue()`): `ModeAdjust(formatValue(), speed, brightness, colorHex)` →
  `[7, formatValue(), speed, brightness, ...colorHexBytes]`

`Mode.formatValue()` packs `modeId` (lower 5 bits) with a flag in the top
bit(s) depending on which mode family it is (section/direction bit 6/7,
alternate bit 6 for mode 16, on/off status bit 6 for mode 17, colorful bit 6
for mode 14) -- see `Mode.java` `formatValue()` for the exact per-mode logic
if implementing this.

**`RGB_MODES`** -- 30 named gradient/chase patterns (`Mode.java` static
initializer + `arrays.xml` → `mode_rgb_colors`), each defined by how many
colors cycle and in what order (color indices, not raw RGB -- presumably
indices into a user-configurable color palette elsewhere in the UI):

| ID | Colors (index pattern) | Name |
|---|---|---|
| 0 | 6,7 | Orange→White |
| 1 | 5,6 | Violet→Orange |
| 2 | 4,5 | Cyan→Violet |
| 3 | 3,4 | Yellow→Cyan |
| 4 | 2,3 | Blue→Yellow |
| 5 | 1,2 | Green→Blue |
| 6 | 0,1 | Red→Green |
| 7 | 5,6,7,0 | Violet→Orange→White |
| 8 | 4,5,6,0 | Cyan→Violet→Orange |
| 9 | 3,4,5,6 | Yellow→Cyan→Violet |
| 10 | 2,3,4,0 | Blue→Yellow→Cyan |
| 11 | 1,2,3,0 | Green→Blue→Yellow |
| 12 | 0,1,2,0 | Red→Green→Blue |
| 13 | 4,5,6,7 | Cyan→Violet→Orange→White |
| 14-29 | (more of the same, escalating color counts up to full 7-color rainbow + white) | see `mode_rgb_colors` array / `RGB_MODES` in `Mode.java` for the complete list |

**`SPHERE_MODES`** -- 8 patterns, specific to a sphere-shaped light variant
(likely not relevant to VC-BLELIGHT specifically, but noted for completeness):
2-3 color combinations of Red/Green/Blue/White (`mode_sphere_colors` array).

**`MAGIC_MODES`** -- 18 categories (`MODE1`-`MODE18`), each with up to 38
variants (base pattern × forward/reverse × on/off-status-like flags depending
on category). These looked like classic "chase along N of 8 conceptual
segments" patterns (patterns like `"0512345000"` read as: which of up to 8
segments are "lit" in this frame of the chase, essentially a bitmap/sequence
description) -- exact rendering semantics not reverse-engineered, would need
empirical testing per category to map to something like "wipe", "chase",
"bounce", etc.

**Not yet tested against real hardware** -- VC-BLELIGHT currently doesn't
respond to any command at all due to a separate suspected BLE bonding issue
(see main project history); this data is ready to use once that's resolved.

---

## Magic Home / Zengge (package `com.zengge.wifi`, controls the LEDnetWF chip family VC Light uses)

This is the manufacturer app for the same chip family already implemented in
`protocols/lednetwf.py` (reverse-engineered earlier from community research,
not from this decompile). This app confirms and extends that research with
clean, localized effect names.

### Symphony effects (44 named + custom slots)

Defined in `aa/i.java` (`f()` method) as `SymphonyEffect(id, name, uiType)`,
IDs 1-44, each tagged with what color inputs its UI needs
(`SymphonyEffectUIType`: foreground+background, start+end, first+second,
foreground-only, background-only, or no color input at all):

| ID | Name | UI color type |
|---|---|---|
| 1 | Change gradually | start+end color |
| 2 | Bright up and Fade gradually | foreground only |
| 3 | Change quickly | start+end color |
| 4 | Strobe-flash | start+end color |
| 5 | Running, 1point from start to end | foreground+background |
| 6 | Running, 1point from end to start | foreground+background |
| 7 | Running, 1point from the middle to the both ends | foreground+background |
| 8 | Running, 1point from the both ends to the middle | foreground+background |
| 9 | Overlay, from start to end | foreground+background |
| 10 | Overlay, from end to start | foreground+background |
| 11 | Overlay, from the middle to the both ends | foreground+background |
| 12 | Overlay, from the both ends to the middle | foreground+background |
| 13 | Fading and running, 1point from start to end | foreground+background |
| 14 | Fading and running, 1point from end to start | foreground+background |
| 15 | Olivary Flowing, from start to end | foreground+background |
| 16 | Olivary Flowing, from end to start | foreground+background |
| 17 | Running, 1point w/background from start to end | foreground+background |
| 18 | Running, 1point w/background from end to start | foreground+background |
| 19 | 2 colors run, multi points w/black background, start→end | first+second color |
| 20 | 2 colors run, multi points w/black background, end→start | first+second color |
| 21 | 2 colors run alternately, fading, start→end | first+second color |
| 22 | 2 colors run alternately, fading, end→start | first+second color |
| 23 | 2 colors run alternately, multi points, start→end | first+second color |
| 24 | 2 colors run alternately, multi points, end→start | first+second color |
| 25 | Fading out Flows, start→end | first+second color |
| 26 | Fading out Flows, end→start | first+second color |
| 27 | 7 colors run alternately, 1pt w/ multi-pt background, start→end | background only |
| 28 | 7 colors run alternately, 1pt w/ multi-pt background, end→start | background only |
| 29 | 7 colors run alternately, 1 point, start→end | no color (fixed rainbow) |
| 30 | 7 colors run alternately, 1 point, end→start | no color |
| 31 | 7 colors run alternately, multi points, start→end | no color |
| 32 | 7 colors run alternately, multi points, end→start | no color |
| 33 | 7 colors overlay, multi points, start→end | no color |
| 34 | 7 colors overlay, multi points, end→start | no color |
| 35 | 7 colors overlay, multi points, middle→both ends | no color |
| 36 | 7 colors overlay, multi points, both ends→middle | no color |
| 37 | 7 colors flow gradually, start→end | no color |
| 38 | 7 colors flow gradually, end→start | no color |
| 39 | Fading out run, 7 colors, start→end | no color |
| 40 | Fading out run, 7 colors, end→start | no color |
| 41 | Runs in olivary, 7 colors, start→end | no color |
| 42 | Runs in olivary, 7 colors, end→start | no color |
| 43 | Fading out run, 7 colors starting white, start→end | no color |
| 44 | Fading out run, 7 colors starting white, end→start | no color |

Plus up to 300 additional user-buildable custom patterns ("SymphonyBuild_1"
through "SymphonyBuild_300", `aa/i.java` method `i()`), which are
user-programmed pixel sequences rather than fixed presets -- lower priority to
support.

### Byte command for selecting a symphony effect

**Not confirmed from this decompile** -- the exact send call site wasn't found
in the (heavily obfuscated) app code within a reasonable search budget. Use
the community-reverse-engineered format already documented in
`protocols/lednetwf.py`'s history/research instead (from
`github.com/8none1/zengge_lednetwf`), which matched real captured traffic for
this exact chip family:

```
[0x00, counter, 0x80, 0x00, 0x00, 0x04, 0x05, 0x0b, 0x38, effect_id, speed, brightness]
```

Given this is a different byte layout than what `lednetwf.py` currently
implements (only power_on/off/set_rgb so far), effect support for VC Light
would be new protocol code, not yet written.

---

## Summary / next steps

- **MrStar (Desk Strip)**: byte format for selecting an effect is known; effect
  ID → name/behavior mapping is not, needs empirical sweep testing.
- **Raingel (VC-BLELIGHT)**: full byte format and 30 named RGB gradient modes
  are known with high confidence (matches the app's actual source); blocked on
  the separate hardware bonding issue before any of this can be tested.
- **Magic Home / LEDnetWF (VC Light)**: 44 named symphony effects with clean
  descriptions are known; the exact send-command bytes are inferred from prior
  community research, not from this decompile -- worth a quick real-hardware
  test before building on top of it, same as every other protocol in this
  project.
