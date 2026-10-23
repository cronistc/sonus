import json,sys,asyncio,urllib.request,websockets
async def m():
  u=[p for p in json.load(urllib.request.urlopen('http://127.0.0.1:9339/json/list')) if p['url'].endswith('home.html')][0]['webSocketDebuggerUrl']
  async with websockets.connect(u,max_size=None) as w:
    await w.send(json.dumps({'id':1,'method':'Runtime.evaluate','params':{'expression':sys.argv[1],'returnByValue':True}}))
    while True:
      r=json.loads(await w.recv())
      if r.get('id')==1: print(r['result']['result'].get('value')); return
asyncio.run(m())
