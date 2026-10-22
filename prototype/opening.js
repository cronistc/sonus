// Abertura (só na inicialização fria):
// 1) "Sonus" caligráfico (o mesmo desenho do splash do Android) 2) as letras SE TRANSFORMAM, contorno a contorno,
// na clave de sol 3) a clave sobe sozinha até o cabeçalho 4) encaixa como uma joia e só então a tela inicial aparece.
// Desenho quadro a quadro em canvas; os contornos casados vêm de opening-pontos.js (tools/abertura/gera.py).
(() => {
  const CLAVE_ALTURA = 156;                         // altura da clave no meio da tela (px)
  // linha do tempo (ms)
  const MORPH_INI = 300, MORPH_DUR = 1250, ATRASO_LETRA = 0, PAUSA = 220, SUBIDA = 950, ENCAIXE = 0, REVELA = 700;
  const MORPH_FIM = MORPH_INI + MORPH_DUR;
  const SOBE_INI = MORPH_FIM + PAUSA, SOBE_FIM = SOBE_INI + SUBIDA, T = SOBE_FIM + ENCAIXE + REVELA;
  let desloc = 0, letrasOnus, layer, base, bg, canvas, ctx, off, octx, ratio, cor, fundo, alvo, clavePath, textoPath, D, pares, startAt, done = false;
  const clamp = v => Math.max(0, Math.min(1, v));
  const fase = (t, a, b) => clamp((t - a) / (b - a));
  const suave = t => t * t * (3 - 2 * t);
  const inOut = t => t < .5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
  const inOutQuint = t => t < .5 ? 16 * t ** 5 : 1 - Math.pow(-2 * t + 2, 5) / 2;

  function prepare() {
    D = window.__SONUS_ABERTURA;
    const logo = document.getElementById("brandLetter"), phone = document.getElementById("phone");
    if (!D || !logo || !phone) return false;
    const r = logo.getBoundingClientRect(), esc = (r.width || 28) / 64;
    // ponto de chegada = centro da clave desenhada no cabeçalho (não o centro da caixa do ícone)
    alvo = { x: r.left + D.ccx * esc, y: r.top + D.ccy * esc, escala: esc };
    cor = getComputedStyle(logo).color;
    fundo = getComputedStyle(phone).background;
    clavePath = new Path2D(logo.querySelector("path").getAttribute("d"));
    textoPath = new Path2D(D.texto);
    // "onus": cada letra como um desenho só (contorno + furo), para deslizar para dentro do S/clave e sumir
    letrasOnus = [1, 2, 3, 4].map(l => {
      const cs = D.pares.filter(p => p.l === l), path = new Path2D(); let sx = 0, sy = 0, n = 0;
      for (const c of cs) {
        path.moveTo(c.a[0], c.a[1]);
        for (let i = 2; i < c.a.length; i += 2) { path.lineTo(c.a[i], c.a[i + 1]); if (!c.f) { sx += c.a[i]; sy += c.a[i + 1]; n++; } }
        path.closePath();
      }
      return { path, cx: sx / n, cy: sy / n };
    });
    const s = CLAVE_ALTURA / D.ch;
    pares = D.pares.map(p => ({
      l: p.l, furo: !!p.f, cresce: !!p.g,
      a: p.a.length ? Float32Array.from(p.a) : null,
      b: Float32Array.from(p.b, v => v * s),
      o: p.o ? [p.o[0] * s, p.o[1] * s] : null,
    }));
    layer = document.createElement("div"); layer.className = "sonus-opening-layer";
    // começa na cor lisa do splash do Android e passa para o fundo do tema sem salto
    base = document.createElement("div"); base.className = "sonus-opening-bg";
    base.style.background = getComputedStyle(document.documentElement).getPropertyValue("--bg").trim() || "#0e0e10";
    bg = document.createElement("div"); bg.className = "sonus-opening-bg"; bg.style.background = fundo; bg.style.opacity = 0;
    canvas = document.createElement("canvas"); layer.append(base, bg, canvas);
    ctx = canvas.getContext("2d");
    document.body.classList.add("sonus-opening");
    document.body.appendChild(layer);
    ratio = Math.min(window.devicePixelRatio || 1, 3);
    canvas.width = Math.round(innerWidth * ratio); canvas.height = Math.round(innerHeight * ratio);
    off = document.createElement("canvas"); off.width = canvas.width; off.height = canvas.height;
    octx = off.getContext("2d");
    layer.addEventListener("pointerdown", e => { e.preventDefault(); e.stopPropagation(); finish(); }, { passive: false });
    return true;
  }

  // um contorno no meio da transformação (texto → clave), em coordenadas da tela
  function contorno(c, p, t, cx, cy) {
    const b = p.b, n = b.length / 2;
    if (p.cresce) {                                        // parte da clave que nasce de um ponto e cresce
      const k = suave(t), ox = p.o[0], oy = p.o[1];
      c.moveTo(cx + ox + (b[0] - ox) * k, cy + oy + (b[1] - oy) * k);
      for (let i = 1; i < n; i++) c.lineTo(cx + ox + (b[i * 2] - ox) * k, cy + oy + (b[i * 2 + 1] - oy) * k);
    } else {
      const a = p.a;
      c.moveTo(cx + a[0] + (b[0] - a[0]) * t, cy + a[1] + (b[1] - a[1]) * t);
      for (let i = 1; i < n; i++) c.lineTo(cx + a[i * 2] + (b[i * 2] - a[i * 2]) * t, cy + a[i * 2 + 1] + (b[i * 2 + 1] - a[i * 2 + 1]) * t);
    }
    c.closePath();
  }

  function paint(ms) {
    // centro alinhado com o splash do Android (que centra na tela inteira); vai se ajustando durante a morfose
    const cx = innerWidth / 2, cy = innerHeight / 2 + desloc * (1 - inOut(fase(ms, MORPH_INI, MORPH_FIM)));
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    ctx.fillStyle = cor;

    if (ms < MORPH_INI) {                                  // 1) a palavra, parada
      ctx.save(); ctx.translate(cx, cy); ctx.fill(textoPath); ctx.restore();
    } else if (ms < MORPH_FIM) {                           // 2) o S vira a clave; "onus" desliza para dentro dela
      const t = inOut(fase(ms, MORPH_INI, MORPH_FIM));
      octx.setTransform(1, 0, 0, 1, 0, 0);
      octx.clearRect(0, 0, off.width, off.height);
      octx.setTransform(ratio, 0, 0, ratio, 0, 0);
      octx.fillStyle = cor;
      octx.beginPath();
      for (const p of pares) if (!p.furo && (p.l === 0 || p.cresce)) contorno(octx, p, p.cresce ? fase(ms, MORPH_INI + MORPH_DUR * .45, MORPH_FIM) : t, cx, cy);
      octx.fill("nonzero");
      octx.globalCompositeOperation = "destination-out";
      octx.beginPath();
      for (const p of pares) if (p.furo && (p.l === 0 || p.cresce)) contorno(octx, p, fase(ms, MORPH_INI + MORPH_DUR * .4, MORPH_FIM), cx, cy);
      octx.fill("nonzero");
      octx.globalCompositeOperation = "source-over";
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.drawImage(off, 0, 0);
      ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
      const k = inOut(fase(ms, MORPH_INI, MORPH_INI + MORPH_DUR * .78));
      letrasOnus.forEach((L, i) => {
        const kk = clamp(k * (1 + i * .06));               // as mais distantes andam um pouco mais rápido
        ctx.save();
        ctx.globalAlpha = 1 - kk * kk;
        ctx.translate(cx + L.cx * (1 - kk), cy + L.cy * (1 - kk));
        ctx.scale(1 - .7 * kk, 1 - .7 * kk);
        ctx.translate(-L.cx, -L.cy);
        ctx.fill(L.path);
        ctx.restore();
      });
    } else {                                               // 3) a clave sobe sozinha e 4) encaixa no cabeçalho
      const s0 = CLAVE_ALTURA / D.ch;
      // subida num movimento só: começa macio e chega parada, exatamente onde a clave fica na tela inicial
      const u = fase(ms, SOBE_INI, SOBE_FIM);
      const w = u * u * u * (u * (u * 6 - 15) + 10);          // para exatamente no lugar, sem passar do ponto
      const s = s0 + (alvo.escala - s0) * w;
      const x = cx + (alvo.x - cx) * w, y = cy + (alvo.y - cy) * w;
      ctx.save(); ctx.translate(x, y); ctx.scale(s, s); ctx.translate(-D.ccx, -D.ccy);
      ctx.fill(clavePath); ctx.restore();
    }
    // fundo: entra o do tema logo no começo. Depois que a clave chegou, a tela inicial aparece num
    // esmaecimento radial que nasce da própria clave e se abre até as bordas
    bg.style.opacity = String(suave(fase(ms, 0, 420)));
    const r = fase(ms, SOBE_FIM, T);
    if (r > 0) {
      const raio = Math.hypot(innerWidth, innerHeight) * suave(r) * 1.1, borda = 140;
      const m = `radial-gradient(circle at ${alvo.x}px ${alvo.y}px, transparent ${Math.max(0, raio - borda)}px, #000 ${raio}px)`;
      base.style.maskImage = bg.style.maskImage = base.style.webkitMaskImage = bg.style.webkitMaskImage = m;
    }
  }

  function finish() {
    if (done) return;
    done = true;
    document.body.classList.remove("sonus-opening");
    const l = layer;
    l?.remove();                                           // a clave do cabeçalho já está no mesmo lugar: troca invisível
    layer = base = bg = canvas = ctx = off = octx = null;
  }

  function frame(now) {
    if (done) return;
    const ms = now - startAt;
    paint(Math.min(ms, T));
    if (ms >= T) finish();
    else requestAnimationFrame(frame);
  }

  window.__sonusOpeningLaunch = async (cold, dy = 0) => {
    desloc = +dy || 0;
    if (!cold || !window.Sounos?.native || done) {
      if (window.SounosNative) SounosNative.call("openingReady", "{}");
      return;
    }
    try {
      if (!prepare()) throw new Error("faltam elementos da abertura");
      paint(0);
      // só troca a palavra nativa pela WebView depois que o 1º quadro foi DE FATO para a tela (sem piscar)
      await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
      SounosNative.call("openingReady", "{}");
      await new Promise(r => setTimeout(r, 240));     // começa depois que a palavra nativa saiu
      startAt = performance.now();
      requestAnimationFrame(frame);
    } catch (e) {
      console.warn("Abertura animada indisponível", e);
      document.body.classList.remove("sonus-opening");
      layer?.remove();
      SounosNative.call("openingReady", "{}");
    }
  };
})();
