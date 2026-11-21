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
  - `ui/AppViewModel.kt`: guided test state machine, manual editor, persistence
- `tools/build_irdb.py`: Flipper-IRDB (CC0) → `app/src/main/assets/irdb.json`
- `tools/legacy/`: original Python converter + Sony generator + captured payloads
- Tests: `./gradlew :app:testDebugUnitTest` (Sony frame, Google-captured LG NEC code,
  Pronto round trip, RC5/RC6 lengths, whole-DB encode stats)

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
  "magic" key; key_button_map_g20_magic).
- MTU_SIZE_SETTING = 180 → max 177 bytes per write. `ir_code_merge` never updates
  `ir_current_button` and the key id is cleared after the first code write, so a code must
  arrive in ONE write. Long/prepared writes won't work either.
- Flash slot ~256 B per key (5 keys per 1280 B), 14-bit pulse counts (max 16383 cycles).
- `programmed_key_count` caps at 5: writing the same key twice in a session uses a slot.
- 30 s programming timeout, restarted on every write.

## Payload format (big-endian u16 words)
- byte0 type, byte1 duty % (0x21), word1 carrier/100 Hz
- type1 once: word2 pairs (6 B header)
- type2 repeat: word2 pairs, word3 repeat delay (cycles); whole sequence loops while held
- type3 once+repeat: word2 once pairs, word3 repeat pairs; repeat part loops while held
- type4 toggle: word2 pairs A, word3 pairs B; odd presses A, even presses B (used for RC5/RC6)
- then mark/space pairs in carrier cycles

## Encoding choices
- NEC*/Samsung: type 3 = frame + protocol repeat code
- SIRC: type 3 = N frames once (N fitted to 177 B, 2 for SIRC12) + 1 frame repeat
- RC5/RC6: type 4 with toggle 0/1
- others / raw: type 2 single frame repeated
- Not usable (too long): Kaseikyo (Panasonic), NEC42, some raw captures

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
