import json, subprocess, sys, time, asyncio, urllib.request, websockets
S=sys.argv[1]; N=int(sys.argv[2])
adb=lambda *a: subprocess.run(["adb","-s",S,*a],capture_output=True,text=True).stdout
async def main():
    adb("shell","am","start","-n","app.sounos/.MainActivity"); time.sleep(8)
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
        st=lambda: ev("Sounos.call('getState')")
        s=await st()
        if not s.get("playing"): await ev("Sounos.call('play'),1"); await asyncio.sleep(4)
        ok=0; ruins=[]
        for i in range(N):
            s=await st(); cur=(s.get("current") or {}).get("id"); d=s.get("durationMs",0)
            if d<8000: await asyncio.sleep(3); continue
            await ev(f"Sounos.call('seek',{{sec:{(d-2500)/1000}}}),1")
            t0=time.time(); novo=None
            while time.time()-t0<20:
                await asyncio.sleep(.5); s2=await st()
                c2=(s2.get("current") or {}).get("id")
                if c2!=cur and s2.get("playing") and s2.get("positionMs",0)>300: novo=round(time.time()-t0,1); break
            if novo: ok+=1
            else:
                ruins.append((i,cur,s2.get("playing"),s2.get("loading")))
                print("FALHA volta",i,"faixa",cur,"dur",d,"-> acompanhando 30s:",flush=True)
                for k in range(15):
                    await asyncio.sleep(2); x=await st()
                    print("  ",2*(k+1),"s pos",x.get("positionMs"),"playing",x.get("playing"),"loading",x.get("loading"),"cur",(x.get("current") or {}).get("id"),flush=True)
                print(adb("logcat","-d","-s","Sonus:*")[-6000:]); break
            if pid!=adb("shell","pidof","app.sounos").strip(): print("APP MORREU na volta",i); break
        print(f"trocas automáticas OK: {ok}/{N}; falhas: {ruins}")
asyncio.run(main())
