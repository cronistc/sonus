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
  const usuarioAntigo = () => {
    const historico = Sounos.call("getHistory");
    let listas = [];
    try { listas = JSON.parse(localStorage.getItem("sounos.playlists") || "[]"); } catch {}
    if (!Array.isArray(listas)) listas = window.SounosData?.getPlaylists?.() || [];
    return (Array.isArray(historico) && historico.length > 0) || (Array.isArray(listas) && listas.length > 0);
  };

  const estadoSalvo = lerEstado();
  if (estadoSalvo?.versao === VERSAO && estadoSalvo.concluida) return;
  const antigo = usuarioAntigo();
  if (antigo && Object.values(permissao()).every(Boolean)) return;

  let tela, fechando = false;
  function atualizar() {
    if (!tela?.isConnected) return;
    const estados = permissao();
    for (const [id, concedida] of Object.entries(estados)) {
      const botao = tela.querySelector(`[data-permissao="${id}"]`);
      if (!botao) continue;
      botao.classList.toggle("concedida", concedida);
      botao.disabled = concedida || (botao.dataset.ocupado === "true");
      botao.innerHTML = concedida
        ? '<span class="bv-marca" aria-hidden="true">✓</span><span>Ativo</span>'
        : '<span>Permitir</span><span class="bv-seta" aria-hidden="true">›</span>';
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
    setTimeout(remover, 380);
  }

  function mostrar() {
    tela = document.createElement("section");
    tela.className = "bv-tela";
    tela.setAttribute("role", "dialog");
    tela.setAttribute("aria-modal", "true");
    tela.setAttribute("aria-labelledby", "bv-titulo");
    tela.innerHTML = `
      <div class="bv-brilho" aria-hidden="true"></div>
      <div class="bv-miolo">
        <div class="bv-simbolo" aria-hidden="true"><span>♪</span></div>
        <p class="bv-chamada">SONUS <span>·</span> CONFIGURAÇÃO RÁPIDA</p>
        <h1 id="bv-titulo">Seu som, sem pausas.</h1>
        <p class="bv-intro">Ative o que ajuda o Sonus a cuidar da sua música. Você pode pular e mudar isso depois.</p>
        <div class="bv-lista">
          <article class="bv-item">
            <span class="bv-icone bv-roxo" aria-hidden="true">↻</span>
            <span class="bv-textos"><strong>Atualizar sozinho</strong><small>Permite instalar atualizações do Sonus.</small></span>
            <button class="bv-permissao" type="button" data-permissao="instalar" aria-label="Permitir atualização automática"></button>
          </article>
          <article class="bv-item">
            <span class="bv-icone bv-rosa" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M18 9a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/></svg></span>
            <span class="bv-textos"><strong>Notificações</strong><small>Mostra os controles da música na notificação.</small></span>
            <button class="bv-permissao" type="button" data-permissao="notificacoes" aria-label="Permitir notificações"></button>
          </article>
          <article class="bv-item">
            <span class="bv-icone bv-ambar" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M12 3v2m0 14v2m9-9h-2M5 12H3m15.36-6.36-1.42 1.42M7.06 16.94l-1.42 1.42m12.72 0-1.42-1.42M7.06 7.06 5.64 5.64"/><circle cx="12" cy="12" r="4"/></svg></span>
            <span class="bv-textos"><strong>Tocar sem interrupções</strong><small>Ajuda a música a continuar em segundo plano.</small></span>
            <button class="bv-permissao" type="button" data-permissao="bateria" aria-label="Permitir reprodução sem interrupções"></button>
          </article>
        </div>
        <button class="bv-comecar" type="button">Começar <span aria-hidden="true">→</span></button>
        <p class="bv-rodape">As permissões ficam disponíveis em Configurações.</p>
      </div>`;
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

  window.__sonusBoasVindas = false;
  const esperaAbertura = setInterval(() => {
    if (window.__sonusAberturaFim === undefined || document.body.classList.contains("sonus-opening")) return;
    clearInterval(esperaAbertura);
    window.__sonusBoasVindas = true;
    mostrar();
  }, 120);
})();
