// Ponte com o app Android (SounosNative). Fora do app (navegador), Sounos.native = false e as telas usam os dados de exemplo.

// ---- Versão para a pessoa ----
// Internamente a versão tem 4 números (0.1.1.0); a pessoa vê o NOME do 2º + "Beta" (1º = 0) + "3º.4º" → "Sunshine Beta 1.0".
// A contagem do 3º/4º recomeça a cada versão grande (nome novo). Para ordenar versões, use o versionCode, nunca o nome.
// Detalhes em docs/VERSOES.md. Para dar nome a uma nova versão grande, basta acrescentar na tabela.
window.SounosVersao = (() => {
  const NOMES = { 1: "Sunshine" };
  const esc = t => String(t).replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  function parse(v) {
    const raw = String(v ?? "").trim();
    const p = raw.split(/[.-]/).map(x => Number.parseInt(x, 10));
    const name = NOMES[p[1]];
    if (!raw || !name || p.some((x, i) => i < 4 && Number.isNaN(x))) return { name: "", stage: "", num: "", label: raw, raw };   // desconhecida: mostra o texto cru
    const stage = p[0] === 0 ? "Beta" : "";
    const num = `${p[2] || 0}.${p[3] || 0}`;                // 3º.4º, sempre os dois: 0.1.1.0 → "1.0"
    return { name, stage, num, label: [name, stage, num].filter(Boolean).join(" "), raw };
  }
  // Mesmo texto, com só o nome dentro de <span class="sun-glow"> (a Configuração faz o nome brilhar).
  const html = v => {
    const r = parse(v);
    return r.name ? `<span class="sun-glow">${esc(r.name)}</span>${esc(r.label.slice(r.name.length))}` : esc(r.label);
  };
  return { parse, label: v => parse(v).label, html };
})();

// ---- Proteção dos dados da pessoa (só no app) ----
// localStorage é a cópia que as telas usam; o app guarda um espelho em arquivo (files/userdata) que sobrevive a
// limpeza do WebView, cota estourada e atualizações. Regra de ouro: localStorage válido SEMPRE vence o espelho.
(() => {
  const N = window.SounosNative;
  if (!N || window.__sounosMirror) return;
  window.__sounosMirror = true;
  const KEYS = ["sounos.playlists", "sounos.searchHistory", "sounos.settings", "sounos.downloads", "sounos.following", "sounos.tips", "sounos.tipsVersion", "sounos.boasVindas"];
  // igual a CoverStore.artistKey (Kotlin)
  const artistKey = n => { const f = String(n || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/ø/g, "o").replace(/æ/g, "ae").replace(/œ/g, "oe").replace(/ß/g, "ss").replace(/đ/g, "d").replace(/ł/g, "l").replace(/þ/g, "th");
    const a = f.replace(/[^a-z0-9]/g, ""); if (a) return a;
    // nome sem letras latinas (아이유, 宇多田ヒカル): código das letras, senão todos viram o mesmo artista
    const h = [...f].filter(c => /[\p{L}\p{N}]/u.test(c)).map(c => c.codePointAt(0).toString(16)).join("").slice(0, 80); return h ? "u" + h : ""; };
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
  // correções de metadados feitas pelo app (artistas separados, título limpo, capa quadrada): valem também
  // para as playlists e buscas recentes, que moram aqui. Só baixa a lista quando ela mudou (rev).
  const FIX_FIELDS = ["title", "artist", "artists", "artistUrl", "thumb", "thumbs"];
  let fixRev = -1;
  const applyFixes = () => {
    try {
      const r = nativeCall("trackFixes", { rev: fixRev });
      if (r.rev === undefined || r.rev === fixRev || !r.fixes) { if (r.rev !== undefined) fixRev = r.rev; return; }
      fixRev = r.rev;
      for (const k of ["sounos.playlists", "sounos.searchHistory"]) {
        const raw = read(k);
        if (!isJson(raw)) continue;
        const v = JSON.parse(raw);
        let changed = false;
        const walk = o => {
          if (Array.isArray(o)) return o.forEach(walk);
          if (!o || typeof o !== "object") return;
          const f = typeof o.id === "string" && "title" in o ? r.fixes[o.id] : null;
          if (f) for (const x of FIX_FIELDS) if (x in o && x in f && JSON.stringify(o[x]) !== JSON.stringify(f[x])) { o[x] = f[x]; changed = true; }
          Object.values(o).forEach(walk);
        };
        walk(v);
        if (changed) localStorage.setItem(k, JSON.stringify(v));      // passa pelo espelho nativo
      }
    } catch (e) { console.warn("[sounos] correções de metadados", e); }
  };
  applyFixes();
  document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") applyFixes(); });

  window.addEventListener("pagehide", flushAll);
  document.addEventListener("visibilitychange", () => { if (document.visibilityState === "hidden") flushAll(); });
  window.__sounosSave = (k, v) => { schedule(k, v); flushKey(k); };
})();

window.Sounos = (() => {
  const N = window.SounosNative;
  if (N) document.documentElement.classList.add("native");
  const listeners = {}, pending = {}, coverRevision = {};
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
  const refreshCoverUrls = (id, revision) => {
    const rewrite = css => String(css || "").replace(/url\(([^)]+)\)/g, (full, raw) => {
      const value = raw.trim().replace(/^['"]|['"]$/g, "");
      try {
        const url = new URL(value, location.href);
        if (decodeURIComponent(url.pathname.slice("/cover/".length)) !== id || !url.pathname.startsWith("/cover/")) return full;
        url.searchParams.set("v", revision);
        return `url("${url.pathname}${url.search}")`;
      } catch { return full; }
    });
    document.querySelectorAll("*").forEach(el => {
      if (el.style.backgroundImage) el.style.backgroundImage = rewrite(el.style.backgroundImage);
      if (el.style.background) el.style.background = rewrite(el.style.background);
      if (el instanceof HTMLImageElement && el.src.includes("/cover/")) el.src = rewrite(`url("${el.src}")`).slice(5, -2);
    });
  };
  // Capa nova ou melhor: só troca a URL da tela depois que a resposta atualizada carregou.
  // O app emite o evento apenas quando a resolução do arquivo aumentou ou a foto foi validada.
  (listeners.coverReady ||= []).push(ev => {
    if (!ev?.id) return;
    const now = Date.now();
    const revision = String(now);
    coverRevision[ev.id] = revision;
    const probe = new Image();
    probe.onload = () => refreshCoverUrls(ev.id, revision);
    probe.src = Sounos.coverUrl({ id: ev.id }, false, !!ev.large);
  });
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
    on(name, f) {
      (listeners[name] ||= []).push(f);
      return () => { const list = listeners[name]; if (!list) return; const i = list.indexOf(f); if (i >= 0) list.splice(i, 1); };
    },
    /** Chave igual à usada pela ponte nativa para o cache de metadados. */
    cacheKey(method, value) {
      const raw = String(value || "").trim();
      const key = method === "artist" ? artistKey(raw) : raw.toLowerCase();
      return `${method}:${key}`;
    },
    /** capa servida pelo app (do disco quando já guardada) */
    coverUrl(t, pin = false, large = false) {
      if (!t) return "";
      const id = String(t.id);
      const isArtist = id.startsWith("artist:"), isAlbum = id.startsWith("album:");
      const name = isArtist ? (t.name || t.artist || "") : isAlbum ? (t.name || t.album || t.title || "") : "";
      const albumArtist = isAlbum ? (t.albumArtist || t.artist || "") : "";
      const revision = coverRevision[t.id];
      return `/cover/${encodeURIComponent(id)}?u=${encodeURIComponent(t.thumb || "")}${name ? `&n=${encodeURIComponent(name)}` : ""}${albumArtist ? `&a=${encodeURIComponent(albumArtist)}` : ""}${pin ? "&pin=1" : ""}${large ? "&large=1" : ""}${revision ? `&v=${revision}` : ""}`;
    },
    /** cores provisórias (até a capa chegar): derivadas do id, sempre as mesmas para a mesma música */
    hashColors(id) { const h = hash(id || "x") % 360; return [`hsl(${h} 65% 42%)`, `hsl(${(h + 40) % 360} 70% 50%)`, `hsl(${(h + 80) % 360} 80% 58%)`]; },
    /** É música (capa quadrada do álbum)? Vídeos e clipes só têm miniatura 16:9 — esses não aparecem no app. */
    isSong(t) { return !!t && ((t.thumbs || []).some(x => x.w > 0 && Math.abs(x.w - x.h) <= 2) || /googleusercontent\.com/.test(t.thumb || "")); },
    /** Texto completo para exibição; faixas antigas ainda têm somente `artist`. */
    artistLine(t) { const names = Array.isArray(t?.artists) ? t.artists.filter(x => String(x || "").trim()) : []; return (names.length ? names : [t?.artist || ""]).join(", "); },
    handle(name) { return String(name || "").trim(); },   // nome do artista como vem (sem @)
  };
})();
