# TempWatch

An Android app for the **TempPro TempSpike CORE (TP961)** wireless meat thermometer, the one formerly sold as ThermoPro.
It also handles the other TempSpike models (TP960, TP962, TP970, TP972).
It needs no account, cloud service or pairing.

## Features

- **Live temperatures**: the meat temperature on a big dial, plus grill air temperature, probe battery and signal strength.
- **Meat presets**: beef, pork, chicken, turkey, lamb, ground meat, fish, BBQ (brisket, pulled pork, ribs) and ham, each with doneness choices. Targets below the USDA safe minimum are flagged. You can also set a custom target.
- **Target alarm**: when the meat hits the target, your phone's alarm sound plays on the alarm volume and keeps going until you stop it (it gives up after 10 minutes). There's also an optional "almost done" alert 5°F (3°C) before the target.
- **Time-left estimate**: "About 25m left · ready around 6:42 PM", based on how fast the meat has risen over the last 20 minutes. It tells you when the cook has stalled.
- **Live graph** of meat, grill and target during the cook.
- **Cook history**: every finished cook is saved with its graph, cook time, peak temperatures and time to target.
- **Lock-screen live card**: during a cook the app keeps listening with the screen off. It shows a live card on the lock screen, and on Android 16 a temperature chip in the status bar (Samsung's Now Bar). The card has *End cook* and *Stop alarm* buttons.
- **Signal-lost warning** after 90 seconds without a reading.
- **OLED dark look**: true black everywhere, one card for the probe with a ring of ticks that warms from cool blue through red and orange as the meat heats up. The temperature numbers take the same colour, and the target is a white tag on the ring. The app icon is the same ring, on black.

## How it works

Most TempSpike boosters put the probe's readings into their Bluetooth advertisement, so for those the app just listens for broadcasts.
The TP961 is different; see below.
The byte layout comes from the open-source [thermopro-ble](https://github.com/Bluetooth-Devices/thermopro-ble) project, which is what Home Assistant uses:

| bytes | meaning |
| --- | --- |
| 0 | probe number |
| 1–2 | meat temperature, °C + 30 (little-endian) |
| 3–4 | battery, millivolts |
| 5–6 | grill temperature, °C + 30 |

Android splits the first two bytes off as a "company ID", so `TempSpikeDecoder.frame()` glues them back on before decoding.

### TP961 (TempSpike CORE): readings over a connection

The TP961 advertises as **"I61"**, and its broadcast carries only its own address backwards.
So TempWatch connects to it (`SpikeLink`), with no pairing or login, and subscribes to one characteristic:

- service `93bb7cab-eb14-c5a9-2354-a24f4df9330f`, characteristic `0000ff01-…` (notify)
- every 3 s, one notification per probe slot: `10` followed by the same 7-byte frame as above, e.g. `10 00 3D 00 3D 0A 3D 00` = probe 1, meat 31°C, 2621 mV, grill 31°C
- the booster reports two slots; an empty slot reads −1°C on both sensors and is skipped

The link stays up while the app is open or a cook is running, and reconnects on its own (screen off included).
The booster takes one connection at a time, so close the TempPro app while using TempWatch.

Things to know:

- **Close the TempPro app while using TempWatch.** The booster may stop broadcasting while another app is connected to it.
- Readings come in **whole °C**, so in °F they step by about 2°.
- If your thermometer shows up but no temperatures appear, open **Settings → Bluetooth details**, tap **Copy report**, and paste it into an issue.

## Install

Each push to `main` builds a signed APK and publishes it under **Releases**.
On your phone, download the newest `tempwatch-1.0.N.apk` and open it.
New versions install over old ones and keep your settings and history.

## Build locally

```
./gradlew testDebugUnitTest assembleRelease
```

Requires JDK 17 and the Android SDK (compileSdk 36).
The release build is signed with the shared sideload key in `keystore/`.

Not made by or affiliated with TempPro / ThermoPro.
