// Ponte com o app Android (SounosNative). Fora do app (navegador), Sounos.native = false e as telas usam os dados de exemplo.
window.Sounos = (() => {
  const N = window.SounosNative;
  if (N) document.documentElement.classList.add("native");
  const listeners = {}, pending = {};
  let seq = 0;
  const doc = Math.random().toString(36).slice(2, 7);    // pedidos de cada página com nome próprio
  window.__sounos = {
    emit(name, p) {
      // o app fala só com a página principal; ela repassa ao player embutido (quadro)
      for (let i = 0; i < window.frames.length; i++) { try { window.frames[i].__sounos?.emit(name, p); } catch {} }
      if (name === "reply") { const f = pending[p.reqId]; if (f) { delete pending[p.reqId]; f(p); } return; }
      (listeners[name] || []).forEach(f => { try { f(p); } catch (e) { console.error(e); } });
    },
  };
  const hash = s => { let h = 0; for (const ch of s) h = (h * 31 + ch.charCodeAt(0)) | 0; return Math.abs(h); };
  return {
    native: !!N,
    /** comando ou leitura imediata */
    call(m, a = {}) { if (!N) return {}; try { return JSON.parse(N.call(m, JSON.stringify(a)) || "{}"); } catch { return {}; } },
    /** pedido que depende da internet (busca etc.): devolve uma Promise */
    ask(m, a = {}) {
      if (!N) return Promise.resolve({ error: "fora do app" });
      return new Promise(res => { const reqId = "r" + doc + (++seq); pending[reqId] = res; N.call(m, JSON.stringify({ ...a, reqId })); });
    },
    on(name, f) { (listeners[name] ||= []).push(f); },
    /** capa servida pelo app (do disco quando já guardada) */
    coverUrl(t, pin = false) { return t ? `/cover/${encodeURIComponent(t.id)}?u=${encodeURIComponent(t.thumb || "")}${pin ? "&pin=1" : ""}` : ""; },
    /** cores provisórias (até a capa chegar): derivadas do id, sempre as mesmas para a mesma música */
    hashColors(id) { const h = hash(id || "x") % 360; return [`hsl(${h} 65% 42%)`, `hsl(${(h + 40) % 360} 70% 50%)`, `hsl(${(h + 80) % 360} 80% 58%)`]; },
    handle(name) { return "@" + String(name || "").normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().trim().replace(/\s+/g, "_").replace(/[^a-z0-9._]/g, ""); },
  };
})();
