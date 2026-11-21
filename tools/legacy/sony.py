# Sony SIRC-12, device 1 (TV), 40 kHz, 3 frames, 45 ms frame period
F=400  # 40.0 kHz in 100 Hz units; 1 cycle = 25 us
def frame(cmd, dev=1):
    bits=[(cmd>>i)&1 for i in range(7)]+[(dev>>i)&1 for i in range(5)]
    p=[96,24]+[x for b in bits for x in ((48 if b else 24),24)]
    p[-1]+=1800-sum(p)   # pad last space to 45 ms
    return p
def payload(cmd, frames=3):
    d=[x for _ in range(frames) for x in frame(cmd)]
    return "".join(f"{x:04x}" for x in [0x0221,F,len(d)//2,sum(d)]+d)
keys=[("vol up",b"\\x00\\x18",18),("vol down",b"\\x00\\x19",19),("mute",b"\\x00\\xa4",20),("power",b"\\x00\\x1a",21),("input",b"\\x00\\xb2",37)]
print("codes_to_program = [")
for n,k,c in keys:
    print(f'    [  # {n} -> Sony TV SIRC 1/{c}\n        b"{k.decode()}",\n        "{payload(c)}",\n    ],')
print("]")
