#!/usr/bin/env python3
"""Convert Pronto hex IR codes to the payload format the Google G10/G20
reference remote accepts (as used by Genius1237/g10-ir), and back.

Format (decoded from captured payloads, big-endian 16-bit words):
  (confirmed against Google's Telink reference firmware, app_ir.c)
  byte 0   type: 0 = disabled, 1 = one-time, 2 = repeated, 3 = one-time + repeated
  byte 1   duty cycle in % (0x21 = 33 %)
  word 1   carrier frequency in 100 Hz units (0x017c = 38.0 kHz)
  type 1:  word 2 = pair count (no word 3)
  type 2:  word 2 = pair count, word 3 = repeat delay
  type 3:  word 2 = once pair count, word 3 = repeat pair count
  max 300 bytes per code, 5 keys, all keys in one programming session
  then     mark/space pairs, in carrier cycles
"""
import sys

PRONTO_UNIT = 0.241246  # microseconds


def pronto_to_g20(pronto: str, freq_100hz: int | None = None) -> str:
    w = [int(x, 16) for x in pronto.split()]
    if w[0] != 0x0000:
        raise ValueError("only raw Pronto codes (starting 0000) are supported")
    src_hz = 1e6 / (w[1] * PRONTO_UNIT)
    f = freq_100hz or round(src_hz / 1000) * 10  # snap to whole kHz, like Google's DB
    once_n, rep_n = w[2], w[3]
    durs = w[4:4 + 2 * (once_n + rep_n)]
    if len(durs) != 2 * (once_n + rep_n):
        raise ValueError("pair count in header does not match data")
    scale = (f * 100) / src_hz
    if abs(scale - 1) > 0.01:  # only rescale if the carrier really changes
        durs = [max(1, round(d * scale)) for d in durs]
    duty = 0x21  # 33 % duty cycle, as in Google's codes
    if once_n == 0:                      # type 2: repeated, word 3 = repeat delay
        hdr = [0x0200 | duty, f, rep_n, sum(durs)]
    elif rep_n == 0:                     # type 1: one-time, no 4th word
        hdr = [0x0100 | duty, f, once_n]
    else:                                # type 3: one-time + repeated
        hdr = [0x0300 | duty, f, once_n, rep_n]
    return "".join(f"{x:04x}" for x in hdr + durs)


def g20_to_pronto(payload: str) -> str:
    w = [int(payload[i:i + 4], 16) for i in range(0, len(payload), 4)]
    typ, f = w[0] >> 8, w[1]
    durs = w[3:] if typ == 0x01 else w[4:]
    word = round(1e6 / (f * 100 * PRONTO_UNIT))
    once, rep = {1: (w[2], 0), 2: (0, w[2])}.get(typ, (w[2], w[3]))
    return " ".join(f"{x:04X}" for x in [0, word, once, rep] + durs)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit('usage: pronto2g20.py "0000 006D 0000 0020 ..."   (or --back <payload>)')
    if sys.argv[1] == "--back":
        print(g20_to_pronto(sys.argv[2]))
    else:
        print(pronto_to_g20(" ".join(sys.argv[1:])))
