// Módulo Mini player — ligação com o player nativo (estado, progresso, botões). Ver MODULO.md
(() => {
  const A = window.SonusApp; if (!A) return;
  const cur = A.cur, bg = A.bg;
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
    A.S = st; const S = st;
    const t = cur();
    const appeared = !!t && !$("mini").classList.contains("has-track") && stateSeen;   // mini-player acabou de aparecer
    stateSeen = true;
    $("mini").classList.toggle("has-track", !!t);
    $("miniAdd").hidden = !t;
    $("phone").classList.toggle("paused", !st.playing);
    $("phone").style.setProperty("--mini-h", t ? $("mini").offsetHeight + "px" : "0px");
    playing = !!st.playing; shuffle = st.shuffle; repeat = st.repeat;
    pos = (st.positionMs || 0) / 1000;
    last = performance.now();
    renderMiniIcon(!!st.playing || (!!st.loading && !!st.playWhenReady), !!st.loading);
    homeClockChanged();
    fitHome();                             // blocos se reajustam à nova altura disponível
    if (appeared) miniRise();
    if (!t) return;
    (st.colors || []).forEach((c, k) => root.setProperty(`--c${k + 1}`, c));
    window.SounosSyncSystemBars?.();
    if (before !== t.id) { shownId = t.id; resetMiniBar(); loadMini(); if (before) SonusInicio.render(); }
    if (homeCanAnimate()) drawMiniProgress();   // barra já na posição nova (não espera o próximo ciclo do relógio)
    SounosTips?.offer("mini");
  }
  loadMini = function () {
    const t = cur(); if (!t) return;
    $("miniIni").textContent = "";
    $("miniCv").style.background = bg(t);
    $("miniT").textContent = t.title;
    $("miniA").textContent = Sounos.handle(Sounos.artistLine(t));
    if (!miniFirst) {
      swapIn($("miniCv"), { opacity: .3, transform: "scale(.85)" });
      swapIn($("miniT"), { opacity: 0, transform: "translateX(12px)" });
      swapIn($("miniA"), { opacity: 0, transform: "translateX(12px)" }, 360);
    }
    miniFirst = false;
  };
  curDur = () => Math.max(1, (A.S?.durationMs || 0) / 1000 || cur()?.dur || 1);
  durKnown = () => (A.S?.durationMs || 0) > 0 || (cur()?.dur || 0) > 1;
  atEnd = () => { pos = curDur(); };                 // o player nativo passa para a próxima
  step = d => Sounos.call(d > 0 ? "next" : "prev");
  doShuffle = () => Sounos.call("setShuffle", { on: !shuffle });
  doRepeat = () => Sounos.call("setRepeat", { mode: { off: "all", all: "one", one: "off" }[repeat] });
  doToggle = () => {
    const nextPlaying = !(!!playing || (!!A.S?.loading && !!A.S?.playWhenReady));
    if (!A.S?.loading) { playing = nextPlaying; $("phone").classList.toggle("paused", !playing); homeClockChanged(); }
    renderMiniIcon(nextPlaying, !!A.S?.loading);
    Sounos.call(nextPlaying ? "play" : "pause");
  };
  let seekT = 0;
  scrubBy = dx => { const d = curDur(); pos = Math.max(0, Math.min(d, pos + dx * 0.002 * d)); clearTimeout(seekT); seekT = setTimeout(() => Sounos.call("seek", { sec: pos }), 120); };
  currentForAdd = () => cur();

  Sounos.on("state", applyState);
  Sounos.on("progress", p => {
    if (p.id && cur() && p.id !== cur().id) return;       // progresso de outra música (troca em andamento)
    const changed = playing !== p.playing;
    if (!scrubbing) pos = p.pos;
    playing = p.playing; $("phone").classList.toggle("paused", !playing); last = performance.now();
    if (changed) renderMiniIcon(!!p.playing || (!!p.loading && !!p.playWhenReady), !!p.loading);
    if (homeCanAnimate() && !playing) drawMiniProgress();
    if (changed) homeClockChanged();
  });
  addEventListener("sounos:visibility", e => {
    if (!e.detail?.active) return;
    const p = Sounos.call("getProgress");
    if (Number.isFinite(p.pos) && !scrubbing) pos = p.pos;
    playing = !!p.playing; $("phone").classList.toggle("paused", !playing); last = performance.now();
    renderMiniIcon(!!p.playing || (!!p.loading && !!p.playWhenReady), !!p.loading);
    if (homeCanAnimate() && !playing) drawMiniProgress();
    homeClockChanged();
  });
  applyState(A.S);
  if (cur()) SounosTips?.offer("mini");
  // O player embutido lê a letra nativa; este envio mantém o quadro sincronizado ao carregá-la.
  if (cur()) window.__sounos.emit("lyrics", Sounos.call("getLyrics"));
})();
