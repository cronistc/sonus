import json, subprocess, sys, time, asyncio, urllib.request, websockets
S=sys.argv[1]; js=sys.argv[2]
adb=lambda *a: subprocess.run(["adb","-s",S,*a],capture_output=True,text=True).stdout
async def main():
    pid=adb("shell","pidof","app.sounos").strip()
    adb("forward","tcp:9339",f"localabstract:webview_devtools_remote_{pid}")
    url=[p for p in json.load(urllib.request.urlopen("http://127.0.0.1:9339/json/list")) if p["url"].endswith("home.html")][0]["webSocketDebuggerUrl"]
    async with websockets.connect(url,max_size=None) as ws:
        await ws.send(json.dumps({"id":1,"method":"Runtime.evaluate","params":{"expression":js,"returnByValue":True}}))
        while True:
            m=json.loads(await ws.recv())
            if m.get("id")==1: print(m.get("result",{}).get("result",{}).get("value")); break
asyncio.run(main())
