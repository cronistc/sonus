// Clave do topo avisa atualização: brilha e pulsa em branco enquanto houver versão nova.
//  · de vez em quando (no máximo a cada 8 h) um balão diz que tem versão nova;
//  · tocar na clave: ela vai um pouco para a esquerda, o nome da versão sai de dentro dela e aparece "Atualizar agora";
//  · "Atualizar agora": a animação volta (o nome entra de novo na clave) e, enquanto baixa, clave e nome se alternam;
//    no fim o Sonus instala e abre de novo sozinho. Tocar fora fecha sem fazer nada.
(() => {
  if (!window.Sounos?.native) return;
  const clave = document.getElementById("brandLetter"), head = clave?.parentElement;
  if (!clave || !head) return;
  head.classList.add("cv-head");
  const txt = document.createElement("span"); txt.className = "cv-txt"; txt.setAttribute("aria-hidden", "true");
  const bubble = document.createElement("button"); bubble.className = "cv-bubble"; bubble.hidden = true;
  head.append(txt, bubble);

  const AVISO = "sonusClaveAviso", AVISO_MS = 8 * 3600 * 1000;
  let upd = Sounos.call("getUpdate") || {}, aberto = false, loop = 0;
  const tem = () => !!upd.update && ["available", "ready", "downloading", "installing", "error"].includes(upd.status);
  const baixando = () => ["downloading", "installing"].includes(upd.status);

  // o balão é um botão só: "Atualizar agora" (ou, no aviso de vez em quando, o nome da versão nova)
  function balao(html) {
    bubble.innerHTML = html;
    bubble.hidden = false; bubble.classList.remove("on"); void bubble.offsetWidth; bubble.classList.add("on");
  }
  function semBalao() { bubble.classList.remove("on"); setTimeout(() => { if (!bubble.classList.contains("on")) bubble.hidden = true; }, 260); }

  function abrir() {
    aberto = true;
    txt.innerHTML = SounosVersao.html(upd.update.versionName);
    // a clave anda metade da largura do texto para a esquerda; o texto sai de trás dela
    const w = txt.offsetWidth, gap = 8, cw = clave.getBoundingClientRect().width;   // svg não tem offsetWidth
    // conta: o par [clave][texto] fica centrado no topo
    head.style.setProperty("--cv-shift", `${-(w + gap) / 2}px`);
    head.style.setProperty("--cv-txt-x", `${(cw + gap) / 2}px`);
    head.classList.add("cv-open");
    balao("Atualizar agora");
  }
  function fechar() { aberto = false; head.classList.remove("cv-open"); semBalao(); }

  function render() {
    const on = tem();
    clave.classList.toggle("cv-glow", on && !baixando());
    clave.style.cursor = on ? "pointer" : "";
    if (baixando() && !loop) {
      fechar();
      txt.innerHTML = SounosVersao.html(upd.update.versionName);
      // enquanto baixa: clave ↔ nome da versão, um de cada vez
      setTimeout(() => { if (baixando()) { head.classList.add("cv-loop"); loop = setInterval(() => head.classList.toggle("cv-alt"), 1700); } }, 520);
    }
    if (!baixando() && loop) { clearInterval(loop); loop = 0; head.classList.remove("cv-loop", "cv-alt"); }
    if (!on && aberto) fechar();
  }

  clave.addEventListener("click", e => {
    if (!tem() || baixando()) return;
    e.stopPropagation();
    aberto ? fechar() : abrir();
  });
  bubble.addEventListener("click", e => {
    e.stopPropagation();
    // aviso de vez em quando: tocar abre a clave (nome da versão + Atualizar agora)
    if (!aberto) { semBalao(); return setTimeout(abrir, 200); }
    semBalao(); aberto = false; head.classList.remove("cv-open");
    Sounos.call("installUpdate");
  });
  document.addEventListener("click", () => { if (aberto) fechar(); else if (!bubble.hidden && !head.classList.contains("cv-open")) semBalao(); });
  Sounos.on("update", st => { upd = st || {}; render(); avisoDeVezEmQuando(); });

  // balão de vez em quando: com a tela inicial livre (abertura, boas-vindas e player fora do caminho)
  function avisoDeVezEmQuando() {
    if (!tem() || baixando()) return;
    let ultimo = 0; try { ultimo = +localStorage.getItem(AVISO) || 0; } catch {}
    if (Date.now() - ultimo < AVISO_MS) return;
    try { localStorage.setItem(AVISO, String(Date.now())); } catch {}
    const livre = () => window.__sonusAberturaFim !== undefined && !document.body.classList.contains("sonus-opening")
      && !window.__sonusBoasVindas && !window.__playerOpen;
    const tenta = () => {
      if (!livre()) return setTimeout(tenta, 800);
      setTimeout(() => {
        if (aberto || !tem()) return;
        balao(`Versão nova: ${SounosVersao.html(upd.update.versionName)}`);
        setTimeout(() => { if (!aberto) semBalao(); }, 5000);
      }, 1500);
    };
    tenta();
  }
  render(); avisoDeVezEmQuando();
})();
