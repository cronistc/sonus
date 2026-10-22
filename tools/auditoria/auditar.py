#!/usr/bin/env python3
"""Auditoria visual do Sonus pela depuração remota da WebView.

Requer somente Python 3 e adb. Não inicia o app, não toca na tela, não muda rede e
não limpa dados. O Sonus precisa estar aberto em primeiro plano no aparelho.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import select
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit


DEFAULT_TERMS = ["Tribalistas", "Marisa Monte", "Belchior", "Anavitória"]
APP_ACTIVITY = "app.sounos/.MainActivity"


def adb(serial: str, *args: str, timeout: float = 8) -> bytes:
    return subprocess.run(["adb", "-s", serial, *args], check=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=timeout).stdout


def cdp_targets(port: int) -> list[dict[str, Any]]:
    with urllib.request.urlopen(f"http://127.0.0.1:{port}/json/list", timeout=3) as response:
        return json.load(response)


class DevTools:
    """Cliente CDP mínimo, sem dependência de pacote WebSocket externo."""

    def __init__(self, url: str):
        parsed = urlsplit(url)
        host, port = parsed.hostname or "127.0.0.1", parsed.port or 9333
        path = parsed.path.lstrip("/")
        self.sock = socket.create_connection((host, port), timeout=4)
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        request = (f"GET /{path} HTTP/1.1\r\nHost: {host}:{port}\r\n"
                   "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                   f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n")
        self.sock.sendall(request.encode("ascii"))
        header = bytearray()
        while not header.endswith(b"\r\n\r\n"):
            chunk = self.sock.recv(1)
            if not chunk:
                raise RuntimeError("WebView fechou o WebSocket do DevTools")
            header.extend(chunk)
            if len(header) > 8192:
                raise RuntimeError("Cabeçalho WebSocket inválido")
        if b" 101 " not in header:
            raise RuntimeError("DevTools recusou WebSocket: " + header.decode("latin1", "replace"))
        self.sock.settimeout(None)
        self.next_id = 0
        self.events: list[dict[str, Any]] = []
        self.console_errors: list[str] = []
        self.resource_errors: list[str] = []

    def _exact(self, length: int, deadline: float) -> bytes:
        parts = bytearray()
        while len(parts) < length:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("timeout lendo DevTools")
            ready, _, _ = select.select([self.sock], [], [], remaining)
            if not ready:
                raise TimeoutError("timeout lendo DevTools")
            data = self.sock.recv(length - len(parts))
            if not data:
                raise RuntimeError("WebView fechou o DevTools")
            parts.extend(data)
        return bytes(parts)

    def _send_frame(self, payload: bytes, opcode: int = 1) -> None:
        first = bytes((0x80 | opcode,))
        size = len(payload)
        if size < 126:
            head = first + bytes((0x80 | size,))
        elif size < 65536:
            head = first + bytes((0x80 | 126,)) + struct.pack("!H", size)
        else:
            head = first + bytes((0x80 | 127,)) + struct.pack("!Q", size)
        mask = os.urandom(4)
        masked = bytes(value ^ mask[index & 3] for index, value in enumerate(payload))
        self.sock.sendall(head + mask + masked)

    def _recv_message(self, timeout: float) -> str:
        deadline = time.monotonic() + timeout
        chunks = bytearray()
        while True:
            first, second = self._exact(2, deadline)
            opcode, final = first & 0x0F, bool(first & 0x80)
            size = second & 0x7F
            if size == 126:
                size = struct.unpack("!H", self._exact(2, deadline))[0]
            elif size == 127:
                size = struct.unpack("!Q", self._exact(8, deadline))[0]
            masked = bool(second & 0x80)
            mask = self._exact(4, deadline) if masked else b""
            data = self._exact(size, deadline)
            if masked:
                data = bytes(value ^ mask[index & 3] for index, value in enumerate(data))
            if opcode == 8:
                raise RuntimeError("DevTools encerrou a sessão")
            if opcode == 9:
                self._send_frame(data, opcode=10)
                continue
            if opcode in (1, 0):
                chunks.extend(data)
                if final:
                    return chunks.decode("utf-8")

    def _event(self, message: dict[str, Any]) -> None:
        self.events.append(message)
        method = message.get("method")
        params = message.get("params", {})
        if method == "Runtime.consoleAPICalled" and params.get("type") == "error":
            parts = [arg.get("value", arg.get("description", "")) for arg in params.get("args", [])]
            self.console_errors.append("console.error: " + " ".join(map(str, parts)))
        elif method == "Runtime.exceptionThrown":
            detail = params.get("exceptionDetails", {})
            text = detail.get("text", "JavaScript exception")
            exception = detail.get("exception", {}).get("description", "")
            self.console_errors.append(f"exception: {text} {exception}".strip())
        elif method == "Log.entryAdded":
            entry = params.get("entry", {})
            if entry.get("level") == "error":
                detail = "Log.error: " + entry.get("text", "")
                if entry.get("url"):
                    detail += " | " + entry["url"]
                if entry.get("source"):
                    detail += " | source=" + entry["source"]
                if entry.get("source") == "network":
                    self.resource_errors.append(detail)
                else:
                    self.console_errors.append(detail)

    def call(self, method: str, params: dict[str, Any] | None = None, timeout: float = 7) -> dict[str, Any]:
        self.next_id += 1
        command_id = self.next_id
        payload = json.dumps({"id": command_id, "method": method, "params": params or {}},
                             separators=(",", ":")).encode("utf-8")
        self._send_frame(payload)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            message = json.loads(self._recv_message(max(0.05, deadline - time.monotonic())))
            if message.get("id") == command_id:
                if "error" in message:
                    raise RuntimeError(f"CDP {method}: {message['error']}")
                return message.get("result", {})
            self._event(message)
        raise TimeoutError(f"CDP {method} excedeu {timeout:.1f}s")

    def evaluate(self, expression: str, timeout: float = 7) -> Any:
        result = self.call("Runtime.evaluate", {
            "expression": expression,
            "returnByValue": True,
            "awaitPromise": True,
            "userGesture": False,
            "timeout": max(250, int(timeout * 1000) - 250),
        }, timeout=timeout)
        value = result.get("result", {})
        if result.get("exceptionDetails"):
            detail = result["exceptionDetails"]
            exception = detail.get("exception", {})
            message = detail.get("text", "JavaScript exception")
            description = exception.get("description", "")
            raise RuntimeError((message + ": " + description).strip(": "))
        return value.get("value")

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


INSPECT_JS = r"""(function(doc,screen,selector){
  const issues=[], covers=[];
  if(!doc)return {issues:[{kind:'sem DOM',detail:'documento indisponível'}],covers:[]};
  const owner=doc.nodeType===9?doc:doc.ownerDocument,view=owner.defaultView||window;
  const scope=selector?(typeof selector==='string'?owner.querySelector(selector):selector):(doc.nodeType===9?doc.body:doc);
  if(!scope)return {issues:[{kind:'sem DOM',detail:'document.body indisponível'}],covers:[]};
  const inView=e=>{const r=e.getBoundingClientRect();if(r.height<=0||r.bottom<=0||r.right<0||r.top>=view.innerHeight||r.left>view.innerWidth)return false;
    for(let p=e;p&&p!==owner;p=p.parentElement){const s=getComputedStyle(p);if(p.hidden||s.display==='none'||s.visibility==='hidden'||+s.opacity<.03)return false}return true};
  const visible=e=>{const r=e.getBoundingClientRect();if(r.width<=0||r.height<=0||r.bottom<=0||r.right<=0||r.top>=view.innerHeight||r.left>=view.innerWidth)return false;
    for(let p=e;p&&p!==owner;p=p.parentElement){const s=getComputedStyle(p);if(p.hidden||s.display==='none'||s.visibility==='hidden'||+s.opacity<.03)return false}return true};
  const label=e=>{const p=e.closest('[data-id],.tr,.blk,.ar,.s-row,.s-best,.s-album,.s-art,.s-pl,.qi,.hero')||e;return (p.dataset?.name||p.dataset?.id||p.querySelector?.('.t,.n,.name,.qt,.meta')?.textContent||p.getAttribute?.('aria-label')||e.textContent||'').trim().replace(/\s+/g,' ').slice(0,100)};
  const shown=[...scope.querySelectorAll('*')].filter(visible);
  for(const e of scope.querySelectorAll('*'))if(inView(e)&&e.namespaceURI!=='http://www.w3.org/2000/svg'&&getComputedStyle(e).display!=='contents'&&e.clientWidth===0&&!e.matches('.lyr .w'))
    issues.push({kind:'largura zero',screen,selector:e.tagName.toLowerCase()+(e.id?'#'+e.id:''),text:(e.textContent||'').trim().replace(/\s+/g,' ').slice(0,100)});
  for(const e of shown){
    const text=(e.childElementCount===0?e.textContent:e.childNodes.length===1&&e.firstChild.nodeType===3?e.textContent:'').trim();
    const s=getComputedStyle(e),r=e.getBoundingClientRect();
    const clamp=s.webkitLineClamp&&s.webkitLineClamp!=='none'&&s.webkitLineClamp!=='0';
    const ellipsis=s.textOverflow==='ellipsis'||clamp;
    if(text&&!ellipsis&&e.clientWidth>0&&e.scrollWidth>e.clientWidth+2&&s.overflowX!=='visible'&&s.overflowX!=='clip')issues.push({kind:'texto cortado sem reticências',screen,text:text.slice(0,100),label:label(e),selector:e.tagName.toLowerCase()+(e.className&&typeof e.className==='string'?'.'+e.className.trim().split(/\s+/).join('.'):''),scroll:e.scrollWidth,width:e.clientWidth});
    if(text&&!ellipsis&&e.clientHeight>0&&e.scrollHeight>e.clientHeight+2&&s.overflowY!=='visible'&&s.overflowY!=='clip')issues.push({kind:'texto cortado sem reticências',screen,text:text.slice(0,100),label:label(e),selector:e.tagName.toLowerCase()+(e.id?'#'+e.id:''),scroll:e.scrollHeight,height:e.clientHeight});
    const bg=s.backgroundImage||'';
    const urls=[...bg.matchAll(/url\(["']?([^"')]+)["']?\)/g)].map(m=>m[1]);
    const imageUrl=e.tagName==='IMG'?e.currentSrc||e.src:'';
    for(const raw of [...urls,...(imageUrl?[imageUrl]:[])]){
      let u;try{u=new URL(raw,owner.location.href)}catch{continue}
      if(!u.pathname.includes('/cover/'))continue;
      const id=decodeURIComponent(u.pathname.split('/cover/')[1]||'');
      covers.push({url:u.href,id,name:label(e),screen,element:e.tagName.toLowerCase()+(e.id?'#'+e.id:''),fallbackGradient:/linear-gradient\(/i.test(bg)});
      if(e.tagName==='IMG'&&e.complete&&e.naturalWidth===0)issues.push({kind:'imagem naturalWidth 0',screen,id,name:label(e),url:u.href});
    }
  }
  const root=owner.documentElement,body=owner.body,viewport=view.innerWidth;
  if(root.scrollWidth>viewport+2||body.scrollWidth>viewport+2)issues.push({kind:'overflow horizontal da página',screen,documentWidth:root.scrollWidth,viewport,bodyWidth:body.scrollWidth});
  const texts=shown.filter(e=>e.childElementCount===0&&e.textContent.trim().length>1&&!e.closest('.lyr .w')).slice(0,220).flatMap(e=>{
    const range=owner.createRange();range.selectNodeContents(e);
    return [...range.getClientRects()].filter(r=>r.width>1&&r.height>1).map(r=>({e,r}));
  });
  let overlapCount=0;
  for(let i=0;i<texts.length&&overlapCount<30;i++)for(let j=i+1;j<texts.length&&overlapCount<30;j++){
    const a=texts[i],b=texts[j];if(a.e===b.e||a.e.contains(b.e)||b.e.contains(a.e))continue;
    const x=Math.max(0,Math.min(a.r.right,b.r.right)-Math.max(a.r.left,b.r.left));
    const y=Math.max(0,Math.min(a.r.bottom,b.r.bottom)-Math.max(a.r.top,b.r.top));
    const common=x*y,small=Math.min(a.r.width*a.r.height,b.r.width*b.r.height);
    if(common>8&&small>0&&common/small>.35){
      const px=Math.max(a.r.left,b.r.left)+x/2,py=Math.max(a.r.top,b.r.top)+y/2;
      const stack=owner.elementsFromPoint?.(px,py)||[owner.elementFromPoint?.(px,py)].filter(Boolean);
      const belongsToText=e=>stack.some(n=>n===e||e.contains(n));
      if(belongsToText(a.e)&&belongsToText(b.e)){issues.push({kind:'textos sobrepostos',screen,a:a.e.textContent.trim().slice(0,70),b:b.e.textContent.trim().slice(0,70)});overlapCount++;}
    }
  }
  return {issues,covers};
})(document,screen,null)"""


ROUTE_CHECK_JS = r"""async function(items){
  const all=[];const seen=new Set();
  for(const x of items||[]){if(!x.url||seen.has(x.url))continue;seen.add(x.url);all.push(x)}
  const withTimeout=(promise,ms,label,cancel=()=>{})=>{let timer;return Promise.race([promise,new Promise((_,reject)=>{timer=setTimeout(()=>{cancel();reject(new Error(label))},ms)})]).finally(()=>clearTimeout(timer))};
  let next=0;const out=[];const worker=async()=>{while(true){const i=next++;if(i>=all.length)return;const x=all[i];
    const url=new URL(x.url,location.href);url.searchParams.set('auditoria',String(Date.now())+'-'+i);
    const fetchTimeout=x.id?.startsWith('artist:')?12000:1800;
    let status=0,width=0,error='';
    const controller=new AbortController();
    try{
      const r=await withTimeout(fetch(url.href,{method:'GET',cache:'no-store',signal:controller.signal}),fetchTimeout,'fetch timeout '+fetchTimeout+'ms',()=>controller.abort());status=r.status;
      if(r.ok){const blob=await withTimeout(r.blob(),2500,'body timeout 2500ms',()=>controller.abort());
        if(window.createImageBitmap){const bmp=await Promise.race([createImageBitmap(blob),new Promise((_,rej)=>setTimeout(()=>rej(new Error('decode timeout 1.2s')),1200))]);width=bmp.width;bmp.close();}
        else {const probe=new Image();const b=URL.createObjectURL(blob);width=await new Promise(resolve=>{const t=setTimeout(()=>{probe.src='';resolve(0)},1200);probe.onload=()=>{clearTimeout(t);resolve(probe.naturalWidth)};probe.onerror=()=>{clearTimeout(t);resolve(0)};probe.src=b});URL.revokeObjectURL(b)}
      }
    }catch(e){error=String(e?.message||e)}
    out.push({...x,status,naturalWidth:width,error});
  }};
  await Promise.all([worker(),worker(),worker()]);return out;
}"""


def js_quote(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def inspect_expr(name: str, doc: str = "document", selector: str | None = None) -> str:
    return INSPECT_JS.replace("(document,screen,null)",
                              f"({doc},{js_quote(name)},{selector or 'null'})")


def main() -> int:
    parser = argparse.ArgumentParser(description="Percorre a interface do Sonus pelo DevTools da WebView.")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"), help="serial adb do aparelho (obrigatório se há mais de um)")
    parser.add_argument("--port", type=int, default=9333, help="porta local para adb forward (padrão: 9333)")
    parser.add_argument("--terms", nargs=4, default=DEFAULT_TERMS, metavar=("T1", "T2", "T3", "T4"), help="quatro termos de busca")
    parser.add_argument("--output", type=Path, help="salva o relatório JSON neste caminho")
    args = parser.parse_args()
    if not args.serial:
        print("Informe --serial; consulte `adb devices -l`.", file=sys.stderr)
        return 2

    top = adb(args.serial, "shell", "dumpsys", "activity", "activities", timeout=10).decode("utf-8", "replace")
    top_line = next((line.strip() for line in top.splitlines() if "topResumedActivity" in line), "")
    if APP_ACTIVITY not in top_line:
        print(f"Pare: Sonus não está em primeiro plano ({top_line or 'atividade não encontrada'}).", file=sys.stderr)
        return 2

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    screenshot = Path(tempfile.gettempdir()) / f"sonus-auditoria-antes-{stamp}.png"
    screenshot.write_bytes(adb(args.serial, "exec-out", "screencap", "-p", timeout=8))
    pid = adb(args.serial, "shell", "pidof", "app.sounos").decode().strip().split()
    if not pid:
        print("Pare: processo app.sounos não encontrado.", file=sys.stderr)
        return 2
    abstract = f"localabstract:webview_devtools_remote_{pid[0]}"
    forward_set = False
    client: DevTools | None = None
    report: dict[str, Any] = {
        "data": datetime.now().astimezone().isoformat(timespec="seconds"),
        "aparelho": args.serial, "atividade": top_line, "screenshot_inicial": str(screenshot),
        "termos": args.terms, "telas": [], "rotas": [], "erros_console": [], "erros_recursos": [], "observacoes": [],
    }
    storage_before: str | None = None
    mirror_before: str | None = None
    player_was_open = False
    original_repeat: str | None = None
    restored = False
    history_restored = False
    home_scroll_before: dict[str, Any] | None = None
    settings_were_open = False
    update_was_open = False

    def connect_client() -> DevTools:
        nonlocal abstract
        deadline = time.monotonic() + 8
        top_now = ""
        while time.monotonic() < deadline:
            current_top = adb(args.serial, "shell", "dumpsys", "activity", "activities", timeout=10).decode("utf-8", "replace")
            top_now = next((line.strip() for line in current_top.splitlines() if "topResumedActivity" in line), "")
            if APP_ACTIVITY in top_now:
                break
            time.sleep(0.25)
        if APP_ACTIVITY not in top_now:
            raise RuntimeError(f"Sonus saiu do primeiro plano ({top_now or 'atividade não encontrada'}); restauração pausada após 8 s")
        current_pid = adb(args.serial, "shell", "pidof", "app.sounos").decode().strip().split()
        if not current_pid:
            raise RuntimeError("processo app.sounos não encontrado ao reconectar")
        current_abstract = f"localabstract:webview_devtools_remote_{current_pid[0]}"
        if current_abstract != abstract:
            subprocess.run(["adb", "-s", args.serial, "forward", "--remove", f"tcp:{args.port}"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
            adb(args.serial, "forward", f"tcp:{args.port}", current_abstract)
            abstract = current_abstract
        targets = cdp_targets(args.port)
        page = next((x for x in targets if x.get("type") == "page" and "appassets.androidplatform.net/home.html" in x.get("url", "")), None)
        if not page:
            raise RuntimeError("WebView do home.html não apareceu em /json/list")
        connected = DevTools(page["webSocketDebuggerUrl"])
        connected.call("Runtime.enable")
        connected.call("Log.enable")
        connected.call("Page.enable")
        return connected

    def restore_on_page(connected: DevTools) -> None:
        nonlocal history_restored
        if original_repeat is not None:
            connected.evaluate(f"Sounos.call('setRepeat',{{mode:{js_quote(original_repeat)}}})", timeout=2)
        connected.evaluate("window.homeSearch?.close();document.querySelector('.upd .close')?.click();document.querySelector('.set-ov .close')?.click();if(SounosPages.isOpen())SounosPages.closeTop()", timeout=3)
        if storage_before is not None:
            connected.evaluate("localStorage.setItem('sounos.searchHistory'," + js_quote(storage_before) + ");Sounos.call('saveData',{key:'sounos.searchHistory',value:" + js_quote(storage_before) + "})", timeout=2)
        elif mirror_before is not None:
            connected.evaluate("localStorage.setItem('sounos.searchHistory'," + js_quote(mirror_before) + ");Sounos.call('saveData',{key:'sounos.searchHistory',value:" + js_quote(mirror_before) + "})", timeout=2)
        else:
            connected.evaluate("Storage.prototype.removeItem.call(localStorage,'sounos.searchHistory')", timeout=2)
        expected_history = storage_before if storage_before is not None else mirror_before
        if expected_history is not None:
            restored_data = connected.evaluate("({local:localStorage.getItem('sounos.searchHistory'),mirror:Sounos.call('loadData',{key:'sounos.searchHistory'}).value})", timeout=2)
            history_restored = restored_data.get("local") == expected_history and restored_data.get("mirror") == expected_history
            if not history_restored:
                raise RuntimeError("histórico de busca não voltou exatamente ao valor original")
        else:
            history_restored = connected.evaluate("localStorage.getItem('sounos.searchHistory')", timeout=2) is None
        report["historico_restaurado"] = history_restored
        if home_scroll_before is not None:
            connected.evaluate("(()=>{const x=" + js_quote(home_scroll_before) + ";for(const [id,k] of [['recent','recent'],['artists','artists'],['playlists','playlists']]){const e=document.getElementById(id);if(e)e.scrollLeft=x[k]||0}const s=document.querySelector('.scroll');if(s)s.scrollTop=x.root||0})()", timeout=2)
        if player_was_open:
            connected.evaluate("window.__playerShell?.open();new Promise(r=>setTimeout(r,450))", timeout=2)
        else:
            connected.evaluate("window.__playerShell?.close()", timeout=2)
        if settings_were_open:
            connected.evaluate("document.getElementById('gearBtn')?.click();new Promise(r=>setTimeout(r,350))", timeout=2)
            if update_was_open:
                connected.evaluate("document.querySelector('.set-ov.open [data-act=upd-open]')?.click();new Promise(r=>setTimeout(r,300))", timeout=2)
    try:
        adb(args.serial, "forward", f"tcp:{args.port}", abstract)
        forward_set = True
        client = connect_client()
        # Coleta identidade da tela atual sem mudar reprodução ou biblioteca.
        initial = client.evaluate(r"""(()=>{
          const state=Sounos.call('getState')||{},history=Sounos.call('getHistory')||[],mine=Sounos.call('getMineTracks')||{};
          const tracks=[...(history||[]).map(x=>x.track),...(mine.downloaded||[]),...(mine.cached||[]),...(state.queue||[]),...(state.suggestions||[]),state.current].filter(Boolean);
          const map=new Map();for(const t of tracks)if(t.id)map.set(t.id,{id:t.id,name:t.title||t.artist||t.id,url:Sounos.coverUrl(t,true)});
          const artists=[...document.querySelectorAll('#artists .ar .n')].map(e=>e.textContent.trim()).filter(Boolean);
          const key=n=>String(n||'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[^a-z0-9]/g,'');
          const artistCovers=new Map(artists.map(name=>[key(name),{id:'artist:'+key(name),name,url:Sounos.coverUrl({id:'artist:'+key(name),name},true),screen:'Seus artistas'}]));
          const storedKeys=Sounos.call('getCoverArtistKeys').keys||[];
          for(const artistKey of storedKeys)if(!artistCovers.has(artistKey))artistCovers.set(artistKey,{id:'artist:'+artistKey,name:artistKey,url:Sounos.coverUrl({id:'artist:'+artistKey,name:artistKey},true),screen:'índice de fotos salvas'});
          const sc=document.querySelector('.scroll');
          return {tracks:[...map.values()],artists,artistCovers:[...artistCovers.values()],playlists:SounosData.getPlaylists(),mine:[...(mine.downloaded||[]),...(mine.cached||[])],playerOpen:!!window.__playerOpen,repeat:state.repeat||null,current:state.current||null,settingsOpen:!!document.querySelector('.set-ov.open'),updateOpen:!!document.querySelector('.upd.open'),scroll:{recent:document.getElementById('recent')?.scrollLeft||0,artists:document.getElementById('artists')?.scrollLeft||0,playlists:document.getElementById('playlists')?.scrollLeft||0,root:sc?.scrollTop||0}};
        })()""")
        if not initial:
            raise RuntimeError("Não consegui ler o estado atual da tela inicial")
        storage_before = client.evaluate("localStorage.getItem('sounos.searchHistory')")
        mirror_before = client.evaluate("Sounos.call('loadData',{key:'sounos.searchHistory'}).value||null")
        player_was_open = bool(initial.get("playerOpen"))
        original_repeat = initial.get("repeat")
        settings_were_open = bool(initial.get("settingsOpen"))
        update_was_open = bool(initial.get("updateOpen"))
        home_scroll_before = initial.get("scroll", {})

        # Retira somente o quadro de player da frente; a faixa e sua fila continuam intactas.
        if player_was_open:
            client.evaluate("window.__playerShell?.close()")
            client.evaluate("new Promise(r=>setTimeout(r,450))", timeout=2)
        if update_was_open:
            client.evaluate("document.querySelector('.upd .close')?.click()")
            client.evaluate("new Promise(r=>setTimeout(r,250))", timeout=2)
        if settings_were_open:
            client.evaluate("document.querySelector('.set-ov .close')?.click()")
            client.evaluate("new Promise(r=>setTimeout(r,350))", timeout=2)

        async_wait = "new Promise(r=>setTimeout(r,250))"
        # Todos os carrosséis da tela inicial, rolados até o último cartão.
        home = client.evaluate(r"""(()=>{
          for(const id of ['recent','artists','playlists']){const e=document.getElementById(id);if(e){e.scrollLeft=e.scrollWidth;e.dispatchEvent(new Event('scroll'))}}
          const sc=document.querySelector('.scroll');if(sc){sc.scrollTop=sc.scrollHeight;sc.dispatchEvent(new Event('scroll'))}
          return {carrosseis:['recent','artists','playlists'].map(id=>{const e=document.getElementById(id);return {id,scrollLeft:e?.scrollLeft||0,scrollWidth:e?.scrollWidth||0,clientWidth:e?.clientWidth||0}})}
        })()""")
        client.evaluate(async_wait, timeout=2)
        report["telas"].append({"nome": "inicial", "dados": home, "diagnostico": client.evaluate(inspect_expr("inicial"))})

        # Busca real pelo campo da tela; rola resultados até não acrescentarem mais músicas.
        for term in args.terms:
            code = f"window.homeSearch.query({js_quote(term)})"
            client.evaluate(code)
            client.evaluate(r"""new Promise(resolve=>{const end=Date.now()+6000;const t=setInterval(()=>{
              const b=document.querySelector('.search-ov.open .sbody');const txt=b?.innerText||'';
              if(b&&(!txt.includes('Buscando')||Date.now()>end)){clearInterval(t);resolve(true)}
              else if(Date.now()>end){clearInterval(t);resolve(false)}
            },150)})""", timeout=7)
            search = client.evaluate(r"""async()=>{
              const b=document.querySelector('.search-ov.open .sbody');if(!b)return {rows:0,loaded:false};
              let last=-1,stable=0,passes=0;
              while(passes++<5&&stable<2){b.scrollTop=b.scrollHeight;b.dispatchEvent(new Event('scroll'));await new Promise(r=>setTimeout(r,650));
                const n=b.querySelectorAll('.s-row[data-pick="nt"]').length;if(n===last)stable++;else stable=0;last=n;
              }
              return {rows:b.querySelectorAll('.s-row[data-pick="nt"]').length,scrollHeight:b.scrollHeight,passes,loaded:!b.innerText.includes('Buscando')};
            }""", timeout=6)
            search_name = "busca: " + term
            report["telas"].append({"nome": search_name, "dados": search,
                                    "diagnostico": client.evaluate(inspect_expr(search_name, selector="document.querySelector('.search-ov.open')"))})
        client.evaluate("window.homeSearch?.close()")
        client.evaluate("new Promise(r=>setTimeout(r,450))", timeout=2)

        # Perfil de todo artista que está realmente listado em “Seus artistas”.
        for artist in initial.get("artists", []):
            name = str(artist)
            client.evaluate(f"window.SounosPages.openArtist({js_quote(name)})")
            client.evaluate(r"""new Promise(resolve=>{const end=Date.now()+9000,t=setInterval(()=>{const p=document.querySelector('.pg.artist');if(!p||!p.querySelector('.sk')||Date.now()>end){clearInterval(t);resolve(true)}},200)})""", timeout=10)
            data = client.evaluate(r"""(()=>{const p=document.querySelector('.pg.artist');const l=p?.querySelector('.list');if(l){l.scrollTop=l.scrollHeight;l.dispatchEvent(new Event('scroll'))}
              return {aberta:!!p,nome:p?.querySelector('.name')?.textContent||'',musicas:p?.querySelectorAll('.tr').length||0}})()""")
            artist_screen = "artista: " + name
            report["telas"].append({"nome": artist_screen, "dados": data,
                                    "diagnostico": client.evaluate(inspect_expr(artist_screen, selector="document.querySelector('.pg.artist')"))})
            client.evaluate("window.SounosPages.closeTop()")
            client.evaluate("new Promise(r=>setTimeout(r,360))", timeout=2)
        if not initial.get("artists"):
            report["observacoes"].append("“Seus artistas” vazio; não havia perfis para abrir.")

        # Dois ou três álbuns reais encontrados pela busca de catálogo.
        albums: list[dict[str, Any]] = []
        for term in [*args.terms, *initial.get("artists", [])]:
            if len(albums) >= 3:
                break
            # ask devolve Promise; o limite não bloqueia o thread visual da WebView.
            result = client.evaluate(f"Promise.race([Sounos.ask('searchAlbums',{{q:{js_quote(str(term))}}}),new Promise(r=>setTimeout(()=>r({{timeout:true}}),6500))])", timeout=7)
            for album in (result or {}).get("items", []):
                if album.get("url") and all(x.get("url") != album.get("url") for x in albums):
                    albums.append(album)
                    if len(albums) >= 3:
                        break
        for album in albums:
            client.evaluate("window.SounosPages.openAlbum(" + ",".join(js_quote(album.get(k, "")) for k in ("url", "name", "artist", "thumb")) + ")")
            client.evaluate(r"""new Promise(resolve=>{const end=Date.now()+9000,t=setInterval(()=>{const p=document.querySelector('.pg.album');if(!p||!p.querySelector('.sk')||Date.now()>end){clearInterval(t);resolve(true)}},200)})""", timeout=10)
            data = client.evaluate(r"""(()=>{const p=document.querySelector('.pg.album'),l=p?.querySelector('.list');if(l){l.scrollTop=l.scrollHeight;l.dispatchEvent(new Event('scroll'))}
              return {aberta:!!p,nome:p?.querySelector('.name')?.textContent||'',musicas:p?.querySelectorAll('.tr').length||0}})()""")
            album_screen = "álbum: " + str(album.get("name", ""))
            report["telas"].append({"nome": album_screen, "dados": data,
                                    "diagnostico": client.evaluate(inspect_expr(album_screen, selector="document.querySelector('.pg.album')"))})
            client.evaluate("window.SounosPages.closeTop()")
            client.evaluate("new Promise(r=>setTimeout(r,360))", timeout=2)
        if not albums:
            report["observacoes"].append("Busca não encontrou álbuns para abrir.")

        # Playlist existente (leitura somente) e Minhas músicas.
        playlists = initial.get("playlists", [])
        if playlists:
            playlist = playlists[0]
            client.evaluate(f"window.SounosPages.openPlaylist({js_quote(playlist.get('id',''))})")
            client.evaluate("new Promise(r=>setTimeout(r,350))", timeout=2)
            data = client.evaluate(r"""(()=>{const p=document.querySelector('.pg.playlist'),l=p?.querySelector('.list');if(l){l.scrollTop=l.scrollHeight;l.dispatchEvent(new Event('scroll'))}
              return {aberta:!!p,nome:p?.querySelector('.name')?.textContent||'',musicas:p?.querySelectorAll('.tr').length||0}})()""")
            playlist_screen = "playlist: " + str(playlist.get("name", ""))
            report["telas"].append({"nome": playlist_screen, "dados": data,
                                    "diagnostico": client.evaluate(inspect_expr(playlist_screen, selector="document.querySelector('.pg.playlist')"))})
            client.evaluate("window.SounosPages.closeTop()")
            client.evaluate("new Promise(r=>setTimeout(r,360))", timeout=2)
        else:
            report["observacoes"].append("Não há playlist salva para abrir.")
        client.evaluate("window.SounosPages.openMine(" + js_quote(initial.get("mine", [])) + ")")
        client.evaluate("new Promise(r=>setTimeout(r,250))", timeout=2)
        mine_data = client.evaluate(r"""(()=>{const p=document.querySelector('.pg.mine'),l=p?.querySelector('.list');if(l){l.scrollTop=l.scrollHeight;l.dispatchEvent(new Event('scroll'))}
          return {aberta:!!p,musicas:p?.querySelectorAll('.tr').length||0}})()""")
        report["telas"].append({"nome": "Minhas músicas", "dados": mine_data,
                                "diagnostico": client.evaluate(inspect_expr("Minhas músicas", selector="document.querySelector('.pg.mine')"))})
        client.evaluate("window.SounosPages.closeTop()")
        client.evaluate("new Promise(r=>setTimeout(r,360))", timeout=2)

        # Configurações e painel de atualização; nenhum botão de instalar/limpar é acionado.
        client.evaluate("document.getElementById('gearBtn')?.click()")
        client.evaluate("new Promise(r=>setTimeout(r,350))", timeout=2)
        report["telas"].append({"nome": "configurações",
                                "diagnostico": client.evaluate(inspect_expr("configurações", selector="document.querySelector('.set-ov.open')"))})
        client.evaluate("document.querySelector('.set-ov [data-act=upd-open]')?.click()")
        client.evaluate("new Promise(r=>setTimeout(r,300))", timeout=2)
        report["telas"].append({"nome": "atualização",
                                "diagnostico": client.evaluate(inspect_expr("atualização", selector="document.querySelector('.upd.open')"))})
        client.evaluate("document.querySelector('.upd .close')?.click()")
        client.evaluate("document.querySelector('.set-ov .close')?.click()")
        client.evaluate("new Promise(r=>setTimeout(r,450))", timeout=2)

        # Player: fila original e sugestões temporárias, com modo de repetição restaurado logo após.
        player_info = client.evaluate(r"""(()=>{const s=Sounos.call('getState')||{};return {has:!!s.current,repeat:s.repeat,position:s.positionMs||0,duration:s.durationMs||0}})()""")
        if player_info.get("has"):
            client.evaluate("window.__playerShell?.open()")
            client.evaluate("new Promise(r=>setTimeout(r,550))", timeout=2)
            player_doc = "(function(){const f=document.querySelector('iframe[title=Player]');return f?.contentDocument?.querySelector('#phone')||document})()"
            # Diagnóstico do quadro visível (player.html tem sua própria viewport).
            expr = inspect_expr("player: fila", player_doc)
            report["telas"].append({"nome": "player: fila", "dados": client.evaluate(f"(()=>{{const d={player_doc};const q=d?.querySelector('#queue');if(q)q.scrollLeft=q.scrollWidth;return {{aberto:!!d?.querySelector('#phone'),itens:q?.children.length||0}}}})()"), "diagnostico": client.evaluate(expr)})
            remaining = (float(player_info.get("duration", 0)) - float(player_info.get("position", 0))) / 1000
            if remaining > 12 and player_info.get("repeat") != "one":
                client.evaluate("Sounos.call('setRepeat',{mode:'one'})")
                client.evaluate("new Promise(r=>setTimeout(r,400))", timeout=2)
                sug_data = client.evaluate(f"(()=>{{const d={player_doc};const q=d?.querySelector('#queue');if(q)q.scrollLeft=q.scrollWidth;return {{aberto:!!d?.querySelector('#phone'),itens:q?.children.length||0,repeat:Sounos.call('getState').repeat}}}})()")
                sug_expr = inspect_expr("player: sugestões", player_doc)
                report["telas"].append({"nome": "player: sugestões", "dados": sug_data, "diagnostico": client.evaluate(sug_expr)})
                client.evaluate(f"Sounos.call('setRepeat',{{mode:{js_quote(player_info.get('repeat','off'))}}})")
                client.evaluate("new Promise(r=>setTimeout(r,350))", timeout=2)
            else:
                report["observacoes"].append("Sugestões do player não foram abertas: não há faixa ou ela está a menos de 12 s do fim.")
            client.evaluate("window.__playerShell?.close()")
            client.evaluate("new Promise(r=>setTimeout(r,450))", timeout=2)
        else:
            report["observacoes"].append("Player sem faixa; fila e sugestões não puderam ser abertas sem iniciar reprodução.")

        # Rotas de capa diretas para os IDs conhecidos e todas as capas vistas nas telas.
        covers_by_url: dict[str, dict[str, Any]] = {}
        for t in initial.get("tracks", []):
            covers_by_url[t["url"]] = {"url": t["url"], "id": t["id"], "name": t["name"], "screen": "biblioteca / histórico / baixadas"}
        for artist_cover in initial.get("artistCovers", []):
            covers_by_url[artist_cover["url"]] = {**artist_cover, "screen": artist_cover.get("screen", "Seus artistas")}
        for page in report["telas"]:
            for cover in (page.get("diagnostico") or {}).get("covers", []):
                item = covers_by_url.setdefault(cover["url"], {**cover, "screen": cover["screen"]})
                item["fallbackGradient"] = item.get("fallbackGradient", False) or cover.get("fallbackGradient", False)
                if page["nome"] not in item["screen"]:
                    item["screen"] += ", " + page["nome"]
        route_items = list(covers_by_url.values())
        for start in range(0, len(route_items), 12):
            batch = route_items[start:start + 12]
            try:
                routes = client.evaluate("(" + ROUTE_CHECK_JS + ")(" + js_quote(batch) + ")", timeout=30)
                report["rotas"].extend(routes or [])
            except Exception as error:
                message = f"lote de capas {start + 1}–{start + len(batch)}: {type(error).__name__}: {error}"
                report["observacoes"].append("Verificação de rotas incompleta: " + message)
                report["erro_execucao"] = message
                break
        for result in report["rotas"]:
            if result.get("status") != 200 or result.get("naturalWidth", 0) <= 0:
                report["telas"].append({"nome": "falha de rota: " + str(result.get("id", "?")), "diagnostico": {"issues": [{
                    "kind": "capa/foto falhou", "screen": result.get("screen", "rota direta"), "id": result.get("id"),
                    "name": result.get("name"), "status": result.get("status"), "naturalWidth": result.get("naturalWidth"),
                    "error": result.get("error"), "fallbackGradient": result.get("fallbackGradient", False),
                }], "covers": []}})

        restore_on_page(client)
        restored = True
        report["erros_console"] = list(dict.fromkeys(client.console_errors))
        report["erros_recursos"] = list(dict.fromkeys(client.resource_errors))
    except Exception as error:  # relatório parcial é mais útil que perder toda a execução
        report["erro_execucao"] = f"{type(error).__name__}: {error}"
        if client:
            try:
                restore_on_page(client)
                restored = True
            except Exception:
                pass
    finally:
        if (storage_before is not None or mirror_before is not None or player_was_open) and not restored:
            try:
                if client:
                    client.close()
                recovered = connect_client()
                restore_on_page(recovered)
                report["erros_console"].extend(recovered.console_errors)
                report["erros_recursos"].extend(recovered.resource_errors)
                recovered.close()
                restored = True
            except Exception as restore_error:
                report["observacoes"].append(f"Não foi possível confirmar restauração após reconexão: {type(restore_error).__name__}: {restore_error}")
        if client:
            report["erros_console"] = list(dict.fromkeys(report["erros_console"] + client.console_errors))
            report["erros_recursos"] = list(dict.fromkeys(report["erros_recursos"] + client.resource_errors))
            client.close()
        if forward_set:
            subprocess.run(["adb", "-s", args.serial, "forward", "--remove", f"tcp:{args.port}"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)

    print(f"Auditoria Sonus — {report['data']}")
    print(f"Aparelho: {report['aparelho']} | Screenshot inicial: {report['screenshot_inicial']}")
    for page in report["telas"]:
        issues = (page.get("diagnostico") or {}).get("issues", [])
        status = f"{len(issues)} falha(s)" if issues else "OK"
        print(f"  {status:14} {page['nome']}")
        for issue in issues:
            print("    - " + json.dumps(issue, ensure_ascii=False))
    failures = [x for x in report["rotas"] if x.get("status") != 200 or x.get("naturalWidth", 0) <= 0]
    print(f"Rotas diretas de capas: {len(report['rotas'])} verificadas, {len(failures)} falha(s)")
    for issue in report["erros_console"]:
        print("Console JS: " + issue)
    for issue in report["erros_recursos"]:
        if "raw.githubusercontent.com/cronistc/sonus/updates/support.json" in issue and "404" in issue:
            print("Recurso opcional ausente (404): " + issue)
        else:
            print("Recurso com erro: " + issue)
    for note in report["observacoes"]:
        print("Observação: " + note)
    if report.get("erro_execucao"):
        print("Auditoria interrompida: " + report["erro_execucao"])
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Relatório JSON: {args.output}")
    unexpected_resources = [x for x in report["erros_recursos"] if not (
        "raw.githubusercontent.com/cronistc/sonus/updates/support.json" in x and "404" in x
    )]
    return 1 if report.get("erro_execucao") or failures or report["erros_console"] or unexpected_resources or any(
        (p.get("diagnostico") or {}).get("issues") for p in report["telas"]
    ) else 0


if __name__ == "__main__":
    sys.exit(main())
