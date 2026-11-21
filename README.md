# G20 IR Setup

An Android TV app that programs the five IR keys (power, input, volume +/−, mute) of the
**Google G20 reference remote** while it stays paired to the device the app runs on, for
example an **NVIDIA Shield** that has no "Set up remote buttons" menu.

## Why "guided testing" and not IR learning

The G20 cannot learn codes from another remote: its firmware (Telink TLSR827x,
`refDesignRcu/vendor/827x_ble_remote/app_ir.c`) only transmits IR and has no receive path,
and the Shield has no IR receiver either. So the app does what Google's own setup does:

1. You pick what to set up (TV, TV power/input only, or soundbar/receiver volume), the kind
   of device and the brand.
2. The app writes the most common code set for that brand to the remote and asks you to
   press a key (volume + first, because it is harmless).
3. The remote reports the key press to the Shield over Bluetooth, and the app asks:
   *did the TV respond?*
4. **No** → next code set. **Yes** → the app checks the other keys one by one. If one
   doesn't work it tries the alternatives from the same brand. If none work, the key is
   left on Bluetooth.
5. The working codes are saved on the remote and in the app.

You can also set any key by hand:
- **From the code database:** pick a brand and code.
- **Protocol, address and command:** with "Command +1/−1" to step through a protocol's
  commands until the TV reacts.
- **Paste a code:** Pronto hex, a raw G20 payload, or raw µs timings.

## Check remote, export and import

- **Check remote** shows the firmware version and battery, then asks you to press each key.
  A key with an IR code is reported by the remote itself; a key without one arrives as a
  normal Bluetooth key. The stored codes themselves can't be read back: the firmware has no
  read access to its IR table.
- Settings are stored **per remote** (by Bluetooth address). For each remote the app also
  remembers exactly what it last wrote successfully, and the home screen says whether the
  remote holds the saved codes.
- **Export / import**: while that screen is open the TV serves a small page on port 8080
  (for example `http://192.168.1.42:8080/`). From a phone or PC you can download
  `g20-ir-config.json` or upload one; uploads must be confirmed on the TV. The same file is
  also kept in `Android/data/io.github.g20ir/files/` for `adb pull` / `adb push`.

  File format: `{"format": "g20-ir-setup", "version": 1, "inputKeyId": "0x00B2",
  "keys": {"power": {"signal": ["SIRC", 1, 21], "source": "...", "g20": "...", "pronto": "..."}}}`.
  On import each key needs one of `signal`, `g20` or `pronto`, so a hand-written file with
  only Pronto codes works.

## Code database

`app/src/main/assets/irdb.json` comes from [Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB)
(CC0). It holds TVs, projectors, monitors, soundbars, AV receivers and speakers, keeping only
the five relevant functions. Model files that share codes are merged and ranked by how many
models use them. Regenerate it with:

    git clone --depth 1 https://github.com/Lucaslhm/Flipper-IRDB
    python3 tools/build_irdb.py Flipper-IRDB app/src/main/assets/irdb.json

The protocol encoders (`ir/Encoders.kt`) are ported from the Flipper Zero firmware so they
match the database exactly. Supported protocols: NEC, NECext, NEC42, Samsung32, RC5/RC5X,
RC6, SIRC 12/15/20, RCA, Pioneer, Kaseikyo, and raw.

## Remote limits that matter

- A code must arrive in **one** BLE write. The firmware's chunk reassembly is broken, and its
  MTU is 180, so a code can be at most **177 bytes**. Panasonic's Kaseikyo (48-bit) and NEC42
  codes don't fit, so the app shows them as not usable.
- At most 5 keys. All keys are written in one session, and starting a session clears the table.
- Program from the Shield itself: the firmware wipes the IR table when the remote bonds with
  a different host.
- If your remote's input key reports as the "magic" key, switch the input key ID to 0x0139
  under Diagnostics.

## Build and install

    ./gradlew :app:testDebugUnitTest :app:assembleRelease
    adb connect <shield-ip>:5555        # Settings → Device preferences → Developer options → Network debugging
    adb install -r app/build/outputs/apk/release/app-release.apk

Ready-made APKs are on the [Releases](../../releases) page; pushing a `v*` tag builds and
publishes one (`.github/workflows/release.yml`).

Signing: Android only installs an update signed with the same key as the installed app, so
releases should use one fixed key kept in GitHub secrets (never in the repo):

    keytool -genkeypair -keystore g20ir.jks -alias g20ir -keyalg RSA -keysize 2048 -validity 10000
    base64 -w0 g20ir.jks    # -> secret KEYSTORE_BASE64

and add the secrets `KEYSTORE_PASSWORD`, `KEY_ALIAS` (g20ir) and `KEY_PASSWORD` under
Settings → Secrets and variables → Actions. Without them the workflow signs with a throwaway
debug key, which works for a first install but can't update a previous build.

`tools/legacy/` holds the original Python scripts (Pronto ↔ G20 converter and the Sony SIRC
generator).
