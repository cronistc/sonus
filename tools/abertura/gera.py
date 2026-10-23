# Gera a abertura do Sonus: contornos de "Sonus" (fonte caligráfica, estilo da clave) casados, contorno a contorno,
# com os contornos da clave do logo — para a palavra SE TRANSFORMAR na clave (sem partículas).
#   python gera.py <fonte-variavel.ttf> <home.html> <saida.js> <splash.xml>
import json, math, re, sys
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.recordingPen import RecordingPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

FONTE_PX, PESO, M_FORA, M_FURO = 64, 500, 260, 140
f = instantiateVariableFont(TTFont(sys.argv[1]), {"wght": PESO})
gs, cmap, hmtx, upm = f.getGlyphSet(), f.getBestCmap(), f["hmtx"], f["head"].unitsPerEm
k = FONTE_PX / upm

def flatten(rec):
    cs, cur, last = [], [], None
    q = lambda p0, p1, p2, n=10: [((1-t)**2*p0[0]+2*(1-t)*t*p1[0]+t*t*p2[0], (1-t)**2*p0[1]+2*(1-t)*t*p1[1]+t*t*p2[1]) for t in [i/n for i in range(1, n+1)]]
    c = lambda p0, p1, p2, p3, n=12: [((1-t)**3*p0[0]+3*(1-t)**2*t*p1[0]+3*(1-t)*t*t*p2[0]+t**3*p3[0], (1-t)**3*p0[1]+3*(1-t)**2*t*p1[1]+3*(1-t)*t*t*p2[1]+t**3*p3[1]) for t in [i/n for i in range(1, n+1)]]
    for op, a in rec:
        if op == "moveTo": cur = [a[0]]; last = a[0]
        elif op == "lineTo": cur.append(a[0]); last = a[0]
        elif op == "qCurveTo":
            pts, p0 = list(a), last
            for i in range(len(pts) - 1):
                p1 = pts[i]; p2 = pts[i+1] if i == len(pts) - 2 else ((pts[i][0]+pts[i+1][0])/2, (pts[i][1]+pts[i+1][1])/2)
                cur += q(p0, p1, p2); p0 = p2
            last = p0
        elif op == "curveTo": cur += c(last, *a); last = a[-1]
        elif op in ("closePath", "endPath"):
            if len(cur) > 3: cs.append(cur)
            cur = []
    return cs

# ---- "Sonus": contornos por letra, em px (fonte de 64), y para baixo
letras, x = [], 0
for ch in "Sonus":
    g = cmap[ord(ch)]; rec = RecordingPen()
    gs[g].draw(TransformPen(rec, (k, 0, 0, -k, x * k, 0)))
    letras.append(flatten(rec.value)); x += hmtx[g][0]
todos = [p for L in letras for c in L for p in c]
cx0 = (min(p[0] for p in todos) + max(p[0] for p in todos)) / 2
cy0 = (min(p[1] for p in todos) + max(p[1] for p in todos)) / 2
letras = [[[(p[0] - cx0, p[1] - cy0) for p in c] for c in L] for L in letras]
tw = max(p[0] for p in todos) - min(p[0] for p in todos); th = max(p[1] for p in todos) - min(p[1] for p in todos)

# ---- clave (path do logo, viewBox 64)
d = re.search(r'id="brandLetter".*?<path d="([^"]+)"', open(sys.argv[2]).read(), re.S).group(1)
tok = re.findall(r'[MLQZ]|-?\d+\.?\d*', d); i = 0; rec = []; cmd = None
while i < len(tok):
    if tok[i] in "MLQZ": cmd = tok[i]; i += 1
    if cmd == "Z": rec.append(("closePath", ())); cmd = None; continue
    if cmd is None: i += 1; continue
    if cmd == "M": rec.append(("moveTo", ((float(tok[i]), float(tok[i+1])),))); i += 2; cmd = "L"
    elif cmd == "L": rec.append(("lineTo", ((float(tok[i]), float(tok[i+1])),))); i += 2
    elif cmd == "Q": rec.append(("qCurveTo", ((float(tok[i]), float(tok[i+1])), (float(tok[i+2]), float(tok[i+3]))))); i += 4
clave = flatten(rec)
cxs = [p[0] for c in clave for p in c]; cys = [p[1] for c in clave for p in c]
ccx, ccy = (min(cxs) + max(cxs)) / 2, (min(cys) + max(cys)) / 2
clave = [[(p[0] - ccx, p[1] - ccy) for p in c] for c in clave]
cw, ch = max(cxs) - min(cxs), max(cys) - min(cys)

def area(c): return sum(c[i][0]*c[(i+1) % len(c)][1] - c[(i+1) % len(c)][0]*c[i][1] for i in range(len(c))) / 2
def dentro(p, c):
    r = False
    for i in range(len(c)):
        a, b = c[i], c[i-1]
        if (a[1] > p[1]) != (b[1] > p[1]) and p[0] < (b[0]-a[0])*(p[1]-a[1])/(b[1]-a[1]) + a[0]: r = not r
    return r
def profundidade(c, grupo): return sum(1 for o in grupo if o is not c and dentro(c[0], o))
def centro(c): return (sum(p[0] for p in c)/len(c), sum(p[1] for p in c)/len(c))
def amostra(c, m):
    cl = c + [c[0]]; L = [math.dist(cl[i], cl[i+1]) for i in range(len(c))]; tot = sum(L); out = []; acc = 0; j = 0
    for s in range(m):
        alvo = tot * s / m
        while acc + L[j] < alvo: acc += L[j]; j += 1
        t = (alvo - acc) / L[j] if L[j] else 0
        out.append((cl[j][0] + (cl[j+1][0]-cl[j][0])*t, cl[j][1] + (cl[j+1][1]-cl[j][1])*t))
    return out
def casa(a, b, escala_b):
    """mesma orientação e melhor ponto de partida (forma relativa ao centro, tamanhos normalizados)"""
    if (area(a) > 0) != (area(b) > 0): b = b[::-1]
    ca, cb = centro(a), centro(b)
    na = max(math.dist(p, ca) for p in a) or 1; nb = max(math.dist(p, cb) for p in b) or 1
    A = [((p[0]-ca[0])/na, (p[1]-ca[1])/na) for p in a]; B = [((p[0]-cb[0])/nb, (p[1]-cb[1])/nb) for p in b]
    m = len(a); melhor = min(range(0, m, 2), key=lambda s: sum((A[i][0]-B[(i+s) % m][0])**2 + (A[i][1]-B[(i+s) % m][1])**2 for i in range(0, m, 3)))
    return b[melhor:] + b[:melhor]

cl_fora = [c for c in clave if profundidade(c, clave) % 2 == 0]
cl_furo = [c for c in clave if profundidade(c, clave) % 2 == 1]
principal = max(cl_fora, key=lambda c: abs(area(c)))
pares = []
for li, L in enumerate(letras):
    for c in L:
        # na fonte, furo = sentido contrário ao contorno maior da letra (contornos podem se sobrepor)
        furo = (area(c) > 0) != (area(max(L, key=lambda o: abs(area(o)))) > 0)
        m = M_FURO if furo else M_FORA
        a = amostra(c, m)
        if furo:   # furo da letra vai para o furo da clave mais parecido em posição relativa
            alvo = min(cl_furo, key=lambda h: abs(centro(h)[0]/cw - centro(c)[0]/tw) + abs(centro(h)[1]/ch - centro(c)[1]/th))
        else: alvo = principal
        b = casa(a, amostra(alvo, m), 1)
        pares.append({"l": li, "f": 1 if furo else 0, "a": a, "b": b})
# partes da clave que nenhuma letra virou (furos/ilhas restantes) nascem de um ponto e crescem
for c in clave:
    if c is principal: continue
    furo = c in cl_furo
    m = M_FURO; b = amostra(c, m); o = centro(c)
    pares.append({"l": -1, "f": 1 if furo else 0, "g": 1, "a": None, "b": b, "o": o})

r = lambda v: round(v, 2)
saida = {"tw": r(tw), "th": r(th), "cw": r(cw), "ch": r(ch), "ccx": r(ccx), "ccy": r(ccy), "pares": [
    {"l": p["l"], "f": p["f"], "g": p.get("g", 0),
     "a": [r(v) for q in (p["a"] or []) for v in q], "b": [r(v) for q in p["b"] for v in q],
     **({"o": [r(p["o"][0]), r(p["o"][1])]} if "o" in p else {})} for p in pares]}

def path_texto(dx, dy):
    pen, x = SVGPathPen(None, ntos=lambda v: ("%.2f" % v).rstrip("0").rstrip(".")), 0
    for ch_ in "Sonus":
        g = cmap[ord(ch_)]
        gs[g].draw(TransformPen(pen, (k, 0, 0, -k, x * k - cx0 + dx, -cy0 + dy))); x += hmtx[g][0]
    return pen.getCommands()
saida["texto"] = path_texto(0, 0)
open(sys.argv[3], "w").write("// Gerado por tools/abertura/gera.py — contornos de \"Sonus\" e da clave, casados para a transformação.\n"
                             "window.__SONUS_ABERTURA = " + json.dumps(saida, separators=(",", ":")) + ";\n")
open(sys.argv[4], "w").write(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Gerado por tools/abertura/gera.py: "Sonus" caligráfico — splash do sistema e palavra nativa da abertura -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="288dp" android:height="288dp" android:viewportWidth="288" android:viewportHeight="288">
    <path android:fillColor="@color/sounos_fg" android:pathData="{path_texto(144, 144)}" />
</vector>
''')
print("contornos:", [(p["l"], p["f"], p.get("g", 0)) for p in pares], "texto", r(tw), r(th), "clave", r(cw), r(ch))
