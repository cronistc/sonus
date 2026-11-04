#!/usr/bin/env python3
# uso: cdp.py <serial> <pagina(home|player)> <js> [segundos_escutando]
import json, subprocess, sys, asyncio, urllib.request, websockets
S, PAGE, JS = sys.argv[1], sys.argv[2], sys.argv[3]
WAIT = float(sys.argv[4]) if len(sys.argv) > 4 else 0
adb = lambda *a: subprocess.run(["adb", "-s", S, *a], capture_output=True, text=True).stdout
async def main():
    pid = adb("shell", "pidof", "app.sounos").strip()
    adb("forward", "tcp:9339", f"localabstract:webview_devtools_remote_{pid}")
    pages = json.load(urllib.request.urlopen("http://127.0.0.1:9339/json/list"))
    url = [p for p in pages if p["url"].split("?")[0].endswith(PAGE + ".html")][0]["webSocketDebuggerUrl"]
    async with websockets.connect(url, max_size=None) as ws:
        n = 0
        async def send(m, p=None):
            nonlocal n; n += 1
            await ws.send(json.dumps({"id": n, "method": m, "params": p or {}})); return n
        await send("Runtime.enable")
        i = await send("Runtime.evaluate", {"expression": JS, "returnByValue": True, "awaitPromise": True})
        end = asyncio.get_event_loop().time() + WAIT + 30
        got = False
        while True:
            t = end - asyncio.get_event_loop().time()
            if t <= 0: break
            try: m = json.loads(await asyncio.wait_for(ws.recv(), t))
            except asyncio.TimeoutError: break
            if m.get("id") == i:
                r = m.get("result", {})
                print("RESULT:", r.get("result", {}).get("value", r.get("exceptionDetails", {}).get("exception", {}).get("description")))
                got = True; end = asyncio.get_event_loop().time() + WAIT
            elif m.get("method") == "Runtime.exceptionThrown":
                d = m["params"]["exceptionDetails"]; print("EXCEPTION:", d.get("exception", {}).get("description", d.get("text")), d.get("url"), d.get("lineNumber"))
            elif m.get("method") == "Runtime.consoleAPICalled" and m["params"]["type"] in ("error", "warning"):
                print("CONSOLE", m["params"]["type"], [a.get("value", a.get("description")) for a in m["params"]["args"]])
asyncio.run(main())
