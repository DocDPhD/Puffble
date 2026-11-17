# Pebble app store listing — Puffble 0.5.0

Everything to paste into the developer dashboard, field by field.
Dashboard: <https://developer.repebble.com/dashboard> (sign in with GitHub as DocDPhD).
Apps go live as soon as they're submitted; there is no review queue.

## Basics

| Field | Value |
|---|---|
| App name | `Puffble` |
| Type | Watchapp |
| Category | `Remotes` |
| Version | `0.5.0` |
| Build | `Puffble-watch-0.5.0.pbw` from the [v0.5.0 release](https://github.com/DocDPhD/Puffble/releases/tag/v0.5.0) |
| Visibility | Listed (choose Unlisted if you'd like to share the link privately first) |
| Website URL | `https://github.com/DocDPhD/Puffble` |
| Source code URL | `https://github.com/DocDPhD/Puffble` |

## Description (plain text; the store doesn't render formatting)

```
Puffble puts your Puffco Proxy on your wrist.

See the live temperature, flip between your four heat profiles, start a session with a deliberate hold, and watch the countdown, all without reaching for your phone. Each profile gets a page in its own ring color, with chunky pixel art and classic Pebble page swipes.

- Live heater temperature, target, countdown and Proxy battery
- Change profiles instantly with Up and Down
- Start with hold-to-confirm; stop any time by holding Select
- "All done" screen, end-of-session buzz and a timeline pin
- Cool-down screen with a 2-minute gauge if the Proxy shuts off to cool
- Settings on the watch: °F/°C, session alerts, button buzz, light always on, animations, timeline pins, backlight color (Pebble Time 2)

REQUIRES the free Puffble Android companion app (Android 13+), which connects to the Proxy over Bluetooth. Get it from github.com/DocDPhD/Puffble/releases. No iPhone support yet.

Tested on Pebble Round 2 and Pebble Time 2 with a Puffco Proxy. Close the official Puffco app while using Puffble.

Puffble controls a device that gets very hot: stay with it while it heats. Puffble is an independent project, not made or endorsed by Puffco. Puffco and Proxy are trademarks of their owner. Puffble collects no data: github.com/DocDPhD/Puffble/blob/main/PRIVACY.md
```

## Companion app section

| Field | Value |
|---|---|
| Name | `Puffble companion` |
| Store URL | `https://github.com/DocDPhD/Puffble/releases/latest` (the field says Play Store; if it rejects a GitHub link, leave this section empty — the description and the watch app both point to the releases page) |
| Icon (144×144) | `store/icon_144.png` |

## Images

| Asset | File | Size |
|---|---|---|
| Large icon | `store/icon_144.png` | 144×144 |
| Small icon | `store/icon_80.png` | 80×80 |
| Banner | `store/banner_720x320.png` | 720×320 |
| Screenshots | see below | per platform |

### Screenshots

Upload these under the matching platform (files in `store/screenshots/`):

| Platform | Watch | Files (in this order) |
|---|---|---|
| gabbro | Pebble Round 2 (round, 260×260) | `gabbro_1_profile.png`, `gabbro_2_session.png`, `gabbro_3_cooldown.png` |
| emery | Pebble Time 2 (rectangular, 200×228) | `emery_1_profile.png`, `emery_2_session.png`, `emery_3_cooldown.png` |

They were captured from the emulator with demo data (`PUFFBLE_DEMO` builds; the regular app never
contains it). Other platforms can go without screenshots.

## Release notes (per release, optional)

```
First public release. Live Proxy state, instant profile changes, hold-to-confirm start, session countdown, timeline pins and a cool-down screen. Requires the Puffble Android companion.
```

## Content rules

The store's program policies prohibit illegal activity and malware but say nothing about
vaporizers, tobacco or cannabis (checked 2026-11-17). Puffble doesn't sell anything or encourage
use; it's a remote for a device you own. The description keeps to that, includes the safety and
not-affiliated notes, and links the privacy policy.

## Before you press Submit

- [ ] Signed in with GitHub as DocDPhD
- [ ] `.pbw` uploaded is the one from the v0.5.0 release (UUID `1b43e005-bf25-4e85-909e-80da9a074ece`)
- [ ] Icons, banner and at least one screenshot uploaded
- [ ] Category `Remotes`, description pasted, source URL set
- [ ] Companion section filled (or left empty, see above)
