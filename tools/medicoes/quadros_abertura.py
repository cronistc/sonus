import json, subprocess, sys, asyncio, urllib.request, websockets, base64, io, time
from PIL import Image
S = sys.argv[1]; OUT = sys.argv[2]
adb = lambda *a: subprocess.run(["adb", "-s", S, *a], capture_output=True, text=True).stdout
async def main():
    adb("shell", "am", "force-stop", "app.sounos"); time.sleep(1)
    adb("shell", "monkey", "-p", "app.sounos", "-c", "android.intent.category.LAUNCHER", "1")
    url = None
    for _ in range(80):
        pid = adb("shell", "pidof", "app.sounos").strip()
        if pid:
            adb("forward", "tcp:9339", f"localabstract:webview_devtools_remote_{pid}")
            try:
                ps = json.load(urllib.request.urlopen("http://127.0.0.1:9339/json/list", timeout=1))
                h = [p for p in ps if p["url"].endswith("home.html")]
                if h: url = h[0]["webSocketDebuggerUrl"]; break
            except Exception: pass
        await asyncio.sleep(.1)
    t0 = time.time()
    async with websockets.connect(url, max_size=None) as ws:
        await ws.send(json.dumps({"id": 1, "method": "Page.startScreencast", "params": {"format": "jpeg", "quality": 60, "maxWidth": 384, "maxHeight": 832, "everyNthFrame": 1}}))
        frames = []
        end = time.time() + 7
        while time.time() < end:
            try: m = json.loads(await asyncio.wait_for(ws.recv(), 1))
            except asyncio.TimeoutError: continue
            if m.get("method") == "Page.screencastFrame":
                p = m["params"]; frames.append((p["metadata"]["timestamp"], p["data"]))
                await ws.send(json.dumps({"id": 2, "method": "Page.screencastFrameAck", "params": {"sessionId": p["sessionId"]}}))
    print("quadros", len(frames), "começou após", round(t0 - 0, 1))
    res = []
    for i, (ts, d) in enumerate(frames):
        im = Image.open(io.BytesIO(base64.b64decode(d))).convert("L"); w, h = im.size
        # região da clave do cabeçalho (centro, topo) e a região do meio
        top = im.crop((w // 2 - 14, int(h * .012), w // 2 + 14, int(h * .075)))
        brilho = sum(1 for v in top.getdata() if v > 170)
        res.append((round(ts - frames[0][0], 3), brilho))
        im.save(f"{OUT}/c{i:03d}.png")
    for t, b in res: print(t, b)
asyncio.run(main())
