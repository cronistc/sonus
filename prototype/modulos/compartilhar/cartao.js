// Módulo Compartilhar — a imagem para compartilhar (desenho escolhido em 27/10/2026). Ver MODULO.md
// Folha por cima da tela: prévia da imagem (cartão 3:4 ou objeto solto), fileira de MODELOS, CORES do modelo (tiradas
// da capa/foto) e Salvar / Copiar link / Compartilhar. Tudo desenhado num canvas, com as capas de verdade e as fontes do app.
//  · letra (player): Capa viva, Clave, Onda, Capa cheia, Ingresso, Fita
//  · artista: Retrato, Foto cheia · álbum: Vinil, Capa cheia · playlist: Leque, Lista
// Uso: SonusCartao.abrir({ tipo, titulo, artista, link, … }) — os campos de cada tipo estão em `abrir`.
window.SonusCartao = (() => {
  // cartões em 3:4 (1080×1440); Ingresso e Fita são OBJETOS: saem sozinhos, num PNG transparente do tamanho deles
  const W = 1080, H = 1440, M = 88;
  const TIT = '"SonusTitulo", "SonusFont", system-ui, sans-serif';
  const TXT = '"SonusFont", system-ui, sans-serif';
  const CUR = '"SonusCursiva", Georgia, serif';
  const $ = id => document.getElementById(id);
  // a clave do Sonus (mesmo desenho do app), caixa 20..44 × 1..63
  const CLAVE = new Path2D("M 42.54 39.23 Q 42.54 45.1 37.09 47.15 Q 37.16 47.5 38 51.43 Q 38.55 53.99 38.55 55.74 Q 38.55 58.4 36.74 60.07 Q 34.92 61.74 32.26 61.74 Q 29.89 61.74 28.11 60.41 Q 26.13 58.95 26.13 56.68 Q 26.13 55.22 27.15 53.91 Q 28.17 52.59 29.6 52.59 Q 30.99 52.59 31.97 53.66 Q 32.94 54.74 32.94 56.13 Q 32.94 59.24 29.47 59.24 Q 30.35 60.61 32.29 60.61 Q 33.91 60.61 35.31 59.7 Q 37.35 58.37 37.35 55.09 Q 37.35 53.89 35.96 47.57 Q 34.56 47.89 33.26 47.89 Q 28.46 47.89 24.96 44.23 Q 21.46 40.56 21.46 35.73 Q 21.46 31.55 24.44 27.23 Q 26.16 24.77 30.57 20.36 Q 29.47 16.4 29.47 12.86 Q 29.47 10.01 30.28 7.32 Q 31.29 4.01 33.14 2.68 Q 33.82 2.19 34.4 2.19 Q 34.98 2.19 35.54 2.84 Q 38.16 5.96 38.16 11.79 Q 38.16 19.87 32.58 25.03 L 33.78 30.83 Q 34.66 30.67 35.24 30.67 Q 38.49 30.67 40.59 33.39 Q 42.54 35.89 42.54 39.23Z M 36.77 9.88 Q 36.77 7.12 35.08 7.12 Q 33.07 7.12 31.81 10.66 Q 30.8 13.38 30.8 15.91 Q 30.8 17.83 31.51 19.51 Q 33.36 18.38 35.06 15.23 Q 36.77 12.09 36.77 9.88Z M 40.24 40.89 Q 40.24 38.29 38.71 36.57 Q 37.09 34.79 34.53 34.79 L 36.8 46.08 Q 40.24 44.65 40.24 40.89Z M 35.76 46.43 33.46 34.92 Q 32.03 35.34 30.96 36.65 Q 29.89 37.97 29.89 39.43 Q 29.89 41.31 31.37 42.57 Q 32.84 43.84 32.84 43.74 L 32.49 43.97 Q 30.48 43.45 29.18 41.81 Q 27.88 40.17 27.88 38.1 Q 27.88 35.92 29.26 33.9 Q 30.64 31.87 32.65 31.09 L 31.64 25.87 Q 23.89 32.32 23.89 38.45 Q 23.89 42.02 26.62 44.52 Q 29.24 46.95 32.84 46.95 Q 33.72 46.95 35.76 46.43Z");

  // ---------- cores ----------
  const hex = c => {
    const m = /^#?([0-9a-f]{6})$/i.exec(String(c || "").trim());
    if (m) return [0, 2, 4].map(i => parseInt(m[1].slice(i, i + 2), 16));
    const r = String(c || "").match(/[\d.]+/g);
    return r && r.length >= 3 ? r.slice(0, 3).map(Number) : [120, 120, 130];
  };
  const rgb = a => `rgb(${a.map(v => Math.round(Math.max(0, Math.min(255, v)))).join(",")})`;
  const mix = (a, b, f) => { const x = hex(a), y = hex(b); return rgb(x.map((v, i) => v * (1 - f) + y[i] * f)); };
  const escuro = (c, f) => mix(c, "#000000", f), claro = (c, f) => mix(c, "#ffffff", f);
  const lum = c => { const [r, g, b] = hex(c).map(v => v / 255); return .2126 * r + .7152 * g + .0722 * b; };
  const escurece = (c, max) => { let o = c, f = 0; while (lum(o) > max && f < .85) { f += .08; o = escuro(c, f); } return o; };   // escurece até a luminância cair abaixo de `max`
  // cores da capa, sempre com vida (capa cinza/bege não vira um cartão apagado)
  function paleta(cores) {
    const c = (cores || []).filter(Boolean);
    const base = ["#7c3aed", "#ec4899", "#f59e0b", "#f59e0b"];
    const [c1, c2, c3, c4] = [0, 1, 2, 3].map(i => c[i] || c[i - 1] || base[i]);
    // cores da capa REALMENTE diferentes entre si (capa com dois laranjas parecidos dava bolinhas iguais)
    const dist = (a, b) => { const x = hex(a), y = hex(b); return Math.hypot(x[0] - y[0], x[1] - y[1], x[2] - y[2]); };
    const distintas = [];
    [c1, c2, c3, c4].forEach(c => { if (distintas.every(k => dist(k, c) > 70)) distintas.push(c); });
    return { c1, c2, c3, c4, distintas: distintas.slice(0, 3) };
  }
  const forte = k => lum(k) > .25 ? k : claro(k, .35);      // destaque que aparece sobre fundo escuro

  // ---------- desenho ----------
  function rr(ctx, x, y, w, h, r) { ctx.beginPath(); ctx.roundRect(x, y, w, h, r); }
  function grad(ctx, x0, y0, x1, y1, stops) {
    const g = ctx.createLinearGradient(x0, y0, x1, y1);
    stops.forEach((c, i) => g.addColorStop(i / Math.max(1, stops.length - 1), c));
    return g;
  }
  function capa(ctx, d, x, y, w, h, r) {
    ctx.save(); rr(ctx, x, y, w, h, r); ctx.clip();
    if (d.capa) {
      const s = Math.max(w / d.capa.naturalWidth, h / d.capa.naturalHeight), iw = d.capa.naturalWidth * s, ih = d.capa.naturalHeight * s;
      ctx.drawImage(d.capa, x + (w - iw) / 2, y + (h - ih) / 2, iw, ih);
    } else { ctx.fillStyle = grad(ctx, x, y, x + w, y + h, [d.P.c1, d.P.c2, d.P.c3]); ctx.fillRect(x, y, w, h); }
    ctx.restore();
  }
  function clave(ctx, x, y, alt, cor) {               // desenha a clave com altura `alt` a partir de (x, y)
    const s = alt / 62;
    ctx.save(); ctx.translate(x - 20 * s, y - 1 * s); ctx.scale(s, s); ctx.fillStyle = cor; ctx.fill(CLAVE); ctx.restore();
    return 24 * s;
  }
  function corta(ctx, t, max) {                        // texto numa linha, com "…" se não couber
    if (ctx.measureText(t).width <= max) return t;
    while (t.length > 1 && ctx.measureText(t + "…").width > max) t = t.slice(0, -1);
    return t.trimEnd() + "…";
  }
  function linha(ctx, t, x, y, fonte, cor, max, alinha = "left") {
    ctx.font = fonte; ctx.fillStyle = cor; ctx.textAlign = alinha; ctx.fillText(max ? corta(ctx, t, max) : t, x, y); ctx.textAlign = "left";
  }
  function espaçado(ctx, t, x, y, px, cor, ls = .16, peso = 800) {   // rótulo em maiúsculas com letras afastadas
    ctx.font = `${peso} ${px}px ${TXT}`; ctx.fillStyle = cor;
    if ("letterSpacing" in ctx) { ctx.letterSpacing = `${px * ls}px`; ctx.fillText(t, x, y); ctx.letterSpacing = "0px"; }
    else ctx.fillText(t, x, y);
  }
  function quebra(ctx, texto, max) {
    const out = []; let l = "";
    for (let p of String(texto).split(/\s+/)) {
      // palavra maior que a linha inteira: quebrada em pedaços (senão sai pela borda)
      while (ctx.measureText(p).width > max && p.length > 1) {
        if (l) { out.push(l); l = ""; }
        let k = p.length; while (k > 1 && ctx.measureText(p.slice(0, k)).width > max) k--;
        out.push(p.slice(0, k)); p = p.slice(k);
      }
      const t = l ? l + " " + p : p;
      if (ctx.measureText(t).width > max && l) { out.push(l); l = p; } else l = t;
    }
    if (l) out.push(l);
    return out;
  }
  // as linhas escolhidas no maior tamanho que couber na caixa (maxW × maxH); se nem no menor couber,
  // fica no menor e corta o que sobra, com "…" no fim da última linha que coube
  function ajusta(ctx, linhas, fonte, maxW, maxH, maior, menor, alt = 1.2, respiro = .28) {
    let tam = maior, rows, h;
    for (; tam >= menor; tam -= 2) {
      ctx.font = fonte(tam); rows = []; h = 0;
      linhas.forEach((l, k) => {
        if (k) h += tam * respiro;
        quebra(ctx, l, maxW).forEach(q => { rows.push({ t: q, y: h }); h += tam * alt; });
      });
      if (h <= maxH) break;
    }
    if (h > maxH) {
      tam = menor; ctx.font = fonte(tam);
      const cabem = rows.filter(r => r.y + tam * alt <= maxH);
      if (!cabem.length) cabem.push(rows[0]);
      const ult = cabem[cabem.length - 1];
      ult.t = corta(ctx, ult.t + "…", maxW); if (!ult.t.endsWith("…")) ult.t = corta(ctx, ult.t, maxW - ctx.measureText("…").width) + "…";
      rows = cabem; h = ult.y + tam * alt;
    }
    return { tam, rows, h, fonte: fonte(tam), alt };
  }
  function escreve(ctx, lay, x, y, cor, sombra) {
    ctx.font = lay.fonte; ctx.fillStyle = cor; ctx.textBaseline = "alphabetic";
    if (sombra) { ctx.shadowColor = sombra; ctx.shadowBlur = 28; }
    lay.rows.forEach(r => ctx.fillText(r.t, x, y + r.y + lay.tam));
    ctx.shadowColor = "transparent"; ctx.shadowBlur = 0;
  }
  const tempo = s => { s = Math.max(0, Math.round(s || 0)); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`; };
  const textoClaro = c => lum(c) > .6 ? "#16161a" : "#ffffff";

  // ---------- os modelos da letra ----------
  const LETRA = [
    { id: "capa", nome: "Capa viva",
      cores: P => [
        ...P.distintas.map((k, i) => ({ nome: `Cor ${i + 1} da capa`, bg: [k, escuro(k, .35), escuro(k, .72)] })),
        { nome: "Escuro", bg: ["#1d1d22", "#141416", "#0b0b0d"] },
        { nome: "Claro", bg: ["#f7f3ec", "#efe9df", "#e3dccf"] }],
      desenha(ctx, d, v) {
        ctx.fillStyle = grad(ctx, 0, 0, W * .45, H, v.bg); ctx.fillRect(0, 0, W, H);
        const tx = textoClaro(v.bg[1]), sub = tx === "#ffffff" ? "rgba(255,255,255,.8)" : "rgba(22,22,26,.65)";
        ctx.save(); ctx.shadowColor = "rgba(0,0,0,.3)"; ctx.shadowBlur = 40; ctx.shadowOffsetY = 14; capa(ctx, d, M, M + 40, 220, 220, 36); ctx.restore();
        const x = M + 220 + 40, mw = W - x - M;
        linha(ctx, d.titulo, x, M + 40 + 92, `700 50px ${TIT}`, tx, mw);
        linha(ctx, d.artista, x, M + 40 + 152, `600 40px ${TXT}`, sub, mw);
        const lay = ajusta(ctx, d.linhas, t => `800 ${t}px ${TIT}`, W - 2 * M, H - 420 - 300, 104, 48);
        escreve(ctx, lay, M, 420 + (H - 420 - 300 - lay.h) / 2, tx);
        const lw = clave(ctx, M, H - 196, 92, tx);
        linha(ctx, "Sonus", M + lw + 18, H - 128, `700 42px ${TXT}`, tx);
      } },
    { id: "clave", nome: "Clave",
      cores: P => [
        // o rodapé (título e artista) é branco: o fim do degradê nunca fica claro demais para ele (contraste >= 4,5:1)
        ...P.distintas.map((k, i) => ({ nome: `Cor ${i + 1} da capa`, bg: ["#121014", escuro(k, .62), escurece(k, .42)] })),
        { nome: "Noite", bg: ["#0b0b0d", "#17171b", "#2c2c33"] },
        { nome: "Claro", bg: ["#f7f3ec", "#efe6d8", claro(P.c1, .5)] }],
      desenha(ctx, d, v) {
        const g = ctx.createLinearGradient(0, 0, 0, H); g.addColorStop(0, v.bg[0]); g.addColorStop(.62, v.bg[1]); g.addColorStop(1, v.bg[2]);
        ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
        const tx = textoClaro(v.bg[0]), fraco = tx === "#ffffff" ? "rgba(255,255,255," : "rgba(22,22,26,";
        ctx.save(); ctx.globalAlpha = .12; clave(ctx, W - 380, 90, 920, tx); ctx.restore();
        espaçado(ctx, `AOS ${tempo(d.ini)}`, M, 196, 34, fraco + ".6)");
        const lay = ajusta(ctx, d.linhas, t => `800 ${t}px ${TIT}`, W - 2 * M - 40, H - 280 - 640, 96, 44);
        escreve(ctx, lay, M, 280, tx);
        // a onda do player, com a marca do momento do trecho
        const n = 46, larg = W - 2 * M, passo = larg / n, base = H - 450, alto = 140, pos = Math.max(.04, Math.min(.96, d.ini / (d.dur || 1)));
        for (let i = 0; i < n; i++) {
          const h = alto * d.onda[i % d.onda.length], antes = i / n < pos;
          ctx.fillStyle = antes ? fraco + ".95)" : fraco + ".35)";
          rr(ctx, M + i * passo, base - h / 2, passo * .45, h, 6); ctx.fill();
        }
        ctx.save(); ctx.shadowColor = tx; ctx.shadowBlur = 30; ctx.fillStyle = tx; rr(ctx, M + larg * pos - 4, base - alto / 2 - 10, 8, alto + 20, 4); ctx.fill(); ctx.restore();
        capa(ctx, d, M, H - 250, 150, 150, 28);
        linha(ctx, d.titulo, M + 186, H - 186, `700 44px ${TIT}`, tx, W - M - 186 - M);
        linha(ctx, `${d.artista} · no Sonus`, M + 186, H - 132, `600 36px ${TXT}`, fraco + ".75)", W - M - 186 - M);
      } },
    { id: "onda", nome: "Onda",
      cores: P => [
        ...P.distintas.map((k, i) => ({ nome: `Cor ${i + 1} da capa`, acc: forte(k), glow: escuro(k, .55), base: "#120b09" })),
        { nome: "Branco", acc: "#ffffff", glow: escuro(P.c1, .6), base: "#0f0f12" }],
      desenha(ctx, d, v) {
        const g = ctx.createRadialGradient(W / 2, H * .38, 0, W / 2, H * .38, H * .7); g.addColorStop(0, v.glow); g.addColorStop(1, v.base);
        ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
        capa(ctx, d, M, M + 30, 160, 160, 30);
        linha(ctx, d.titulo, M + 196, M + 100, `700 46px ${TIT}`, "#fff", W - M - 196 - M);
        linha(ctx, d.artista, M + 196, M + 156, `600 38px ${TXT}`, "rgba(255,255,255,.72)", W - M - 196 - M);
        const n = 54, larg = W - 2 * M, passo = larg / n, meio = 520, alto = 300;
        const a = Math.floor(n * (d.ini / (d.dur || 1))), b = Math.max(a + 3, Math.ceil(n * (d.fim / (d.dur || 1))));
        for (let i = 0; i < n; i++) {
          const h = Math.max(16, alto * d.onda[i % d.onda.length]), dentro = i >= a && i <= b;
          ctx.globalAlpha = dentro ? 1 : .28; ctx.fillStyle = dentro ? (i % 3 ? v.acc : claro(v.acc, .3)) : v.acc;
          rr(ctx, M + i * passo, meio - h / 2, passo * .52, h, 8); ctx.fill();
        }
        ctx.globalAlpha = 1;
        ctx.textBaseline = "alphabetic";
        linha(ctx, "0:00", M, meio + alto / 2 + 74, `700 32px ${TXT}`, "rgba(255,255,255,.55)");
        linha(ctx, `${tempo(d.ini)} — ${tempo(d.fim)}`, W / 2, meio + alto / 2 + 74, `800 34px ${TXT}`, v.acc, 0, "center");
        linha(ctx, tempo(d.dur), W - M, meio + alto / 2 + 74, `700 32px ${TXT}`, "rgba(255,255,255,.55)", 0, "right");
        const topo = meio + alto / 2 + 150;
        const lay = ajusta(ctx, d.linhas, t => `800 ${t}px ${TIT}`, W - 2 * M, H - topo - 200, 84, 40);
        escreve(ctx, lay, M, topo, "#fff");
        espaçado(ctx, "OUVIDO NO SONUS", M, H - 110, 34, "rgba(255,255,255,.55)");
      } },
    { id: "cheia", nome: "Capa cheia",
      cores: P => [
        ...P.distintas.map((k, i) => ({ nome: `Cor ${i + 1} da capa`, ov: escuro(k, .72), tx: "#fff" })),
        { nome: "Preto", ov: "#000000", tx: "#fff" },
        { nome: "Claro", ov: "#fbf8f2", tx: "#16161a" }],
      desenha(ctx, d, v) {
        capa(ctx, d, 0, 0, W, H, 0);
        const [r, g2, b] = hex(v.ov), o = a => `rgba(${r},${g2},${b},${a})`;
        const g = ctx.createLinearGradient(0, 0, 0, H); g.addColorStop(0, o(0)); g.addColorStop(.3, o(0)); g.addColorStop(.52, o(.6)); g.addColorStop(1, o(.95));
        ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
        ctx.font = `700 36px ${TXT}`;
        const etiqueta = corta(ctx, `${d.titulo} · ${d.artista}`, W - 2 * 64 - 64), pw = ctx.measureText(etiqueta).width + 64;
        ctx.fillStyle = "rgba(0,0,0,.45)"; rr(ctx, 64, 72, pw, 84, 42); ctx.fill();
        linha(ctx, etiqueta, 96, 126, `700 36px ${TXT}`, "#fff");
        const lay = ajusta(ctx, d.linhas, t => `800 ${t}px ${TIT}`, W - 2 * M, 560, 92, 42);
        escreve(ctx, lay, M, H - 230 - lay.h, v.tx, v.tx === "#fff" ? "rgba(0,0,0,.45)" : null);
        espaçado(ctx, "SONUS", M, H - 120, 34, v.tx === "#fff" ? "rgba(255,255,255,.7)" : "rgba(22,22,26,.6)");
      } },
    { id: "ingresso", nome: "Ingresso",
      cores: P => [
        ...P.distintas.map((k, i) => ({ nome: `Cor ${i + 1} da capa`, corpo: [k, escuro(k, .22)], canhoto: escuro(k, .38),
          tx: textoClaro(k), rot: textoClaro(k) === "#ffffff" ? "rgba(255,255,255,.7)" : "rgba(22,22,26,.6)", fundo: escuro(k, .7) })),
        { nome: "Papel", corpo: ["#f6eedc", "#ede1c9"], canhoto: "#e4d4b5", tx: "#2b120c", rot: escurece(escuro(P.c1, .2), .16),   /* rótulos do papel: nunca mais claros que luminância .16 */ fundo: escuro(P.c1, .75) },
        { nome: "Preto", corpo: ["#222226", "#18181b"], canhoto: "#111113", tx: "#fff", rot: P.c1, fundo: "#26262b" }],
      giro: -2.5,
      monta(d, v) {
        // o ingresso sozinho (o PNG sai transparente em volta); o picote é recortado de verdade
        const meas = document.createElement("canvas").getContext("2d");
        const tw = 860, faixa = 380, q = ajusta(meas, [`“${d.linhas.join(" ")}”`], t => `italic 700 ${t}px ${CUR}`, tw - 120, 560, 84, 40, 1.18);
        const corpoH = faixa + 120 + q.h + 70, pic = 70, canH = 470, th = corpoH + pic + canH;
        const tk = document.createElement("canvas"); tk.width = tw; tk.height = th; const c = tk.getContext("2d");
        rr(c, 0, 0, tw, th, [64, 64, 64, 64]); c.clip();
        c.fillStyle = grad(c, 0, 0, tw * .6, corpoH, v.corpo); c.fillRect(0, 0, tw, corpoH + pic / 2);
        c.fillStyle = v.canhoto; c.fillRect(0, corpoH + pic / 2, tw, th);
        capa(c, d, 0, 0, tw, faixa, 0);
        espaçado(c, "TRECHO", 60, faixa + 86, 30, v.rot, .2);
        escreve(c, q, 60, faixa + 120, v.tx);
        c.globalCompositeOperation = "destination-out";
        [0, tw].forEach(x => { c.beginPath(); c.arc(x, corpoH + pic / 2, 44, 0, Math.PI * 2); c.fill(); });
        c.globalCompositeOperation = "source-over";
        c.setLineDash([22, 18]); c.lineWidth = 5; c.strokeStyle = v.tx === "#fff" || v.tx === "#ffffff" ? "rgba(255,255,255,.45)" : "rgba(43,18,12,.35)";
        c.beginPath(); c.moveTo(70, corpoH + pic / 2); c.lineTo(tw - 70, corpoH + pic / 2); c.stroke(); c.setLineDash([]);
        const campo = (rot, val, x, y) => { espaçado(c, rot, x, y, 26, v.rot, .18); linha(c, val, x, y + 52, `700 38px ${TIT}`, v.tx, tw / 2 - 90); };
        const y0 = corpoH + pic + 50;
        campo("FAIXA", d.titulo, 60, y0); campo("MOMENTO", tempo(d.ini), tw / 2 + 20, y0);
        campo("ARTISTA", d.artista, 60, y0 + 130); campo("OUVIDO NO", "Sonus", tw / 2 + 20, y0 + 130);
        const n = 48, bw = (tw - 120) / n, by = th - 60;
        c.fillStyle = v.tx;
        for (let i = 0; i < n; i++) { const h = 100 * (.3 + .7 * d.onda[(i * 7) % d.onda.length]); c.globalAlpha = .9; c.fillRect(60 + i * bw, by - h, bw * .62, h); }
        c.globalAlpha = 1;
        return tk;
      } },
    { id: "fita", nome: "Fita",
      cores: P => [
        { nome: "Creme", corpo: "#ece4d3", etiqueta: "#fbf7ee", janela: "#3a2a24" },
        { nome: "Preta", corpo: "#1f1b19", etiqueta: "#f1e9d8", janela: "#0b0a09" },
        { nome: "Transparente", corpo: "rgba(236,228,211,.55)", etiqueta: "#fbf7ee", janela: "rgba(0,0,0,.42)" }],
      monta(d, v) {
        const tinta = "#2b120c", destaque = escurece(escuro(d.P.c1, .15), .2);      // rótulos sobre papel claro: nunca mais claros que luminância .2 (capa amarela/branca ficava ~1,4:1)
        // a fita
        const fw = 920, fh = 600, f = document.createElement("canvas"); f.width = fw; f.height = fh; const c = f.getContext("2d");
        c.fillStyle = v.corpo; rr(c, 0, 0, fw, fh, 48); c.fill();
        c.fillStyle = v.etiqueta; rr(c, 40, 40, fw - 80, 330, 22); c.fill();
        espaçado(c, `LADO A · ${tempo(d.ini)}`, 80, 104, 30, destaque, .22);
        c.save(); c.translate(fw - 150, 120); c.rotate(6 * Math.PI / 180);
        c.shadowColor = "rgba(0,0,0,.3)"; c.shadowBlur = 16; c.shadowOffsetY = 6; capa(c, d, -68, -68, 136, 136, 10); c.restore();
        const q = ajusta(c, d.linhas, t => `italic 700 ${t}px ${CUR}`, fw - 80 - 80 - 190, 220, 58, 28, 1.16, .1);
        escreve(c, q, 80, 130 + (220 - q.h) / 2, tinta);
        c.strokeStyle = "rgba(43,18,12,.22)"; c.lineWidth = 3; c.beginPath(); c.moveTo(80, 352); c.lineTo(fw - 80, 352); c.stroke();
        c.fillStyle = v.janela; rr(c, 170, 410, fw - 340, 140, 70); c.fill();
        [[250, 480, 26], [fw - 250, 480, 40]].forEach(([x, y, aro]) => {
          c.fillStyle = v.etiqueta; c.beginPath(); c.arc(x, y, 50, 0, Math.PI * 2); c.fill();
          c.fillStyle = v.janela; c.beginPath(); c.arc(x, y, 50 - aro + 18, 0, Math.PI * 2); c.fill();
          c.fillStyle = v.etiqueta; c.beginPath(); c.arc(x, y, 12, 0, Math.PI * 2); c.fill();
        });
        c.fillStyle = "rgba(107,74,60,.85)"; c.fillRect(300, 468, fw - 600, 24);
        // a capinha de papel
        const pw = 860, ph = 430, p = document.createElement("canvas"); p.width = pw; p.height = ph; const k = p.getContext("2d");
        k.fillStyle = "#fbf7ee"; rr(k, 0, 0, pw, ph, 18); k.fill();
        espaçado(k, "MINHA FITA", 56, 86, 28, destaque, .22);
        k.font = `700 48px ${TIT}`; const todas = quebra(k, d.titulo, pw - 112), tl = todas.slice(0, 2);
        tl.forEach((l, i) => linha(k, i === 1 && todas.length > 2 ? l + "…" : l, 56, 160 + i * 60, `700 48px ${TIT}`, tinta, pw - 112));
        linha(k, d.artista, 56, 160 + tl.length * 60 + 20, `600 40px ${TXT}`, "#6b4a3c", pw - 112);
        espaçado(k, "GRAVADO NO SONUS", 56, ph - 50, 26, "#6b4a3c", .18);
        k.font = `800 26px ${TXT}`; k.textAlign = "right"; k.fillStyle = "#6b4a3c"; k.fillText("C-90", pw - 56, ph - 50); k.textAlign = "left";
        // juntas, uma sobre a outra, levemente tortas (sem fundo)
        const ow = 1040, oh = 1180, o = document.createElement("canvas"); o.width = ow; o.height = oh; const x = o.getContext("2d");
        x.save(); x.translate(ow / 2, 330); x.rotate(-3 * Math.PI / 180);
        x.shadowColor = "rgba(0,0,0,.4)"; x.shadowBlur = 50; x.shadowOffsetY = 28; x.drawImage(f, -fw / 2, -fh / 2); x.restore();
        x.save(); x.translate(ow / 2, 900); x.rotate(1.5 * Math.PI / 180);
        x.shadowColor = "rgba(0,0,0,.35)"; x.shadowBlur = 44; x.shadowOffsetY = 22; x.drawImage(p, -pw / 2, -ph / 2); x.restore();
        return o;
      } },
  ];

  // ---------- artista, álbum e playlist ----------
  // variações de cor comuns: as cores distintas da capa/foto + um neutro
  const corDaCapa = (P, f) => [...P.distintas.map((k, i) => ({ nome: `Cor ${i + 1} da capa`, ...f(k) }))];
  function rotuloSonus(ctx, x, y, cor, alinha = "left") {        // a clave + "Sonus" pequenos, como assinatura
    ctx.font = `700 40px ${TXT}`;
    const larg = 24 * 70 / 62 + 16 + ctx.measureText("Sonus").width;
    const x0 = alinha === "center" ? x - larg / 2 : alinha === "right" ? x - larg : x;
    const lw = clave(ctx, x0, y - 58, 70, cor);
    linha(ctx, "Sonus", x0 + lw + 16, y, `700 40px ${TXT}`, cor);
  }
  function pilula(ctx, t, x, y, fundo, cor) {                    // retorna a largura
    ctx.font = `700 34px ${TXT}`; const w = ctx.measureText(t).width + 56;
    ctx.fillStyle = fundo; rr(ctx, x, y, w, 72, 36); ctx.fill();
    linha(ctx, t, x + 28, y + 48, `700 34px ${TXT}`, cor);
    return w;
  }
  function centrado(ctx, texto, cx, y, fonteDe, maxW, maior, menor, cor, maxLinhas = 2) {   // nome grande, centralizado
    const { ls, tam } = titulo(ctx, texto, maxW, fonteDe, maior, menor, maxLinhas);
    ls.forEach((l, i) => linha(ctx, l, cx, y + tam + i * tam * 1.08, fonteDe(tam), cor, maxW, "center"));
    return ls.length * tam * 1.08;
  }
  // nome em até `max` linhas: o maior tamanho (de `maior` até `menor`) em que couber; se nem assim, "…" no fim
  function titulo(ctx, texto, maxW, fonteDe, maior, menor, max = 2) {
    let tam = maior, ls;
    for (; tam >= menor; tam -= 4) { ctx.font = fonteDe(tam); ls = quebra(ctx, texto, maxW); if (ls.length <= max) break; }
    if (ls.length > max) { tam = menor; ctx.font = fonteDe(tam); ls = quebra(ctx, texto, maxW); ls = ls.slice(0, max); ls[max - 1] = corta(ctx, ls[max - 1] + "…", maxW); if (!ls[max - 1].endsWith("…")) ls[max - 1] = corta(ctx, ls[max - 1], maxW - ctx.measureText("…").width) + "…"; }
    return { ls, tam, fonte: fonteDe(tam) };
  }
  const minutos = s => { const m = Math.round((s || 0) / 60); return m >= 60 ? `${Math.floor(m / 60)} h ${m % 60 ? (m % 60) + " min" : ""}`.trim() : `${m} min`; };

  const ARTISTA = [
    { id: "retrato", nome: "Retrato",
      cores: P => [...corDaCapa(P, k => ({ bg: [claro(k, .08), escuro(k, .45), escuro(k, .86)], anel: [claro(k, .55), k, "#ffffff"] })),
        { nome: "Noite", bg: ["#26262c", "#17171b", "#0b0b0d"], anel: [P.c1, claro(P.c1, .5), "#ffffff"] }],
      desenha(ctx, d, v) {
        ctx.fillStyle = grad(ctx, W * .2, 0, W * .5, H, v.bg); ctx.fillRect(0, 0, W, H);
        const r = 270, cx = W / 2, cy = 120 + r;
        const g = ctx.createConicGradient(-Math.PI / 2, cx, cy); v.anel.concat(v.anel[0]).forEach((c, i, a) => g.addColorStop(i / (a.length - 1), c));
        ctx.save(); ctx.shadowColor = "rgba(0,0,0,.35)"; ctx.shadowBlur = 50; ctx.shadowOffsetY = 18;
        ctx.fillStyle = g; ctx.beginPath(); ctx.arc(cx, cy, r + 18, 0, Math.PI * 2); ctx.fill(); ctx.restore();
        capa(ctx, d, cx - r, cy - r, 2 * r, 2 * r, r);
        let y = cy + r + 70;
        y += centrado(ctx, d.titulo, cx, y, t => `800 ${t}px ${TIT}`, W - 2 * M, 104, 64, "#fff");
        ctx.textAlign = "center"; espaçado(ctx, "ARTISTA · NO SONUS", cx, y + 64, 34, "rgba(255,255,255,.72)"); ctx.textAlign = "left";
        y += 130;
        (d.musicas || []).slice(0, 3).forEach((t, i) => {
          if (y + 60 > H - 160) return;
          linha(ctx, String(i + 1), M + 20, y + 44, `700 40px ${TIT}`, "rgba(255,255,255,.55)");
          linha(ctx, t, M + 90, y + 44, `600 44px ${TXT}`, "#fff", W - 2 * M - 110);
          y += 82;
        });
        rotuloSonus(ctx, W / 2, H - 90, "rgba(255,255,255,.8)", "center");
      } },
    { id: "fotocheia", nome: "Foto cheia",
      cores: P => [...corDaCapa(P, k => ({ ov: escuro(k, .7) })), { nome: "Preto", ov: "#000000" }],
      desenha(ctx, d, v) {
        capa(ctx, d, 0, 0, W, H, 0);
        const [r, g2, b] = hex(v.ov), o = a => `rgba(${r},${g2},${b},${a})`;
        const g = ctx.createLinearGradient(0, 0, 0, H); g.addColorStop(0, o(.15)); g.addColorStop(.42, o(0)); g.addColorStop(.62, o(.55)); g.addColorStop(1, o(.97));
        ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
        rotuloSonus(ctx, M, 150, "#fff");
        // de baixo para cima: as músicas, o nome e o rótulo
        const musicas = (d.musicas || []).slice(0, 3);
        const topoMus = H - 110 - musicas.length * 66;
        musicas.forEach((t, i) => linha(ctx, t, M, topoMus + 44 + i * 66, `600 42px ${TXT}`, "rgba(255,255,255,.85)", W - 2 * M));
        const n = titulo(ctx, d.titulo, W - 2 * M, t => `800 ${t}px ${TIT}`, 132, 84);
        const y = topoMus - 30 - n.ls.length * n.tam * 1.04;
        espaçado(ctx, "ARTISTA", M, y - 6, 34, "rgba(255,255,255,.75)");
        ctx.save(); ctx.shadowColor = "rgba(0,0,0,.4)"; ctx.shadowBlur = 30;
        n.ls.forEach((l, i) => linha(ctx, l, M, y + n.tam + i * n.tam * 1.04, n.fonte, "#fff"));
        ctx.restore();
      } },
  ];

  const ALBUM = [
    { id: "vinil", nome: "Vinil",
      cores: P => [...corDaCapa(P, k => ({ bg: ["#2a2a2e", "#141416", escuro(k, .55)], acc: forte(k) })),
        { nome: "Claro", bg: ["#f7f3ec", "#efe9df", claro(P.c1, .55)], acc: escuro(P.c1, .1), claro: true }],
      desenha(ctx, d, v) {
        ctx.fillStyle = grad(ctx, 0, 0, W * .5, H, v.bg); ctx.fillRect(0, 0, W, H);
        const tx = v.claro ? "#16161a" : "#ffffff", sub = v.claro ? "rgba(22,22,26,.7)" : "rgba(255,255,255,.8)";
        const lado = 640, x = M, y = M + 20, dr = 300, dcx = x + lado - 40 + 30, dcy = y + lado / 2;
        // o disco saindo da capa
        ctx.save(); ctx.shadowColor = "rgba(0,0,0,.55)"; ctx.shadowBlur = 50; ctx.shadowOffsetY = 20;
        ctx.fillStyle = "#151517"; ctx.beginPath(); ctx.arc(dcx, dcy, dr, 0, Math.PI * 2); ctx.fill(); ctx.restore();
        ctx.strokeStyle = "rgba(255,255,255,.07)"; ctx.lineWidth = 3;
        for (let rr2 = dr - 24; rr2 > dr * .36; rr2 -= 22) { ctx.beginPath(); ctx.arc(dcx, dcy, rr2, 0, Math.PI * 2); ctx.stroke(); }
        ctx.fillStyle = v.acc; ctx.beginPath(); ctx.arc(dcx, dcy, dr * .3, 0, Math.PI * 2); ctx.fill();
        ctx.fillStyle = "#0e0e10"; ctx.beginPath(); ctx.arc(dcx, dcy, 14, 0, Math.PI * 2); ctx.fill();
        ctx.save(); ctx.shadowColor = "rgba(0,0,0,.55)"; ctx.shadowBlur = 60; ctx.shadowOffsetX = 24; ctx.shadowOffsetY = 30;
        capa(ctx, d, x, y, lado, lado, 20); ctx.restore();
        let ty = y + lado + 90;
        const n = titulo(ctx, d.titulo, W - 2 * M, t => `800 ${t}px ${TIT}`, 120, 80);
        n.ls.forEach((l, i) => linha(ctx, l, M, ty + n.tam + i * n.tam * 1.05, n.fonte, tx));
        ty += n.ls.length * n.tam * 1.05 + 70;
        linha(ctx, d.artista, M, ty, `600 50px ${TXT}`, sub, W - 2 * M);
        let px = M; ty += 50;
        px += pilula(ctx, "Álbum", px, ty, v.acc, textoClaro(v.acc)) + 16;
        if (d.n) pilula(ctx, `${d.n} música${d.n > 1 ? "s" : ""}`, px, ty, v.claro ? "rgba(22,22,26,.08)" : "rgba(255,255,255,.12)", tx);
        espaçado(ctx, "OUÇA NO SONUS", M, H - 90, 34, v.claro ? "rgba(22,22,26,.55)" : "rgba(255,255,255,.55)");
      } },
    { id: "cheia", nome: "Capa cheia",
      cores: P => [...corDaCapa(P, k => ({ ov: escuro(k, .72), tx: "#fff" })), { nome: "Preto", ov: "#000000", tx: "#fff" }, { nome: "Claro", ov: "#fbf8f2", tx: "#16161a" }],
      desenha(ctx, d, v) {
        capa(ctx, d, 0, 0, W, H, 0);
        const [r, g2, b] = hex(v.ov), o = a => `rgba(${r},${g2},${b},${a})`;
        const g = ctx.createLinearGradient(0, 0, 0, H); g.addColorStop(0, o(0)); g.addColorStop(.4, o(0)); g.addColorStop(.62, o(.65)); g.addColorStop(1, o(.97));
        ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
        const fraco = v.tx === "#fff" ? "rgba(255,255,255,.75)" : "rgba(22,22,26,.65)";
        const n = titulo(ctx, d.titulo, W - 2 * M, t => `800 ${t}px ${TIT}`, 112, 76);
        const y = H - 250 - n.ls.length * n.tam * 1.05;
        espaçado(ctx, d.n ? `ÁLBUM · ${d.n} MÚSICA${d.n > 1 ? "S" : ""}` : "ÁLBUM", M, y - 20, 32, fraco);
        n.ls.forEach((l, i) => linha(ctx, l, M, y + n.tam + i * n.tam * 1.05, n.fonte, v.tx));
        linha(ctx, d.artista, M, H - 180, `600 48px ${TXT}`, fraco, W - 2 * M);
        espaçado(ctx, "OUÇA NO SONUS", M, H - 90, 30, fraco);
      } },
  ];

  const PLAYLIST = [
    { id: "leque", nome: "Leque",
      cores: P => [{ nome: "Cores das capas", bg: [P.c1, P.c2, P.c3].map(k => escurece(escuro(k, .12), .42)) },     // texto branco: nenhum ponto do fundo passa de luminância .42 (capa amarela dava ~1,4:1)
        ...corDaCapa(P, k => ({ bg: [claro(k, .1), escuro(k, .35), escuro(k, .75)].map(c => escurece(c, .42)) })),
        { nome: "Noite", bg: ["#26262c", "#17171b", "#0b0b0d"] }],
      desenha(ctx, d, v) {
        ctx.fillStyle = grad(ctx, 0, 0, W * .6, H, v.bg); ctx.fillRect(0, 0, W, H);
        const cs = d.capas.length ? d.capas : [null];
        const pos = [[-150, 60, -12, 470], [-40, 0, -3, 490], [150, 40, 8, 480], [0, 200, 2, 470]];
        pos.slice(0, Math.max(1, Math.min(4, cs.length))).forEach(([dx, dy, a, lado], i) => {
          ctx.save(); ctx.translate(W / 2 + dx, 120 + dy + lado / 2); ctx.rotate(a * Math.PI / 180);
          ctx.shadowColor = "rgba(0,0,0,.45)"; ctx.shadowBlur = 50; ctx.shadowOffsetY = 22;
          capa(ctx, { ...d, capa: cs[i % cs.length] }, -lado / 2, -lado / 2, lado, lado, 34); ctx.restore();
        });
        let y = 880;
        espaçado(ctx, "PLAYLIST", M, y, 34, "rgba(255,255,255,.75)");
        const n = titulo(ctx, d.titulo, W - 2 * M, t => `800 ${t}px ${TIT}`, 104, 72);
        n.ls.forEach((l, i) => linha(ctx, l, M, y + 20 + n.tam + i * n.tam * 1.05, n.fonte, "#fff"));
        y += 20 + n.ls.length * n.tam * 1.05 + 60;
        linha(ctx, `${d.n} música${d.n === 1 ? "" : "s"}${d.dur ? " · " + minutos(d.dur) : ""}`, M, y, `600 42px ${TXT}`, "rgba(255,255,255,.85)");
        y += 40;
        d.faixas.slice(0, 2).forEach((f, i) => {
          if (y + 90 > H - 130) return;
          capa(ctx, { ...d, capa: f.img }, M, y + 20, 80, 80, 14);
          linha(ctx, `${f.titulo}${f.artista ? " · " + f.artista : ""}`, M + 108, y + 74, `600 40px ${TXT}`, "#fff", W - 2 * M - 108);
          y += 100;
        });
        if (d.n > 2) { ctx.textAlign = "right"; espaçado(ctx, `+${d.n - 2} NO SONUS`, W - M, H - 90, 32, "rgba(255,255,255,.75)"); ctx.textAlign = "left"; }
        else espaçado(ctx, "NO SONUS", M, H - 90, 32, "rgba(255,255,255,.75)");
      } },
    { id: "lista", nome: "Lista",
      cores: P => [{ nome: "Escuro", bg: "#141416", acc: forte(P.c1) }, ...corDaCapa(P, k => ({ bg: escuro(k, .78), acc: forte(k) })),
        { nome: "Claro", bg: "#f7f3ec", acc: escuro(P.c1, .15), claro: true }],
      desenha(ctx, d, v) {
        ctx.fillStyle = v.bg; ctx.fillRect(0, 0, W, H);
        const tx = v.claro ? "#16161a" : "#fff", fraco = v.claro ? "rgba(22,22,26,.6)" : "rgba(255,255,255,.62)";
        // mosaico 2×2 das capas
        const lado = 300, mz = lado / 2;
        [0, 1, 2, 3].forEach(i => capa(ctx, { ...d, capa: d.capas[i % Math.max(1, d.capas.length)] || null }, M + (i % 2) * mz, M + Math.floor(i / 2) * mz, mz, mz, 0));
        ctx.save(); rr(ctx, M, M, lado, lado, 28); ctx.strokeStyle = v.bg; ctx.lineWidth = 6; ctx.stroke(); ctx.restore();
        const x = M + lado + 44, mw = W - x - M;
        espaçado(ctx, "PLAYLIST", x, M + 80, 30, v.acc);
        const n = titulo(ctx, d.titulo, mw, t => `800 ${t}px ${TIT}`, 64, 48);
        n.ls.forEach((l, i) => linha(ctx, l, x, M + 90 + n.tam + i * n.tam * 1.1, n.fonte, tx));
        linha(ctx, `${d.n} música${d.n === 1 ? "" : "s"}${d.dur ? " · " + minutos(d.dur) : ""}`, x, M + lado - 10, `600 38px ${TXT}`, fraco, mw);
        let y = M + lado + 70;
        const cabe = Math.floor((H - 200 - y) / 104);
        d.faixas.slice(0, cabe).forEach((f, i) => {
          linha(ctx, String(i + 1), M + 10, y + 64, `700 36px ${TIT}`, v.acc);
          capa(ctx, { ...d, capa: f.img }, M + 70, y + 8, 84, 84, 14);
          linha(ctx, f.titulo, M + 180, y + 44, `700 40px ${TXT}`, tx, W - M - 180 - M);
          linha(ctx, f.artista, M + 180, y + 88, `600 32px ${TXT}`, fraco, W - M - 180 - M);
          y += 104;
        });
        const resto = d.n - Math.min(cabe, d.faixas.length);
        rotuloSonus(ctx, M, H - 84, tx);
        if (resto > 0) { ctx.textAlign = "right"; espaçado(ctx, `+${resto} MÚSICAS`, W - M, H - 96, 30, fraco); ctx.textAlign = "left"; }
      } },
  ];
  const CONJUNTOS = { letra: LETRA, artista: ARTISTA, album: ALBUM, playlist: PLAYLIST };
  // amostra de cor de cada variação (para o botão redondo)
  const amostra = v => v.etiqueta ? `linear-gradient(135deg, ${v.corpo} 0 60%, ${v.janela} 60%)`
    : Array.isArray(v.bg) ? `linear-gradient(135deg, ${v.bg[0]} 0 55%, ${v.bg[2]})` : v.bg && v.acc ? `linear-gradient(135deg, ${v.bg} 0 55%, ${v.acc} 55%)` : v.acc ? v.acc
    : v.ov ? v.ov : v.corpo ? (Array.isArray(v.corpo) ? v.corpo[0] : v.corpo) : "#888";

  // ---------- cores tiradas das imagens (artista, álbum, playlist) ----------
  // a imagem reduzida a 32×32; as cores mais frequentes, com peso para as vivas (fundo cinza não vira a cor do cartão)
  function coresDe(imgs) {
    const conta = new Map(), cv = document.createElement("canvas"); cv.width = cv.height = 32;
    const c = cv.getContext("2d", { willReadFrequently: true });
    imgs.filter(Boolean).forEach(img => {
      try {
        c.clearRect(0, 0, 32, 32); c.drawImage(img, 0, 0, 32, 32);
        const px = c.getImageData(0, 0, 32, 32).data;
        for (let i = 0; i < px.length; i += 4) {
          if (px[i + 3] < 200) continue;
          const r = px[i], g = px[i + 1], b = px[i + 2], mx = Math.max(r, g, b), mn = Math.min(r, g, b);
          if (mx < 28 || mn > 235) continue;                      // quase preto / quase branco
          const k = (r >> 4) << 8 | (g >> 4) << 4 | (b >> 4), sat = mx ? (mx - mn) / mx : 0;
          const e = conta.get(k) || { n: 0, r: 0, g: 0, b: 0 };
          e.n += .35 + sat * 1.6; e.r += r; e.g += g; e.b += b; e.q = (e.q || 0) + 1; conta.set(k, e);
        }
      } catch {}
    });
    return [...conta.values()].sort((a, b) => b.n - a.n).slice(0, 12)
      .map(e => `#${[e.r, e.g, e.b].map(v => Math.round(v / e.q).toString(16).padStart(2, "0")).join("")}`);
  }
  const carrega = url => url ? new Promise(ok => { const i = new Image(); i.onload = () => ok(i); i.onerror = () => ok(null); i.src = url; }) : Promise.resolve(null);

  // ---------- a folha ----------
  // criada na primeira vez, dentro da tela onde foi pedida (player ou páginas)
  const ROTULOS = { letra: "Compartilhar trecho da letra", artista: "Compartilhar artista", album: "Compartilhar álbum", playlist: "Compartilhar playlist" };
  function monta() {
    if ($("lcSheet")) return $("lcSheet");
    const el = document.createElement("div");
    el.className = "sheet lc"; el.id = "lcSheet"; el.setAttribute("role", "dialog");
    el.innerHTML = `<button class="lc-fechar" id="lcFechar" aria-label="Fechar"><svg class="lu" viewBox="0 0 24 24"><path d="M18 6 6 18M6 6l12 12"/></svg></button>
      <div class="lc-prev"><div class="lc-palco" id="lcPalco"><canvas id="lcPrevia" aria-label="Prévia da imagem"></canvas></div></div>
      <div class="lc-folha">
        <div class="lc-pega"></div>
        <div class="lc-rot">Modelo</div>
        <div class="lc-modelos" id="lcModelos"></div>
        <div class="lc-linha"><span class="lc-rot">Cor</span><div class="lc-cores" id="lcCores"></div></div>
        <div class="lc-acoes">
          <button class="lc-redondo" id="lcSalvar" aria-label="Salvar imagem"><svg class="lu" viewBox="0 0 24 24"><path d="M12 3v12M7 10l5 5 5-5M5 21h14"/></svg></button>
          <button class="lc-redondo" id="lcCopiar" aria-label="Copiar link"><svg class="lu" viewBox="0 0 24 24"><path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/></svg></button>
          <button class="lc-enviar" id="lcEnviar"><svg class="lu" viewBox="0 0 24 24"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="m8.6 13.5 6.8 4M15.4 6.5l-6.8 4"/></svg>Compartilhar</button>
        </div>
      </div>`;
    ($("phone") || document.body).appendChild(el);
    liga();
    return el;
  }

  let d = null, tipoAtual = "letra", modelo = "capa", cor = 0, previa = null;
  // o último modelo de cada tipo (a letra também lia o "sonus.letraModelo" antigo)
  let escolhas = {};
  try { escolhas = JSON.parse(localStorage.getItem("sonus.cartaoModelo") || "null") || {}; } catch {}
  try { if (!escolhas.letra) { const m = JSON.parse(localStorage.getItem("sonus.letraModelo") || "null"); if (m) escolhas.letra = m; } } catch {}
  const lembra = () => { escolhas[tipoAtual] = { modelo, cor }; try { localStorage.setItem("sonus.cartaoModelo", JSON.stringify(escolhas)); } catch {} };
  const modelos = () => CONJUNTOS[tipoAtual] || LETRA;
  const atual = () => modelos().find(m => m.id === modelo) || modelos()[0];

  function desenha(cv, m, v) {
    cv.classList.toggle("objeto", !!m.monta);
    if (m.monta) {
      const obj = m.monta(d, v), a = (m.giro || 0) * Math.PI / 180, mg = 70;
      const bw = Math.abs(obj.width * Math.cos(a)) + Math.abs(obj.height * Math.sin(a)), bh = Math.abs(obj.width * Math.sin(a)) + Math.abs(obj.height * Math.cos(a));
      cv.width = Math.ceil(bw + 2 * mg); cv.height = Math.ceil(bh + 2 * mg);
      const ctx = cv.getContext("2d");
      ctx.clearRect(0, 0, cv.width, cv.height);
      ctx.save(); ctx.translate(cv.width / 2, cv.height / 2); ctx.rotate(a);
      if (m.giro) { ctx.shadowColor = "rgba(0,0,0,.45)"; ctx.shadowBlur = 50; ctx.shadowOffsetY = 26; }
      ctx.drawImage(obj, -obj.width / 2, -obj.height / 2); ctx.restore();
      return;
    }
    cv.width = W; cv.height = H;
    const ctx = cv.getContext("2d"); ctx.textBaseline = "alphabetic";
    m.desenha(ctx, d, v);
  }
  const quieto = () => matchMedia("(prefers-reduced-motion: reduce)").matches;
  // como = "abre" (a imagem entra crescendo) | "modelo" (a anterior sai, a nova entra) | "cor" (só a cor muda: troca suave)
  function mostra(como = "cor") {
    const m = atual(), vs = m.cores(d.P); if (cor >= vs.length) cor = 0;
    let velha = null;
    if (como !== "abre" && previa.classList.contains("pronta") && !quieto()) {
      // a imagem de agora vira uma cópia por cima, que sai enquanto a nova entra
      velha = document.createElement("canvas"); velha.width = previa.width; velha.height = previa.height;
      velha.className = previa.className + " lc-velha"; velha.removeAttribute("id");
      velha.getContext("2d").drawImage(previa, 0, 0);
      $("lcPalco").appendChild(velha);
    }
    desenha(previa, m, vs[cor]);
    if (como === "abre") {
      $("lcPalco").querySelector(".lc-espera")?.classList.add("some");
      setTimeout(() => $("lcPalco").querySelector(".lc-espera")?.remove(), 260);
      requestAnimationFrame(() => requestAnimationFrame(() => previa.classList.add("pronta")));
    } else if (velha) {
      const modeloNovo = como === "modelo";
      velha.animate([{ opacity: 1, transform: "none" }, { opacity: 0, transform: modeloNovo ? "scale(.94) translateY(10px)" : "none" }],
        { duration: modeloNovo ? 240 : 260, easing: "ease-out", fill: "forwards" }).onfinish = () => velha.remove();
      previa.animate([{ opacity: 0, transform: modeloNovo ? "scale(1.04) translateY(-6px)" : "none" }, { opacity: 1, transform: "none" }],
        { duration: modeloNovo ? 380 : 260, easing: "cubic-bezier(.2,1,.3,1)" });
    }
    $("lcModelos").querySelectorAll("button").forEach(b => { const on = b.dataset.m === modelo; b.classList.toggle("on", on); b.setAttribute("aria-pressed", on); });
    const cores = $("lcCores"), mesmas = cores.childElementCount === vs.length && como === "cor";
    if (mesmas) cores.querySelectorAll("button").forEach((b, i) => { b.classList.toggle("on", i === cor); b.setAttribute("aria-pressed", i === cor); });
    else cores.innerHTML = vs.map((v, i) => `<button class="entra${i === cor ? " on" : ""}" style="--i:${i};background:${amostra(v)}" data-c="${i}" aria-label="${v.nome}" aria-pressed="${i === cor}"></button>`).join("");
  }
  /**
   * info.tipo: "letra" (padrão) | "artista" | "album" | "playlist"; titulo; link; e
   *  · letra: artista, linhas, ini, fim, dur, cores, capaUrl, id
   *  · artista: fotoUrl, musicas (nomes das mais tocadas)
   *  · album: artista, capaUrl, n (músicas)
   *  · playlist: faixas [{ titulo, artista, capaUrl }], n, dur (segundos)
   */
  let seq = 0;                                         // a folha reaberta rápido: a abertura antiga desiste
  async function abrir(info) {
    const meu = ++seq;
    tipoAtual = CONJUNTOS[info.tipo] ? info.tipo : "letra";
    const esc = escolhas[tipoAtual];
    modelo = esc && modelos().some(x => x.id === esc.modelo) ? esc.modelo : modelos()[0].id; cor = esc?.cor | 0;
    const el = monta();
    el.setAttribute("aria-label", ROTULOS[tipoAtual]);
    $("lcCopiar").setAttribute("aria-label", { letra: "Copiar link da música", artista: "Copiar link do artista", album: "Copiar link do álbum", playlist: "Copiar link da playlist" }[tipoAtual]);
    d = { ...info, titulo: info.titulo || "", artista: info.artista || "", P: paleta(info.cores), capa: null, capas: [], faixas: [], onda: onda(info.id || info.titulo) };
    previa = $("lcPrevia");
    previa.classList.remove("pronta");
    $("lcPalco").querySelectorAll(".lc-velha, .lc-espera").forEach(x => x.remove());
    $("lcPalco").insertAdjacentHTML("afterbegin", '<div class="lc-espera" aria-hidden="true"></div>');
    $("lcModelos").innerHTML = ""; $("lcCores").innerHTML = "";
    el.offsetWidth;                                     // a folha parte de baixo mesmo quando acabou de ser criada
    el.classList.add("open");
    // fontes do app e as imagens grandes antes de desenhar
    const fontes = [`800 40px ${TIT}`, `700 40px ${TIT}`, `600 40px ${TXT}`, `800 40px ${TXT}`, `italic 700 40px ${CUR}`];
    await Promise.all(fontes.map(f => document.fonts.load(f).catch(() => {})));
    if (meu !== seq) return;
    if (tipoAtual === "playlist") {
      const fx = (info.faixas || []).slice(0, 12);
      const imgs = await Promise.all(fx.map(f => carrega(f.capaUrl)));
      if (meu !== seq) return;
      d.faixas = fx.map((f, i) => ({ ...f, img: imgs[i] }));
      // capas diferentes para o leque e o mosaico (playlist com várias músicas do mesmo disco)
      const vistas = new Set(); d.capas = imgs.filter((im, i) => im && !vistas.has(fx[i].capaUrl) && vistas.add(fx[i].capaUrl)).slice(0, 4);
      d.capa = d.capas[0] || null;
    } else { const capa = await carrega(info.fotoUrl || info.capaUrl); if (meu !== seq) return; d.capa = capa; }
    if (!info.cores?.length) d.P = paleta(coresDe(tipoAtual === "playlist" ? d.capas : [d.capa]));
    if (!el.classList.contains("open")) return;
    $("lcModelos").innerHTML = modelos().map((m, i) => `<button class="entra" style="--i:${i}" data-m="${m.id}">${m.nome}</button>`).join("");
    mostra("abre");
    $("lcModelos").querySelector(".on")?.scrollIntoView({ inline: "center", block: "nearest" });
  }
  function fechar() { $("lcSheet")?.classList.remove("open"); }
  // ondinha própria de cada música (sempre a mesma para a mesma faixa)
  function onda(semente) {
    let h = 2166136261; for (const ch of String(semente)) h = Math.imul(h ^ ch.charCodeAt(0), 16777619);
    return Array.from({ length: 64 }, (_, i) => { h = Math.imul(h ^ (h >>> 13), 1274126177) >>> 0; const r = (h % 1000) / 1000; return .22 + .78 * (.5 + .5 * Math.sin(i * .55 + r * 2.4)) * (.55 + .45 * r); });
  }
  // a imagem que sai (Salvar e Compartilhar) é a da tela: em pé (9:16, o tamanho do Stories), o fundo com as cores da
  // música e o cartão no meio com os cantos arredondados e a sombra (ou o objeto solto, Ingresso e Fita). Mandar só o
  // cartão fazia o Instagram esticá-lo na tela inteira, de cantos retos.
  const SW = 1080, SH = 1920;
  function composta() {
    const cv = document.createElement("canvas"); cv.width = SW; cv.height = SH;
    const c = cv.getContext("2d");
    // fundo: a capa reduzida a poucos pontos e ampliada (fica desfocada sem filtro) + as cores escurecidas por cima
    c.fillStyle = grad(c, 0, 0, SW * .4, SH, [escuro(d.P.c1, .45), escuro(d.P.c2, .7), escuro(d.P.c3, .85)]); c.fillRect(0, 0, SW, SH);
    if (d.capa) {
      const mini = document.createElement("canvas"); mini.width = 9; mini.height = 16;
      const m = mini.getContext("2d"), im = d.capa, s0 = Math.max(9 / im.naturalWidth, 16 / im.naturalHeight);
      m.drawImage(im, (9 - im.naturalWidth * s0) / 2, (16 - im.naturalHeight * s0) / 2, im.naturalWidth * s0, im.naturalHeight * s0);
      c.save(); c.globalAlpha = .55; c.imageSmoothingQuality = "high"; c.drawImage(mini, 0, 0, SW, SH); c.restore();
    }
    const vela = c.createLinearGradient(0, 0, 0, SH); vela.addColorStop(0, "rgba(0,0,0,.25)"); vela.addColorStop(.5, "rgba(0,0,0,.1)"); vela.addColorStop(1, "rgba(0,0,0,.45)");
    c.fillStyle = vela; c.fillRect(0, 0, SW, SH);
    const objeto = !!atual().monta;
    const max = objeto ? [SW - 60, SH - 300] : [SW - 200, SH - 360];
    const k = Math.min(max[0] / previa.width, max[1] / previa.height), w = previa.width * k, h = previa.height * k, x = (SW - w) / 2, y = (SH - h) / 2;
    if (objeto) c.drawImage(previa, x, y, w, h);                    // o objeto já traz a própria sombra
    else {
      const r = 20 * (w / previa.clientWidth || 2.6);                // o mesmo arredondado da prévia, na escala da imagem
      c.save(); c.shadowColor = "rgba(0,0,0,.5)"; c.shadowBlur = 90; c.shadowOffsetY = 40;
      c.fillStyle = "#000"; rr(c, x, y, w, h, r); c.fill(); c.restore();
      c.save(); rr(c, x, y, w, h, r); c.clip(); c.drawImage(previa, x, y, w, h); c.restore();
    }
    return cv;
  }
  const tipo = () => "image/jpeg";
  const imagem = () => composta().toDataURL(tipo(), .93);
  const nome = () => `${d.titulo.replace(/\s*\/\s*/g, ", ")}${tipoAtual === "letra" ? " - trecho" : ""}`.replace(/[\\/:*?"<>|]/g, "");
  const texto = () => `${d.titulo}${d.artista && tipoAtual !== "artista" ? " · " + d.artista : ""}${d.link ? "\n" + d.link : ""}`;
  function ok(b, rotulo) {
    if (b.classList.contains("feito")) return;          // dois toques: o ícone original não se perde
    const antes = b.innerHTML; b.classList.add("feito"); b.setAttribute("aria-label", rotulo);
    b.innerHTML = '<svg class="lu" viewBox="0 0 24 24"><path d="m5 12.5 4.5 4.5L19 7.5"/></svg>';
    setTimeout(() => { b.classList.remove("feito"); b.innerHTML = antes; }, 1600);
  }

  // eventos (ligados quando a folha é criada)
  function liga() {
    $("lcModelos").addEventListener("click", e => { const b = e.target.closest("[data-m]"); if (!b) return; if (b.dataset.m === modelo) return; modelo = b.dataset.m; cor = 0; lembra(); mostra("modelo"); });
    $("lcCores").addEventListener("click", e => { const b = e.target.closest("[data-c]"); if (!b || +b.dataset.c === cor) return; cor = +b.dataset.c; lembra(); mostra("cor"); });
    $("lcFechar").onclick = fechar;
    $("lcSalvar").onclick = e => {
      const b = e.currentTarget;
      if (window.Sounos?.native) { if (Sounos.call("salvarImagem", { dados: imagem(), nome: nome(), tipo: tipo() }).ok) ok(b, "Salvo na galeria"); }
      else { const a = document.createElement("a"); a.href = imagem(); a.download = nome() + ".jpg"; a.click(); }
    };
    $("lcCopiar").onclick = e => {
      if (!d.link) return;
      if (window.Sounos?.native) Sounos.call("copiarTexto", { texto: d.link, rotulo: d.titulo }); else navigator.clipboard?.writeText(d.link);
      ok(e.currentTarget, "Link copiado");
    };
    $("lcEnviar").onclick = () => {
      if (window.Sounos?.native) return Sounos.call("compartilharImagem", { dados: imagem(), tipo: tipo(), titulo: d.titulo, texto: texto() });
      composta().toBlob(async blob => { try { await navigator.share({ files: [new File([blob], nome() + ".jpg", { type: tipo() })], text: texto() }); } catch {} }, tipo(), .93);
    };
  }
  // "voltar" do celular fecha a folha antes de tudo (o __sonusBack da tela é lido na hora: a home o define depois)
  const aberta = () => !!$("lcSheet")?.classList.contains("open");
  function prendeVoltar() {
    const antes = window.__sonusBack;
    if (antes?.__cartao) return;
    const novo = () => { if (aberta()) { fechar(); return true; } return antes ? antes() : false; };
    novo.__cartao = true; window.__sonusBack = novo;
  }
  prendeVoltar();
  addEventListener("load", prendeVoltar);

  return { abrir, fechar, aberta, CONJUNTOS };
})();
window.SonusLetraCartao = window.SonusCartao;      // nome antigo (player.js)
