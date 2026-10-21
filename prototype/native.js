// Ponte com o app Android (SounosNative). Fora do app (navegador), Sounos.native = false e as telas usam os dados de exemplo.

// ---- Proteção dos dados da pessoa (só no app) ----
// localStorage é a cópia que as telas usam; o app guarda um espelho em arquivo (files/userdata) que sobrevive a
// limpeza do WebView, cota estourada e atualizações. Regra de ouro: localStorage válido SEMPRE vence o espelho.
(() => {
  const N = window.SounosNative;
  if (!N || window.__sounosMirror) return;
  window.__sounosMirror = true;
  const KEYS = ["sounos.playlists", "sounos.searchHistory", "sounos.settings", "sounos.downloads"];
  const nativeCall = (m, a) => { try { return JSON.parse(N.call(m, JSON.stringify(a)) || "{}"); } catch { return {}; } };
  const isJson = s => { if (typeof s !== "string") return false; try { const v = JSON.parse(s); return v !== null && typeof v === "object"; } catch { return false; } };
  // vazio ([] ou {}): suspeito quando o espelho tem conteúdo (sinal de dados perdidos), por isso não vale como "válido"
  const isEmpty = s => { try { const v = JSON.parse(s); return Array.isArray(v) ? !v.length : !Object.keys(v).length; } catch { return true; } };
  const rawGet = Storage.prototype.getItem, rawSet = Storage.prototype.setItem;
  const read = k => { try { return rawGet.call(localStorage, k); } catch { return null; } };

  const pending = {}, timers = {};
  const flushKey = k => {
    clearTimeout(timers[k]); delete timers[k];
    if (!(k in pending)) return;
    const v = pending[k]; delete pending[k];
    if (!nativeCall("saveData", { key: k, value: v }).ok) console.warn("[sounos] espelho nativo recusou", k);
  };
  const flushAll = () => Object.keys(pending).forEach(flushKey);
  const schedule = (k, v) => { pending[k] = v; clearTimeout(timers[k]); timers[k] = setTimeout(() => flushKey(k), 300); };

  // restaura/espelha ANTES de qualquer outro script ler o armazenamento (idempotente; também roda no quadro do player)
  for (const k of KEYS) {
    try {
      const ls = read(k);
      const mirror = nativeCall("loadData", { key: k }).value;
      const lsOk = isJson(ls);
      const mirrorOk = isJson(mirror);
      if (lsOk && !(isEmpty(ls) && mirrorOk && !isEmpty(mirror))) {
        if (!mirrorOk || mirror !== ls) nativeCall("saveData", { key: k, value: ls });        // localStorage vence
      } else if (mirrorOk) {
        rawSet.call(localStorage, k, mirror);                                                 // sumiu/corrompeu: volta do espelho
        console.warn("[sounos] " + k + " restaurado do espelho nativo");
      }
    } catch (e) { console.warn("[sounos] restauro de " + k, e); }
  }

  // toda escrita em "sounos.*" também vai para o espelho (inclusive as de settings.js e search.js)
  Storage.prototype.setItem = function (k, v) {
    const mine = this === window.localStorage && typeof k === "string" && k.startsWith("sounos.");
    if (!mine) return rawSet.call(this, k, v);
    try { rawSet.call(this, k, v); }
    catch (e) { schedule(k, String(v)); flushKey(k); throw e; }       // cota estourada: o espelho guarda mesmo assim
    schedule(k, String(v));
  };
  window.addEventListener("pagehide", flushAll);
  document.addEventListener("visibilitychange", () => { if (document.visibilityState === "hidden") flushAll(); });
  window.__sounosSave = (k, v) => { schedule(k, v); flushKey(k); };
})();

window.Sounos = (() => {
  const N = window.SounosNative;
  if (N) document.documentElement.classList.add("native");
  const listeners = {}, pending = {};
  let seq = 0;
  const doc = Math.random().toString(36).slice(2, 7);    // pedidos de cada página com nome próprio
  window.__sounos = {
    emit(name, p) {
      // o app fala só com a página principal; ela repassa ao player embutido (quadro)
      for (let i = 0; i < window.frames.length; i++) {
        if (name === "progress" && window.__playerShell && !window.__playerOpen) continue;
        try { window.frames[i].__sounos?.emit(name, p); } catch {}
      }
      if (name === "reply") { const f = pending[p.reqId]; if (f) { delete pending[p.reqId]; f(p); } return; }
      (listeners[name] || []).forEach(f => { try { f(p); } catch (e) { console.error(e); } });
    },
  };
  // A tela pode ficar invisível mesmo que a WebView continue com os temporizadores ativos.
  function setPageActive(active) {
    const visible = !!active && !document.hidden;
    document.documentElement.classList.toggle("page-hidden", !visible);
    window.dispatchEvent(new CustomEvent("sounos:visibility", { detail: { active: visible } }));
    for (let i = 0; i < window.frames.length; i++) {
      try { window.frames[i].__sounosPageActive?.(active); } catch {}
    }
  }
  window.__sounosPageActive = setPageActive;
  document.addEventListener("visibilitychange", () => setPageActive(!document.hidden));
  setPageActive(true);

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
    coverUrl(t, pin = false) {
      if (!t) return "";
      const artist = String(t.id).startsWith("artist:") ? (t.name || t.artist || "") : "";
      return `/cover/${encodeURIComponent(t.id)}?u=${encodeURIComponent(t.thumb || "")}${artist ? `&n=${encodeURIComponent(artist)}` : ""}${pin ? "&pin=1" : ""}`;
    },
    /** cores provisórias (até a capa chegar): derivadas do id, sempre as mesmas para a mesma música */
    hashColors(id) { const h = hash(id || "x") % 360; return [`hsl(${h} 65% 42%)`, `hsl(${(h + 40) % 360} 70% 50%)`, `hsl(${(h + 80) % 360} 80% 58%)`]; },
    /** É música (capa quadrada do álbum)? Vídeos e clipes só têm miniatura 16:9 — esses não aparecem no app. */
    isSong(t) { return !!t && ((t.thumbs || []).some(x => x.w > 0 && Math.abs(x.w - x.h) <= 2) || /googleusercontent\.com/.test(t.thumb || "")); },
    handle(name) { return String(name || "").trim(); },   // nome do artista como vem (sem @)
  };
})();
