// Faz textos truncados andarem em CSS e só recalcula quando o conteúdo ou o espaço muda.
(() => {
  const seletor = [
    ".info h1", ".info p", ".mini .t", ".mini .a", ".blk .t", ".blk .a", ".ar .n",
    ".qi .qt", ".qi .qa", ".pg .tr .t", ".pg .tr .a", ".s-row .t", ".s-row .a",
    ".s-best .t", ".s-best .a", ".s-art .n", ".s-album .n", ".s-album .a",
    ".add-head .t", ".add-head .a", ".add-row .t", ".add-row .a"
  ].join(",");
  const estados = new WeakMap();
  const estilo = document.createElement("style");
  estilo.dataset.sounosMarquee = "";
  document.head.append(estilo);
  let proximo = 0;

  const redimensionados = new ResizeObserver(entradas => {
    for (const entrada of entradas) atualizar(entrada.target);
  });
  const visibilidade = "IntersectionObserver" in window
    ? new IntersectionObserver(entradas => {
      for (const entrada of entradas) {
        const estado = estados.get(entrada.target);
        if (!estado) continue;
        estado.visivel = entrada.isIntersecting && entrada.intersectionRatio > 0;
        entrada.target.classList.toggle("marquee-visivel", estado.visivel);
      }
    }) : null;

  function preparar(el) {
    let estado = estados.get(el);
    if (estado) return estado;
    estado = { visivel: !visibilidade, trilho: null, distancia: 0, nome: "" };
    estados.set(el, estado);
    redimensionados.observe(el);
    visibilidade?.observe(el);
    return estado;
  }

  function removerTrilho(el, estado) {
    const trilho = estado.trilho;
    if (!trilho || trilho.parentNode !== el) return;
    estado.trilho = null;
    estado.distancia = 0;
    el.classList.remove("marquee-ativo", "marquee-visivel");
    el.style.removeProperty("--marquee-duracao");
    trilho.replaceWith(...trilho.childNodes);
  }

  function atualizar(el) {
    if (!el.isConnected) return;
    const estado = preparar(el);
    const css = getComputedStyle(el);
    if (css.display === "none" || css.visibility === "hidden" || css.whiteSpace !== "nowrap" ||
        css.textOverflow !== "ellipsis" || el.clientWidth < 2 || el.scrollWidth <= el.clientWidth + 1) {
      removerTrilho(el, estado);
      return;
    }

    let trilho = estado.trilho;
    if (!trilho || trilho.parentNode !== el) {
      trilho = document.createElement("span");
      trilho.className = "marquee-trilho";
      while (el.firstChild) trilho.append(el.firstChild);
      el.append(trilho);
      estado.trilho = trilho;
      estado.distancia = 0;
      if (!estado.nome) estado.nome = `sonusMarquee${++proximo}`;
      if (indiceRegra(estado.nome) < 0) {
        estilo.sheet.insertRule(`@keyframes ${estado.nome} { from { transform: translateX(0); } to { transform: translateX(0); } }`);
      }
      el.classList.add("marquee-ativo");
      el.classList.toggle("marquee-visivel", estado.visivel);
    }

    const distancia = Math.max(0, el.scrollWidth - el.clientWidth);
    if (distancia < 2) {
      removerTrilho(el, estado);
      return;
    }
    if (Math.abs(distancia - estado.distancia) < 1) return;

    estado.distancia = distancia;
    const viagem = Math.max(2, distancia / 36);
    const ciclo = 4 + viagem * 2;
    const pausaInicio = 200 / ciclo;
    const fimIda = (2 + viagem) * 100 / ciclo;
    const fimPausa = (4 + viagem) * 100 / ciclo;
    const regra = indiceRegra(estado.nome);
    const quadro = `@keyframes ${estado.nome} { 0%, ${pausaInicio}% { transform: translateX(0); } ` +
      `${fimIda}%, ${fimPausa}% { transform: translateX(-${distancia}px); } 100% { transform: translateX(0); } }`;
    if (regra >= 0) estilo.sheet.deleteRule(regra);
    estilo.sheet.insertRule(quadro, regra >= 0 ? regra : estilo.sheet.cssRules.length);
    el.style.setProperty("--marquee-duracao", `${ciclo}s`);
    trilho.style.animationName = estado.nome;
  }

  function indiceRegra(nome) {
    for (let i = 0; i < estilo.sheet.cssRules.length; i++) {
      if (estilo.sheet.cssRules[i].name === nome) return i;
    }
    return -1;
  }

  function soltar(el) {
    const estado = estados.get(el);
    if (!estado) return;
    redimensionados.unobserve(el);
    visibilidade?.unobserve(el);
    const regra = indiceRegra(estado.nome);
    if (regra >= 0) estilo.sheet.deleteRule(regra);
    estados.delete(el);
  }

  function examinar(raiz) {
    if (raiz.nodeType !== Node.ELEMENT_NODE) raiz = raiz.parentElement;
    if (!raiz) return;
    if (raiz.matches?.(seletor)) atualizar(raiz);
    raiz.querySelectorAll?.(seletor).forEach(atualizar);
    const dono = raiz.closest?.(seletor);
    if (dono) atualizar(dono);
  }

  const mudancas = new MutationObserver(registros => {
    for (const registro of registros) {
      if (registro.type === "childList") {
        for (const removido of registro.removedNodes) {
          if (removido.nodeType !== Node.ELEMENT_NODE) continue;
          if (removido.matches?.(seletor)) soltar(removido);
          removido.querySelectorAll?.(seletor).forEach(soltar);
        }
      }
      examinar(registro.target);
    }
  });
  function iniciar() {
    examinar(document.body);
    mudancas.observe(document.body, { childList: true, characterData: true, attributes: true, subtree: true });
  }
  if (document.body) iniciar();
  else document.addEventListener("DOMContentLoaded", iniciar, { once: true });
})();
