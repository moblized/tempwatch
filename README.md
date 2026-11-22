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
- Light and dark themes follow the phone, and so does the app icon.

## How it works

The TempSpike booster puts the probe's readings into its Bluetooth advertisement, so the app just listens for broadcasts.
It never connects to the booster.
The byte layout comes from the open-source [thermopro-ble](https://github.com/Bluetooth-Devices/thermopro-ble) project, which is what Home Assistant uses:

| bytes | meaning |
| --- | --- |
| 0 | probe number |
| 1–2 | meat temperature, °C + 30 (little-endian) |
| 3–4 | battery, millivolts |
| 5–6 | grill temperature, °C + 30 |

Android splits the first two bytes off as a "company ID", so `TempSpikeDecoder.frame()` glues them back on before decoding.

The TP961 advertises under the name **"I61"**, not "TP961" (also found in [thermopro-ble #224](https://github.com/Bluetooth-Devices/thermopro-ble/pull/224)).
Between readings, for example while the probe is in the booster, it sends a 6-byte frame that is just its own address backwards.
Those frames are ignored.

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
