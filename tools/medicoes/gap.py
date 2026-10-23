import json, subprocess, sys, time, asyncio, urllib.request, websockets
S=sys.argv[1]
adb=lambda *a: subprocess.run(["adb","-s",S,*a],capture_output=True,text=True).stdout
async def main():
    adb("shell","am","start","-n","app.sounos/.MainActivity"); time.sleep(7)
    pid=adb("shell","pidof","app.sounos").strip()
    adb("forward","tcp:9339",f"localabstract:webview_devtools_remote_{pid}")
    url=[p for p in json.load(urllib.request.urlopen("http://127.0.0.1:9339/json/list")) if p["url"].endswith("home.html")][0]["webSocketDebuggerUrl"]
    async with websockets.connect(url,max_size=None) as ws:
        n=0
        async def ev(e):
            nonlocal n; n+=1; my=n
            await ws.send(json.dumps({"id":my,"method":"Runtime.evaluate","params":{"expression":e,"returnByValue":True}}))
            while True:
                m=json.loads(await ws.recv())
                if m.get("id")==my: return m.get("result",{}).get("result",{}).get("value")
        st=lambda: ev("(()=>{const s=Sounos.call('getState');return [s.current&&s.current.id,s.positionMs,s.durationMs,s.playing,s.loading]})()")
        s=await st()
        if not s[3]: await ev("Sounos.call('play'),1"); await asyncio.sleep(4)
        for r in range(int(sys.argv[2])):
            await asyncio.sleep(int(sys.argv[3]))      # deixa a música ficar no cache/tocar
            cur,p,d,pl,lo=await st()
            await ev(f"Sounos.call('seek',{{sec:{(d-8000)/1000}}}),1"); t0=time.time(); ult=None; ini=None; maxpos=0
            while time.time()-t0<25:
                c,p,d2,pl,lo=await st(); t=time.time()-t0
                if c==cur: maxpos=max(maxpos,p); ult=(t,p,d2)
                elif p>0: ini=t; break
                await asyncio.sleep(.1)
            if ini and ult:
                print(f"volta {r}: última posição vista {ult[1]}/{ult[2]} ms em {ult[0]:.2f}s; próxima tocando em {ini:.2f}s -> buraco ≈ {(ini-ult[0])*1000 + (ult[2]-ult[1]):.0f} ms", flush=True)
            else: print(f"volta {r}: NÃO PASSOU (pos {maxpos}/{d})", flush=True)
asyncio.run(main())
