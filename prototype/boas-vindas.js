// Configuração inicial de permissões: aparece no app depois da animação de abertura.
(() => {
  const CHAVE = "sounos.boasVindas";
  const VERSAO = 1;
  const phone = document.getElementById("phone");
  if (!phone || !window.Sounos?.native) return;

  const lerEstado = () => {
    try { return JSON.parse(localStorage.getItem(CHAVE) || "null"); }
    catch { return null; }
  };
  const salvarEstado = () => {
    const valor = JSON.stringify({ versao: VERSAO, concluida: true });
    try { localStorage.setItem(CHAVE, valor); } catch {}
    window.__sounosSave?.(CHAVE, valor);
  };
  const permissao = () => ({
    instalar: !!Sounos.call("getUpdate").canInstall,
    notificacoes: !!Sounos.call("getNotificationPermission").granted,
    bateria: !!Sounos.call("getBatteryOptimization").enabled,
  });
  // Quem ainda não passou por esta tela vê (instalação nova ou vindo da 0.1.2). Quem já passou, nunca mais,
  // mesmo atualizando — a não ser que a tela mude (aí sobe VERSAO).
  const estadoSalvo = lerEstado();
  if (estadoSalvo?.versao === VERSAO && estadoSalvo.concluida) return;
  window.__sonusBoasVindas = true;          // dicas e novidades já esperam desde agora
  window.__sonusBoasVindasFechar = () => { if (tela?.isConnected) fechar(); };   // botão voltar = Começar

  let tela, fechando = false;
  function atualizar() {
    if (!tela?.isConnected) return;
    const estados = permissao();
    const nomes = {
      notificacoes: "Notificações",
      bateria: "Tocar sem interrupções",
      instalar: "Atualizar sozinho",
    };
    for (const [id, concedida] of Object.entries(estados)) {
      const linha = tela.querySelector(`[data-permissao="${id}"]`);
      const chave = linha?.querySelector(".sw");
      if (!linha || !chave) continue;
      chave.setAttribute("aria-checked", String(concedida));
      linha.setAttribute("aria-label", `${nomes[id]}: ${concedida ? "ativa" : "permitir"}`);
      linha.disabled = concedida || (linha.dataset.ocupado === "true");
    }
  }

  function fechar() {
    if (fechando) return;
    fechando = true;
    salvarEstado();
    tela.classList.add("saindo");
    const remover = () => {
      tela?.remove();
      window.__sonusBoasVindas = false;
    };
    tela.addEventListener("transitionend", remover, { once: true });
    setTimeout(remover, 440);
  }

  function mostrar() {
    tela = document.createElement("section");
    tela.className = "bv-tela";
    tela.setAttribute("role", "dialog");
    tela.setAttribute("aria-modal", "true");
    tela.setAttribute("aria-labelledby", "bv-titulo");
    tela.innerHTML = `
      <header class="bv-head"><h1 id="bv-titulo">Antes de começar</h1></header>
      <main class="bv-body">
        <p class="bv-intro">Você pode mudar essas permissões depois em Configurações.</p>
        <section class="set-sec" aria-label="Permissões">
          <h3>Permissões</h3>
          <div class="set-card">
            <button class="set-row bv-row" type="button" data-permissao="notificacoes">
              <div class="tx"><div class="t">Notificações</div><div class="d">Mostra os controles da música na notificação.</div></div>
              <span class="sw" aria-hidden="true"></span>
            </button>
            <button class="set-row bv-row" type="button" data-permissao="bateria">
              <div class="tx"><div class="t">Tocar sem interrupções</div><div class="d">Ajuda a música a continuar em segundo plano.</div></div>
              <span class="sw" aria-hidden="true"></span>
            </button>
            <button class="set-row bv-row" type="button" data-permissao="instalar">
              <div class="tx"><div class="t">Atualizar sozinho</div><div class="d">Permite instalar atualizações do Sonus.</div></div>
              <span class="sw" aria-hidden="true"></span>
            </button>
          </div>
        </section>
      </main>
      <footer class="bv-foot"><button class="upd-btn primary bv-comecar" type="button"><span>Começar</span></button></footer>`;
    phone.appendChild(tela);
    atualizar();
    requestAnimationFrame(() => tela?.classList.add("visivel"));

    tela.addEventListener("click", e => {
      const botao = e.target.closest("[data-permissao]");
      if (botao && !botao.disabled) {
        botao.dataset.ocupado = "true";
        atualizar();
        const acao = {
          instalar: () => Sounos.call("allowInstalls"),
          notificacoes: () => Sounos.call("requestNotificationPermission"),
          bateria: () => Sounos.call("requestBatteryOptimization"),
        }[botao.dataset.permissao];
        acao?.();
        return;
      }
      if (e.target.closest(".bv-comecar")) fechar();
    });
  }

  window.addEventListener("sounos:resume", () => {
    if (!tela?.isConnected) return;
    tela.querySelectorAll("[data-permissao]").forEach(botao => { delete botao.dataset.ocupado; });
    atualizar();
  });
  window.addEventListener("visibilitychange", () => {
    if (!document.hidden) {
      tela?.querySelectorAll("[data-permissao]").forEach(botao => { delete botao.dataset.ocupado; });
      atualizar();
    }
  });

  const esperaAbertura = setInterval(() => {
    if (window.__sonusAberturaFim === undefined || document.body.classList.contains("sonus-opening")) return;
    clearInterval(esperaAbertura);
    mostrar();
  }, 120);
})();
