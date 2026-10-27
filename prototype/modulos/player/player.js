// Módulo Player — capa, anel de progresso, letra, fila, botões e gestos. Ver MODULO.md
// marca se a página entrou com a transição animada (para conferir no protótipo)
addEventListener("pagereveal", e => {
  window.__enteredWithTransition = !!e.viewTransition;
  e.viewTransition?.types.add("open");                 // o player está entrando: sobe
});
// ---- Dados de exemplo (faixas e letras fictícias) ----
// c = cores predominantes da capa (no app virão da extração de cor da imagem real)
const tracks = [
  { title: "Luz da Cidade", artist: "Banda Exemplo", dur: 214, c: ["#7c3aed", "#ec4899", "#f59e0b"],
    lrc: [[0,"♪"],[8,"Acendo a rua com a luz do meu olhar"],[16,"Cada esquina guarda um verso pra contar"],[25,"O vento leva o que eu não sei dizer"],[33,"E a cidade inteira dança pra você"],[42,"♪"],[50,"Se a noite é longa, eu fico até o fim"],[58,"Toda canção começa e termina em mim"],[67,"Luz da cidade, me leva daqui"],[75,"Luz da cidade, eu sempre volto aqui"]] },
  { title: "Maré Alta", artist: "Coletivo Demo", dur: 187, c: ["#06b6d4", "#22c55e", "#a3e635"],
    lrc: [[0,"♪"],[6,"A maré subiu e eu nem vi"],[13,"Fiquei olhando o céu de abril"],[21,"Tudo que eu quero é ficar aqui"],[29,"Na beira do mar, longe do frio"]] },
];
// Catálogo fictício extra (para playlist, lista aleatória e sugestões)
[["Faixa Sem Letra","Artista Teste"],["Neon Azul","Os Placeholders"],["Poeira de Estrada","Duo Fictício"],
 ["Noite Lenta","Lo-Fi Amostra"],["Verde Musgo","Grupo Modelo"],["Último Trem","Banda Exemplo"],
 ["Sal e Areia","Coletivo Demo"],["Céu de Vidro","Trio Protótipo"],["Fim de Tarde","Lo-Fi Amostra"],
 ["Chuva Fina","Duo Fictício"],["Rádio Velho","Os Placeholders"],["Asfalto Quente","MC Mockup"],
 ["Lua Cheia","Grupo Modelo"],["Vento Norte","Trio Protótipo"],["Café Frio","Lo-Fi Amostra"],
 ["Ponte Aérea","MC Mockup"],["Girassol","Coletivo Demo"],["Garoa","Banda Exemplo"],
 ["Mar Aberto","Duo Fictício"],["Eco","Os Placeholders"],["Outono","Artista Teste"],["Faísca","MC Mockup"]]
 .forEach(([title, artist], i) => {
   const h = (i * 47 + 20) % 360;
   tracks.push({ title, artist, dur: 160 + (i * 37) % 110, lrc: null,
     c: [`hsl(${h} 70% 42%)`, `hsl(${(h + 40) % 360} 75% 52%)`, `hsl(${(h + 80) % 360} 85% 60%)`] });
 });
const PLAYLIST = { name: "Minha playlist", tracks: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9] };

// Nome do artista → @ estilo Instagram: minúsculo, sem acento, espaço vira "_"
function toHandle(name) {
  return String(name ?? "").trim();  // nome do artista como vem (sem @)
}

// ---- Estado ----
// context: "playlist" = a pessoa deu play numa playlist
//          "radio"    = pesquisou/tocou uma música solta → lista aleatória
// base = a lista principal (playlist ou aleatória) e a posição nela
// sugg = sugestões, mostradas no lugar da lista enquanto "repetir faixa" estiver ligado
let context = "playlist";
let base = { order: [...PLAYLIST.tracks], cur: 0 };
let sugg = [];
let nowT = base.order[0];                 // faixa tocando agora (índice em tracks)
let pos = 0, playing = false, shuffle = false;
let repeat = "off"; // off → all → one
let lastTick = 0, playerVisible = window.parent === window, playerPageActive = !document.hidden;

const $ = id => document.getElementById(id);
$("phone").classList.toggle("player-hidden", !playerVisible);
document.documentElement.classList.toggle("page-hidden", !playerPageActive || document.hidden);
const fmt = s => `${Math.floor(s/60)}:${String(Math.floor(s%60)).padStart(2,"0")}`;
const track = () => tracks[nowT];
// Repetir muda somente o comportamento ao fim da faixa; o carrossel sempre mostra a fila atual.
const queueMode = () => "base";
const shownList = () => queueMode() === "sugg" ? sugg : base.order;
function shuffled(arr) {
  const a = [...arr];
  for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; }
  return a;
}
// mocks do motor de recomendação (no app: seção 5 do design)
const randomTracks = (n, exclude) => shuffled(tracks.map((_, i) => i).filter(i => !exclude.includes(i))).slice(0, n);
const suggestionsFor = ti => randomTracks(10, [ti]);

// ---- Anel de progresso ----
const ringPath = $("ringPath");
const SW = 6, RADIUS = 10;             // espessura da barra e raio da borda da capa
const FADE_PX = 34;                    // comprimento do esfumaçado nas pontas
let RING_LEN = 0, ringPts = [], RING = null;
// Monta o contorno em pixels, encostado do lado de fora da borda da capa
function layoutRing() {
  const c = $("cover"), w = c.offsetWidth, h = c.offsetHeight, o = SW / 2, r = RADIUS + o, pad = 20;
  const svg = $("ringSvg");
  Object.assign(svg.style, { left: -(o + pad) + "px", top: -(o + pad) + "px",
    width: w + 2 * (o + pad) + "px", height: h + 2 * (o + pad) + "px" });
  const vb = [-o - pad, -o - pad, w + 2 * (o + pad), h + 2 * (o + pad)];
  svg.setAttribute("viewBox", vb.join(" "));
  const L = -o, T = -o, R = w + o, B = h + o;
  const d = `M${w/2} ${T} H${R - r} A${r} ${r} 0 0 1 ${R} ${T + r} V${B - r} A${r} ${r} 0 0 1 ${R - r} ${B}`
          + ` H${L + r} A${r} ${r} 0 0 1 ${L} ${B - r} V${T + r} A${r} ${r} 0 0 1 ${L + r} ${T} Z`;
  ["ringPath", "ringFill", "ringHit"].forEach(id => $(id).setAttribute("d", d));
  // barra desenhada num canvas (placa de vídeo): mesma forma do contorno, sem refazer máscara SVG
  const cv = $("ringCanvas"), dpr = Math.min(3, (devicePixelRatio || 1) * (window.SounosEscala || 1));   // nitidez também com a tela ampliada
  Object.assign(cv.style, { left: svg.style.left, top: svg.style.top, width: svg.style.width, height: svg.style.height });
  cv.width = Math.round(vb[2] * dpr); cv.height = Math.round(vb[3] * dpr);
  RING = { path: new Path2D(d), vb, dpr };
  lastRingPixel = -1;
  ["x", "y", "width", "height"].forEach((k, i) => { $("maskBg").setAttribute(k, vb[i]); $("ringMask").setAttribute(k, vb[i]); });
  $("fadeStart").setAttribute("cx", w / 2); $("fadeStart").setAttribute("cy", T);
  RING_LEN = ringPath.getTotalLength();
  ringPts = null;
}
// Barra em volta da capa: UMA cor, a mais chamativa da capa (--c4, escolhida pelo app), com as pontas
// esfumaçadas. Desenhada num canvas: muda só quando a ponta avança um pixel, sem gradiente girando.
const FADE_STOPS = [[0, 1], [.25, .898], [.5, .533], [.75, .169], [1, 0]];   // mesmo esfumaçado de antes
let ringColor = null, lastRingPixel = -1;
new MutationObserver(() => {
  ringColor = null; lastRingPixel = -1;
  if (playerCanAnimate()) drawRingFill((track().dur > 1 ? Math.min(pos / track().dur, 1) : 0) * 1000);
}).observe(document.documentElement, { attributes: true, attributeFilter: ["style", "data-theme"] });
function drawRingFill(P) {
  if (!RING) return;
  P = Math.max(0, Math.min(1000, P));
  const { path, vb, dpr } = RING;
  const px = Math.floor(P / 1000 * RING_LEN * dpr);
  if (px === lastRingPixel) return;
  lastRingPixel = px;
  const cv = $("ringCanvas"), g = cv.getContext("2d");
  g.setTransform(1, 0, 0, 1, 0, 0); g.clearRect(0, 0, cv.width, cv.height);
  if (P <= 0) return;
  if (!ringColor) { const cs = getComputedStyle(document.documentElement); ringColor = (cs.getPropertyValue("--c4") || cs.getPropertyValue("--c3")).trim() || "#fff"; }
  const L = P / 1000 * RING_LEN;
  g.setTransform(dpr, 0, 0, dpr, -vb[0] * dpr, -vb[1] * dpr);
  g.lineWidth = SW; g.lineCap = "butt"; g.strokeStyle = ringColor;
  g.setLineDash([L, RING_LEN * 2]); g.stroke(path);
  const r = Math.max(0, Math.min(FADE_PX, L / 2));                      // barra curta → esfumaçado menor
  if (r < .5) return;
  const head = ringPath.getPointAtLength(L), start = ringPath.getPointAtLength(0);
  g.setLineDash([]); g.globalCompositeOperation = "destination-out";
  for (const p of [start, head]) {
    const gr = g.createRadialGradient(p.x, p.y, 0, p.x, p.y, r);
    FADE_STOPS.forEach(([o, a]) => gr.addColorStop(o, `rgba(0,0,0,${a})`));
    g.fillStyle = gr; g.beginPath(); g.arc(p.x, p.y, r, 0, Math.PI * 2); g.fill();
  }
  g.globalCompositeOperation = "source-over";
}

// ---- Movimento: pequenas animações de troca (ícone, faixa) ----
const EASE = "cubic-bezier(.2,.85,.25,1)";
const reduceMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;
function swapIn(el, from, ms = 320) {
  if (reduceMotion || !el.animate) return;
  el.animate([from, { opacity: 1, transform: "none" }], { duration: ms, easing: EASE });
}

// ---- Faixa atual ----
let firstLoad = true;
function loadCurrent() {
  pos = 0;
  const t = track(), root = document.documentElement.style;
  t.c.forEach((col, k) => root.setProperty(`--c${k+1}`, col));
  $("cover").textContent = t.title[0];
  $("title").textContent = t.title;
  $("artist").textContent = toHandle(t.artist);
  // troca de música: capa "assenta" e nome/@ entram deslizando (na 1ª carga quem anima é a transição entre telas)
  if (!firstLoad) {
    swapIn($("cover"), { opacity: .35, transform: "scale(.94)" }, 420);
    swapIn($("title"), { opacity: 0, transform: "translateX(14px)" });
    swapIn($("artist"), { opacity: 0, transform: "translateX(14px)" }, 380);
  }
  firstLoad = false;
  $("dur").textContent = fmt(t.dur);
  buildLyrics(); buildWave(); markQueue(); centerQueue(); render();
  if (typeof renderDl === "function") renderDl();
}

// O anel só redesenha quando a ponta avança um pixel; outras partes do player usam o mesmo compasso visual.
let lastEqDraw = -1e9, lastCurText = "";
function render(fromTick) {
  if (!playerCanAnimate()) return;
  const t = track(), f = t.dur > 1 ? Math.min(Math.max(pos / t.dur, 0), 1) : 0, now = performance.now();
  // um só compasso visual (~10×/s): barrinhas, anel e tempo mudam juntos → a tela redesenha poucas vezes
  if (fromTick && now - lastEqDraw < 50) { syncLyrics(); return; }
  lastEqDraw = now;
  if (!inLyrics()) drawEq(now);               // no modo letra a fila some: ninguém vê as barrinhas
  drawRingFill(f * 1000);
  const ct = fmt(pos); if (ct !== lastCurText) { lastCurText = ct; $("cur").textContent = ct; }
  renderPlayIcon();
  if (scrubbing) { moveWave(); $("scrubTime").textContent = fmt(pos); }
  if ($("phone").classList.contains("paused") === playing) $("phone").classList.toggle("paused", !playing);
  syncLyrics();
}

function setPlaying(v) { playing = v; lastTick = performance.now(); render(); syncPlayerClock(); }

// Toca a posição i da lista principal (sem reconstruir a fila: só move o "cursor")
function playBase(i) { base.cur = i; nowT = base.order[i]; loadCurrent(); }

// Toca uma sugestão: ela entra na lista principal logo depois da atual
function playSuggestion(ti) {
  const existing = base.order.indexOf(ti);
  if (existing !== -1) { base.order.splice(existing, 1); if (existing < base.cur) base.cur--; }
  base.order.splice(base.cur + 1, 0, ti);
  base.cur++; nowT = ti;
  sugg = suggestionsFor(ti);
  refreshQueue(true);
  loadCurrent();
}

function next(auto = false) {
  if (repeat === "one") {
    if (auto) { pos = 0; sugg = suggestionsFor(nowT); refreshQueue(true); return render(); }  // repete e renova as sugeridas
    return playSuggestion(sugg[0]);
  }
  if (base.cur + 1 < base.order.length) return playBase(base.cur + 1);
  if (context === "radio") {                 // lista aleatória nunca acaba: renova e continua
    base = { order: [nowT, ...randomTracks(12, [nowT])], cur: 0 };
    refreshQueue(true);
    return playBase(1);
  }
  if (repeat === "all" || !auto) return playBase(0);
  setPlaying(false); pos = 0; render();
}

function prev() {
  if (pos > 3 || repeat === "one") { pos = 0; return render(); }
  playBase(base.cur > 0 ? base.cur - 1 : (repeat === "all" ? base.order.length - 1 : 0));
}

// ---- Fila (carrossel) ----
const queue = $("queue");
function buildQueue() {
  queue.innerHTML = shownList().map((ti, i) => {
    const t = tracks[ti];
    return `<div class="qi" data-t="${ti}" style="--i:${i};background:linear-gradient(135deg,${t.c[0]},${t.c[1]} 60%,${t.c[2]})">
      <div class="ini">${t.title[0]}</div>
      <span class="eq"><i></i><i></i><i></i></span>
      <div class="meta"><div class="qt">${t.title}</div><div class="qa">${toHandle(t.artist)}</div></div></div>`;
  }).join("");
  markQueue();
}
function markQueue() {
  const isBase = queueMode() === "base";
  [...queue.children].forEach((el, i) => {
    el.classList.toggle("now", isBase ? i === base.cur : +el.dataset.t === nowT);
    el.classList.toggle("played", isBase && i < base.cur);
  });
}
function centerQueue(smooth = true) {
  if (qg.mode) return;
  if (queueMode() === "sugg") return queue.scrollTo({ left: 0, behavior: smooth ? "smooth" : "auto" });
  const el = queue.children[base.cur]; if (!el) return;
  queue.scrollTo({ left: el.offsetLeft - queue.clientWidth / 2 + el.offsetWidth / 2, behavior: smooth ? "smooth" : "auto" });
}
// Reconstrói a fila; com animação = "giro" rápido: os blocos saem pela esquerda e os novos entram pela direita
let spinT = 0;
function refreshQueue(animated) {
  clearTimeout(spinT);
  if (!animated) { buildQueue(); return centerQueue(false); }
  queue.classList.remove("spin-in");
  queue.classList.add("spin-out");
  spinT = setTimeout(() => {
    queue.classList.remove("spin-out");
    buildQueue(); centerQueue(false);
    queue.classList.add("spin-in");
    spinT = setTimeout(() => queue.classList.remove("spin-in"), 900);
  }, 180);
}

// Gestos da fila: toque, rolagem com inércia e reordenar segurando o bloco
const qg = { mode: null, x: 0, y: 0, sl: 0, item: null, holdT: 0, vx: 0, lastX: 0, lastT: 0, grab: 0, raf: 0 };
const contentX = clientX => clientX - queue.getBoundingClientRect().left + queue.scrollLeft;

queue.addEventListener("pointerdown", e => {
  cancelAnimationFrame(qg.raf);
  try { queue.setPointerCapture(e.pointerId); } catch {}
  const item = e.target.closest(".qi");
  Object.assign(qg, { mode: "pending", x: e.clientX, y: e.clientY, sl: queue.scrollLeft, item,
                      vx: 0, lastX: e.clientX, lastT: performance.now() });
  if (item) qg.holdT = setTimeout(() => startDrag(e.clientX), 350);
});
queue.addEventListener("pointermove", e => {
  if (!qg.mode) return;
  const dx = e.clientX - qg.x;
  if (qg.mode === "pending" && Math.hypot(dx, e.clientY - qg.y) > 8) { clearTimeout(qg.holdT); qg.mode = "scroll"; }
  if (qg.mode === "scroll") {
    queue.scrollLeft = qg.sl - dx;
    const now = performance.now(), dt = now - qg.lastT;
    if (dt > 0) qg.vx = .8 * ((e.clientX - qg.lastX) / dt) + .2 * qg.vx;
    qg.lastX = e.clientX; qg.lastT = now;
  } else if (qg.mode === "drag") {
    qg.lastX = e.clientX; moveDrag();
  }
});
function endQueueGesture(tap) {
  clearTimeout(qg.holdT);
  const mode = qg.mode; qg.mode = null;
  if (mode === "pending" && tap && qg.item) {
    const i = [...queue.children].indexOf(qg.item), ti = +qg.item.dataset.t;
    if (queueMode() === "sugg") { if (ti !== nowT) playSuggestion(ti); }
    else if (i !== base.cur) playBase(i);
    setPlaying(true);
  } else if (mode === "scroll") {
    momentum(qg.vx);
  } else if (mode === "drag") {
    endDrag();
  }
}
queue.addEventListener("pointerup", () => endQueueGesture(true));
queue.addEventListener("pointercancel", () => endQueueGesture(false));

function momentum(v) {
  let last = performance.now();
  const step = now => {
    const dt = now - last; last = now;
    queue.scrollLeft -= v * dt;
    v *= Math.pow(.94, dt / 16);
    if (Math.abs(v) > .02) qg.raf = requestAnimationFrame(step);
  };
  qg.raf = requestAnimationFrame(step);
}

function startDrag(clientX) {
  if (qg.mode !== "pending") return;
  qg.mode = "drag";
  const el = qg.item;
  el.classList.add("lift");
  qg.grab = contentX(clientX) - (el.offsetLeft + el.offsetWidth / 2);
  qg.lastX = clientX;
  if (navigator.vibrate) navigator.vibrate(15);
  edgeScroll();
}
function moveDrag() {
  const el = qg.item, want = contentX(qg.lastX) - qg.grab;   // centro desejado do bloco arrastado
  const others = [...queue.children].filter(c => c !== el);
  let idx = others.findIndex(c => c.offsetLeft + c.offsetWidth / 2 > want);
  if (idx === -1) idx = others.length;
  const curIdx = [...queue.children].indexOf(el);
  if (idx !== curIdx) {
    // FLIP: guarda posições, move no DOM e anima os vizinhos até o novo lugar
    const before = new Map(others.map(c => [c, c.offsetLeft]));
    queue.insertBefore(el, others[idx] || null);
    others.forEach(c => {
      const d = before.get(c) - c.offsetLeft; if (!d) return;
      c.style.transition = "none"; c.style.transform = `translateX(${d}px)`;
      requestAnimationFrame(() => { c.style.transition = "transform .2s ease"; c.style.transform = ""; });
    });
  }
  el.style.transition = "none";
  el.style.transform = `translateX(${want - (el.offsetLeft + el.offsetWidth / 2)}px) scale(1.08)`;
}
// Segurando perto da borda, a fila rola sozinha
function edgeScroll() {
  if (qg.mode !== "drag") return;
  const r = queue.getBoundingClientRect(), edge = 48;
  let v = 0;
  if (qg.lastX < r.left + edge) v = -(r.left + edge - qg.lastX) / 4;
  else if (qg.lastX > r.right - edge) v = (qg.lastX - (r.right - edge)) / 4;
  if (v) { queue.scrollLeft += v; moveDrag(); }
  requestAnimationFrame(edgeScroll);
}
function endDrag() {
  const el = qg.item;
  el.classList.remove("lift");
  el.style.transition = "transform .2s ease"; el.style.transform = "";
  const newOrder = [...queue.children].map(c => +c.dataset.t);
  if (queueMode() === "sugg") sugg = newOrder;
  else { base.order = newOrder; base.cur = newOrder.indexOf(nowT); }   // a faixa atual continua tocando
  markQueue();
}

// Protótipo: simula a origem da reprodução
function setContext(c) {
  context = c;
  if (c === "radio") base = { order: [nowT, ...randomTracks(12, [nowT])], cur: 0 };
  else {
    const order = shuffle ? [nowT, ...shuffled(PLAYLIST.tracks.filter(i => i !== nowT))] : [...PLAYLIST.tracks];
    if (!order.includes(nowT)) order.unshift(nowT);
    base = { order, cur: order.indexOf(nowT) };
  }
  if (queueMode() === "base") refreshQueue(true);
}
// tecla C: alterna a simulação
document.addEventListener("keydown", e => {
  if (e.code === "KeyC") setContext(context === "playlist" ? "radio" : "playlist");
});

// ---- Letras ----
const lyricsOffset = () => SounosSettings.get().lyricsOffset;
addEventListener("sounos:settings", () => { lastLine = -1; syncLyrics(); });
let lastLine = -1, lyrMarked = -2, lyrMarkedFor = null;
function lyricIndex(lrc, at) {
  let lo = 0, hi = lrc.length;
  while (lo < hi) { const mid = (lo + hi) >>> 1; if (lrc[mid][0] <= at) lo = mid + 1; else hi = mid; }
  return lo - 1;
}
const lyricLines = $("lines");
lyricLines.addEventListener("scroll", () => {
  lyricLines.classList.toggle("scrolled", lyricLines.scrollTop > 1);
}, { passive: true });
function buildLyrics() {
  const box = $("lines"), t = track();
  box.innerHTML = ""; lastLine = -1; lyrMarked = -2; lyrMarkedFor = null;
  box.classList.remove("scrolled"); box.scrollTop = 0;
  exitSelect();
  $("shareBtn").classList.toggle("hidden", !t.lrc);
  if (t.lrc === undefined) { box.innerHTML = '<div class="empty">…</div>'; return; }   // letra ainda carregando
  if (!t.lrc) { box.innerHTML = '<div class="empty">Letra indisponível para esta faixa</div>'; return; }
  const marked = lyricIndex(t.lrc, pos + lyricsOffset());
  t.lrc.forEach((ln, i) => {
    const s = ln[0], txt = parseWordTags(t.lrc, i);
    const d = document.createElement("div");
    d.className = "line" + (isNote(txt) ? " note" : "") + (i === marked ? " now" : i < marked ? " past" : "");
    d.textContent = txt;
    d.onclick = () => selecting ? toggleLine(i) : (pos = s, render());
    box.appendChild(d);
  });
  lyrMarked = marked; lyrMarkedFor = box.firstElementChild;
}
// LRC "enhanced": remove do texto as marcas <mm:ss.xx> que indicam o início de cada palavra.
function parseWordTags(lrc, i) {
  const raw = lrc[i][1];
  if (!/<\d+:\d+(\.\d+)?>/.test(raw)) return raw.trim().split(/\s+/).join(" ");
  return raw.replace(/<\d+:\d+(?:\.\d+)?>/g, " ").trim().split(/\s+/).join(" ");
}
// barrinhas do bloco que está tocando: sobe e desce em 0,9 s (defasadas), param quando pausa
function drawEq(now) {
  const bars = document.querySelectorAll("#queue .qi.now .eq i"); if (!bars.length) return;
  bars.forEach((b, k) => {
    const ph = playing ? ((now / 900) + k / 3) % 1 : 0;
    const e = .5 - .5 * Math.cos(ph * 2 * Math.PI);              // vai e volta suave (como o ease-in-out de antes)
    const transform = `scaleY(${(.2857 + .7143 * e).toFixed(3)})`;
    if (b.style.transform !== transform) b.style.transform = transform;
  });
}
function syncLyrics() {
  const t = track(); if (!t.lrc) return;
  const at = pos + lyricsOffset();                                  // + ajuste de sincronia das configurações
  const ln = lyricIndex(t.lrc, at);
  const els = $("lines").children;
  // A montagem já deixa todas as linhas marcadas. Depois, só atualiza o trecho entre uma linha e outra.
  if (lyrMarkedFor !== els[0]) {
    for (let i = 0; i < els.length; i++) { els[i].classList.toggle("now", i === ln); els[i].classList.toggle("past", i < ln); }
    lyrMarkedFor = els[0]; lyrMarked = ln;
  } else if (ln !== lyrMarked) {
    const from = Math.max(0, Math.min(ln, lyrMarked)), to = Math.min(els.length - 1, Math.max(ln, lyrMarked));
    for (let i = from; i <= to; i++) { els[i].classList.toggle("now", i === ln); els[i].classList.toggle("past", i < ln); }
    lyrMarked = ln;
  }
  if (ln !== lastLine && ln >= 0 && lyricsMode && !selecting) {
    const box = $("lines");
    // Mantém o começo da letra no topo; as linhas seguintes sobem suavemente.
    const top = ln === 0 ? 0 : els[ln].offsetTop - box.clientHeight * .35;
    box.scrollTo({ top, behavior: "smooth" });
  }
  lastLine = ln;
}

// ---- Relógio visual: só existe enquanto o player está visível e tocando ----
function tick(now) {
  const dt = (now - lastTick) / 1000;
  if (playing) {
    pos += dt;
    if (pos >= track().dur) { pos = track().dur; next(true); }
    render(true);
  }
  lastTick = now;
}
// Relógio por timer (não rAF): rAF contínuo faz o WebView redesenhar a cada vsync (~120 quadros/s) sem necessidade.
// O visual só muda ~10×/s; parado, fechado ou coberto, não há timer para acordar a página.
const VIS_MS = 100, LYR_MS = 110;           // 10×/s (barrinhas + anel); modo letra: sem barrinhas, só anel/letra
function inLyrics() { return $("phone").classList.contains("lyr-mode"); }
let clockTimer = 0;
function playerCanAnimate() {
  return playerVisible && playerPageActive && !document.hidden && !$("phone").classList.contains("overlay-open");
}
function syncPlayerClock() {
  clearTimeout(clockTimer); clockTimer = 0;
  if (playing && playerCanAnimate() && !$("phone").classList.contains("loading")) clockTimer = setTimeout(clockLoop, inLyrics() ? LYR_MS : VIS_MS);
}
function clockLoop() {
  clockTimer = 0;
  if (!playing || !playerCanAnimate() || $("phone").classList.contains("loading")) return;
  tick(performance.now());
  syncPlayerClock();
}
function refreshNativePosition() {
  if (!window.Sounos?.native) return;
  const p = Sounos.call("getProgress");
  if (!Number.isFinite(p.pos)) return;
  pos = p.pos;
  if (p.dur > 0) track().dur = p.dur;
  playing = !!p.playing || (!!p.loading && !!p.playWhenReady);
  renderPlayIcon();
}
function setPlayerVisible(visible) {
  playerVisible = !!visible;
  $("phone").classList.toggle("player-hidden", !playerVisible);
  if (playerVisible) window.SounosTips?.offer("player");
  if (playerCanAnimate()) refreshNativePosition();
  lastTick = performance.now();
  if (playerCanAnimate()) render();
  syncPlayerClock();
  scheduleMockDownload();
}
window.__setPlayerVisible = setPlayerVisible;
window.addEventListener("sounos:visibility", e => {
  playerPageActive = !!e.detail?.active;
  document.documentElement.classList.toggle("page-hidden", !playerPageActive || document.hidden);
  if (playerCanAnimate()) refreshNativePosition();
  lastTick = performance.now();
  if (playerCanAnimate()) render();
  syncPlayerClock();
  scheduleMockDownload();
});
const playerOverlaySelector = ".add-ov, .sheet";
function syncPlayerOverlay() {
  const open = !!$("phone").querySelector(".add-ov.open, .sheet.open");
  if ($("phone").classList.contains("overlay-open") === open) return;
  $("phone").classList.toggle("overlay-open", open);
  if (playerCanAnimate()) render();
  syncPlayerClock();
  scheduleMockDownload();
}
new MutationObserver(changes => {
  if (changes.some(m => m.type === "attributes" ? m.target.matches?.(playerOverlaySelector) :
      [...m.addedNodes, ...m.removedNodes].some(n => n.nodeType === 1 && (n.matches?.(playerOverlaySelector) || n.querySelector?.(playerOverlaySelector))))) syncPlayerOverlay();
}).observe($("phone"), { subtree: true, childList: true, attributes: true, attributeFilter: ["class"] });

// ---- Eventos ----
$("next").onclick = () => next(false);
$("prev").onclick = prev;

// ---- Botão multifunção ----
// Ícones (mesmos traços do lucide)
const LU = {
  play:    '<svg class="lu solid" viewBox="0 0 24 24" style="margin-left:4px"><polygon points="6 3 20 12 6 21 6 3"/></svg>',
  pause:   '<svg class="lu solid" viewBox="0 0 24 24"><rect x="14" y="4" width="4" height="16" rx="1"/><rect x="6" y="4" width="4" height="16" rx="1"/></svg>',
  shuffle: '<svg class="lu" viewBox="0 0 24 24"><path d="M2 18h1.4c1.3 0 2.5-.6 3.3-1.7l6.1-8.6c.7-1.1 2-1.7 3.3-1.7H22"/><path d="m18 2 4 4-4 4"/><path d="M2 6h1.9c1.5 0 2.9.9 3.6 2.2"/><path d="M22 18h-5.9c-1.3 0-2.6-.7-3.3-1.8l-.5-.8"/><path d="m18 14 4 4-4 4"/></svg>',
  repeat:  '<svg class="lu" viewBox="0 0 24 24"><path d="m17 2 4 4-4 4"/><path d="M3 11v-1a4 4 0 0 1 4-4h14"/><path d="m7 22-4-4 4-4"/><path d="M21 13v1a4 4 0 0 1-4 4H3"/></svg>',
  repeat1: '<svg class="lu" viewBox="0 0 24 24"><path d="m17 2 4 4-4 4"/><path d="M3 11v-1a4 4 0 0 1 4-4h14"/><path d="m7 22-4-4 4-4"/><path d="M21 13v1a4 4 0 0 1-4 4H3"/><path d="M11 10h1v4"/></svg>',
};
let lastIconKey = "", lastModes = null, flash = null;
// O botão mostra sempre play/pausa. Ao trocar um modo (aleatório / repetir), o ícone do modo aparece
// por um instante (apagado = desligou) e volta para play/pausa — a música não para.
function renderPlayIcon() {
  const modes = shuffle + "|" + repeat;
  if (lastModes !== null && modes !== lastModes) {
    const [s0, r0] = lastModes.split("|");
    const html = String(shuffle) !== s0 ? LU.shuffle : (repeat === "one" ? LU.repeat1 : LU.repeat);
    const off = String(shuffle) !== s0 ? !shuffle : repeat === "off";
    flash = { key: "m" + modes, html: `<span style="display:flex;opacity:${off ? .35 : 1}">${html}</span>`, until: performance.now() + 850 };
  }
  lastModes = modes;
  let key, html, combo = false;
  if ($("phone").classList.contains("loading")) { flash = null; key = playing ? "pause" : "play"; html = playing ? LU.pause : LU.play; }
  else if (flash && performance.now() < flash.until) { key = flash.key; html = flash.html; }
  else if (playing) { flash = null; key = "pause"; html = LU.pause; }
  else              { flash = null; key = "play"; html = LU.play; }
  if (key === lastIconKey) return;
  lastIconKey = key;
  const el = $("playIcon"), first = !el.innerHTML; el.innerHTML = html; el.classList.toggle("combo", combo);
  if (!first) swapIn(el, { opacity: 0, transform: "scale(.5) rotate(-12deg)" }, 260);   // ícone troca com giro curto
}

// Forma de onda (mock: no app virá da amplitude real do áudio)
const BARS = 151, BAR_STEP = 12;   // 2px de barra + 10px de espaço
function buildWave() {
  const hs = Array.from({ length: BARS }, () => Math.random() * 25 + 5);
  $("waveDim").innerHTML = hs.map(h => `<i style="height:${h}px"></i>`).join("");
  $("waveBig").innerHTML = hs.map(h => `<i style="height:${h}px"></i>`).join("");
}
function moveWave() {
  const off = (pos / track().dur) * (BARS - 1) * BAR_STEP;
  $("waveDim").style.transform = `translateX(${-off}px)`;
  const big = $("waveBig");
  big.style.transformOrigin = `${off}px 50%`;
  big.style.transform = `translateX(${-off}px) scale(1.8)`;
}

let scrubbing = false;
function setScrubbing(v) {
  scrubbing = v;
  $("play").classList.toggle("scrub", v);
  $("controls").classList.toggle("scrubbing", v);
  $("scrubTime").classList.toggle("show", v);
  render();
}
// Repetir faixa liga → a fila vira "sugeridas"; desliga → volta a lista principal
function cycleRepeat() {
  const before = queueMode();
  repeat = { off: "all", all: "one", one: "off" }[repeat];
  if (queueMode() === "sugg" && before !== "sugg") sugg = suggestionsFor(nowT);
  if (queueMode() !== before) refreshQueue(true);
  render();
}
// Aleatório: toda vez que liga, reembaralha o que ainda vai tocar; desligar na playlist volta à ordem original
function toggleShuffle() {
  shuffle = !shuffle;
  if (shuffle) base.order = [...base.order.slice(0, base.cur + 1), ...shuffled(base.order.slice(base.cur + 1))];
  else if (context === "playlist") base.order = PLAYLIST.tracks.includes(nowT) ? [...PLAYLIST.tracks] : [nowT, ...PLAYLIST.tracks];
  base.cur = base.order.indexOf(nowT);
  if (queueMode() === "base") refreshQueue(true);
  render();
}
// arrastar 1px = 0,1% da faixa
function scrubBy(dx) { pos = Math.max(0, Math.min(track().dur, pos + dx * 0.001 * track().dur)); render(); }

const g = { x: 0, y: 0, holding: false, moved: false, startedScrub: false, dir: null, shuffled: false, holdT: 0, idleT: 0 };
function resetIdle() { clearTimeout(g.idleT); g.idleT = setTimeout(() => setScrubbing(false), 10000); }

const playBtn = $("play");
playBtn.addEventListener("pointerdown", e => {
  try { playBtn.setPointerCapture(e.pointerId); } catch {}
  Object.assign(g, { x: e.clientX, y: e.clientY, holding: false, moved: false, startedScrub: false, dir: null, shuffled: false });
  if (scrubbing) clearTimeout(g.idleT);
  g.holdT = setTimeout(() => g.holding = true, 200);
});
playBtn.addEventListener("pointermove", e => {
  if (!playBtn.hasPointerCapture(e.pointerId)) return;
  const dx = e.clientX - g.x, dy = e.clientY - g.y;
  if (!g.dir && Math.hypot(dx, dy) > 10) { g.moved = true; g.dir = Math.abs(dx) > Math.abs(dy) ? "h" : "v"; }
  // deslizar para cima/baixo = aleatório (segurando ou não)
  if (g.dir === "v" && !g.shuffled && Math.abs(dy) > 32) { toggleShuffle(); g.shuffled = true; clearTimeout(g.holdT); return; }
  if (g.holding) {
    if ((g.dir === "h" || !g.dir) && Math.abs(dx) > 5) {
      if (!scrubbing) { setScrubbing(true); g.startedScrub = true; }
      scrubBy(dx); g.x = e.clientX; resetIdle();
    }
  } else if (scrubbing) {
    scrubBy(dx); g.x = e.clientX; resetIdle();
  }
});
playBtn.addEventListener("pointerup", () => {
  clearTimeout(g.holdT);
  if (scrubbing) {
    if (!g.startedScrub && !g.moved) setScrubbing(false); else resetIdle();
  } else if (g.moved) {
    // foi gesto (deslizar), não toque: nunca pausa
  } else if (g.holding) {
    cycleRepeat();
  } else {
    setPlaying(!playing);
  }
  g.holding = false;
});
playBtn.addEventListener("pointercancel", () => { clearTimeout(g.holdT); g.holding = false; });

// ---- Baixar: um toque e pronto (sem janela, sem aviso). No app: baixa áudio + capa + metadados + letra
// para o armazenamento privado; a partir daí a música toca offline. Aqui o progresso é simulado. ----
const dl = { timer: 0, track: -1, p: 0 };
function scheduleMockDownload() {
  clearTimeout(dl.timer); dl.timer = 0;
  if (dl.track >= 0 && playerCanAnimate()) dl.timer = setTimeout(stepMockDownload, 180);
}
function stepMockDownload() {
  dl.timer = 0;
  if (dl.track < 0 || !playerCanAnimate()) return;
  dl.p = Math.min(100, dl.p + 6 + Math.random() * 10);
  if (dl.p >= 100) { SounosData.markDownloaded(dl.track); dl.track = -1; }
  renderDl();
  scheduleMockDownload();
}
// Seta e ✓ usam a mesma estrutura de três segmentos para permitir interpolar o traço.
const DL_ARROW_POINTS = [12, 7.5, 12, 15.7, 8.5, 12.2, 12, 15.7, 15.5, 12.2, 12, 15.7];
const DL_CHECK_POINTS = [7.3, 12.2, 10, 15.3, 10, 15.3, 12.2, 13.1, 12.2, 13.1, 17, 8.5];
function dlPath(points) {
  return `M${points[0]} ${points[1]}L${points[2]} ${points[3]}M${points[4]} ${points[5]}L${points[6]} ${points[7]}M${points[8]} ${points[9]}L${points[10]} ${points[11]}`;
}
function dlEase(p) {
  const curve = (t, a, b) => 3 * (1 - t) ** 2 * t * a + 3 * (1 - t) * t ** 2 * b + t ** 3;
  let lo = 0, hi = 1, t = p;
  for (let i = 0; i < 6; i++) { if (curve(t, .2, .25) < p) lo = t; else hi = t; t = (lo + hi) / 2; }
  return curve(t, .85, 1);
}
function syncDlMark(button, done, trackId) {
  const path = button.querySelector(".dl-arrow"); if (!path) return;
  const id = String(trackId ?? ""), goal = done ? "true" : "false";
  const trackChanged = path.dataset.track !== id;
  const target = done ? DL_CHECK_POINTS : DL_ARROW_POINTS;
  const setPath = points => path.setAttribute("d", dlPath(points));
  if (trackChanged || path.dataset.done == null) {
    if (path._dlRaf) cancelAnimationFrame(path._dlRaf);
    path._dlRaf = 0; path._dlPoints = target.slice(); path._dlTarget = goal;
    path.dataset.track = id; path.dataset.done = goal; setPath(target);
    button.classList.add("dl-snap");
    const token = button._dlSnapToken = (button._dlSnapToken || 0) + 1;
    requestAnimationFrame(() => requestAnimationFrame(() => {
      if (button._dlSnapToken === token) button.classList.remove("dl-snap");
    }));
    return;
  }
  if (path.dataset.done === goal && path._dlTarget === goal) return;
  const from = path._dlPoints || (path.dataset.done === "true" ? DL_CHECK_POINTS : DL_ARROW_POINTS);
  if (path._dlRaf) cancelAnimationFrame(path._dlRaf);
  path.dataset.done = goal; path._dlTarget = goal;
  if (matchMedia("(prefers-reduced-motion: reduce)").matches) {
    path._dlPoints = target.slice(); setPath(target); return;
  }
  const started = performance.now(), duration = 400;
  const frame = now => {
    const p = Math.min(1, (now - started) / duration), eased = dlEase(p);
    const points = from.map((value, i) => value + (target[i] - value) * eased);
    path._dlPoints = points; setPath(points);
    if (p < 1) path._dlRaf = requestAnimationFrame(frame);
    else { path._dlRaf = 0; path._dlPoints = target.slice(); }
  };
  path._dlRaf = requestAnimationFrame(frame);
}
function renderDl() {
  const b = $("dlBtn"), done = !!SounosData.getDownloads()[nowT], loading = dl.track === nowT && !done;
  b.classList.toggle("done", done);
  b.classList.toggle("loading", loading);
  b.querySelector(".dl-prog").style.strokeDasharray = `${loading ? dl.p : 0} 100`;
  syncDlMark(b, done, nowT);
  b.setAttribute("aria-label", done ? "Baixada" : loading ? "Baixando" : "Baixar música");
}
$("dlBtn").onclick = () => {
  if (SounosData.getDownloads()[nowT] || dl.track === nowT) return;
  clearTimeout(dl.timer);
  Object.assign(dl, { track: nowT, p: 0 });
  scheduleMockDownload();
  renderDl();
};

// "+": janela de adicionar à playlist / criar playlist
const add = SounosAdd.attach($("phone"));
$("addBtn").onclick = () => add.open(nowT);

// Botão "A": esconde nome/artista/fila/botões e mostra a letra no lugar
let lyricsMode = false;
// Tela cheia (ver CSS ".immersive")
let immersiveByLyrics = false;
function setImmersive(on) {
  $("phone").classList.toggle("immersive", on);
  requestAnimationFrame(fitCover);
}
const isImmersive = () => $("phone").classList.contains("immersive");
$("lyricsBtn").onclick = () => {
  lyricsMode = !lyricsMode;
  // abrir a letra também entra em tela cheia; fechar sai (se foi a letra que entrou)
  if (lyricsMode && !isImmersive()) { immersiveByLyrics = true; setImmersive(true); }
  else if (!lyricsMode && immersiveByLyrics) { immersiveByLyrics = false; setImmersive(false); }
  $("phone").classList.toggle("lyr-mode", lyricsMode);
  $("lyricsBtn").classList.toggle("on", lyricsMode);
  if (lyricsMode) {
    lastLine = -1;
    requestAnimationFrame(() => { if (lyricsMode) syncLyrics(); });
  } else exitSelect();
};
window.__sonusBack = () => {
  if (!lyricsMode) return false;
  $("lyricsBtn").click();
  return true;
};

// ---- Compartilhar letra: escolher até 10 linhas → imagem com faixa, @artista e trechos ----
const MAX_LINES = 10;
let selecting = false, selected = new Set();
const isNote = txt => !txt.replace(/[♪♫\s]/g, "");
function enterSelect() { selecting = true; selected.clear(); updateSel(); $("lyr").classList.add("selecting"); $("shareBtn").classList.add("on"); }
function exitSelect() { selecting = false; selected.clear(); $("lyr").classList.remove("selecting"); $("shareBtn").classList.remove("on"); updateSel(); }
function toggleLine(i) {
  if (selected.has(i)) selected.delete(i);
  else if (selected.size < MAX_LINES) selected.add(i);
  else { const c = $("selCount"); c.classList.remove("nudge"); void c.offsetWidth; c.classList.add("nudge"); return; }
  updateSel();
}
function updateSel() {
  const lines = $("lines");
  lines.querySelectorAll(".line.sel").forEach(el => el.classList.remove("sel"));
  selected.forEach(i => lines.children[i]?.classList.add("sel"));
  $("selCount").textContent = `${selected.size}/${MAX_LINES}`;
  $("selCount").classList.toggle("full", selected.size === MAX_LINES);
  $("selGo").disabled = selected.size === 0;
}
$("shareBtn").onclick = () => selecting ? exitSelect() : enterSelect();
$("selCancel").onclick = exitSelect;

// Desenha o bloco de imagem (1080×1350) no canvas, do zero
function wrapLines(ctx, text, maxW) {
  const words = text.split(" "), out = []; let line = "";
  words.forEach(w => {
    const test = line ? line + " " + w : w;
    if (ctx.measureText(test).width > maxW && line) { out.push(line); line = w; } else line = test;
  });
  if (line) out.push(line);
  return out;
}
// Estilos do bloco: fundo, cor do texto e da capinha
const CARD_STYLES = {
  grad:  t => ({ bg: t.c, shade: .38, text: "#fff", sub: "rgba(255,255,255,.75)", thumb: t.c, ini: "rgba(255,255,255,.85)" }),
  dark:  () => ({ bg: ["#3f3f45", "#323237", "#26262a"], shade: 0, text: "#fff", sub: "rgba(255,255,255,.7)",
                  thumb: ["#8a8a90", "#6a6a70", "#55555a"], ini: "rgba(255,255,255,.9)" }),
  light: () => ({ bg: ["#fafafa", "#f1f1f3", "#e7e7ea"], shade: 0, text: "#111113", sub: "rgba(17,17,19,.6)",
                  thumb: ["#d4d4d8", "#c4c4c9", "#b4b4ba"], ini: "rgba(255,255,255,.95)" }),
};
// Desenha o bloco no canvas. Largura fixa (1080); a ALTURA acompanha o tamanho dos trechos escolhidos.
function makeCard(style = "grad") {
  const t = track(), st = CARD_STYLES[style](t), W = 1080, P = 90, S = 132, MAX_H = 1920;
  const font = 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';
  const meas = document.createElement("canvas").getContext("2d");
  // monta as linhas (trechos separados ganham um respiro); só diminui a fonte se passar da altura máxima
  const idx = [...selected].sort((a, b) => a - b), maxW = W - 2 * P, top = P + S + 80;
  let size = 68, layout, textH;
  for (; size >= 34; size -= 2) {
    meas.font = `800 ${size}px ${font}`;
    const lh = size * 1.22;
    layout = []; let y = 0;
    idx.forEach((li, k) => {
      if (k && li !== idx[k - 1] + 1) { layout.push(["...", y, true]); y += lh; }   // trechos não sequenciais
      wrapLines(meas, t.lrc[li][1], maxW).forEach(l => { layout.push([l, y]); y += lh; });
    });
    textH = y;
    if (top + textH + P <= MAX_H) break;
  }
  const H = Math.round(top + textH + P);

  const cv = document.createElement("canvas"); cv.width = W; cv.height = H;
  const ctx = cv.getContext("2d");
  const grad = (x0, y0, x1, y1, c) => { const g = ctx.createLinearGradient(x0, y0, x1, y1);
    g.addColorStop(0, c[0]); g.addColorStop(.6, c[1]); g.addColorStop(1, c[2]); return g; };
  ctx.fillStyle = grad(0, 0, W, H, st.bg); ctx.fillRect(0, 0, W, H);
  if (st.shade) { ctx.fillStyle = `rgba(0,0,0,${st.shade})`; ctx.fillRect(0, 0, W, H); }
  // cabeçalho: capinha + nome da faixa + @artista
  ctx.beginPath(); ctx.roundRect(P, P, S, S, 14); ctx.fillStyle = grad(P, P, P + S, P + S, st.thumb); ctx.fill();
  ctx.fillStyle = st.ini; ctx.font = `800 64px ${font}`; ctx.textAlign = "center"; ctx.textBaseline = "middle";
  ctx.fillText(t.title[0], P + S / 2, P + S / 2 + 2);
  ctx.textAlign = "left"; ctx.textBaseline = "alphabetic";
  const tx = P + S + 32, tw = W - tx - P;
  ctx.fillStyle = st.text; ctx.font = `800 46px ${font}`;
  ctx.fillText(wrapLines(ctx, t.title, tw)[0], tx, P + 60);
  ctx.fillStyle = st.sub; ctx.font = `500 34px ${font}`;
  ctx.fillText(toHandle(t.artist), tx, P + 108);
  // trechos
  ctx.font = `800 ${size}px ${font}`;
  layout.forEach(([l, y, sep]) => { ctx.fillStyle = sep ? st.sub : st.text; ctx.fillText(l, P, top + y + size); });
  return cv;
}
let cardCanvas = null, cardStyle = "grad";
function renderCard() {
  cardCanvas = makeCard(cardStyle);
  $("cardImg").src = cardCanvas.toDataURL("image/png");
  [...$("cardStyles").children].forEach(b => b.classList.toggle("on", b.dataset.style === cardStyle));
}
$("cardStyles").onclick = e => {
  const b = e.target.closest("button"); if (!b) return;
  cardStyle = b.dataset.style; renderCard();
};
$("selGo").onclick = () => { renderCard(); $("sheet").classList.add("open"); };
const closeSheet = () => $("sheet").classList.remove("open");
$("sheetClose").onclick = closeSheet;
$("sheet").onclick = e => { if (e.target === $("sheet")) closeSheet(); };
const cardName = () => `${track().title} - letra.png`;
function saveCard() {
  const a = document.createElement("a");
  a.href = cardCanvas.toDataURL("image/png"); a.download = cardName(); a.click();
}
$("saveCard").onclick = saveCard;
// Compartilhar pelo sistema (Android abre a folha de apps); sem suporte → salva a imagem
$("sendCard").onclick = () => cardCanvas.toBlob(async blob => {
  const file = new File([blob], cardName(), { type: "image/png" });
  if (navigator.canShare && navigator.canShare({ files: [file] })) {
    try { await navigator.share({ files: [file], title: track().title }); } catch {}
  } else saveCard();
});

// O anel só mostra o andamento: avançar/voltar é pelo botão de play (segurar e arrastar)
const ring = $("ring");
$("ringHit").style.pointerEvents = "none";

document.addEventListener("keydown", e => {
  if (e.code === "Space") { e.preventDefault(); setPlaying(!playing); }
  if (e.code === "ArrowRight") next(false);
  if (e.code === "ArrowLeft") prev();
});

// A capa grande mantém o cálculo atual; a fila cresce 10% sobre o tamanho anterior.
const TILE_MIN = 116, TILE_MAX = 260;
function fitCover() {
  const ph = $("phone"), cs = getComputedStyle(ph), root = document.documentElement.style;
  const outer = el => { const st = getComputedStyle(el); return el.offsetHeight + parseFloat(st.marginTop) + parseFloat(st.marginBottom); };
  const innerH = ph.clientHeight - parseFloat(cs.paddingTop) - parseFloat(cs.paddingBottom);
  const maxW = ph.clientWidth - 28;            // deixa 14px de cada lado para a barra de progresso
  // capa e blocos têm SEMPRE o mesmo tamanho (em tela cheia também): só a posição muda
  const fixed = 8 + outer($("times"))
    + ["info", "controls"].reduce((a, id) => a + outer($(id)), 0)
    + 24;                                      // fila sem o bloco: margens (4 − 10) + respiro (14 + 16)
  const oldTop = 40;                           // espaço que o topo ocupava antes de ser removido
  // A capa do A54 (navegação por gestos: altura útil 763px) é a referência. Em tela mais baixa (botões de navegação,
  // barra de status alta, tela mais curta) a capa NÃO encolhe: cedem antes a fila (até TILE_MIN) e o respiro entre a
  // fila e o play (até RESPIRO_MIN). Só se nem assim couber a capa diminui.
  const H_A54 = 763, RESPIRO_MIN = 6;
  const coverA54 = Math.min(maxW, H_A54 - fixed - oldTop - TILE_MIN);
  const coverAntes = Math.min(maxW, innerH - fixed - oldTop - TILE_MIN);          // telas altas: regra de sempre
  const coverCabe = innerH - fixed - TILE_MIN - RESPIRO_MIN;
  const cover = Math.max(160, coverAntes, Math.min(coverA54, coverCabe));
  const oldTile = Math.max(TILE_MIN, Math.min(TILE_MAX, innerH - fixed - oldTop - cover));
  const tile = Math.max(TILE_MIN, Math.min(TILE_MAX, Math.round(oldTile * 1.1), innerH - fixed - cover - RESPIRO_MIN));
  root.setProperty("--cover-size", cover + "px");
  root.setProperty("--tile", tile + "px");
}
fitCover();
new ResizeObserver(fitCover).observe($("phone"));
layoutRing();
new ResizeObserver(() => { layoutRing(); render(); }).observe($("cover"));
// Vindo da tela inicial: ?t=faixa (música solta → lista aleatória) · ?p=playlist
const qp = new URLSearchParams(location.search);
if (!window.Sounos?.native) {
  if (qp.has("t")) { nowT = +qp.get("t"); setContext("radio"); }
  buildQueue();
  loadCurrent();
  if (qp.has("t") || qp.has("p")) setPlaying(true);
}
lastTick = performance.now(); syncPlayerClock();
