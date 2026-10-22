#!/usr/bin/env python3
"""Bateria geral do Sonus — SÓ DENTRO DO APP (DevTools da WebView + adb no próprio app). Confere RESULTADOS
(a música toca de verdade, o tempo anda, capas carregam, voltar faz o certo, offline funciona…), não só "não travou".
Uso: tools/estresse/bateria.py <serial-adb>
Regras: nunca mexe fora do app; rede só via `cmd connectivity set-package-networking-enabled` (sempre restaura)."""
import json, subprocess, sys, time, asyncio, urllib.request, re
import websockets

S = sys.argv[1]
adb = lambda *a: subprocess.run(["adb", "-s", S, *a], capture_output=True, text=True).stdout
res = []          # (nome, ok, detalhe)

def front():
    for _ in range(6):
        if "app.sounos/.MainActivity" in adb("shell", "dumpsys", "activity", "activities").split("topResumedActivity")[1][:200]: return True
        time.sleep(.5)
    return False

def ws_url():
    pid = adb("shell", "pidof", "app.sounos").strip()
    subprocess.run(["adb", "-s", S, "forward", "tcp:9336", f"localabstract:webview_devtools_remote_{pid}"], capture_output=True)
    for _ in range(20):
        try:
            pages = json.load(urllib.request.urlopen("http://127.0.0.1:9336/json/list", timeout=5))
            return [p for p in pages if p["url"].endswith("home.html")][0]["webSocketDebuggerUrl"]
        except Exception: time.sleep(.5)
    raise RuntimeError("sem DevTools")

def cpu(seg=10):
    pid = adb("shell", "pidof", "app.sounos").strip()
    def ticks():
        f = adb("shell", "cat", f"/proc/{pid}/stat").split(")")[-1].split()
        return int(f[11]) + int(f[12])
    a = ticks(); time.sleep(seg); b = ticks()
    return round((b - a) / 100 / seg * 100)

class Page:
    def __init__(self, ws): self.ws, self.n, self.errors = ws, 0, []
    async def ev(self, expr, timeout=8):
        self.n += 1; my = self.n
        await self.ws.send(json.dumps({"id": my, "method": "Runtime.evaluate",
                                       "params": {"expression": expr, "returnByValue": True, "awaitPromise": True}}))
        end = time.time() + timeout
        while time.time() < end:
            m = json.loads(await asyncio.wait_for(self.ws.recv(), timeout=max(.1, end - time.time())))
            if m.get("method") == "Runtime.exceptionThrown":
                self.errors.append(m["params"]["exceptionDetails"].get("exception", {}).get("description", "?")[:200])
            if m.get("id") == my:
                r = m.get("result", {})
                if "exceptionDetails" in r: return {"__erro": json.dumps(r["exceptionDetails"])[:200]}
                return r.get("result", {}).get("value")
        raise TimeoutError("página não respondeu: " + expr[:60])
    async def espera(self, expr, timeout=12, passo=.25):
        t0 = time.time()
        while time.time() - t0 < timeout:
            v = await self.ev(expr)
            if v and not (isinstance(v, dict) and "__erro" in v): return round(time.time() - t0, 2), v
            await asyncio.sleep(passo)
        return None, None

ST = "Sounos.call('getState')"
POS = f"(()=>{{const s={ST};return s.positionMs}})()"
def tocando(minimo=1200, id_=None):
    cond = f"s.playing && s.positionMs>{minimo}" + (f" && s.current && s.current.id==={json.dumps(id_)}" if id_ else "")
    return f"(()=>{{const s={ST};return ({cond}) ? s.positionMs : 0}})()"

def ok(nome, cond, det=""): res.append((nome, bool(cond), det)); print(("OK   " if cond else "FALHA") + f" {nome} {det}", flush=True)

async def conecta():
    ws = await websockets.connect(ws_url(), max_size=None)
    p = Page(ws); await ws.send(json.dumps({"id": 0, "method": "Runtime.enable"}))
    return ws, p

async def main():
    mortes0 = adb("shell", "dumpsys", "activity", "exit-info", "app.sounos").count("timestamp=")
    adb("shell", "cmd", "connectivity", "set-package-networking-enabled", "true", "app.sounos")

    # 1) abertura a frio
    adb("shell", "am", "force-stop", "app.sounos"); t0 = time.time()
    adb("shell", "am", "start", "-n", "app.sounos/.MainActivity"); time.sleep(2.5)
    ws, p = await conecta()
    t, _ = await p.espera("window.__sonusAberturaFim", 12)
    ok("abertura termina", t is not None, f"{round(time.time()-t0,1)}s até a tela inicial")
    v = await p.ev("[document.querySelectorAll('.blk,.ar').length, !!document.querySelector('.bv-tela')]")
    ok("tela inicial com conteúdo", v and v[0] > 0, f"{v}")
    if v and v[1]: await p.ev("document.querySelector('.bv-comecar')?.click(),1"); await asyncio.sleep(1)
    await p.ev("Sounos.call('pause'),1")

    # 2) busca pela tela (como a pessoa faz) + histórico + mais resultados
    q = "Djavan Oceano"
    await p.ev("document.querySelector('#searchBtn')?.click(),1"); await asyncio.sleep(.6)
    await p.ev(f"(()=>{{const i=document.querySelector('.search-ov input');i.value={json.dumps(q)};i.dispatchEvent(new Event('input'));i.dispatchEvent(new KeyboardEvent('keydown',{{key:'Enter'}}));return 1}})()")
    t, n = await p.espera("document.querySelectorAll('.search-ov .s-row').length", 15)
    ok("busca traz músicas", t is not None, f"{n} músicas em {t}s")
    t2, _ = await p.espera("document.querySelectorAll('.search-ov .s-art,.search-ov .s-album').length", 8)
    ok("busca traz artistas/álbuns", t2 is not None)
    hist = await p.ev("localStorage.getItem('sounos.searchHistory')||''")
    ok("histórico salvou a busca", "Oceano" in (hist or ""))
    antes = n or 0
    for _ in range(4):
        await p.ev("(()=>{const b=document.querySelector('.search-ov .sbody');b.scrollTop=b.scrollHeight;b.dispatchEvent(new Event('scroll'));return 1})()"); await asyncio.sleep(1.5)
    depois = await p.ev("document.querySelectorAll('.search-ov .s-row').length")
    ok("rolagem infinita carrega mais", (depois or 0) > antes, f"{antes} → {depois}")
    await p.ev("(()=>{const b=document.querySelector('.search-ov .sbody');b.scrollTop=0;return 1})()")
    capas = await p.ev("(()=>{const im=[...document.querySelectorAll('.search-ov img')].filter(i=>i.getBoundingClientRect().top<innerHeight);return [im.length, im.filter(i=>i.complete&&i.naturalWidth===0).length]})()")
    await asyncio.sleep(2)
    capas = await p.ev("(()=>{const im=[...document.querySelectorAll('.search-ov img')].filter(i=>i.getBoundingClientRect().top<innerHeight);return [im.length, im.filter(i=>i.complete&&i.naturalWidth===0).length, im.filter(i=>!i.complete).length]})()")
    ok("capas da busca carregam", capas and capas[1] == 0, f"visíveis={capas}")

    # 3) tocar música NUNCA tocada (sem cache) a partir da busca
    await p.ev("document.querySelectorAll('.search-ov .s-row')[3]?.click(),1")
    t, _ = await p.espera("(()=>{const s=" + ST + ";return s.playing && s.positionMs>300})()", 20)
    ok("música nova toca (sem cache)", t is not None, f"som em {t}s")
    t, _ = await p.espera(tocando(3000), 10)
    ok("tempo da música anda", t is not None)
    ids = [await p.ev(f"{ST}.current.id")]
    sug = await p.ev(f"({ST}.suggestions||[]).length + ({ST}.queue||[]).length")
    ok("fila/indicadas preenchidas", (sug or 0) > 1, f"{sug}")
    # player por cima da busca; voltar fecha o player e a busca continua
    await p.ev("window.openPlayer?.(),1"); await asyncio.sleep(1.2)
    adb("shell", "input", "keyevent", "4"); await asyncio.sleep(1.2)
    v = await p.ev("[!!window.__playerOpen, !!document.querySelector('.search-ov.open')]")
    ok("voltar: fecha player, busca continua", v == [False, True], f"{v}")
    adb("shell", "input", "keyevent", "4"); await asyncio.sleep(1)
    v = await p.ev("!!document.querySelector('.search-ov.open')")
    ok("voltar: fecha busca, fica no início", v is False and front())

    # 4) próxima ×3 (músicas novas), anterior, pular para posição, pausar
    for i in range(3):
        antes_id = await p.ev(f"{ST}.current.id")
        await p.ev("Sounos.call('next'),1")
        t, _ = await p.espera(f"(()=>{{const s={ST};return s.current.id!=={json.dumps(antes_id)} && s.playing && s.positionMs>500}})()", 20)
        ok(f"próxima #{i+1} toca", t is not None, f"{t}s")
        ids.append(await p.ev(f"{ST}.current.id"))
    await p.ev("Sounos.call('seek',{sec:0}),1"); await asyncio.sleep(.8)
    await p.ev("Sounos.call('prev'),1")      # no começo da música: volta para a anterior (depois de 3 s ela recomeçaria)
    t, _ = await p.espera(f"(()=>{{const s={ST};return s.current.id==={json.dumps(ids[-2])} && s.playing}})()", 12)
    ok("anterior volta à música anterior", t is not None, f"{t}s (cache)")
    await p.ev("Sounos.call('seek',{sec:60}),1"); await asyncio.sleep(1.5)
    pos = await p.ev(POS)
    ok("pular para 1:00", pos and 58000 < pos < 66000, f"{pos}")
    await p.ev("Sounos.call('pause'),1"); await asyncio.sleep(1); a = await p.ev(POS); await asyncio.sleep(2); b = await p.ev(POS)
    ok("pausa para o tempo", a == b, f"{a}/{b}")
    await p.ev("Sounos.call('play'),1")
    t, _ = await p.espera(f"(()=>{{const s={ST};return s.playing && s.positionMs>{(b or 0)+500}}})()", 6)
    ok("play continua de onde parou", t is not None)

    # 5) música já tocada (cache) começa na hora
    first = await p.ev(f"JSON.stringify({ST}.queue.find(x=>x.id==={json.dumps(ids[0])}) || null)")
    if first and first != "null":
        await p.ev(f"Sounos.call('playSingle',{{track:{first}}}),1")
        t, _ = await p.espera(f"(()=>{{const s={ST};return s.current.id==={json.dumps(ids[0])} && s.playing && s.positionMs>200}})()", 10)
        ok("música em cache começa rápido", t is not None and t < 2.5, f"{t}s")
    else: ok("música em cache começa rápido", False, "não achei a 1ª música na fila")

    # 6) player: abrir/fechar, letra, temas
    for i in range(3):
        t1 = time.time(); await p.ev("window.openPlayer?.(),1"); await asyncio.sleep(.8)
        await p.ev("window.__playerShell?.close?.(),1"); ok_ = await p.ev("1"); dt = round(time.time() - t1 - .8, 2)
    ok("abrir/fechar player responde", dt < 1.2, f"{dt}s")
    await p.ev("window.openPlayer?.(),1"); await asyncio.sleep(1)
    w = "document.querySelector('iframe').contentWindow"
    for _ in range(4):
        await p.ev(f"({w}.document.querySelector('#lyrBtn,.lyr-btn,[data-a=lyrics],[aria-label*=etra]')?.click(),1)"); await asyncio.sleep(.7)
    ok("letra liga/desliga sem travar", await p.ev("1") == 1)
    await p.ev("window.__playerShell?.close?.(),1"); await asyncio.sleep(.8)
    for tema in ["light", "dark", "gradient"]:
        await p.ev(f"(()=>{{const s=SounosSettings.get();window.dispatchEvent(new CustomEvent('sounos:settings',{{detail:{{...s,theme:'{tema}'}}}}));return 1}})()"); await asyncio.sleep(.5)
    ok("troca de tema sem travar", await p.ev("1") == 1)

    # 7) artista e álbum (dados e tela)
    art = await p.ev("Sounos.ask('artist',{name:'Djavan'}).then(r=>[(r.items||[]).length,!!(r.thumb)])", 25)
    ok("artista: músicas e foto", art and art[0] > 0 and art[1], f"{art}")
    alb = await p.ev("Sounos.ask('searchAlbums',{q:'Djavan'}).then(r=>(r.items||r.albums||r)[0]).then(a=>a?Sounos.ask('album',{url:a.url}).then(x=>[a.name,(x.tracks||x.items||[]).length]):null)", 25)
    ok("álbum abre com músicas", alb and alb[1] > 0, f"{alb}")
    await p.ev("document.querySelector('#searchBtn')?.click(),1"); await asyncio.sleep(.6)
    await p.ev("(()=>{const i=document.querySelector('.search-ov input');i.value='Tribalistas';i.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter'}));return 1})()")
    await p.espera("document.querySelector('.search-ov .s-art')", 15)
    await p.ev("document.querySelector('.search-ov .s-art')?.click(),1")
    t, _ = await p.espera("(()=>{const e=document.querySelector('.pg.artist.open .img');const u=e&&getComputedStyle(e).backgroundImage.match(/url\\(\"?([^\")]+)/);if(!u)return false;return new Promise(r=>{const i=new Image();i.onload=()=>r(i.naturalWidth>0);i.onerror=()=>r(false);i.src=u[1]})})()", 10)
    ok("página do artista com foto", t is not None, f"{t}s")
    adb("shell", "input", "keyevent", "4"); await asyncio.sleep(.8)
    adb("shell", "input", "keyevent", "4"); await asyncio.sleep(.8)
    v = await p.ev("[!!document.querySelector('.pg.open,.pg.on'), !!document.querySelector('.search-ov.open')]")
    ok("voltar sai do artista e da busca", front() and v and not v[1], f"{v}")

    # 8) telas por cima: configurações, novidades, + playlist
    await p.ev("document.querySelector('#gearBtn')?.click(),1"); await asyncio.sleep(.8)
    adb("shell", "input", "keyevent", "4"); await asyncio.sleep(.8)
    ok("voltar fecha configurações (app fica)", front() and not await p.ev("!!document.querySelector('.set-ov.open,.settings.open,[class*=set].open')"))
    await p.ev("document.querySelector('.mini [aria-label*=playlist], .mini .add, .mini [data-a=add]')?.click(),1"); await asyncio.sleep(.8)
    adb("shell", "input", "keyevent", "4"); await asyncio.sleep(.8)
    ok("voltar fecha adicionar à playlist", front())

    # 9) segundo plano: voltar no início manda pro fundo; música continua; volta igual
    a = await p.ev(POS)
    adb("shell", "input", "keyevent", "4"); await asyncio.sleep(12)
    vivo = adb("shell", "pidof", "app.sounos").strip() != ""
    adb("shell", "am", "start", "-n", "app.sounos/.MainActivity"); await asyncio.sleep(2)
    try: b = await p.ev(POS)
    except Exception:
        await ws.close(); ws, p = await conecta(); b = await p.ev(POS)
    ok("toca em segundo plano", vivo and b and a and b > a + 9000, f"{a}→{b}")

    # 10) sem internet (só o app) e volta
    cache_id = ids[0]
    adb("shell", "cmd", "connectivity", "set-package-networking-enabled", "false", "app.sounos")
    try:
        # (bloquear só o app não faz o Android avisar "sem rede", então aqui só vale tocar/pular sem internet)
        await asyncio.sleep(1.5)
        first = await p.ev(f"JSON.stringify({ST}.queue.find(x=>x.id==={json.dumps(cache_id)}) || null)")
        await p.ev(f"Sounos.call('playSingle',{{track:{first}}}),1")
        t, _ = await p.espera(tocando(300, cache_id), 10)
        ok("offline: música guardada toca", t is not None, f"{t}s")
        await p.ev("Sounos.call('next'),1"); await asyncio.sleep(5)
        ok("offline: próxima não trava o app", await p.ev("1") == 1 and front())
    finally:
        adb("shell", "cmd", "connectivity", "set-package-networking-enabled", "true", "app.sounos")
    t, _ = await p.espera("!Sounos.call('getOffline').offline", 20)
    ok("volta a ficar online", t is not None, f"{t}s")
    await p.ev("Sounos.call('next'),1")
    t, _ = await p.espera(tocando(500), 20)
    ok("online de novo: próxima toca", t is not None, f"{t}s")

    # 11) processador (tocando)
    await p.ev("window.__playerShell?.close?.(),1"); await asyncio.sleep(2)
    c_home = cpu(10)
    await p.ev("window.openPlayer?.(),1"); await asyncio.sleep(2)
    c_player = cpu(10)
    await p.ev("window.__playerShell?.close?.(),1")
    ok("processador", True, f"início {c_home}% · player {c_player}% (100% = 1 núcleo)")

    # 12) capas na tela inicial
    capas = await p.ev("(()=>{const im=[...document.querySelectorAll('#phone img')];return [im.length, im.filter(i=>i.complete&&i.naturalWidth===0).map(i=>i.src.slice(0,80))]})()")
    ok("capas da tela inicial", capas and not capas[1], f"{capas[0] if capas else '?'} imagens, falhas={capas[1][:3] if capas else '?'}")

    await p.ev("Sounos.call('pause'),1")
    mortes = adb("shell", "dumpsys", "activity", "exit-info", "app.sounos").count("timestamp=") - mortes0
    ok("app não morreu", mortes <= 0, f"mortes={mortes}")
    ok("sem erros de JS", not p.errors, "; ".join(p.errors[:4]))
    await ws.close()
    falhas = [r for r in res if not r[1]]
    print(f"\n== {len(res) - len(falhas)}/{len(res)} OK ==")
    for f in falhas: print("FALHA:", f[0], f[2])

asyncio.run(main())
