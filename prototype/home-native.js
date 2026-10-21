// Tela inicial ligada ao app: histórico real, seus artistas, suas playlists e o mini-player do player nativo.
(() => {
  if (!Sounos.native) return;
  const esc = s => String(s ?? "").replace(/[&<>"]/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[ch]));
  const bg = (t, round) => `url('${Sounos.coverUrl(t, true)}') center/cover no-repeat, linear-gradient(135deg,${Sounos.hashColors(t.id).join(",")})`;
  let S = Sounos.call("getState");
  let lyr = null;                          // letra da música atual (sincronizada)
  let items = [];                          // o que está na tela (para os toques)

  // ---------- seções ----------
  const artistsSec = $("artists").closest("section");
  artistsSec.querySelector("h2").textContent = "Seus artistas";
  document.querySelectorAll(".sec-head button").forEach(b => b.style.display = "none");   // "Ver tudo": tela ainda não existe

  function renderHome() {
    const hist = Sounos.call("getHistory");
    const list = (Array.isArray(hist) ? hist : []).filter(e => Sounos.isSong(e.track));      // vídeos ouvidos antes não aparecem
    items = [];
    // recentes: música solta, ou a playlist de onde a música tocou
    const seenPl = new Set(), recent = [];
    for (const e of list) {
      const src = e.source || {};
      if (src.type === "playlist" && src.id) {
        if (seenPl.has(src.id)) continue; seenPl.add(src.id);
        const p = SounosData.getPlaylists().find(x => x.id === src.id);
        if (p) { recent.push({ kind: "playlist", p }); continue; }
      }
      recent.push({ kind: "track", t: e.track });
      if (recent.length >= 12) break;
    }
    $("recent").innerHTML = recent.length ? recent.map(r => {
      const k = items.push(r) - 1;
      if (r.kind === "playlist") return `<button class="blk big tap" data-k="${k}">${mosaicOf(r.p)}
        <span class="kind">${ICON.playlist}Playlist</span>${meta(esc(r.p.name), count(r.p))}</button>`;
      return `<button class="blk big tap" data-k="${k}" style="background:${bg(r.t)}">${meta(esc(r.t.title), Sounos.handle(r.t.artist))}</button>`;
    }).join("") : `<button class="blk big tap" data-k="search" style="background:linear-gradient(135deg,var(--c1),var(--c2))">
        <div class="ini" style="font-size:44px">♪</div>${meta("Busque uma música", "para começar")}</button>`;

    // seus artistas: os que você mais ouve (no app ainda não existe o botão "seguir")
    const by = new Map();
    for (const e of list) { const a = e.track.artist; if (!a) continue; const x = by.get(a) || { n: 0, t: e.track }; x.n++; by.set(a, x); }
    const arts = [...by.entries()].sort((a, b) => b[1].n - a[1].n).slice(0, 12);
    artistsSec.style.display = arts.length ? "" : "none";
    $("artists").innerHTML = arts.map(([name, x]) => {
      const k = items.push({ kind: "artist", name }) - 1;
      return `<button class="ar tap" data-k="${k}" style="text-align:center"><div class="pic" style="background:${bg(x.t)}"></div><div class="n">${Sounos.handle(name)}</div></button>`;
    }).join("");
    renderPlaylists();
    if (stateSeen) fitHome();   // seção de artistas pode ter aparecido/sumido
  }
  const count = p => p.tracks.length === 1 ? "1 música" : `${p.tracks.length} músicas`;
  const mosaicOf = p => {
    if (!p.tracks.length) return `<div class="mosaic" style="background:linear-gradient(135deg,var(--c1),var(--c2))"></div>`;
    const four = [0, 1, 2, 3].map(i => p.tracks[Math.min(i, p.tracks.length - 1)]);
    return `<div class="mosaic">${four.map(t => `<i style="background:${bg(t)}"></i>`).join("")}</div>`;
  };
  renderPlaylists = function () {
    playlists = SounosData.getPlaylists();
    $("playlists").innerHTML = `<button class="blk new tap" aria-label="Nova playlist"><span class="in">
      <svg class="lu" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>Nova playlist</span></button>`
      + playlists.map(p => { const k = items.push({ kind: "playlist", p }) - 1;
        return `<button class="blk tap" data-k="${k}">${mosaicOf(p)}${meta(esc(p.name), count(p))}</button>`; }).join("");
  };

  // toques nas seções
  document.addEventListener("click", e => {
    const el = e.target.closest("[data-k]"); if (!el || e.target.closest("#mini")) return;
    if (el.dataset.k === "search") return $("searchBtn").click();
    const it = items[+el.dataset.k]; if (!it) return;
    if (it.kind === "track") { Sounos.call("playSingle", { track: it.t, context: { type: "radio" } }); openPlayer(); }
    else if (it.kind === "playlist") pages.openPlaylist(it.p.id);
    else if (it.kind === "artist") pages.openArtist(it.name);
  }, true);
  // "Nova playlist": cria e abre a página dela, com o nome pronto para digitar
  document.addEventListener("click", e => {
    if (!e.target.closest("#playlists .blk.new")) return;
    e.stopImmediatePropagation(); pages.newPlaylist();
  }, true);
  const pages = SounosPages.attach($("phone"), { onChange: () => renderHome() });

  // ---------- mini-player ----------
  const cur = () => S && S.current && S.current !== null ? S.current : null;
  let shownId = null;                      // música que o mini-player está mostrando
  let stateSeen = false;                   // o primeiro estado (ao abrir a tela) não anima
  // Mini-player sobe de baixo; o conteúdo dá uma subidinha e diminui um pouco o zoom (só transform/opacity)
  function miniRise() {
    if (!homeCanAnimate() || matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    const o = { duration: 450, easing: "cubic-bezier(.2,.85,.25,1)" };
    $("mini").animate([{ opacity: 0, transform: "translateY(48px)" }, { opacity: 1, transform: "none" }], o);
    document.querySelector(".scroll").animate([
      { transform: "none" }, { transform: "translateY(-10px) scale(.97)", offset: .4 }, { transform: "none" }], o);
  }
  function applyState(st) {
    const before = shownId;
    S = st;
    const t = cur();
    const appeared = !!t && !$("mini").classList.contains("has-track") && stateSeen;   // mini-player acabou de aparecer
    stateSeen = true;
    $("mini").classList.toggle("has-track", !!t);
    $("phone").classList.toggle("paused", !st.playing);
    $("phone").style.setProperty("--mini-h", t ? $("mini").offsetHeight + "px" : "0px");
    playing = !!st.playing; shuffle = st.shuffle; repeat = st.repeat;
    pos = (st.positionMs || 0) / 1000;
    last = performance.now();
    renderMiniIcon();
    homeClockChanged();
    fitHome();                             // blocos se reajustam à nova altura disponível
    if (appeared) miniRise();
    if (!t) return;
    (st.colors || []).forEach((c, k) => root.setProperty(`--c${k + 1}`, c));
    if (before !== t.id) { shownId = t.id; lastBarPixel = -1; loadMini(); if (before) renderHome(); }
  }
  loadMini = function () {
    const t = cur(); if (!t) return;
    $("miniIni").textContent = "";
    $("miniCv").style.background = bg(t);
    $("miniT").textContent = t.title;
    $("miniA").textContent = Sounos.handle(t.artist);
    if (!miniFirst) {
      swapIn($("miniCv"), { opacity: .3, transform: "scale(.85)" });
      swapIn($("miniT"), { opacity: 0, transform: "translateX(12px)" });
      swapIn($("miniA"), { opacity: 0, transform: "translateX(12px)" }, 360);
    }
    miniFirst = false;
    lyr = null; syncMiniA(); buildMiniLyrics();
  };
  // "A" do mini-player só com letra sincronizada (e a letra aberta fecha quando a música não tem)
  function syncMiniA() {
    $("miniLyr").hidden = !lyr;
    if (!lyr && lyrOpen) $("miniLyr").click();
  }
  syncMiniLyrics = function () {
    if (!lyrOpen) return;
    if (!lyr) { if (lastLine !== "none") { lastLine = "none"; showLine("Letra indisponível para esta faixa", "empty"); } return; }
    const at = pos + lyricsOffset();
    let lo = 0, hi = lyr.length;
    while (lo < hi) { const mid = (lo + hi) >>> 1; if (lyr[mid][0] <= at) lo = mid + 1; else hi = mid; }
    const ln = lo - 1;
    if (ln === lastLine) return;
    lastLine = ln;
    showLine(lyr[Math.max(ln, 0)][1], ln < 0 ? "dim" : "");
  };
  curDur = () => Math.max(1, (S?.durationMs || 0) / 1000 || cur()?.dur || 1);
  atEnd = () => { pos = curDur(); };                 // o player nativo passa para a próxima
  step = d => Sounos.call(d > 0 ? "next" : "prev");
  doShuffle = () => Sounos.call("setShuffle", { on: !shuffle });
  doRepeat = () => Sounos.call("setRepeat", { mode: { off: "all", all: "one", one: "off" }[repeat] });
  doToggle = () => { playing = !playing; $("phone").classList.toggle("paused", !playing); renderMiniIcon(); homeClockChanged(); Sounos.call(playing ? "play" : "pause"); };
  let seekT = 0;
  scrubBy = dx => { const d = curDur(); pos = Math.max(0, Math.min(d, pos + dx * 0.002 * d)); clearTimeout(seekT); seekT = setTimeout(() => Sounos.call("seek", { sec: pos }), 120); };
  currentForAdd = () => cur();

  Sounos.on("state", applyState);
  Sounos.on("progress", p => {
    const changed = playing !== p.playing;
    if (!scrubbing) pos = p.pos;
    playing = p.playing; $("phone").classList.toggle("paused", !playing); last = performance.now();
    if (changed) renderMiniIcon();
    if (homeCanAnimate() && !playing) drawMiniProgress();
    if (changed) homeClockChanged();
  });
  addEventListener("sounos:visibility", e => {
    if (!e.detail?.active) return;
    const p = Sounos.call("getProgress");
    if (Number.isFinite(p.pos) && !scrubbing) pos = p.pos;
    playing = !!p.playing; $("phone").classList.toggle("paused", !playing); last = performance.now();
    renderMiniIcon();
    if (homeCanAnimate() && !playing) drawMiniProgress();
    homeClockChanged();
  });
  Sounos.on("lyrics", p => {
    if (!cur() || p.id !== cur().id || p.pending) return;
    const l = p.lyrics; lyr = l && l.synced ? l.synced : null;
    syncMiniA();
    buildMiniLyrics();
  });

  renderHome();
  applyState(S);
  if (cur()) window.__sounos.emit("lyrics", Sounos.call("getLyrics"));

// ---------- player embutido ----------
// O player fica carregado num quadro por cima da tela inicial (escondido, parado). Abrir/fechar é só
// deslizar esse quadro: sem carregar página, sem montar nada na hora — por isso não engasga.
{
  const f = document.createElement("iframe");
  window.__playerOpen = false;
  f.title = "Player";
  f.style.cssText = "position:fixed;left:0;top:0;width:100%;height:100%;border:0;z-index:60;background:#0e0e10;" +
                    "visibility:hidden;transform:translateY(100%)";
  let afterBack = null;
  let y = null, isOpen = false, pushed = false, anim = null, loaded = false, onLoad = null;
  const H = () => window.innerHeight;
  const win = () => f.contentWindow;
  function place(v) { y = v; f.style.transform = `translateY(${v}px)`; }
  function slide(to, ms, done) {
    const from = y ?? H();
    anim?.cancel();
    f.style.visibility = "visible";
    place(to);
    anim = f.animate([{ transform: `translateY(${from}px)` }, { transform: `translateY(${to}px)` }],
                     { duration: ms, easing: "cubic-bezier(.2,.85,.25,1)" });
    anim.onfinish = () => { anim = null; done?.(); };
  }
  const shell = window.__playerShell = {
    open(opts = {}) {
      if (!cur()) return;
      if (!loaded) { onLoad = () => shell.open(opts); return; }
      if (!isOpen) {
        const d = history.state?.d || 0;
        if (opts.replace) { history.replaceState({ player: true, d }, ""); pushed = true; }
        else if (!pushed) { history.pushState({ player: true, d: d + 1 }, ""); pushed = true; }
      }
      isOpen = true; window.__playerOpen = true; $("phone").classList.add("player-open"); homeClockChanged();
      win().__playerShown?.();
      slide(0, 420);
    },
    // fecha e, depois que o "voltar" do histórico terminar, faz outra coisa (ex.: abrir a página do artista)
    closeThen(fn) { if (isOpen && pushed) { afterBack = fn; shell.close(); } else { shell.close(); fn(); } },
    close(fromPop) {
      const wasOpen = isOpen;
      isOpen = false;
      // continua desenhado logo abaixo da tela (pronto para subir sem atraso), só que parado
      slide(H(), 340, () => { if (!isOpen) { win().__playerHidden?.(); y = null; window.__playerOpen = false; $("phone").classList.remove("player-open"); homeClockChanged(); } });
      if (wasOpen && !fromPop && pushed) { pushed = false; history.back(); }
    },
    // arrastando o mini-player para cima: o player aparece por baixo do dedo
    peek(dy) { anim?.cancel(); anim = null; if (loaded) { f.style.visibility = "visible"; if (!isOpen) win().__playerShown?.(); if (!window.__playerOpen) { window.__playerOpen = true; $("phone").classList.add("player-open"); homeClockChanged(); } place(Math.max(0, H() - dy * 1.1)); } },
    // puxando o player para baixo
    drag(dy) { anim?.cancel(); anim = null; place(dy); },
    snap() { if (isOpen) slide(0, 300); },
  };
  openPlayer = () => shell.open();
  addEventListener("popstate", () => {
    if (isOpen && !history.state?.player) { pushed = false; shell.close(true); }
    const f = afterBack; afterBack = null; f?.();
  });
  f.addEventListener("load", () => { loaded = true; f.style.visibility = "visible"; const g = onLoad; onLoad = null; g?.(); });
  // carrega depois que a tela inicial já apareceu
  setTimeout(() => { f.src = "player.html"; document.body.appendChild(f); }, 350);
  // o "+" ou as configurações mudaram algo no player → a tela inicial acompanha
  addEventListener("storage", e => {
    if (e.key === "sounos.settings") { try { document.documentElement.dataset.theme = JSON.parse(e.newValue).theme; } catch {} }
    else renderHome();
  });
}
})();
