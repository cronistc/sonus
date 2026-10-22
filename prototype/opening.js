// A abertura só começa quando a Activity confirma que esta página é a inicialização fria.
(() => {
  const W = 512, H = 176, LOGO_SIZE = 150, DURATION = 1900;
  let layer, canvas, context, shapeCanvas, shapeContext, sceneCanvas, sceneContext;
  let sourceCanvas, onusCanvas, onusCenter, sOffset, from, to, color, target, targetPath, startAt, done = false;
  const clamp = v => Math.max(0, Math.min(1, v));
  const ease = t => 1 - Math.pow(1 - clamp(t), 3);

  // Distância aproximada em oito direções; duas passagens mantêm o preparo leve no celular.
  function distance(mask, inside) {
    const d = new Float32Array(mask.length), far = 1e5, diagonal = Math.SQRT2;
    for (let i = 0; i < mask.length; i++) d[i] = (mask[i] !== 0) === inside ? 0 : far;
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const i = y * W + x;
      if (x) d[i] = Math.min(d[i], d[i - 1] + 1);
      if (y) {
        d[i] = Math.min(d[i], d[i - W] + 1);
        if (x) d[i] = Math.min(d[i], d[i - W - 1] + diagonal);
        if (x + 1 < W) d[i] = Math.min(d[i], d[i - W + 1] + diagonal);
      }
    }
    for (let y = H - 1; y >= 0; y--) for (let x = W - 1; x >= 0; x--) {
      const i = y * W + x;
      if (x + 1 < W) d[i] = Math.min(d[i], d[i + 1] + 1);
      if (y + 1 < H) {
        d[i] = Math.min(d[i], d[i + W] + 1);
        if (x + 1 < W) d[i] = Math.min(d[i], d[i + W + 1] + diagonal);
        if (x) d[i] = Math.min(d[i], d[i + W - 1] + diagonal);
      }
    }
    return d;
  }

  function signedField(mask) {
    const toInside = distance(mask, true), toOutside = distance(mask, false);
    for (let i = 0; i < mask.length; i++) {
      toInside[i] = mask[i] ? -toOutside[i] : toInside[i];
    }
    return toInside;
  }

  function alphaMask(ctx) {
    const pixels = ctx.getImageData(0, 0, W, H).data, mask = new Uint8Array(W * H);
    for (let i = 0; i < mask.length; i++) mask[i] = pixels[i * 4 + 3] > 24 ? 1 : 0;
    return mask;
  }

  function prepare() {
    const logo = document.getElementById("brandLetter"), phone = document.getElementById("phone");
    if (!logo || !phone) return false;
    const wasHidden = logo.hidden;
    logo.hidden = false;
    const rect = logo.getBoundingClientRect();
    logo.hidden = wasHidden;
    target = { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2, size: rect.width || 28 };
    color = getComputedStyle(logo).color;

    const source = document.createElement("canvas"); source.width = W; source.height = H;
    sourceCanvas = source;
    const sourceContext = source.getContext("2d", { willReadFrequently: true });
    sourceContext.clearRect(0, 0, W, H);
    sourceContext.fillStyle = color;
    sourceContext.font = '700 112px "SonusFont", sans-serif';
    sourceContext.textAlign = "center"; sourceContext.textBaseline = "middle";
    sourceContext.fillText("Sonus", W / 2, H / 2 + 3);
    const sCanvas = document.createElement("canvas"); sCanvas.width = W; sCanvas.height = H;
    const sContext = sCanvas.getContext("2d");
    sContext.fillStyle = color; sContext.font = sourceContext.font;
    sContext.textAlign = "center"; sContext.textBaseline = "middle";
    sContext.fillText("S", W / 2, H / 2 + 3);
    from = signedField(alphaMask(sContext));
    const wordWidth = sourceContext.measureText("Sonus").width;
    const sWidth = sourceContext.measureText("S").width;
    const onusX = (W - wordWidth) / 2 + sWidth;
    const onusWidth = sourceContext.measureText("onus").width;
    onusCenter = onusX + onusWidth / 2;
    sOffset = (W - wordWidth) / 2 + sWidth / 2 - W / 2;
    onusCanvas = document.createElement("canvas"); onusCanvas.width = W; onusCanvas.height = H;
    const onusContext = onusCanvas.getContext("2d");
    onusContext.fillStyle = color; onusContext.font = sourceContext.font;
    onusContext.textAlign = "left"; onusContext.textBaseline = "middle";
    onusContext.fillText("onus", onusX, H / 2 + 3);

    const logoCanvas = document.createElement("canvas"); logoCanvas.width = W; logoCanvas.height = H;
    const logoContext = logoCanvas.getContext("2d");
    logoContext.fillStyle = color;
    logoContext.translate(W / 2, H / 2);
    logoContext.scale(LOGO_SIZE / 64, LOGO_SIZE / 64);
    logoContext.translate(-32, -32);
    targetPath = new Path2D(logo.querySelector("path").getAttribute("d"));
    logoContext.fill(targetPath);
    to = signedField(alphaMask(logoContext));

    shapeCanvas = document.createElement("canvas"); shapeCanvas.width = W; shapeCanvas.height = H;
    shapeContext = shapeCanvas.getContext("2d");
    sceneCanvas = document.createElement("canvas"); sceneCanvas.width = W; sceneCanvas.height = H;
    sceneContext = sceneCanvas.getContext("2d");
    layer = document.createElement("div"); layer.className = "sonus-opening-layer";
    layer.style.background = getComputedStyle(phone).background;
    canvas = document.createElement("canvas"); layer.appendChild(canvas);
    context = canvas.getContext("2d");
    document.body.classList.add("sonus-opening");
    document.body.appendChild(layer);
    resize();
    layer.addEventListener("pointerdown", e => {
      e.preventDefault(); e.stopPropagation();
      finish(true);
    }, { passive: false });
    return true;
  }

  function resize() {
    if (!canvas) return;
    const ratio = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.round(window.innerWidth * ratio);
    canvas.height = Math.round(window.innerHeight * ratio);
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
  }

  function drawShape(morph) {
    if (morph >= 1) {
      shapeContext.clearRect(0, 0, W, H);
      shapeContext.fillStyle = color;
      shapeContext.save();
      shapeContext.translate(W / 2, H / 2);
      shapeContext.scale(LOGO_SIZE / 64, LOGO_SIZE / 64);
      shapeContext.translate(-32, -32);
      shapeContext.fill(targetPath);
      shapeContext.restore();
      return;
    }
    const image = shapeContext.createImageData(W, H), pixels = image.data;
    const c = color.match(/[\d.]+/g)?.map(Number) || [245, 245, 247];
    for (let i = 0, p = 0; i < from.length; i++, p += 4) {
      const d = from[i] * (1 - morph) + to[i] * morph;
      const edge = clamp((1.15 - d) / 2.3);
      pixels[p] = c[0]; pixels[p + 1] = c[1]; pixels[p + 2] = c[2]; pixels[p + 3] = Math.round(edge * 255);
    }
    shapeContext.putImageData(image, 0, 0);
  }

  function paint(progress) {
    const morphTime = clamp((progress - .05) / .95);
    const morph = morphTime * morphTime * (3 - 2 * morphTime);
    const travel = ease(clamp((progress - .68) / .32));
    const scale = .66 + ((target.size / LOGO_SIZE) - .66) * travel;
    const cx = innerWidth / 2 + (target.x - innerWidth / 2) * travel;
    const cy = innerHeight / 2 + (target.y - innerHeight / 2) * travel;
    drawShape(morph);
    sceneContext.clearRect(0, 0, W, H);
    sceneContext.globalAlpha = 1 - progress;
    sceneContext.drawImage(sourceCanvas, 0, 0);
    const onusFade = .38 * 4 * progress * (1 - progress);
    if (onusFade > 0) {
      const onusScale = 1 - .72 * progress;
      const onusX = onusCenter + (W / 2 - onusCenter) * progress;
      sceneContext.save();
      sceneContext.globalAlpha = onusFade;
      sceneContext.translate(onusX, H / 2);
      sceneContext.scale(onusScale, onusScale);
      sceneContext.translate(-onusCenter, -H / 2);
      sceneContext.drawImage(onusCanvas, 0, 0);
      sceneContext.restore();
    }
    const sCenter = W / 2 + sOffset * (1 - morph);
    sceneContext.globalAlpha = 1;
    sceneContext.drawImage(shapeCanvas, sCenter - W / 2, 0);
    context.setTransform(1, 0, 0, 1, 0, 0);
    context.clearRect(0, 0, canvas.width, canvas.height);
    const ratio = Math.min(window.devicePixelRatio || 1, 2);
    context.setTransform(ratio * scale, 0, 0, ratio * scale, ratio * (cx - W * scale / 2), ratio * (cy - H * scale / 2));
    context.imageSmoothingEnabled = true;
    context.drawImage(sceneCanvas, 0, 0);
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
  }

  function finish(skip) {
    if (done) return;
    if (skip) paint(1);
    done = true;
    requestAnimationFrame(() => {
      document.body.classList.remove("sonus-opening");
      layer?.remove();
      layer = canvas = context = null;
    });
  }

  function frame(now) {
    if (done) return;
    const progress = clamp((now - startAt) / DURATION);
    paint(progress);
    if (progress >= 1) finish(false);
    else requestAnimationFrame(frame);
  }

  window.__sonusOpeningLaunch = async cold => {
    if (!cold || !window.Sounos?.native || done) {
      if (window.SounosNative) SounosNative.call("openingReady", "{}");
      return;
    }
    try {
      await document.fonts.load('700 112px "SonusFont"');
      if (!prepare()) throw new Error("faltam elementos da abertura");
      paint(0);
      // O primeiro quadro já está pronto antes de a Activity revelar a WebView.
      SounosNative.call("openingReady", "{}");
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
