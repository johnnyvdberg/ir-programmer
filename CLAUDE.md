# G20 IR programmer for NVIDIA Shield

## Goal
Program the 5 IR keys (power, vol+, vol-, mute, input) of the **Google G20 reference
remote** (TW Electronics/Ohsung, Telink TLSR827x) while it stays paired to an
**NVIDIA Shield TV** (Android TV 11, no Google TV "Set up remote buttons" menu).
Owner's TV: Sony 49" Bravia (85-series) → Flipper-IRDB Sony code 1 (SIRC dev 1:
power 21, vol+ 18, vol- 19, mute 20, input 37), the same as the hand-derived guess.

## Layout
- `app/`: Kotlin + Compose for TV app (package `io.github.g20ir`)
  - `ir/Encoders.kt`: protocol encoders ported from Flipper firmware `lib/infrared/encoder_decoder`
  - `ir/G20Payload.kt`: waveform → G20 payload (type selection, size fitting)
  - `ir/TextFormats.kt`: Pronto / G20 hex / raw µs parsing, Pronto export
  - `ble/G20Remote.kt`: GATT client (program, clear, suppress, key-event notifications)
  - `ui/AppViewModel.kt`: guided test state machine, manual editor, check screen,
    export/import, persistence (prefs keys `config:<MAC>`, `applied:<MAC>`, `inputKeyId:<MAC>`;
    legacy un-keyed `config` migrates to the first remote that connects)
  - `model/ConfigIo.kt`: export/import JSON format
  - `net/WebTransfer.kt`: tiny HTTP server (port 8080, fallback random) for export/import,
    only while the Export/import screen is open
  - `buttons/`: Bluetooth-only buttons (colour, app, gear, info, guide, subtitles, bookmark).
    `ButtonService` = accessibility service (auto-starts at boot) mapping keys by Linux scan
    code to actions; `AdbKeyHelper` connects to the Shield's own adbd (127.0.0.1:5555, dadb,
    key in filesDir) and runs `getevent -l` on a pty (getevent never flushes) to tell the four
    app buttons apart by HID usage; `ButtonStore` = prefs + shared state.
    Actions: open app, Home/Back/Recents/Notifications/Settings, or "Send key" (any Android
    key code via `input keyevent` on a persistent adb shell; `KeyCatalog`). Optional
    "when held" action per button (600 ms; short press then fires on release).
    Android 11 Generic.kl has no entries for KEY_RED..BLUE (398-401), KEY_INFO (358),
    KEY_SUBTITLE (370), KEY_TAPE (384) → apps get KEYCODE_UNKNOWN; defaults therefore send
    PROG_RED..BLUE / INFO / CAPTIONS (needs the helper). GUIDE (362) and BOOKMARK (156) map.
- `tools/build_irdb.py`: Flipper-IRDB (CC0) → `app/src/main/assets/irdb.json`
- `tools/legacy/`: original Python converter + Sony generator + captured payloads
- Tests: `./gradlew :app:testDebugUnitTest` (encoders vs Sony frame and Google-captured LG
  NEC code, Pronto round trip, RC5/RC6, whole-DB encode stats, export/import, web server,
  Paparazzi screenshots; re-record with `:app:recordPaparazziDebug`)
- Releases: Actions "Release APK" (manual run with a tag input, or push a v* tag). Signing key
  only via secrets KEYSTORE_BASE64/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD; never commit keys.

## Firmware facts (verified in refDesignRcu source, commit 66b7eb9)
- No IR learning: the firmware has no IR receive path. Hence the guided-test design.
- Must program FROM the Shield: `app_save_peer_mac_info` wipes IR codes when the remote bonds
  with a different host.
- UUIDs `d343bfcX-5a21-4f05-bc7d-af01f617b664` (stack/ble/service/uuid.h):
  c0 service, c1 control (01 start/clear, 00 stop/save), c2 key id (2 B BE), c3 code,
  c4 suppress (key ids → BLE; zero length → all IR), c5 key event (notify).
- Key event notify `[0 down|1 up, idHi, idLo]` for every programmed IR key press once the
  CCCD is enabled (flag persisted in remote flash). The app uses this to detect test presses.
- Key IDs: vol+ 0018, vol- 0019, mute 00A4, power 001A, input 00B2 (0139 if input is the
  "magic" key; key_button_map_g20_magic). An ID not in the active map is ignored by
  `ir_table_add_key` before `programmed_key_count++`, so the app writes Input under both.
- MTU_SIZE_SETTING = 180 → max 177 bytes per write. `ir_code_merge` never updates
  `ir_current_button` and the key id is cleared after the first code write, so a code must
  arrive in ONE write. Long/prepared writes won't work either.
- Flash slot ~256 B per key (5 keys per 1280 B), 14-bit pulse counts (max 16383 cycles).
- `programmed_key_count` caps at 5: writing the same key twice in a session uses a slot.
- 30 s programming timeout, restarted on every write.
- The IR table can NOT be read back: all IR characteristics are backed by a 1-byte dummy
  (`ir_data`) with no read callback. `applied:<MAC>` (what the app last wrote) is the only record.
- Back to Bluetooth: suppress write (RAM only, reset on every disconnect by
  `ir_suppress_reinit`); empty program session (persistent, keeps bond); bonding to another
  host; factory reset (centre+mute ~4 s, also deletes the bond).

## Payload format (big-endian u16 words)
- byte0 type, byte1 duty % (0x21), word1 carrier/100 Hz
- type1 once: word2 pairs (6 B header)
- type2 repeat: word2 pairs, word3 repeat delay (cycles); whole sequence loops while held
- type3 once+repeat: word2 once pairs, word3 repeat pairs; repeat part loops while held
- type4 toggle: word2 pairs A, word3 pairs B; odd presses A, even presses B (used for RC5/RC6)
- then mark/space pairs in carrier cycles

## Encoding choices
- Default format SIMPLE (Diagnostics → Code format): every code is type 2 = one frame incl.
  gap, re-sent while held. Confirmed on the owner's remote (SIRC 1/18 type 2 works).
- REFERENCE format (optional, for Google reference firmware): NEC*/Samsung type 3 = frame +
  repeat code; SIRC type 3 = N frames + 1 repeat; RC5/RC6 type 4 toggle.
- Not usable (too long): Kaseikyo (Panasonic), NEC42, some raw captures

## Owner's remote (from Check remote)
- Firmware `sdk044_google_ssu`, hardware `wd2308`, software `V01.06`: not the exact
  refDesignRcu reference build, but it implements the same Google IR-over-BLE service.
- Input pressed on the Check screen showed neither an IR report nor a recognised Bluetooth
  key → most likely the 0x00B2 code was rejected (magic key map). Since v1.2 Input is written
  under both 0x00B2 and 0x0139 (the non-matching ID is ignored by `ir_table_add_key` without
  using a slot), and the Check screen logs every IR report and raw Android key code.
- Power never reaches apps as a Bluetooth key (system-intercepted).
- Negotiated MTU is 131 (logcat) → max 128 bytes per code.
- Type 3 codes fail on this firmware: SIRC 1/18 as type 3 (1 frame + 1 repeat, 112 B) did
  nothing, the same frame as type 2 (60 B) works. The SIRC15 26/90 code that "worked" was in
  fact built as type 2 (type 3 needed 136 B > 128), so type 3 has never worked here.
  Hence SIMPLE is the default.
- Boot: the accessibility service's process is started very early, dies with SIGABRT before
  attaching ("failed to attach … start timeout"), and Android never rebinds (service stuck in
  "Binding services", Projectivy's too). `ServiceRepair`/`BootReceiver` re-write
  ENABLED_ACCESSIBILITY_SERVICES ~15 s after BOOT_COMPLETED if the service isn't running;
  needs WRITE_SECURE_SETTINGS, which the adb helper grants itself (`pm grant`).
- Bluetooth-only keys measured with getevent (event8): red/green/yellow/blue 0x69/6A/6C/6B
  (KEY_RED..BLUE), YouTube 0x77, Netflix 0x78, Prime Video 0x79, rocket 0x7A (all KEY_UNKNOWN
  = scan 240, indistinguishable to apps), gear 0x96 (KEY_TAPE 384), info 0x1BD, guide 0x8D
  (KEY_PROGRAM), subtitles 0x61, bookmark 0x22A. Not IR-programmable.

## Status / open questions
- APK builds; unit tests pass. NOT yet tested on a Shield + G20.
- Unverified: Android letting an app open a 2nd GATT client on the bonded HID remote
  (expected fine), type 4 behaviour on hardware, magic-key input ID on G20 units.
- No KVM in the cloud container, so no emulator UI test.

## Sources
- https://github.com/Genius1237/g10-ir
- https://android.googlesource.com/platform/hardware/telink/atv/refDesignRcu (vendor/827x_ble_remote/app_ir.c, app_att.c, app.c, rc_ir.c; stack/ble/service/uuid.h)
- https://github.com/flipperdevices/flipperzero-firmware lib/infrared/encoder_decoder
- https://github.com/Lucaslhm/Flipper-IRDB (CC0)
