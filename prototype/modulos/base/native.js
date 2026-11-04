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
  const KEYS = ["sounos.playlists", "sounos.searchHistory", "sounos.settings", "sounos.downloads", "sounos.following", "sounos.artistPageCache", "sounos.tips", "sounos.tipsVersion", "sounos.boasVindas"];
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
  // quanto cada px da interface vale na tela real (A54 = 1; tela mais larga > 1). Só no app: o navegador do computador não amplia
  window.SounosEscala = N ? Math.max(.5, Math.min(4, (screen.width || innerWidth) / (document.documentElement.clientWidth || innerWidth))) : 1;
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
  // large: só troca o endereço da capa do MESMO tamanho que chegou. Antes a grande nova também recarregava a miniatura
  // que fica por baixo dela no player — as duas sumiam por um instante (o "pisca").
  const refreshCoverUrls = (id, revision, large) => {
    const rewrite = css => String(css || "").replace(/url\(([^)]+)\)/g, (full, raw) => {
      const value = raw.trim().replace(/^['"]|['"]$/g, "");
      try {
        const url = new URL(value, location.href);
        if (decodeURIComponent(url.pathname.slice("/cover/".length)) !== id || !url.pathname.startsWith("/cover/")) return full;
        if (large != null && (url.searchParams.get("large") === "1") !== large) return full;
        url.searchParams.set("v", revision);
        return `url("${url.pathname}${url.search}")`;
      } catch { return full; }
    });
    // 1º descobre o que muda; 2º carrega as imagens com o endereço NOVO exato; 3º só então troca na tela. Trocar antes
    // deixava o fundo (gradiente laranja/colorido da capa) aparecer enquanto a imagem nova chegava — a capa "piscava".
    const trocas = [], novos = new Set();
    const guarda = v => v.replace(/url\("([^"]+)"\)/g, (_, u) => { if (u.includes("v=" + revision)) novos.add(u); return _; });
    document.querySelectorAll("*").forEach(el => {
      // só reescreve o que mudou: regravar o mesmo estilo faz o WebView pedir de novo toda imagem que tinha falhado
      if (el.style.backgroundImage) { const v = rewrite(el.style.backgroundImage); if (v !== el.style.backgroundImage) { guarda(v); trocas.push(() => { el.__capaSuave ? el.__capaSuave(v) : (el.style.backgroundImage = v); }); } }   // __capaSuave: a capa do player entra por cima, com fade
      if (el instanceof HTMLImageElement && el.src.includes("/cover/")) { const v = rewrite(`url("${el.src}")`).slice(5, -2); if (v !== el.src) { novos.add(v); trocas.push(() => { el.src = v; }); } }
    });
    if (!trocas.length) return;
    // as imagens pré-carregadas ficam seguras uns segundos: a tela pega a mesma da memória, sem buscar de novo
    const seguras = [];
    Promise.allSettled([...novos].map(u => new Promise(ok => { const i = new Image(); seguras.push(i); i.onload = i.onerror = ok; i.src = u; })))
      .then(() => { if (coverRevision[id] === revision) trocas.forEach(f => f()); setTimeout(() => seguras.length = 0, 5000); });
  };
  // Capa nova ou melhor: só troca a URL da tela depois que a resposta atualizada carregou.
  // O app emite o evento apenas quando a resolução do arquivo aumentou ou a foto foi validada.
  (listeners.coverReady ||= []).push(ev => {
    if (!ev?.id) return;
    const now = Date.now();
    const revision = String(now);
    coverRevision[ev.id] = revision;
    refreshCoverUrls(ev.id, revision, !!ev.large);   // pré-carrega os endereços novos antes de trocar; só o tamanho que chegou
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
  // igual a CoverStore.artistKey (Kotlin). Faltava aqui: cacheKey("artist", …) dava ReferenceError e a página do artista
  // não recebia as atualizações do catálogo (metadataUpdated)
  const artistKey = n => { const f = String(n || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/ø/g, "o").replace(/æ/g, "ae").replace(/œ/g, "oe").replace(/ß/g, "ss").replace(/đ/g, "d").replace(/ł/g, "l").replace(/þ/g, "th");
    const a = f.replace(/[^a-z0-9]/g, ""); if (a) return a;
    const h = [...f].filter(c => /[\p{L}\p{N}]/u.test(c)).map(c => c.codePointAt(0).toString(16)).join("").slice(0, 80); return h ? "u" + h : ""; };
  return {
    native: !!N,
    /** comando ou leitura imediata */
    call(m, a = {}) { if (!N) return {}; try { return JSON.parse(N.call(m, JSON.stringify(a)) || "{}"); } catch { return {}; } },
    /** pedido que depende da internet (busca etc.): devolve uma Promise */
    ask(m, a = {}) {
      if (!N) return Promise.resolve({ error: "fora do app" });
      return new Promise(res => { const reqId = "r" + doc + (++seq); pending[reqId] = res;
        try { N.call(m, JSON.stringify({ ...a, reqId })); } catch (e) { delete pending[reqId]; res({ error: String(e?.message || e) }); } });   // a ponte falhou: sem isto a promessa ficava pendurada
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
    // mini: capinha (~150 px) para linhas pequenas como as da busca — chega bem mais rápido com internet fraca
    coverUrl(t, pin = false, large = false, mini = false) {
      if (!t) return "";
      const id = String(t.id);
      const isArtist = id.startsWith("artist:"), isAlbum = id.startsWith("album:");
      const name = isArtist ? (t.name || t.artist || "") : isAlbum ? (t.name || t.album || t.title || "") : "";
      const albumArtist = isAlbum ? (t.albumArtist || t.artist || "") : "";
      const revision = coverRevision[t.id];
      // encodeURIComponent deixa o apóstrofo passar ("D'Angelo"): dentro de url('…') ele fecharia o endereço
      return `/cover/${encodeURIComponent(id)}?u=${encodeURIComponent(t.thumb || "")}${name ? `&n=${encodeURIComponent(name)}` : ""}${albumArtist ? `&a=${encodeURIComponent(albumArtist)}` : ""}${pin ? "&pin=1" : ""}${large ? "&large=1" : ""}${mini ? "&mini=1" : ""}${revision ? `&v=${revision}` : ""}`.replace(/'/g, "%27");
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

// Encostou o dedo numa música: o app já começa a buscar o link do áudio (o YouTube leva ~1,5 s para entregar).
// Espera 70 ms parado para não buscar tudo o que passa embaixo do dedo ao rolar; cada música no máximo 1×/min.
window.SounosPrefetchToque = (container, seletor, faixa) => {
  if (!window.Sounos?.native || !container) return;
  const feitas = new Map();
  let timer = 0, x = 0, y = 0;
  const cancela = () => { clearTimeout(timer); timer = 0; };
  container.addEventListener("pointerdown", e => {
    const el = e.target.closest(seletor); if (!el) return;
    cancela(); x = e.clientX; y = e.clientY;
    timer = setTimeout(() => {
      timer = 0;
      const t = faixa(el); if (!t || !t.url) return;
      const agora = Date.now(); if (agora - (feitas.get(t.id) || 0) < 60000) return;
      feitas.set(t.id, agora);
      Sounos.call("prefetch", { tracks: [t] });
      new Image().src = Sounos.coverUrl(t, false, true);      // capa grande (a do player) já começa a vir
    }, 70);
  }, { passive: true });
  container.addEventListener("pointermove", e => { if (timer && Math.hypot(e.clientX - x, e.clientY - y) > 10) cancela(); }, { passive: true });
  container.addEventListener("pointercancel", cancela, { passive: true });
};

// De ponta a ponta: as barras do sistema são transparentes e o app desenha atrás delas. --sb-topo / --sb-base = altura
// da barra de status / de navegação em px da interface; o conteúdo se afasta delas (o fundo vai até a borda).
// O app manda as medidas em px do aparelho (__sonusMedidas); a tela inicial repassa ao player (iframe).
(() => {
  const aplica = (doc, topo, base) => {
    const r = doc.documentElement.style, d = devicePixelRatio || 1;
    r.setProperty("--sb-topo", (topo / d).toFixed(2) + "px");
    r.setProperty("--sb-base", (base / d).toFixed(2) + "px");
  };
  window.__sonusMedidas = (topo, base) => {
    aplica(document, topo, base);
    document.querySelectorAll("iframe").forEach(f => { try { f.contentWindow.__sonusMedidas?.(topo, base); } catch {} });
  };
  try {
    if (window.SounosNative) { const m = JSON.parse(SounosNative.call("getMedidas", "{}")); if (m.topo != null) aplica(document, m.topo, m.base); }
  } catch {}
})();

// Compartilhar música / álbum / artista: o link do YouTube Music (abre no Sonus de quem ligou "Abrir links de música";
// para os outros, no YouTube Music ou no navegador). No app: a tela de compartilhar do Android; no protótipo: navigator.share.
// Os links levam ao site do Sonus (cronistc.github.io/s/): quem tem o Sonus abre direto no app (App Links, o site tem o
// assetlinks.json com a chave do Sonus); quem não tem vê a capa, "Ouvir no YouTube Music" e "Baixar o Sonus".
// O link carrega tudo que a página mostra (id, nome, artista, capa): o site não tem servidor.
window.SounosCompartilhar = (() => {
  const SITE = "https://cronistc.github.io/s/";
  const envia = (titulo, texto) => {
    if (window.Sounos?.native) return Sounos.call("compartilhar", { titulo, texto });
    navigator.share?.({ title: titulo, text: texto }).catch(() => {});
  };
  // capa do Google (lh3…/TOKEN=w544-h544… ou yt3.ggpht.com/TOKEN=s800…) → só o TOKEN ("y/" na frente quando é do yt3);
  // a página monta o endereço no tamanho que quiser
  const capa = url => {
    const m = /^https:\/\/(lh3\.googleusercontent\.com|yt3\.ggpht\.com|yt3\.googleusercontent\.com)\/([^=?#]+)/.exec(url || "");
    return m ? (m[1] === "lh3.googleusercontent.com" ? "" : "y/") + m[2] : "";
  };
  const link = campos => {
    const q = new URLSearchParams();
    Object.entries(campos).forEach(([k, v]) => { if (v) q.set(k, v); });
    return `${SITE}?${q}`;
  };
  const musicaLink = t => {
    if (!t) return "";
    const id = /^yt:([A-Za-z0-9_-]{11})$/.exec(t.id || "")?.[1] || /[?&]v=([A-Za-z0-9_-]{11})/.exec(t.url || "")?.[1];
    const quem = Sounos.artistLine?.(t) || t.artist || "";
    return id ? link({ m: id, n: t.title, a: quem, c: capa(t.thumb) }) : link({ q: `${quem} ${t.title}`.trim(), n: t.title, a: quem, c: capa(t.thumb) });
  };
  const artistaLink = (nome, url, foto) => {
    const canal = /UC[A-Za-z0-9_-]{22}/.exec(url || "")?.[0];
    return link({ ar: canal, q: canal ? "" : nome, tp: canal ? "" : "artista", n: nome, c: capa(foto) });
  };
  const albumLink = (nome, artista, url, foto) => {
    const lista = /[?&]list=([A-Za-z0-9_-]+)/.exec(url || "")?.[1];
    return link({ al: lista, q: lista ? "" : `${artista || ""} ${nome}`.trim(), tp: lista ? "" : "album", n: nome, a: artista, c: capa(foto) });
  };
  // playlist: os ids das músicas no próprio link ("." entre eles), até 100; quem abre no Sonus recebe a lista
  const playlistLink = (nome, tracks) => {
    const ids = (tracks || []).map(t => /^yt:([A-Za-z0-9_-]{11})$/.exec(t.id || "")?.[1] || /[?&]v=([A-Za-z0-9_-]{11})/.exec(t.url || "")?.[1]).filter(Boolean);
    return ids.length ? link({ pl: [...new Set(ids)].slice(0, 100).join("."), n: nome }) : "";
  };
  return {
    musicaLink, artistaLink, albumLink, playlistLink,
    musica(t) {
      if (!t) return;
      const quem = Sounos.artistLine?.(t) || t.artist || "";
      envia(t.title, `${t.title}${quem ? " · " + quem : ""}\n${musicaLink(t)}`);
    },
  };
})();

// Cores da música (--c1..--c3). Antes mudavam com transição de .8 s no :root: animar uma variável herdada recalcula o
// estilo da PÁGINA INTEIRA a cada quadro (tela inicial e player) — era o maior engasgo de cada troca de música
// (145 quadros longos em 10 trocas contra 18 sem a transição). Agora as cores novas entram de uma vez e o fundo
// ANTIGO fica numa camada por cima que some em .8 s (só opacidade: a placa de vídeo faz sozinha).
window.SounosCores = (() => {
  const quietas = () => matchMedia("(prefers-reduced-motion: reduce)").matches;
  // O fundo atual é lido UM quadro depois de cada troca (estilo já calculado: a leitura sai de graça) e guardado.
  // Ler na hora da troca, com a tela recém-mexida, obrigava o navegador a recalcular a página inteira (60–100 ms).
  let fundo = null, leitura = 0;
  const guardaFundo = ph => {
    cancelAnimationFrame(leitura);
    leitura = requestAnimationFrame(() => setTimeout(() => {
      const cs = getComputedStyle(ph); fundo = { cor: cs.backgroundColor, img: cs.backgroundImage };
    }, 0));
  };
  // capa preta/branca/cinza (quase sem cor) deixava o fundo do Gradiente marrom-acinzentado: a cor sem saturação
  // ganha um toque do azul da marca (quanto mais cinza, mais azul); cores vivas ficam como estão
  const BRAND = [47, 107, 255];
  function viva(c) {
    const m = /^#?([0-9a-f]{6})$/i.exec(String(c || "").trim()); if (!m) return c;
    const rgb = [0, 2, 4].map(i => parseInt(m[1].slice(i, i + 2), 16));
    const mx = Math.max(...rgb) / 255, mn = Math.min(...rgb) / 255, l = (mx + mn) / 2, d = mx - mn;
    const sat = d ? d / (1 - Math.abs(2 * l - 1)) : 0;
    if (sat >= .2) return c;
    const f = .6 * (1 - sat / .2);
    return "#" + rgb.map((v, i) => Math.round(v * (1 - f) + BRAND[i] * f).toString(16).padStart(2, "0")).join("");
  }
  function aplicar(cores) {
    if (!Array.isArray(cores) || !cores.length) return;
    cores = cores.map(viva);
    const root = document.documentElement.style;
    if (cores.every((c, k) => root.getPropertyValue(`--c${k + 1}`).trim() === c)) return;
    const ph = document.getElementById("phone");
    const tinha = !!root.getPropertyValue("--c1").trim();
    if (ph && tinha && fundo && !document.hidden && !quietas()) {
      const velho = document.createElement("div");
      velho.className = "cor-fade";
      velho.style.backgroundColor = fundo.cor;
      velho.style.backgroundImage = fundo.img;
      ph.querySelectorAll(":scope > .cor-fade").forEach((x, i, l) => { if (i < l.length - 1) x.remove(); });   // trocas rápidas: no máximo 2
      ph.prepend(velho);
      cores.forEach((c, k) => root.setProperty(`--c${k + 1}`, c));
      const a = velho.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 800, easing: "ease" });
      a.onfinish = a.oncancel = () => velho.remove();
    } else cores.forEach((c, k) => root.setProperty(`--c${k + 1}`, c));
    if (ph) guardaFundo(ph);
  }
  return { aplicar };
})();

// Nome de artista que abre a página dele (mecanismo ÚNICO, em toda a interface).
// Quem desenha um nome de artista usa Sounos.artistHtml(faixa) / Sounos.artistLink(nome, url): o nome vira
// <span class="art-nome" data-artista="…" data-url="…">. UM tratador de toque delegado (abaixo, na fase de captura) abre
// a página do artista e NÃO deixa o toque chegar à linha (que tocaria a música). O resto da linha segue igual.
// Várias pessoas ("A, B"): cada nome abre o seu (o canal vale para o 1º, o único que a faixa traz); sem lista, o nome inteiro.
(() => {
  const escA = s => String(s ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  const norm = s => String(s ?? "").normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().replace(/\s+/g, " ").trim();
  const S = window.Sounos;
  S.artistLink = (nome, url = "") => {
    const n = String(nome ?? "").trim();
    return n ? `<span class="art-nome" data-artista="${escA(n)}"${url ? ` data-url="${escA(url)}"` : ""}>${escA(n)}</span>` : "";
  };
  S.artistHtml = (t, o = {}) => {
    const nomes = (Array.isArray(t?.artists) ? t.artists : []).map(x => String(x ?? "").trim()).filter(Boolean);
    const url = t?.artistUrl || "";
    const lista = nomes.length ? nomes : [String(t?.artist ?? "").trim()].filter(Boolean);
    return lista.map((n, i) => S.artistLink(n, i === 0 ? url : "")).join(o.sep ?? ", ");
  };
  S.openArtist = (nome, url = "") => {
    nome = String(nome ?? "").trim(); if (!nome) return false;
    let w = window, P = w.SounosPages;
    if (!P && window.parent !== window) { try { w = window.parent; P = w.SounosPages; } catch { P = null; } }
    if (!P) return false;
    const abrir = () => { w.__sonusArtistIntentAt = performance.now(); P.openArtist(nome, { __intentAt: w.__sonusArtistIntentAt, url: url || "" }); };
    // do player: ele desce primeiro e a página do artista abre na tela de baixo (como na busca)
    if (w.__playerOpen && w.__playerShell?.closeThen) w.__playerShell.closeThen(abrir); else abrir();
    return true;
  };
  document.addEventListener("click", e => {
    const el = e.target?.closest?.(".art-nome"); if (!el || el.closest("#queue")) return;   // a fila do player trata o toque no pointerup
    const nome = el.dataset.artista || "";
    const pg = el.closest(".pg.artist") || (el.closest(".add-ov") ? (t => t?.classList.contains("artist") ? t : null)([...document.querySelectorAll(".pg:not(.closing)")].pop()) : null);   // folhas: vale a página aberta por baixo
    if (pg && [pg.dataset.artista, pg.querySelector(".name")?.textContent].some(x => norm(x) === norm(nome))) return;   // já é a página dele: o toque segue para a linha
    e.stopPropagation(); e.preventDefault();
    let atraso = 0;
    if (el.closest(".add-ov")) { window.SounosTrackMenu?.close?.(); window.__sounosAdd?.close?.(); atraso = 140; }   // folhas ficam por cima das páginas: fecha antes
    if (atraso) setTimeout(() => S.openArtist(nome, el.dataset.url), atraso); else S.openArtist(nome, el.dataset.url);
  }, true);
})();
