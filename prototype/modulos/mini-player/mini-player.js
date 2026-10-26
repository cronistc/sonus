// Módulo Mini player — abrir o player, gestos, botões e barra de progresso. Ver MODULO.md
// Abre o player completo. A animação é a transição entre telas (view transition):
// o mini-player vira a tela do player, a capinha vira a capa grande e o nome/@ deslizam para o lugar
function openPlayer() { location.href = "player.html"; }
$("miniOpen").onclick = () => { if (!sw.dragged) openPlayer(); };

// Deslizar para cima: o mini-player acompanha o dedo; soltando acima de 90px abre o player, senão volta
const sw = { y: 0, on: false, base: 64, dragged: false };
$("mini").addEventListener("pointerdown", e => {
  if (e.target.closest(".ctrl")) return;
  Object.assign(sw, { y: e.clientY, on: true, base: $("mini").offsetHeight, dragged: false });
});
window.addEventListener("pointermove", e => {
  if (!sw.on) return;
  const dy = Math.max(0, sw.y - e.clientY);
  if (dy > 6) { sw.dragged = true; $("mini").classList.add("dragging"); }
  if (sw.dragged) window.__playerShell ? window.__playerShell.peek(dy) : $("mini").style.height = sw.base + dy * .9 + "px";
});
["pointerup", "pointercancel"].forEach(t => window.addEventListener(t, e => {
  if (!sw.on) return;
  sw.on = false;
  $("mini").classList.remove("dragging");
  if (sw.dragged && sw.y - e.clientY > 90 && t === "pointerup") openPlayer();
  else if (sw.dragged && window.__playerShell) window.__playerShell.close();
  setTimeout(() => sw.dragged = false, 0);
}));

// O "+" abre a janela compartilhada de adicionar à playlist.
const add = SounosAdd.attach($("phone"), { onChange: list => { playlists = list; renderPlaylists(); } });
window.__sounosAdd = add;   // o menu "⋮" das linhas abre a mesma janela do "+"
let currentForAdd = () => order[cur];
$("miniAdd").onclick = () => {
  if (sw.dragged) return;
  const t = currentForAdd();
  if (t != null) add.open(t);
};

// ---- Mini-player: mesmas regras do player completo ----
const fmt = x => `${Math.floor(x / 60)}:${String(Math.floor(x % 60)).padStart(2, "0")}`;
const root = document.documentElement.style;
let order = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9], cur = 0, pos = 40, playing = false, shuffle = false, repeat = "off", scrubbing = false, last = 0;
const durOf = i => 160 + (i * 37) % 110;
// ---- Movimento: pequenas animações de troca (ícone, faixa) ----
const EASE = "cubic-bezier(.2,.85,.25,1)";
const reduceMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;
function swapIn(el, from, ms = 320) {
  if (reduceMotion || !el.animate) return;
  el.animate([from, { opacity: 1, transform: "none" }], { duration: ms, easing: EASE });
}
let miniFirst = true;
function loadMini() {
  const t = tracks[order[cur]];
  t.c.forEach((c, k) => root.setProperty(`--c${k + 1}`, c));
  $("miniIni").textContent = t.title[0];
  $("miniT").textContent = t.title;
  $("miniA").textContent = toHandle(t.artist);
  if (!miniFirst) {
    swapIn($("miniCv"), { opacity: .3, transform: "scale(.85)" });
    swapIn($("miniT"), { opacity: 0, transform: "translateX(12px)" });
    swapIn($("miniA"), { opacity: 0, transform: "translateX(12px)" }, 360);
  }
  miniFirst = false;
  lastBarPixel = -1;
  drawMiniProgress();
}
const LU = {
  play:    '<svg class="lu solid" viewBox="0 0 24 24" style="margin-left:2px"><polygon points="6 3 20 12 6 21 6 3"/></svg>',
  pause:   '<svg class="lu solid" viewBox="0 0 24 24"><rect x="14" y="4" width="4" height="16" rx="1"/><rect x="6" y="4" width="4" height="16" rx="1"/></svg>',
  shuffle: '<svg class="lu" viewBox="0 0 24 24"><path d="M2 18h1.4c1.3 0 2.5-.6 3.3-1.7l6.1-8.6c.7-1.1 2-1.7 3.3-1.7H22"/><path d="m18 2 4 4-4 4"/><path d="M2 6h1.9c1.5 0 2.9.9 3.6 2.2"/><path d="M22 18h-5.9c-1.3 0-2.6-.7-3.3-1.8l-.5-.8"/><path d="m18 14 4 4-4 4"/></svg>',
  repeat:  '<svg class="lu" viewBox="0 0 24 24"><path d="m17 2 4 4-4 4"/><path d="M3 11v-1a4 4 0 0 1 4-4h14"/><path d="m7 22-4-4 4-4"/><path d="M21 13v1a4 4 0 0 1-4 4H3"/></svg>',
  repeat1: '<svg class="lu" viewBox="0 0 24 24"><path d="m17 2 4 4-4 4"/><path d="M3 11v-1a4 4 0 0 1 4-4h14"/><path d="m7 22-4-4 4-4"/><path d="M21 13v1a4 4 0 0 1-4 4H3"/><path d="M11 10h1v4"/></svg>',
};
// Ícone mostra o estado: tocando = pausa; pausado = play, ou o modo ativo (aleatório / repetir / os dois)
let miniFlashTimer = 0;
function renderMiniIcon(showPlaying = playing, loading = false) {
  const rep = repeat === "one" ? LU.repeat1 : LU.repeat, el = $("miniIcon");
  // sempre play/pausa; ao trocar um modo, o ícone dele aparece por um instante (apagado = desligou)
  const modes = shuffle + "|" + repeat;
  if (renderMiniIcon.modes != null && modes !== renderMiniIcon.modes) {
    const sh = String(shuffle) !== renderMiniIcon.modes.split("|")[0];
    renderMiniIcon.flash = { html: `<span style="display:flex;opacity:${(sh ? !shuffle : repeat === "off") ? .35 : 1}">${sh ? LU.shuffle : rep}</span>`, until: performance.now() + 850 };
  }
  renderMiniIcon.modes = modes;
  const fl = renderMiniIcon.flash;
  if (fl) {
    clearTimeout(miniFlashTimer);
    if (performance.now() < fl.until && homeCanAnimate()) miniFlashTimer = setTimeout(() => renderMiniIcon(showPlaying, loading), fl.until - performance.now() + 1);
    else renderMiniIcon.flash = null;
  }
  let html = showPlaying ? LU.pause : LU.play, combo = false;
  if (!loading && fl && performance.now() < fl.until) html = fl.html;
  if (el.dataset.src !== html) {
    const first = !el.dataset.src; el.innerHTML = html; el.dataset.src = html;
    if (!first) swapIn(el, { opacity: 0, transform: "scale(.5) rotate(-12deg)" }, 260);
  }
  el.classList.toggle("combo", combo);
}
function step(d) {
  if (d < 0 && pos > 3) { pos = 0; return; }
  cur = (cur + d + order.length) % order.length; pos = 0; loadMini();
}
$("miniPrev").onclick = () => step(-1);
$("miniNext").onclick = () => step(1);   // (step é trocado no modo app)

// Gestos do botão (mesma lógica do player)
const g = { x: 0, y: 0, holding: false, moved: false, startedScrub: false, dir: null, shuffled: false, holdT: 0, idleT: 0 };
const pp = $("miniPP");
function setScrub(v) { scrubbing = v; $("mini").classList.toggle("scrub", v); window.__homeClockChanged?.(); }
function resetIdle() { clearTimeout(g.idleT); g.idleT = setTimeout(() => setScrub(false), 10000); }
function scrubBy(dx) { const d = durOf(order[cur]); pos = Math.max(0, Math.min(d, pos + dx * 0.002 * d)); }
function doShuffle() { shuffle = !shuffle; }
function doRepeat() { repeat = { off: "all", all: "one", one: "off" }[repeat]; }
function doToggle() { playing = !playing; $("phone").classList.toggle("paused", !playing); renderMiniIcon(); window.__homeClockChanged?.(); }
pp.addEventListener("pointerdown", e => {
  try { pp.setPointerCapture(e.pointerId); } catch {}
  Object.assign(g, { x: e.clientX, y: e.clientY, holding: false, moved: false, startedScrub: false, dir: null, shuffled: false });
  if (scrubbing) clearTimeout(g.idleT);
  g.holdT = setTimeout(() => g.holding = true, 200);
});
pp.addEventListener("pointermove", e => {
  if (!pp.hasPointerCapture(e.pointerId)) return;
  const dx = e.clientX - g.x, dy = e.clientY - g.y;
  if (!g.dir && Math.hypot(dx, dy) > 10) { g.moved = true; g.dir = Math.abs(dx) > Math.abs(dy) ? "h" : "v"; }
  if (g.dir === "v" && !g.shuffled && Math.abs(dy) > 32) { doShuffle(); g.shuffled = true; clearTimeout(g.holdT); return; }
  if (g.holding) {
    if ((g.dir === "h" || !g.dir) && Math.abs(dx) > 5) { if (!scrubbing) { setScrub(true); g.startedScrub = true; } scrubBy(dx); g.x = e.clientX; resetIdle(); }
  } else if (scrubbing) { scrubBy(dx); g.x = e.clientX; resetIdle(); }
});
pp.addEventListener("pointerup", () => {
  clearTimeout(g.holdT);
  if (scrubbing) { if (!g.startedScrub && !g.moved) setScrub(false); else resetIdle(); }
  else if (g.moved) { /* deslizar: gesto, nunca pausa */ }
  else if (g.holding) doRepeat();
  else doToggle();
  g.holding = false;
});
pp.addEventListener("pointercancel", () => { clearTimeout(g.holdT); g.holding = false; });

let curDur = () => durOf(order[cur]);          // no app: duração real da música
let atEnd = () => { if (repeat === "one") pos = 0; else step(1); };   // no app: quem decide é o player nativo
// A barra do mini-player só acompanha o ouvido enquanto a tela inicial está visível.
let lastBarPixel = -1, lastTimeTxt = "", miniTimer = 0, miniRaf = 0;
const miniBar = $("miniBar"), miniTime = $("miniTime"), HOME_MS = 100;
let homePageActive = !document.hidden;
function homeCanAnimate() {
  return homePageActive && !document.hidden && !window.__playerOpen && !$("phone").classList.contains("overlay-open");
}
// Duração ainda desconhecida (0): não dá para calcular a fração; a barra fica em 0 em vez de ir a 100%.
let durKnown = () => true;
// Troca de música: a barra volta a 0 de uma vez (sem a transição de crescimento) e depois cresce suave.
function resetMiniBar() {
  miniBar.style.transition = "none";
  miniBar.style.transform = "scaleX(0)";
  lastBarPixel = -1;
  void miniBar.offsetWidth;                 // fixa o 0 antes de religar a transição
  miniBar.style.transition = "";
}
function drawMiniProgress() {
  const d = curDur(), f = durKnown() && Number.isFinite(pos) ? Math.min(1, Math.max(0, pos / d)) : 0;
  const px = Math.floor(f * miniBar.clientWidth * (devicePixelRatio || 1) / 2);   // passo de 2 pixels do aparelho (imperceptível): metade dos quadros
  if (px !== lastBarPixel) { lastBarPixel = px; miniBar.style.transform = `scaleX(${f})`; }
  if (scrubbing) {
    const tt = `${fmt(pos)} / ${fmt(d)}`;
    if (tt !== lastTimeTxt) { lastTimeTxt = tt; miniTime.textContent = tt; }
  }
}
function stopHomeClock() {
  clearTimeout(miniTimer); miniTimer = 0;
  cancelAnimationFrame(miniRaf); miniRaf = 0;
}
function homeClockTick(now) {
  miniTimer = 0; miniRaf = 0;
  if (!homeCanAnimate()) return;
  const d = curDur();
  if (playing && last) {
    pos += (now - last) / 1000;
    if (pos >= d) atEnd();
  }
  last = now;
  drawMiniProgress();
  if (scrubbing) miniRaf = requestAnimationFrame(homeClockTick);
  else if (playing) miniTimer = setTimeout(() => homeClockTick(performance.now()), HOME_MS);
}
function homeClockChanged() {
  stopHomeClock();
  if (!homeCanAnimate() || (!playing && !scrubbing)) return;
  if (!last) last = performance.now();
  if (scrubbing) miniRaf = requestAnimationFrame(homeClockTick);
  else miniTimer = setTimeout(() => homeClockTick(performance.now()), HOME_MS);
}
window.__homeClockChanged = homeClockChanged;
window.addEventListener("sounos:visibility", e => {
  homePageActive = !!e.detail?.active;
  last = performance.now();
  homeClockChanged();
});
const homeOverlaySelector = ".search-ov, .set-ov, .add-ov, .upd, .sheet";
function syncHomeOverlay() {
  const open = !!$("phone").querySelector(".search-ov.open, .set-ov.open, .add-ov.open, .upd.open, .sheet.open");
  if ($("phone").classList.contains("overlay-open") !== open) {
    $("phone").classList.toggle("overlay-open", open);
    homeClockChanged();
  }
}
new MutationObserver(changes => {
  if (changes.some(m => m.type === "attributes" ? m.target.matches?.(homeOverlaySelector) :
      [...m.addedNodes, ...m.removedNodes].some(n => n.nodeType === 1 && (n.matches?.(homeOverlaySelector) || n.querySelector?.(homeOverlaySelector))))) syncHomeOverlay();
}).observe($("phone"), { subtree: true, childList: true, attributes: true, attributeFilter: ["class"] });
last = performance.now();
drawMiniProgress();
renderMiniIcon();
homeClockChanged();
loadMini();
