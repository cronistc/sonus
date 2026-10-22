#!/usr/bin/env python3
"""Teste de estresse do Sonus SÓ DENTRO DO APP (via DevTools da WebView + adb). Nunca toca fora do app.
Uso: tools/estresse/estresse.py <serial-adb> [rodadas]
Relata: erros JS, mortes do processo (exit-info), crashes (dropbox) e capas que falharam."""
import json, subprocess, sys, time, asyncio, random, urllib.request
import websockets

S = sys.argv[1]; ROUNDS = int(sys.argv[2]) if len(sys.argv) > 2 else 40
adb = lambda *a: subprocess.run(["adb", "-s", S, *a], capture_output=True, text=True).stdout

def front():
    return "app.sounos/.MainActivity" in adb("shell", "dumpsys", "activity", "activities").split("topResumedActivity")[1][:200]

def ws_url():
    pid = adb("shell", "pidof", "app.sounos").strip()
    subprocess.run(["adb", "-s", S, "forward", "tcp:9334", f"localabstract:webview_devtools_remote_{pid}"], capture_output=True)
    pages = json.load(urllib.request.urlopen("http://127.0.0.1:9334/json/list", timeout=5))
    return [p for p in pages if p["url"].endswith("home.html")][0]["webSocketDebuggerUrl"]

class Page:
    def __init__(self, ws): self.ws, self.n, self.errors = ws, 0, []
    async def ev(self, expr, timeout=5):
        self.n += 1; my = self.n
        await self.ws.send(json.dumps({"id": my, "method": "Runtime.evaluate",
                                       "params": {"expression": expr, "returnByValue": True, "awaitPromise": True}}))
        end = time.time() + timeout
        while time.time() < end:
            m = json.loads(await asyncio.wait_for(self.ws.recv(), timeout=end - time.time()))
            if m.get("method") == "Runtime.exceptionThrown":
                self.errors.append(m["params"]["exceptionDetails"].get("exception", {}).get("description", "?")[:300])
            if m.get("id") == my:
                r = m.get("result", {})
                if "exceptionDetails" in r: self.errors.append("eval: " + json.dumps(r["exceptionDetails"])[:300])
                return r.get("result", {}).get("value")
        raise TimeoutError("página não respondeu (travada?) em: " + expr[:80])

# ações dentro do app (JS da própria página; o player é um iframe)
P = "document.querySelector('iframe')?.contentWindow"
ACOES = {
  "abrir_player": "window.openPlayer?.(), 1",
  "fechar_player": "window.__playerShell?.close?.() ?? history.back(), 1",
  "letra": f"(()=>{{const w={P}; w?.document.querySelector('#lyrBtn,.lyr-btn,[data-a=lyrics]')?.click(); return 1}})()",
  "proxima": "Sounos.call('next'), 1",
  "anterior": "Sounos.call('prev'), 1",
  "play_pause": "Sounos.call('toggle'), 1",
  "aleatorio": "Sounos.call('setShuffle',{on:Math.random()<.5}), 1",
  "repetir": "Sounos.call('setRepeat',{mode:['off','all','one'][Math.floor(Math.random()*3)]}), 1",
  "busca": "(()=>{document.querySelector('.search-btn,#searchBtn,[aria-label=Buscar],[aria-label=Pesquisar]')?.click(); return 1})()",
  "voltar": None,   # keyevent BACK (só com o app na frente)
  "artista": "(()=>{const a=[...document.querySelectorAll('[data-artist], .ar')]; a[Math.floor(Math.random()*a.length)]?.click(); return a.length})()",
  "rolar": "(()=>{document.querySelectorAll('.row,.scroll,.sbody,.pg').forEach(e=>{e.scrollLeft+=300;e.scrollTop+=400}); return 1})()",
  "tema": "(()=>{const t=['dark','light','gradient'][Math.floor(Math.random()*3)]; document.documentElement.dataset.theme=t; return t})()",
}

async def main():
    log = []
    before = adb("shell", "dumpsys", "activity", "exit-info", "app.sounos").count("timestamp=")
    adb("shell", "am", "start", "-n", "app.sounos/.MainActivity"); time.sleep(4)
    async with websockets.connect(ws_url(), max_size=None) as ws:
        p = Page(ws)
        await ws.send(json.dumps({"id": 0, "method": "Runtime.enable"}))
        for i in range(ROUNDS):
            nome = random.choice(list(ACOES))
            if not front(): log.append(f"{i}: app saiu da frente"); break
            t0 = time.time()
            try:
                if ACOES[nome] is None: adb("shell", "input", "keyevent", "4")
                else: await p.ev(ACOES[nome])
                ok = await p.ev("1")      # a página responde?
            except Exception as e:
                log.append(f"{i}: {nome}: {e}"); break
            dt = (time.time() - t0) * 1000
            if dt > 400: log.append(f"{i}: {nome} lento {dt:.0f} ms")
            time.sleep(random.uniform(.15, .9))
        falhas = await p.ev("""(async()=>{const u=[...new Set([...document.querySelectorAll('*')].map(e=>getComputedStyle(e).backgroundImage).join(' ').match(/\\/cover\\/[^")]+/g)||[])];
            const r=[]; for (const x of u.slice(0,80)) { const s=await fetch(x).then(r=>r.status).catch(()=>0); if (s!==200) r.push(s+' '+x.slice(0,60)); } return r})()""", timeout=30)
        log += [f"capa falhou: {x}" for x in (falhas or [])]
        log += [f"erro JS: {e}" for e in p.errors]
    after = adb("shell", "dumpsys", "activity", "exit-info", "app.sounos")
    if after.count("timestamp=") > before: log.append("MORTE DO PROCESSO: " + after.split("timestamp=")[1][:300])
    print("\n".join(log) or "OK: nenhum problema")

asyncio.run(main())
