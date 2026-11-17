# Puffble — A Pebble Puffco Companion

Control a **Puffco Proxy** from your **Pebble** watch: see the live temperature, pick a heat profile,
start and stop a session, and watch the countdown, all from your wrist.

![Puffble banner](store/banner_720x320.png)

Puffble has two parts:

| Part | What it does | Where to get it |
|---|---|---|
| **Watch app** (`watch/`) | The screens and buttons on your Pebble | Pebble app store |
| **Android companion** (`companion/`) | Talks to the Proxy over Bluetooth and relays to the watch | [Releases](../../releases/latest) (APK) |

The watch can't talk to the Proxy directly, so the companion must be installed on your phone.

## Requirements

- Android 13 or newer, with the [Pebble app by Core Devices](https://repebble.com)
- A Pebble watch (tested on Pebble Round 2 and Pebble Time 2)
- A Puffco Proxy (tested on firmware API level 7)

## Setup

1. Install the companion APK from [Releases](../../releases/latest).
2. Put the Proxy in Bluetooth pairing mode and pair it in Android's Bluetooth settings.
3. Open **Puffble** on the phone, tap **Connect**, allow Bluetooth and choose your Proxy.
4. Install **Puffble** on the watch from the Pebble app store and accept the
   "exchange messages with Puffble" permission.
5. Close the official Puffco app before using Puffble; two apps controlling the Proxy at once can conflict.

## Using it

| Screen | Buttons |
|---|---|
| Profile | **Up / Down** change profile, **Select** opens the start screen, **hold Up** opens settings, **Back twice** exits |
| Start ("Heat?") | **Hold Select** to start heating, **Back** to cancel |
| Heating / session | **Hold Select** to stop |

Settings on the watch: °F/°C, session alerts, button buzz, light always on, animations,
timeline pins, and backlight color (Pebble Time 2).

## Safety

Puffble controls a device that gets very hot. Starting a heat cycle always needs a deliberate
hold on the watch, and the phone refuses to start one unless the Proxy is idle. Stay with the
device while it heats. You use Puffble at your own risk; see [LICENSE](LICENSE).

## Building

- **Companion:** JDK 21 and the Android SDK (platform 37). `cd companion && ./gradlew assembleDebug`
- **Watch:** the [Pebble SDK](https://developer.repebble.com/sdk/) (Linux, macOS or WSL). `cd watch && pebble build`
- **Icons and store art:** `python watch/tools/make_icons.py` regenerates every icon from one pixel grid.

How the Proxy's Bluetooth protocol works is written up in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Credits

- [PuffcoBLE](https://github.com/Fr0st3h/PuffcoBLE) by Fr0st3h (MIT): the Lorax protocol, opcodes and auth flow.
- [melt-linux](https://github.com/jen88uk/melt-linux) by jen88uk: protocol notes and value encodings.
- [PebbleKit Android 2](https://github.com/pebble-dev/PebbleKitAndroid2) for the watch link.

## Not affiliated

Puffble is an independent project. It is not made, endorsed or supported by Puffco or by
Core Devices. "Puffco" and "Proxy" are trademarks of their owner and are used here only to
say which device Puffble works with. A Puffco firmware update could stop Puffble working.
