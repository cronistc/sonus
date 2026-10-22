// Player ligado ao app: a fila, o tempo e os comandos vêm do player nativo (que continua tocando com a tela desligada).
// Este arquivo só age dentro do app; no navegador o player continua com os dados de exemplo.
(() => {
  if (!Sounos.native) return;
  let S = null;                       // último estado vindo do app
  const lyricsById = {};
  const root = document.documentElement.style;

  const toLocal = t => ({ id: t.id, title: t.title || "—", artist: Sounos.artistLine(t), dur: Math.max(1, t.dur || 1),
                          c: Sounos.hashColors(t.id), lrc: lyricsById[t.id] ? lyricsById[t.id].lrc : undefined, plain: !!(lyricsById[t.id] && lyricsById[t.id].plain), cover: Sounos.coverUrl(t), raw: t });
  const coverBg = t => `url('${t.cover}') center/cover no-repeat, linear-gradient(135deg,${t.c[0]},${t.c[1]} 60%,${t.c[2]})`;

  // ---------- estado do app → tela ----------
  function applyState(st, first) {
    const ids = l => (l || []).map(t => t.id).join("|");
    const prev = S;
    const modeBefore = queueMode();
    S = st;
    tracks.length = 0;
    st.queue.forEach(t => tracks.push(toLocal(t)));
    st.suggestions.forEach(t => tracks.push(toLocal(t)));
    base.order = st.queue.map((_, i) => i);
    base.cur = Math.max(0, st.index);
    sugg = st.suggestions.map((_, i) => st.queue.length + i);
    nowT = base.cur;
    repeat = st.repeat; shuffle = st.shuffle; playing = st.playing;
    context = st.context?.type === "radio" ? "radio" : "playlist";
    const cur = tracks[nowT];
    if (cur) {
      cur.cover = Sounos.coverUrl(st.current || st.queue[nowT], false, true);
      cur.c = st.colors;
      if (st.durationMs > 0) cur.dur = st.durationMs / 1000;
      st.colors.forEach((c, k) => root.setProperty(`--c${k + 1}`, c));
    }
    pos = (st.positionMs || 0) / 1000;

    const queueChanged = first || ids(prev?.queue) !== ids(st.queue) || modeBefore !== queueMode() ||
                         (queueMode() === "sugg" && ids(prev?.suggestions) !== ids(st.suggestions));
    if (queueChanged && !holdQueue) refreshQueue(!first); else if (!holdQueue) markQueue();
    if (first || prev?.current?.id !== st.current?.id) { const keep = pos; loadCurrent(); pos = keep; }
    renderDl(); render();
    syncPlayerClock();
    $("phone").classList.toggle("loading", !!st.loading);
  }

  // ---------- telas: capa de verdade, fila com capas, duração real ----------
  const _loadCurrent = loadCurrent;
  loadCurrent = function () {
    if (!track()) return;
    _loadCurrent();
    const t = track();
    $("cover").textContent = "";
    $("cover").style.background = coverBg(t);
    syncLyrBtn();
  };
  // "A" só existe quando a música tem letra sincronizada; sem ela, a tela da letra fecha sozinha
  function syncLyrBtn() {
    const t = track();
    if (t && t.lrc === undefined) return;          // letra ainda carregando (undefined ≠ null "sem letra"): mantém o "A" e o modo como estão
    const has = !!(t && t.lrc && !t.plain);
    $("lyricsBtn").hidden = !has;
    if (!has && lyricsMode) $("lyricsBtn").click();
  }
  buildQueue = function () {
    queue.innerHTML = shownList().map((ti, i) => {
      const t = tracks[ti]; if (!t) return "";
      return `<div class="qi" data-t="${ti}" style="--i:${i};background:${coverBg(t)}">
        <span class="eq"><i></i><i></i><i></i></span>
        <div class="meta"><div class="qt">${esc(t.title)}</div><div class="qa">${Sounos.handle(t.artist)}</div></div></div>`;
    }).join("");
    markQueue();
  };
  const esc = s => String(s).replace(/[&<>"]/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[ch]));
  const _render = render;
  render = function () { if (track()) _render(); };

  // tempo: o app manda a posição real (o que já saiu no fone) 4×/s; entre uma e outra a tela anda sozinha
  tick = function (now) {
    const dt = (now - lastTick) / 1000;
    if (playing && track() && playerCanAnimate()) {
      pos = Math.min(pos + dt, track().dur);
      render(true);
    }
    lastTick = now;
  };

  // ---------- comandos → app ----------
  setPlaying = function (v) { playing = v; lastTick = performance.now(); Sounos.call(v ? "play" : "pause"); render(); syncPlayerClock(); };
  next = function () { Sounos.call("next"); };
  prev = function () { Sounos.call("prev"); };
  $("prev").onclick = () => prev();
  $("next").onclick = () => next();
  playBase = function (i) { Sounos.call("playIndex", { index: i }); };
  playSuggestion = function (ti) { Sounos.call("playSuggestion", { track: tracks[ti].raw }); };
  endQueueGesture = (orig => function (tap) {
    // toque num bloco: troca de música (o app já começa tocando)
    if (qg.mode === "pending" && tap && qg.item) {
      clearTimeout(qg.holdT); qg.mode = null;
      const i = [...queue.children].indexOf(qg.item), ti = +qg.item.dataset.t;
      if (queueMode() === "sugg") { if (ti !== nowT) playSuggestion(ti); }
      else if (i !== base.cur) playBase(i);
      return;
    }
    return orig(tap);
  })(endQueueGesture);
  endDrag = (orig => function () {
    const from = +qg.item.dataset.t;
    orig();
    if (queueMode() === "base") { const to = [...queue.children].indexOf(qg.item); if (to !== from) Sounos.call("move", { from, to }); }
  })(endDrag);
  cycleRepeat = function () { Sounos.call("setRepeat", { mode: { off: "all", all: "one", one: "off" }[repeat] }); };
  toggleShuffle = function () { Sounos.call("setShuffle", { on: !shuffle }); };

  // avançar/voltar (anel, botão esticado, toque na letra): a tela responde na hora e o app recebe o pedido
  let seekT = 0;
  const seekSoon = () => { clearTimeout(seekT); seekT = setTimeout(() => Sounos.call("seek", { sec: pos }), 120); };
  const _scrubBy = scrubBy; scrubBy = dx => { _scrubBy(dx); seekSoon(); };

  // ---------- letra ----------
  const _buildLyrics = buildLyrics;
  buildLyrics = function () {
    const t = track(); if (!t) return;
    _buildLyrics();
    $("lyr").classList.toggle("plain", !!t.plain);
    [...$("lines").children].forEach((el, i) => {
      if (!t.lrc || !t.lrc[i]) return;
      el.onclick = () => selecting ? toggleLine(i) : (t.plain ? null : (pos = t.lrc[i][0], render(), Sounos.call("seek", { sec: pos })));
    });
  };
  function applyLyrics(p) {
    if (!p || !p.id || p.pending) return;            // ainda procurando: não decide nada

    const l = p.lyrics;
    let lrc = null, plain = false;
    if (l && l.synced) lrc = l.synced;
    else if (l && l.plain) { lrc = l.plain.split("\n").filter(Boolean).map(x => [1e9, x]); plain = true; }
    lyricsById[p.id] = { lrc, plain };               // lrc null = sabemos que não tem letra
    const t = tracks.find(x => x.id === p.id);
    if (t) { t.lrc = lrc; t.plain = plain; if (t === track()) { buildLyrics(); syncLyrics(); syncLyrBtn(); } }
  }

  // ---------- baixar ----------
  const dls = {};                              // id → progresso 0..1
  renderDl = function () {
    const t = track(); if (!t) return;
    const b = $("dlBtn"), done = !!S?.downloaded || dls[t.id] === 1, p = dls[t.id], loading = p != null && p < 1 && !done;
    b.classList.toggle("done", done); b.classList.toggle("loading", loading);
    b.querySelector(".dl-prog").style.strokeDasharray = `${loading ? Math.round(p * 100) : 0} 100`;
    b.setAttribute("aria-label", done ? "Baixada" : loading ? "Baixando" : "Baixar música");
  };
  $("dlBtn").onclick = () => {
    const t = track(); if (!t || S?.downloaded || dls[t.id] != null) return;
    dls[t.id] = 0; renderDl();
    Sounos.call("download", { track: t.raw });
  };
  Sounos.on("download", p => {
    if (p.error) delete dls[p.id]; else dls[p.id] = p.done ? 1 : p.p;
    renderDl();
  });

  // ---------- @artista → página do artista (na tela inicial, por baixo do player) ----------
  $("artist").style.cursor = "pointer";
  $("artist").addEventListener("click", () => {
    const t = track(), P = window.parent !== window && window.parent.SounosPages;
    if (!t || !P) return;
    window.parent.__playerShell.closeThen(() => P.openArtist(t.raw.artist));
  });

  // ---------- "+" com a música de verdade ----------
  $("addBtn").onclick = () => track() && add.open(track().raw);

  // ---------- eventos do app ----------
  Sounos.on("state", st => applyState(st, false));
  Sounos.on("progress", p => {
    if (!track()) return;
    if (!scrubbing && !ring.classList.contains("drag")) pos = p.pos;
    if (p.dur > 0) track().dur = p.dur;
    const changed = p.playing !== playing;
    playing = p.playing; lastTick = performance.now();
    if (changed) { render(); syncPlayerClock(); return; }
    if (!playing && playerCanAnimate()) render();    // parado: uma busca atualiza a tela uma única vez
  });
  Sounos.on("lyrics", applyLyrics);

  // dentro da tela inicial (quadro do player): parado enquanto escondido, arruma a fila ao aparecer
  function syncTheme() {
    try {
      const theme = JSON.parse(localStorage.getItem("sounos.settings"))?.theme;
      if (theme) $("phone").dataset.theme = theme;
    } catch {}
  }
  window.__playerShown = () => { syncTheme(); fitCover(); setPlayerVisible(true); if (S) centerQueue(false); };
  window.__playerHidden = () => setPlayerVisible(false);

  // tema trocado na tela inicial
  addEventListener("storage", e => {
    if (e.key === "sounos.settings" && !$("phone").classList.contains("player-hidden")) {
      try { $("phone").dataset.theme = JSON.parse(e.newValue).theme; } catch {}
    }
  });

  // estado inicial: durante a transição de abertura, a fila (muitas capinhas) espera a animação terminar
  let holdQueue = true;
  let released = false;
  const go = () => { if (released) return; released = true; holdQueue = false; if (S) { refreshQueue(false); centerQueue(false); } };
  (window.__afterReveal || Promise.resolve()).then(go);
  setTimeout(go, 1500);                                    // garantia: nunca fica sem a fila
  const st = Sounos.call("getState");
  if (st && st.queue && st.queue.length) {
    applyState(st, true);
    applyLyrics(Sounos.call("getLyrics"));
  }
})();
