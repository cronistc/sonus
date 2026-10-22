// Dicas contextuais: aparecem uma vez quando o recurso fica visível e não há gesto ativo.
(() => {
  const TIPS_KEY = "sounos.tips";
  const VERSION_KEY = "sounos.tipsVersion";
  const TIPS = [
    { id: "busca-basica", version: "0.1.2", screen: "home", selector: "#searchBtn", text: "Toque na lupa para encontrar músicas e abrir suas pesquisas." },
    { id: "nova-playlist", version: "0.1.2", screen: "home", selector: "#playlists .blk.new", text: "Toque em Nova playlist para criar uma lista e escolher as músicas." },
    { id: "artistas-da-home", version: "0.1.2", screen: "home", selector: "#artists .ar", text: "Toque num artista para ver as músicas que você ouviu dele." },
    { id: "mini-abrir-player", version: "0.1.2", screen: "mini", selector: "#miniOpen", text: "Toque no título ou puxe o mini-player para cima para abrir o player." },
    { id: "mini-adicionar-playlist", version: "0.1.3", screen: "mini", selector: "#miniAdd", text: "Toque em + para adicionar a música atual a uma playlist." },
    { id: "minhas-musicas-offline", version: "0.1.3", screen: "offline", selector: "#recentTitle", text: "Sem internet, Minhas músicas reúne downloads e faixas em cache." , when: () => document.getElementById("offlineMark")?.hidden === false },
    { id: "busca-albuns-artistas", version: "0.1.3", screen: "search-results", selector: ".search-ov.open .sbar", text: "A busca também mostra álbuns e vários artistas. Toque neles para abrir.", when: () => !!document.querySelector(".search-ov.open .s-album, .search-ov.open .s-art") },
    { id: "pagina-artista", version: "0.1.2", screen: "artist", selector: ".pg.artist .ph .name", text: "No perfil do artista, toque numa faixa para ouvi-la." },
    { id: "albuns-do-artista", version: "0.1.3", screen: "artist-albums", selector: ".pg.artist .albums .s-album", text: "Toque num álbum para abrir a lista completa de faixas." },
    { id: "player-toque-repetir", version: "0.1.2", screen: "player", selector: "#play", text: "Toque para tocar ou pausar. Segure e solte para alternar: repetir fila, repetir faixa (ícone com 1) ou desligar." },
    { id: "player-aleatorio", version: "0.1.2", screen: "player", selector: "#play", text: "Deslize para cima ou para baixo no botão para alternar o aleatório. As setas cruzadas indicam essa mudança." },
    { id: "player-buscar-faixa", version: "0.1.2", screen: "player", selector: "#play", text: "Segure o botão e arraste para os lados para avançar ou voltar na música." },
    { id: "player-letra", version: "0.1.2", screen: "player", selector: "#lyricsBtn", text: "Toque no A para abrir a letra. Toque numa linha para ir até esse trecho." },
    { id: "player-fila", version: "0.1.2", screen: "player", selector: "#queueWrap", text: "Toque numa faixa para ouvi-la; arraste para percorrer a fila e segure uma capa para reordenar." },
    { id: "player-sugestoes", version: "0.1.2", screen: "suggestions", selector: "#queueWrap", text: "Ao repetir uma faixa, a fila mostra sugestões. Toque numa para ouvi-la." },
  ];

  const top = (() => { try { return window.top; } catch { return window; } })();
  const hub = top.__sounosTipsHub ||= { active: null, pending: [], queued: new Set(), nextAt: 0, lastTouch: 0, pointers: new Set(), timer: 0 };
  const parse = key => { try { return JSON.parse(localStorage.getItem(key)); } catch { return null; } };
  const readVersion = () => {
    const update = window.Sounos?.call("getUpdate");
    return update?.current?.versionName || "0.1.3";
  };
  const versionParts = value => String(value || "0").split(/[.-]/).map(part => Number.parseInt(part, 10) || 0);
  const compareVersion = (a, b) => {
    const x = versionParts(a), y = versionParts(b);
    for (let i = 0; i < Math.max(x.length, y.length); i++) {
      if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) < (y[i] || 0) ? -1 : 1;
    }
    return 0;
  };
  const previousVersion = value => {
    const parts = versionParts(value);
    while (parts.length < 3) parts.push(0);
    if (parts[2] > 0) parts[2]--;
    else if (parts[1] > 0) { parts[1]--; parts[2] = 99; }
    else if (parts[0] > 0) { parts[0]--; parts[1] = 99; parts[2] = 99; }
    return parts.join(".");
  };
  const hasOldLibrary = () => {
    const history = window.Sounos?.call("getHistory");
    const playlists = parse("sounos.playlists") || window.SounosData?.getPlaylists?.();
    const searches = parse("sounos.searchHistory");
    const downloads = window.Sounos?.call("getDownloads");
    return (Array.isArray(history) && history.length > 0) || (Array.isArray(playlists) && playlists.length > 0) ||
      (Array.isArray(searches) && searches.length > 0) || !!(downloads && Object.keys(downloads).length);
  };
  let state = hub.state || parse(TIPS_KEY);
  if (!state || typeof state !== "object" || Array.isArray(state)) state = { seen: {}, newcomer: !hasOldLibrary() };
  state.seen = state.seen && typeof state.seen === "object" ? state.seen : {};
  const savedVersion = parse(VERSION_KEY);
  if (typeof savedVersion?.version === "string") state.version = savedVersion.version;
  else if (!state.version) state.version = state.newcomer ? "0.0.0" : previousVersion(readVersion());
  hub.state = state;
  const currentVersion = readVersion();

  function save() {
    try {
      localStorage.setItem(TIPS_KEY, JSON.stringify({ seen: state.seen, newcomer: !!state.newcomer }));
      localStorage.setItem(VERSION_KEY, JSON.stringify({ version: state.version }));
    } catch {}
  }
  function eligible(tip) {
    return !state.seen[tip.id] && compareVersion(tip.version, currentVersion) <= 0 &&
      (state.newcomer || compareVersion(tip.version, state.version) > 0) && (!tip.when || tip.when());
  }
  function visible(el) {
    if (!el || !el.isConnected || !el.getClientRects().length) return false;
    const css = getComputedStyle(el), r = el.getBoundingClientRect();
    return css.display !== "none" && css.visibility !== "hidden" && Number(css.opacity) !== 0 && r.width > 0 && r.height > 0 &&
      r.right > 0 && r.bottom > 0 && r.left < innerWidth && r.top < innerHeight;
  }
  function contextActive(screen, target) {
    if (document.hidden || !visible(target)) return false;
    if (window === top && top.__playerOpen) return false;
    if (window !== top && top.__playerShell && !top.__playerOpen) return false;
    if (screen === "home" || screen === "mini" || screen === "offline") {
      if (document.querySelector(".search-ov.open, .set-ov.open, .add-ov.open, .upd.open, .sheet.open")) return false;
      if (document.querySelector("#phone.has-page")) return false;
    }
    if (screen === "search-results" && !document.querySelector(".search-ov.open")) return false;
    if ((screen === "player" || screen === "suggestions") && document.querySelector(".add-ov.open, .sheet.open")) return false;
    if (screen.startsWith("artist") && !target.closest(".pg.artist.open")) return false;
    if (target.closest("#phone.player-hidden")) return false;
    return true;
  }
  function finishTip(tip, bubble) {
    if (bubble.classList.contains("saindo")) return;
    bubble.classList.add("saindo");
    if (hub.active?.bubble === bubble) hub.active = null;
    setTimeout(() => bubble.remove(), 190);
    if (tip) {
      state.seen[tip.id] = true;
      const unfinished = TIPS.some(item => !state.seen[item.id] && compareVersion(item.version, currentVersion) <= 0 &&
        (state.newcomer || compareVersion(item.version, state.version) > 0));
      if (!unfinished) { state.version = currentVersion; state.newcomer = false; }
      save();
    }
    pump();
  }
  function placeTip(entry, target) {
    const bubble = document.createElement("div");
    bubble.className = "sonus-dica";
    bubble.setAttribute("role", "status");
    bubble.textContent = entry.tip.text;
    document.body.appendChild(bubble);
    const tr = target.getBoundingClientRect(), br = bubble.getBoundingClientRect(), gap = 12, pad = 12;
    const below = innerHeight - tr.bottom >= br.height + gap || tr.top < br.height + gap;
    const left = Math.max(pad, Math.min(innerWidth - br.width - pad, tr.left + tr.width / 2 - br.width / 2));
    const topY = below ? Math.min(innerHeight - br.height - pad, tr.bottom + gap) : Math.max(pad, tr.top - br.height - gap);
    bubble.dataset.lado = below ? "baixo" : "cima";
    bubble.style.left = `${left}px`;
    bubble.style.top = `${topY}px`;
    bubble.style.setProperty("--dica-seta", `${Math.max(12, Math.min(br.width - 12, tr.left + tr.width / 2 - left))}px`);
    requestAnimationFrame(() => bubble.classList.add("visivel"));
    state.seen[entry.tip.id] = true;
    hub.active = { bubble, close: () => finishTip(entry.tip, bubble) };
    hub.nextAt = Date.now() + 20000;
    save();
  }
  function schedulePump(delay) {
    if (hub.timer) return;
    hub.timer = setTimeout(() => { hub.timer = 0; pump(); }, Math.max(0, delay));
  }
  function pump() {
    if (hub.active || !hub.pending.length) return;
    const wait = Math.max(hub.nextAt - Date.now(), hub.lastTouch + 650 - Date.now(), hub.pointers.size ? 250 : 0);
    if (wait > 0) return schedulePump(wait);
    const entry = hub.pending.shift();
    hub.queued.delete(entry.tip.id);
    const target = entry.doc.querySelector(entry.tip.selector);
    if (!entry.api.contextActive(entry.tip.screen, target)) {
      if (hub.pending.length) schedulePump(0);
      return;
    }
    entry.api.placeTip(entry, target);
  }
  function offer(screen) {
    for (const tip of TIPS) {
      if (tip.screen !== screen || !eligible(tip)) continue;
      const target = document.querySelector(tip.selector);
      if (!visible(target) || hub.queued.has(tip.id)) continue;
      hub.queued.add(tip.id);
      hub.pending.push({ tip, doc: document, api, screen });
    }
    pump();
  }
  const api = { offer, contextActive, placeTip };
  document.addEventListener("pointerdown", e => {
    hub.pointers.add(`${window === top ? "top" : "frame"}:${e.pointerId}`);
    hub.lastTouch = Date.now();
    hub.active?.close();
  }, true);
  const releasePointer = e => {
    hub.pointers.delete(`${window === top ? "top" : "frame"}:${e.pointerId}`);
    if (hub.pending.length) pump();
  };
  window.addEventListener("pointerup", releasePointer, true);
  window.addEventListener("pointercancel", releasePointer, true);
  document.addEventListener("click", () => hub.active?.close(), true);
  window.SounosTips = api;
  save();
})();
