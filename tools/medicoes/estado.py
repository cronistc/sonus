import json, asyncio, urllib.request, websockets
async def main():
    url=[p for p in json.load(urllib.request.urlopen("http://127.0.0.1:9339/json/list")) if p["url"].endswith("home.html")][0]["webSocketDebuggerUrl"]
    async with websockets.connect(url,max_size=None) as ws:
        await ws.send(json.dumps({"id":1,"method":"Runtime.evaluate","params":{"expression":"(()=>{const s=Sounos.call('getState');return [s.current&&s.current.id,s.positionMs,s.durationMs,s.playing,s.loading]})()","returnByValue":True}}))
        while True:
            m=json.loads(await ws.recv())
            if m.get("id")==1: print(m["result"]["result"]["value"]); break
asyncio.run(main())
