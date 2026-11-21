import sys; sys.path.insert(0,'.')
exec(open('dec.py').read().split('for n,h')[0])
from pronto2g20 import pronto_to_g20, g20_to_pronto
for n,h in codes.items():
    p=g20_to_pronto(h); back=pronto_to_g20(p, 380)
    print(n, back==h, p[:40])
