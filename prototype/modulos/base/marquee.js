// Letreiro: texto que não cabe anda UMA vez até o fim, volta e fica parado.
// Roda ao abrir, quando uma rolagem para (só no que ficou visível), depois de um toque e quando a música muda.
// Nada de vigiar a tela o tempo todo (a versão anterior travava o app): só esses momentos.
(() => {
  const SEL = [".info h1", ".info p", ".mini .t", ".mini .a", ".blk .t", ".blk .a", ".ar .n", ".qi .qt", ".qi .qa",
    ".pg .tr .t", ".pg .tr .a", ".pg .ph .name", ".s-row .t", ".s-row .a", ".s-best .t", ".s-best .a", ".s-art .n",
    ".s-album .n", ".s-album .a"].join(",");
  const running = new WeakMap();

  function play(el) {
    if (running.get(el)) return;
    const r = el.getBoundingClientRect();
    if (!r.width || r.right < 0 || r.left > innerWidth || r.bottom < 0 || r.top > innerHeight) return;   // fora da tela
    const d = el.scrollWidth - el.clientWidth;
    if (d < 2) return;
    const inner = document.createElement("span"); inner.className = "mq";   // embrulha só enquanto anda
    while (el.firstChild) inner.append(el.firstChild);
    el.append(inner);
    const go = Math.max(1200, d * 28), wait = 1300, total = wait + go + wait + go * .6;
    el.classList.add("mq-on");
    const a = inner.animate([
      { transform: "translateX(0)", offset: 0 },
      { transform: "translateX(0)", offset: wait / total },
      { transform: `translateX(${-d}px)`, offset: (wait + go) / total },
      { transform: `translateX(${-d}px)`, offset: (wait + go + wait) / total },
      { transform: "translateX(0)", offset: 1 },
    ], { duration: total, easing: "ease-in-out" });
    running.set(el, a);
    // no fim: desembrulha o texto (os "…" voltam) com um leve esmaecer, sem pulo
    a.onfinish = a.oncancel = () => {
      running.delete(el);
      if (inner.parentNode === el) inner.replaceWith(...inner.childNodes);
      el.classList.remove("mq-on");
      el.animate([{ opacity: .35 }, { opacity: 1 }], { duration: 320, easing: "ease-out" });
    };
  }
  const scan = root => (root && root.querySelectorAll ? root : document).querySelectorAll(SEL).forEach(play);

  let t = 0;
  const later = (root, ms) => { clearTimeout(t); t = setTimeout(() => scan(root), ms); };
  addEventListener("load", () => later(document, 900));
  // rolagem parou: anima o que ficou visível naquela lista
  addEventListener("scroll", e => later(e.target, 250), true);
  addEventListener("pointerup", () => later(document, 700), true);
  // música mudou: o título novo anda uma vez
  let lastId;
  addEventListener("load", () => window.Sounos?.on?.("state", st => {
    const id = st?.current?.id; if (id && id !== lastId) { lastId = id; later(document, 800); }
  }));
})();
