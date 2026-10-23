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
        st=lambda: ev("(()=>{const s=Sounos.call('getState');return [s.current&&s.current.title.slice(0,18),s.positionMs,s.durationMs,s.playing,s.loading]})()")
        q=sys.argv[2]; k=int(sys.argv[3])
        r=await ev(f"Sounos.ask('search',{{q:{json.dumps(q)}}}).then(r=>(r.items||r.tracks||r).slice(0,12))")
        await ev(f"Sounos.call('playQueue',{{tracks:{json.dumps(r)},index:{k},context:{{type:'search'}}}}),1"); await asyncio.sleep(float(sys.argv[4]) if len(sys.argv)>4 else 2.5)
        x=await st(); d=x[2]; tempos=[]
        adb("logcat","-c")
        for frac in (0.6,0.2,0.85,0.4,0.7):
            tgt=d*frac; await ev(f"Sounos.call('seek',{{sec:{tgt/1000}}}),1"); t0=time.time(); hist=[]
            while time.time()-t0<18:
                await asyncio.sleep(.25); x=await st(); hist.append((round(time.time()-t0,1),x[0],x[1],x[3],x[4]))
                if x[1]>tgt+100 and not x[4]: break
            dt=round(time.time()-t0,2); tempos.append(dt)
            if dt>4: print("   LENTO:",frac,hist[:3],"...",hist[-3:],flush=True)
        print(x[0],"| pulos (s):",tempos); lg=adb("logcat","-d","-s","Sonus:W","Sonus:E","AndroidRuntime:E"); print(lg[-1500:] if max(tempos)>4 else "")
asyncio.run(main())
