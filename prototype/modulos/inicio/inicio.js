// Módulo Início — tela inicial. Ver MODULO.md
// Três partes, sempre numa tela só (sem rolar): o bloco dinâmico (janelas que passam para o lado), Seus artistas
// (só quem a pessoa segue) e Suas playlists. "Ver tudo" transforma a própria tela em vez de abrir outra.
// Os dados vêm de uma FONTE: no navegador, os exemplos abaixo; no app, inicio-app.js (histórico, seguidos, playlists).

// ---- Dados de exemplo (os mesmos do player) ----
const NAMES = [["Luz da Cidade","Banda Exemplo"],["Maré Alta","Coletivo Demo"],["Faixa Sem Letra","Artista Teste"],
 ["Neon Azul","Os Placeholders"],["Poeira de Estrada","Duo Fictício"],["Noite Lenta","Lo-Fi Amostra"],
 ["Verde Musgo","Grupo Modelo"],["Último Trem","Banda Exemplo"],["Sal e Areia","Coletivo Demo"],
 ["Céu de Vidro","Trio Protótipo"],["Fim de Tarde","Lo-Fi Amostra"],["Chuva Fina","Duo Fictício"],
 ["Rádio Velho","Os Placeholders"],["Asfalto Quente","MC Mockup"],["Lua Cheia","Grupo Modelo"],
 ["Vento Norte","Trio Protótipo"],["Café Frio","Lo-Fi Amostra"],["Ponte Aérea","MC Mockup"],
 ["Girassol","Coletivo Demo"],["Garoa","Banda Exemplo"],["Mar Aberto","Duo Fictício"],["Eco","Os Placeholders"],
 ["Outono","Artista Teste"],["Faísca","MC Mockup"]];
const FIXED = [["#7c3aed", "#ec4899", "#f59e0b"], ["#06b6d4", "#22c55e", "#a3e635"]];
const tracks = NAMES.map(([title, artist], i) => {
  const h = ((i - 2) * 47 + 20 + 3600) % 360;
  return { title, artist, c: FIXED[i] || [`hsl(${h} 70% 42%)`, `hsl(${(h + 40) % 360} 75% 52%)`, `hsl(${(h + 80) % 360} 85% 60%)`] };
});
const grad = c => `linear-gradient(135deg,${c[0]},${c[1]} 60%,${c[2]})`;
const toHandle = name => String(name ?? "").trim();  // nome do artista como vem (sem @)
const artistColors = a => tracks.find(x => x.artist === a).c;
const $ = id => document.getElementById(id);

// Playlists da conta do app (capa automática: mosaico das 4 primeiras faixas)
// Playlists da conta do app (capa automática: mosaico das 4 primeiras faixas)
let playlists = SounosData.getPlaylists();
const followed = ["Banda Exemplo", "Lo-Fi Amostra", "Coletivo Demo", "Duo Fictício", "MC Mockup", "Trio Protótipo", "Os Placeholders"];

const SonusInicio = (() => {
  const EASE = "cubic-bezier(.2,.85,.25,1)";
  const RECENTES_MS = 6 * 3600e3;        // cada música fica 6 h em "Ouvidos recentemente"; tocar de novo renova (não aparece na tela)
  const JANELA_MS = 6000;                // com mais de uma janela, o bloco passa para a próxima sozinho
  const esc = s => String(s ?? "").replace(/[&<>"]/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[ch]));
  const quietas = () => matchMedia("(prefers-reduced-motion: reduce)").matches;
  const anima = (el, k, o) => quietas() || !el.animate ? { finished: Promise.resolve(), cancel() {} } : el.animate(k, { easing: EASE, ...o });
  const plural = (n, um, varios) => n === 1 ? `1 ${um}` : `${n} ${varios}`;
  const I = {
    play: '<svg viewBox="0 0 24 24"><polygon points="7 4 20 12 7 20" fill="currentColor"/></svg>',
    mais: '<svg class="lu" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>',
  };

  // ---------- fonte de exemplo (navegador) ----------
  const exemplo = {
    dados() {
      const agora = Date.now();
      return {
        offline: false,
        recentes: [0, 1, 20, 9, 3, 5, 7].map((t, k) => ({ ...tracks[t], at: agora - k * 40 * 60e3 }))
          .filter(t => agora - t.at < RECENTES_MS),
        minhas: [],
        artistas: followed.map(name => ({ name })),
        playlists: playlists.map((p, i) => ({ ...p, i })),
      };
    },
    capa: t => t?.c ? grad(t.c) : "var(--surface)",
    foto: a => grad(artistColors(a.name)),
    mosaico: p => p.cover ? `url('${p.cover.replace(/'/g, "%27")}') center/cover` : null,
    capaDaPlaylist: (p, k) => { const t = p.tracks[Math.min(k, p.tracks.length - 1)]; return exemplo.capa(typeof t === "object" ? t : tracks[t]); },
    tocar(lista, id, i = 0) { location.href = `player.html?t=${tracks.indexOf(tracks.find(x => x.title === lista[i]?.title))}`; },
    escute: () => [4, 9, 0, 13, 6, 2].map(t => tracks[t]),
    abrirArtista() {},
    abrirPlaylist(p) { location.href = `player.html?p=${p.i}`; },
    novaPlaylist() { add.openCreate(); },
    buscar() { $("searchBtn").click(); },
    novidades: () => new Promise(r => setTimeout(() => r([
      { title: "Maré Alta (Ao Vivo)", kind: "Álbum", year: new Date().getFullYear(), artist: "Coletivo Demo", c: tracks[1].c },
      { title: "Neon Azul", kind: "Single", year: new Date().getFullYear(), artist: "Os Placeholders", c: tracks[3].c },
      { title: "Noite Lenta", kind: "EP", year: new Date().getFullYear() - 1, artist: "Lo-Fi Amostra", c: tracks[5].c },
    ]), 700)),
    capaLancamento: r => r.c ? grad(r.c) : "var(--surface)",
    abrirLancamento() {},
  };
  let fonte = exemplo, d = null;

  // ---------- bloco dinâmico ----------
  // Cada janela: rótulo, lista e o que fazer ao tocar. Próximas janelas (novidades, mix do dia…) entram aqui.
  function janelas() {
    if (d.offline) return [{ id: "minhas", rotulo: "Minhas músicas", lista: d.minhas, sub: "Toque para ouvir todas", vazio: "Nenhuma música disponível offline" }];
    return [{ id: "recentes", rotulo: "Ouvidos recentemente", lista: d.recentes, sub: "Toque para ouvir tudo de novo", vazio: "Busque uma música para começar" }];
  }
  let jan = [], atual = 0, chaveJan = "", passaT = 0;
  function htmlJanela(j, k) {
    const n = j.lista.length, id = k === 0 ? ' id="recentTitle"' : "";
    if (!n) return `<button class="jan vazia tap" data-jan="${k}"><div class="jx"><div class="lbl"${id}>${j.rotulo}</div><div class="big">${j.vazio}</div></div></button>`;
    // as últimas 6 músicas, paradas (sem piscar); com menos de 6 a grade se ajusta
    const capas = j.lista.slice(0, 6), c = n >= 5 ? 3 : n === 4 ? 2 : n, l = n >= 4 ? 2 : 1;
    const cel = Array.from({ length: c * l }, (_, i) => `<i style="background:${fonte.capa(capas[i % capas.length])}"></i>`).join("");
    return `<button class="jan tap" data-jan="${k}"><div class="jmos" style="grid-template-columns:repeat(${c},1fr);grid-template-rows:repeat(${l},1fr)">${cel}</div>
      <div class="jx"><div class="big"${id}>${j.rotulo}</div><div class="sub">${j.sub}</div></div>
      <span class="jpp">${I.play}</span></button>`;
  }
  function renderJanelas() {
    jan = janelas();
    const chave = jan.map(j => j.id + ":" + j.lista.slice(0, 6).map(t => t.id || t.title).join(",") + ":" + j.lista.length).join("|");
    if (chave === chaveJan) return;                 // nada mudou: não redesenha (o mosaico não pisca)
    chaveJan = chave;
    $("dynTrack").innerHTML = jan.map(htmlJanela).join("");
    $("dynDots").innerHTML = jan.length > 1 ? jan.map((_, k) => `<i class="${k === atual ? "on" : ""}"></i>`).join("") : "";
    irPara(Math.min(atual, jan.length - 1), false);
  }
  function irPara(k, animar = true) {
    atual = (k + jan.length) % jan.length;
    const tr = $("dynTrack");
    tr.style.transition = animar && !quietas() ? "" : "none";
    tr.style.transform = `translateX(${-100 * atual}%)`;
    if (!animar) void tr.offsetWidth, tr.style.transition = "";
    [...$("dynDots").children].forEach((x, i) => x.classList.toggle("on", i === atual));
    agendar();
  }
  function agendar() {
    clearTimeout(passaT);
    if (jan.length < 2) return;
    passaT = setTimeout(() => (window.homeCanAnimate?.() ?? true) && !modo ? irPara(atual + 1) : agendar(), JANELA_MS);
  }
  // arrastar para o lado troca de janela; com uma janela só, o bloco estica um pouco e volta
  const ar = { on: false, x: 0, y: 0, dx: 0, foi: false };
  $("dyn").addEventListener("pointerdown", e => { Object.assign(ar, { on: true, x: e.clientX, y: e.clientY, dx: 0, foi: false }); clearTimeout(passaT); });
  addEventListener("pointermove", e => {
    if (!ar.on) return;
    ar.dx = e.clientX - ar.x;
    if (!ar.foi && Math.abs(ar.dx) > 8 && Math.abs(ar.dx) > Math.abs(e.clientY - ar.y)) ar.foi = true;
    if (!ar.foi) return;
    const sozinha = jan.length < 2, dx = sozinha ? ar.dx * .25 : ar.dx;
    $("dynTrack").style.transition = "none";
    $("dynTrack").style.transform = `translateX(calc(${-100 * atual}% + ${dx}px))`;
  });
  ["pointerup", "pointercancel"].forEach(t => addEventListener(t, () => {
    if (!ar.on) return;
    ar.on = false;
    $("dynTrack").style.transition = "";
    if (ar.foi && jan.length > 1 && Math.abs(ar.dx) > 50) irPara(atual + (ar.dx < 0 ? 1 : -1));
    else irPara(atual);
    if (ar.foi) setTimeout(() => ar.foi = false, 0);
  }));
  $("dyn").addEventListener("click", e => {
    if (ar.foi) return;
    const j = jan[+e.target.closest("[data-jan]")?.dataset.jan]; if (!j) return;
    if (!j.lista.length) return fonte.buscar();
    fonte.tocar(j.lista, j.id);
  });

  // ---------- seções ----------
  function renderArtistas() {
    $("artists").innerHTML = d.artistas.length ? d.artistas.map((a, k) =>
      `<button class="ar tap" data-ar="${k}"><div class="pic" style="background:${fonte.foto(a)}"></div><div class="n">${esc(a.name)}</div></button>`).join("")
      : `<button class="ar tap convite" data-act="buscar"><div class="pic">${I.mais}</div><div class="n">Seguir artistas</div></button>`;
  }
  const mosaicoDe = p => {
    const capa = fonte.mosaico(p);
    if (capa) return `<div class="mosaic" style="background:${capa}"></div>`;
    if (!p.tracks.length) return `<div class="mosaic" style="background:linear-gradient(135deg,var(--c1),var(--c2))"></div>`;
    return `<div class="mosaic">${[0, 1, 2, 3].map(k => `<i style="background:${fonte.capaDaPlaylist(p, k)}"></i>`).join("")}</div>`;
  };
  function renderPlaylistsInicio() {
    $("playlists").innerHTML = d.playlists.map((p, k) => `<button class="blk tap" data-pl="${k}">${mosaicoDe(p)}
      <div class="meta"><div class="t">${esc(p.name)}</div><div class="a">${plural(p.tracks.length, "música", "músicas")}</div></div></button>`).join("")
      + `<button class="blk new tap" data-act="nova" aria-label="Nova playlist"><span class="in">${I.mais}Nova playlist</span></button>`;
  }
  $("phone").addEventListener("click", e => {
    if (e.target.closest("#mini")) return;          // arrasto nos carrosséis já é barrado antes (captura)
    const a = e.target.closest("[data-ar]"), p = e.target.closest("[data-pl]"), act = e.target.closest("[data-act]"), v = e.target.closest("[data-ver]");
    const r = e.target.closest("[data-rel]"), ou = e.target.closest("[data-ou]");
    if (v) return modo ? fecharVerTudo() : abrirVerTudo(v.dataset.ver);
    if (a) return fonte.abrirArtista(d.artistas[+a.dataset.ar]);
    if (p) return fonte.abrirPlaylist(d.playlists[+p.dataset.pl]);
    if (r) return fonte.abrirLancamento(novas[+r.dataset.rel]);
    if (ou) return fonte.tocar(escute, "escute", +ou.dataset.ou);
    if (act?.dataset.act === "nova") return fonte.novaPlaylist();
    if (act?.dataset.act === "buscar") return fonte.buscar();
  });

  function render() {
    d = fonte.dados();
    renderJanelas();
    renderArtistas();
    renderPlaylistsInicio();
    if (modo === "playlists") ajustarGrade();
    fitHome();
  }

  // ---------- "Ver tudo": a tela inicial se transforma ----------
  let modo = null, ocupado = false, novas = [], escute = [];
  const sc = () => $("phone").querySelector(".scroll");
  const secao = m => sc().querySelector(`.vt-sec[data-modo="${m}"]`);
  const outras = m => [...sc().querySelectorAll(".dyn-sec, .vt-sec")].filter(x => x !== secao(m));
  function trocaRotulo(m, texto) {
    const b = secao(m).querySelector("[data-ver]");
    anima(b, [{ opacity: 0, transform: "translateY(4px)" }, { opacity: 1, transform: "none" }], { duration: 260 });
    b.textContent = texto;
  }
  function ajustarGrade() {
    // duas colunas do tamanho da tela (mesmo desenho dos blocos, só maiores)
    const w = (sc().clientWidth - 40 - 12) / 2;
    $("playlists").style.setProperty("--s", Math.floor(w) + "px");
  }
  // FLIP: os blocos saem de onde estavam (fileira) e deslizam até o lugar novo (grade), e vice-versa
  function flipBlocos(trocar) {
    const kids = [...$("playlists").children], r0 = kids.map(k => k.getBoundingClientRect());
    trocar();
    kids.forEach((k, i) => {
      const r1 = k.getBoundingClientRect(); if (!r1.width) return;
      anima(k, [{ transformOrigin: "0 0", transform: `translate(${r0[i].left - r1.left}px,${r0[i].top - r1.top}px) scale(${r0[i].width / r1.width})` },
        { transformOrigin: "0 0", transform: "none" }], { duration: 520, delay: Math.min(i, 8) * 18 });
    });
  }
  async function abrirVerTudo(m) {
    if (modo || ocupado) return;
    ocupado = true; modo = m;
    clearTimeout(passaT);
    $("phone").classList.remove("intro");          // a entrada em cascata não pode repetir quando as seções voltarem
    const alvo = secao(m), resto = outras(m), y0 = alvo.getBoundingClientRect().top;
    // 1) o resto sai (o que está acima sobe, o que está abaixo desce)
    await Promise.all(resto.map(el => anima(el, [{ opacity: 1, transform: "none" },
      { opacity: 0, transform: `translateY(${el.compareDocumentPosition(alvo) & 4 ? -18 : 18}px) scale(.98)` }], { duration: 220, fill: "forwards" }).finished));
    resto.forEach(el => { el.hidden = true; el.getAnimations().forEach(a => a.cancel()); });
    $("phone").classList.add("vt-on", "vt-" + m);
    sc().scrollTop = 0;
    // 2) a seção sobe para o topo; playlists viram grade
    if (m === "playlists") flipBlocos(() => { ajustarGrade(); $("playlists").classList.add("grade"); });
    anima(alvo, [{ transform: `translateY(${y0 - alvo.getBoundingClientRect().top}px)` }, { transform: "none" }], { duration: 460 });
    trocaRotulo(m, "Voltar");
    // 3) o conteúdo novo entra por baixo
    if (m === "artistas") { $("vt").innerHTML = htmlNovidades(null) + htmlEscute(); carregarNovidades(); }
    anima($("vt"), [{ opacity: 0, transform: "translateY(28px)" }, { opacity: 1, transform: "none" }], { duration: 420, delay: 180, fill: "backwards" });
    ocupado = false;
  }
  async function fecharVerTudo() {
    if (!modo || ocupado) return;
    ocupado = true;
    const m = modo, alvo = secao(m), resto = outras(m);
    await anima($("vt"), [{ opacity: 1, transform: "none" }, { opacity: 0, transform: "translateY(20px)" }], { duration: 200, fill: "forwards" }).finished;
    $("vt").innerHTML = ""; $("vt").getAnimations().forEach(a => a.cancel());
    sc().scrollTop = 0;
    const y0 = alvo.getBoundingClientRect().top;
    const voltar = () => { resto.forEach(el => el.hidden = false); $("phone").classList.remove("vt-on", "vt-" + m); };
    if (m === "playlists") flipBlocos(() => { $("playlists").classList.remove("grade"); $("playlists").style.removeProperty("--s"); voltar(); });
    else voltar();
    modo = null;
    anima(alvo, [{ transform: `translateY(${y0 - alvo.getBoundingClientRect().top}px)` }, { transform: "none" }], { duration: 460 });
    resto.forEach(el => anima(el, [{ opacity: 0, transform: `translateY(${el.compareDocumentPosition(alvo) & 4 ? -18 : 18}px) scale(.98)` },
      { opacity: 1, transform: "none" }], { duration: 380, delay: 140, fill: "backwards" }));
    trocaRotulo(m, "Ver tudo");
    fitHome();
    agendar();
    ocupado = false;
  }

  // Novidades de quem a pessoa segue (álbum novo, single, EP, ao vivo). Recomendações entram com o motor de entendimento.
  const etiqueta = r => /ao vivo|\blive\b|ac[uú]stico/i.test(r.title) ? "Ao vivo"
    : r.kind === "Álbum" && r.year === new Date().getFullYear() ? "Álbum novo" : r.kind;
  function htmlNovidades(lista) {
    const corpo = lista === null
      ? Array.from({ length: 3 }, () => '<div class="rel sk"><div class="rc"></div><div class="rt"></div><div class="ra"></div></div>').join("")
      : !lista.length ? '<div class="vt-vazio">Nenhum lançamento recente de quem você segue.</div>'
      : lista.map((r, k) => `<button class="rel tap" data-rel="${k}"><div class="rc" style="background:${fonte.capaLancamento(r)}"><span class="kind">${etiqueta(r)}</span></div>
          <div class="rt">${esc(r.title)}</div><div class="ra">${esc(r.artist)}${r.year ? " · " + r.year : ""}</div></button>`).join("");
    return `<section class="vt-bloco" data-bloco="novidades"><div class="sec-head"><h2>Novidades</h2></div><div class="row vt-row">${corpo}</div></section>`;
  }
  // Escute novamente: as músicas mais ouvidas (no máximo 10), sem prazo — ao contrário de "Ouvidos recentemente"
  function htmlEscute() {
    escute = fonte.escute();
    if (!escute.length) return "";
    return `<section class="vt-bloco"><div class="sec-head"><h2>Escute novamente</h2></div><div class="row vt-row">${escute.map((t, k) =>
      `<button class="rel tap" data-ou="${k}"><div class="rc" style="background:${fonte.capa(t)}"></div>
        <div class="rt">${esc(t.title)}</div><div class="ra">${esc(t.artist)}</div></button>`).join("")}</div></section>`;
  }
  async function carregarNovidades() {
    const lista = await fonte.novidades().catch(() => []);
    if (modo !== "artistas") return;
    novas = lista;
    const g = $("vt").querySelector('[data-bloco="novidades"] .row'); if (!g) return;
    const novo = document.createElement("div"); novo.innerHTML = htmlNovidades(lista);
    g.replaceWith(novo.querySelector(".row"));
    [...$("vt").querySelectorAll('[data-bloco="novidades"] .rel')].forEach((el, i) => anima(el, [{ opacity: 0, transform: "translateY(12px)" }, { opacity: 1, transform: "none" }],
      { duration: 360, delay: Math.min(i, 10) * 40, fill: "backwards" }));
  }

  return {
    usar(f) { fonte = f; render(); },
    render,
    get modo() { return modo; },
    fecharVerTudo,
    RECENTES_MS,
  };
})();
// outros arquivos (mini-player, add) chamam este nome quando as playlists mudam
function renderPlaylists() { SonusInicio.render(); }

// Ajuste de altura: as seções crescem juntas para preencher a tela acima do mini-player.
// Tamanho = mínimo + t × (máximo − mínimo); em telas baixas, reduz junto para caber.
// Só recalcula nos eventos (carregar, redimensionar, --mini-h mudar), nunca a cada quadro. No "Ver tudo" não mexe.
const FIT = [["dyn", "--rec", 150, 236], ["artists", "--art", 64, 110], ["playlists", "--pl", 100, 160]];
let fitKey = "", fitFirst = true;
function fitHome() {
  if (SonusInicio.modo) return;
  const ph = $("phone"), sc = ph.querySelector(".scroll"), cs = getComputedStyle(sc);
  const secs = FIT.map(f => ({ f, sec: $(f[0]).closest("section") })).filter(x => x.sec.offsetParent);   // seção oculta não conta
  const miniH = parseFloat(ph.style.getPropertyValue("--mini-h")) || 0;
  const key = [sc.clientHeight, miniH, secs.length, $("artists").children.length > 0].join("|");
  if (key === fitKey) return;
  fitKey = key;
  // altura fixa (tudo que não é bloco): topo + cabeçalho + margens, títulos e respiros das linhas
  const px = v => parseFloat(v) || 0;
  let over = px(cs.paddingTop) + ph.querySelector(".head").offsetHeight;
  for (const { f, sec } of secs) {
    const sh = sec.querySelector(".sec-head"), r = getComputedStyle($(f[0])), m = getComputedStyle(sec);
    over += px(m.marginTop) + (sh ? sh.offsetHeight + px(getComputedStyle(sh).marginBottom) : 0)
      + px(r.paddingTop) + px(r.paddingBottom) + px(r.marginTop) + px(r.marginBottom);
  }
  const ar = document.querySelector("#artists .ar");
  if (secs.some(x => x.f[0] === "artists")) over += ar ? ar.offsetHeight - ar.querySelector(".pic").offsetHeight : 24;   // nome sob a foto
  const free = sc.clientHeight - px(cs.paddingBottom) - over;      // padding de baixo já reserva o mini-player
  const lo = secs.reduce((a, x) => a + x.f[2], 0), hi = secs.reduce((a, x) => a + x.f[3], 0);
  const scale = lo > 0 && free < lo ? Math.max(0, free / lo) : 1;
  const t = hi > lo ? Math.min(1, Math.max(0, (free / (scale || 1) - lo) / (hi - lo))) : 0;
  if (fitFirst) {                                                  // primeira vez: sem animar a partir do valor inicial
    fitFirst = false; ph.style.transition = "none";
    requestAnimationFrame(() => requestAnimationFrame(() => ph.style.transition = ""));
  }
  for (const f of FIT) ph.style.setProperty(f[1], Math.round((f[2] + t * (f[3] - f[2])) * scale) + "px");
}
new ResizeObserver(() => fitHome()).observe($("phone"));

// Carrosséis: no celular o dedo rola sozinho; com o MOUSE, arrastar também passa (com inércia).
// Um arrasto não conta como toque (não abre música sem querer).
let dragMoved = false;
document.querySelectorAll(".row").forEach(row => {
  const d = { on: false, x: 0, sl: 0, v: 0, lx: 0, lt: 0, raf: 0 };
  row.addEventListener("pointerdown", e => {
    if (e.pointerType !== "mouse" || e.button !== 0) return;
    cancelAnimationFrame(d.raf);
    Object.assign(d, { on: true, x: e.clientX, sl: row.scrollLeft, v: 0, lx: e.clientX, lt: performance.now() });
    dragMoved = false;
    row.style.scrollSnapType = "none";
  });
  window.addEventListener("pointermove", e => {
    if (!d.on) return;
    const dx = e.clientX - d.x;
    if (Math.abs(dx) > 5) dragMoved = true;
    row.scrollLeft = d.sl - dx;
    const now = performance.now(); if (now > d.lt) d.v = .8 * (e.clientX - d.lx) / (now - d.lt) + .2 * d.v;
    d.lx = e.clientX; d.lt = now;
  });
  window.addEventListener("pointerup", () => {
    if (!d.on) return;
    d.on = false;
    let v = d.v, last = performance.now();
    const glide = now => {
      const dt = now - last; last = now;
      row.scrollLeft -= v * dt; v *= Math.pow(.94, dt / 16);
      if (Math.abs(v) > .02) d.raf = requestAnimationFrame(glide); else row.style.scrollSnapType = "";
    };
    d.raf = requestAnimationFrame(glide);
  });
});
document.addEventListener("click", e => { if (dragMoved && e.target.closest(".row")) { e.stopImmediatePropagation(); e.preventDefault(); dragMoved = false; } }, true);
document.querySelectorAll(".row").forEach(r => r.addEventListener("dragstart", e => e.preventDefault()));

if (!window.Sounos?.native) SonusInicio.render();   // no app: inicio-app.js liga a fonte real
