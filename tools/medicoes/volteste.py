import json, subprocess, sys, time, asyncio, urllib.request, websockets
S=sys.argv[1]
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
        await ev("Sounos.call('setRepeat',{mode:'all'}),1")
        for norm in (True, False, True):
            await ev(f"Sounos.call('setSettings',{{...SounosSettings.get(),normalize:{str(norm).lower()}}}),1")
            print("normalize", norm, flush=True)
            for k in range(4):
                await ev("Sounos.call('next'),1"); await asyncio.sleep(46)
        await ev("Sounos.call('setSettings',SounosSettings.get()),1")
        await ev("Sounos.call('setRepeat',{mode:'one'}),1")
        await ev("Sounos.call('pause'),1")
asyncio.run(main())
