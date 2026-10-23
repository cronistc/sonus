import json, subprocess, sys, time, asyncio, urllib.request, websockets
S=sys.argv[1]
adb=lambda *a: subprocess.run(["adb","-s",S,*a],capture_output=True,text=True).stdout
async def main():
    url=[p for p in json.load(urllib.request.urlopen("http://127.0.0.1:9339/json/list")) if p["url"].endswith("home.html")][0]["webSocketDebuggerUrl"]
    async with websockets.connect(url,max_size=None) as ws:
        n=0
        async def ev(e):
            nonlocal n; n+=1; my=n
            await ws.send(json.dumps({"id":my,"method":"Runtime.evaluate","params":{"expression":e,"returnByValue":True,"awaitPromise":True}}))
            while True:
                m=json.loads(await ws.recv())
                if m.get("id")==my: return m.get("result",{}).get("result",{}).get("value")
        r=await ev(f"Sounos.ask('search',{{q:{json.dumps(sys.argv[2])}}}).then(r=>r.items.slice(0,12))")
        k=int(sys.argv[3]) if sys.argv[3].isdigit() else next((i for i,t in enumerate(r) if t["id"]==sys.argv[3]), None)
        if k is None: print("id não veio na busca:", [t["id"] for t in r]); return
        print("faixa:",r[k]["title"],r[k]["id"])
        adb("logcat","-c")
        await ev(f"Sounos.call('playQueue',{{tracks:{json.dumps(r)},index:{k},context:{{type:'search'}}}}),1")
        for i in range(int(sys.argv[4])):
            await asyncio.sleep(1); x=await ev("(()=>{const s=Sounos.call('getState');return [s.index,s.current&&s.current.title.slice(0,14),s.positionMs,s.playing,s.loading,s.playWhenReady]})()"); print(f" {i+1}s",x,flush=True)
        if len(sys.argv)>5 and sys.argv[5]=="seek":
            await ev("Sounos.call('seek',{sec:60}),1"); print(" pulei p/ 60s")
            for i in range(10):
                await asyncio.sleep(1); x=await ev("(()=>{const s=Sounos.call('getState');return [s.index,s.current&&s.current.title.slice(0,14),s.positionMs,s.playing,s.loading,s.playWhenReady]})()"); print(f" {i+1}s",x,flush=True)
        lg=adb("logcat","-d")
        print("\n".join(l for l in lg.splitlines() if ("Sonus" in l or "AndroidRuntime" in l or "MediaExtractor" in l or "MediaCodec" in l) and "cover" not in l and "metadados" not in l and "foto artista" not in l)[-2500:])
asyncio.run(main())
