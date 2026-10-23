import json, sys, time, asyncio, urllib.request, websockets
async def main():
    url=[p for p in json.load(urllib.request.urlopen("http://127.0.0.1:9339/json/list")) if p["url"].endswith("home.html")][0]["webSocketDebuggerUrl"]
    async with websockets.connect(url,max_size=None) as ws:
        n=0
        async def ev(e):
            nonlocal n; n+=1; my=n
            await ws.send(json.dumps({"id":my,"method":"Runtime.evaluate","params":{"expression":e,"returnByValue":True}}))
            while True:
                m=json.loads(await ws.recv())
                if m.get("id")==my: return m.get("result",{}).get("result",{}).get("value")
        st=lambda: ev("(()=>{const s=Sounos.call('getState');return [s.current&&s.current.title,s.positionMs,s.durationMs,s.playing,s.loading]})()")
        print("antes:",await st())
        for k in range(3): await asyncio.sleep(1); print(" ",k+1,"s",await st())
        s=await st(); await ev(f"Sounos.call('seek',{{sec:{s[2]*0.5/1000}}}),1"); print("pulei para 50%")
        for k in range(10): await asyncio.sleep(1); print(" ",k+1,"s",await st())
asyncio.run(main())
