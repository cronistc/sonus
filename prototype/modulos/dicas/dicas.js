// Dicas contextuais: aparecem uma vez quando o recurso fica visível e não há gesto ativo.
(() => {
  const TIPS_KEY = "sounos.tips";
  const VERSION_KEY = "sounos.tipsVersion";
  // Uma dica por vez, só quando a pessoa abre a tela da dica (offer(tela)): a primeira da lista que ela ainda não viu.
  // As outras daquela tela ficam para as próximas vezes. Ordem = importância. `version` = versionCode em que a ferramenta
  // chegou (quem atualiza vê só as das versões novas; quem instala agora vê todas, aos poucos).
  // REGRA: toda versão nova traz as dicas das suas ferramentas novas (com o versionCode dela).
  const TIPS = [
    // tela inicial
    { id: "busca-basica", version: "0.1.2", screen: "home", selector: "#searchBtn", text: "Procure qualquer música, artista ou álbum aqui." },
    { id: "nova-playlist", version: "0.1.2", screen: "home", selector: "#playlists .blk.new", text: "Crie suas playlists aqui." },
    { id: "artistas-da-home", version: "0.1.2", screen: "home", selector: "#artists .ar", text: "Seus artistas ficam aqui. Toque para abrir o perfil." },
    { id: "minhas-musicas-offline", version: "0.1.3", screen: "offline", selector: "#recentTitle", text: "Sem internet, aqui ficam as músicas que tocam offline.", when: () => document.getElementById("offlineMark")?.hidden === false },
    // mini-player
    { id: "mini-abrir-player", version: "0.1.2", screen: "mini", selector: "#miniOpen", text: "Toque ou puxe para cima para abrir o player." },
    { id: "mini-adicionar-playlist", version: "0.1.3", screen: "mini", selector: "#miniAdd", text: "Guarde a música atual numa playlist." },
    // busca
    { id: "busca-albuns-artistas", version: "0.1.3", screen: "search-results", selector: ".search-ov.open .sbar", text: "A busca também traz álbuns e artistas.", when: () => !!document.querySelector(".search-ov.open .s-album, .search-ov.open .s-art") },
    // páginas
    { id: "pagina-aleatorio", version: 13, screen: "artist", selector: ".pg.artist.open .big[data-a=play]", text: "Arraste o play para baixo para trocar entre em ordem e aleatório." },
    { id: "compartilhar-artista", version: 13, screen: "artist", selector: ".pg.artist.open .compart", text: "Novo: compartilhe o artista como imagem. Quem tem o Sonus abre direto no app." },
    { id: "albuns-do-artista", version: "0.1.3", screen: "artist-albums", selector: ".pg.artist .albums .s-album", text: "Os álbuns do artista estão aqui." },
    { id: "compartilhar-album", version: 13, screen: "album", selector: ".pg.album.open .compart", text: "Novo: compartilhe o álbum como imagem ou link." },
    { id: "compartilhar-playlist", version: 13, screen: "playlist", selector: ".pg.playlist.open [data-a=compartilhar]", text: "Novo: mande sua playlist por link. Quem recebe salva uma cópia no Sonus." },
    // player
    { id: "player-toque-repetir", version: "0.1.2", screen: "player", selector: "#play", text: "Toque para tocar ou pausar. Segure para repetir." },
    { id: "player-letra", version: "0.1.2", screen: "player", selector: "#lyricsBtn", text: "Veja a letra. Toque numa linha para ir até ela." },
    { id: "player-aleatorio", version: "0.1.2", screen: "player", selector: "#play", text: "Deslize o play para cima ou para baixo para ligar o aleatório." },
    { id: "player-buscar-faixa", version: "0.1.2", screen: "player", selector: "#play", text: "Segure o play e arraste para os lados para avançar ou voltar." },
    { id: "player-fila", version: "0.1.2", screen: "player", selector: "#queueWrap", text: "Sua fila. Segure uma capa para mudar a ordem." },
    { id: "player-sugestoes", version: "0.1.2", screen: "suggestions", selector: "#queueWrap", text: "Repetindo uma música, aqui aparecem sugestões parecidas." },
    { id: "compartilhar-letra", version: 13, screen: "lyrics", selector: "#shareBtn", text: "Novo: escolha até 6 linhas e compartilhe como imagem." },
  ];

  const top = (() => { try { return window.top; } catch { return window; } })();
  const hub = top.__sounosTipsHub ||= { active: null, pending: [], queued: new Set(), nextAt: 0, lastTouch: 0, pointers: new Set(), timer: 0 };
  const TIP_DURATION = 30000;                     // some sozinha em 30 s (ou no ×, ou usando o próprio botão)
  const TIP_GAP = 30000;                         // depois de uma dica, nenhuma outra por meio minuto (nada em sequência)
  const TIP_DELAY = 900;                         // a tela aparece primeiro; a dica vem logo depois
  const EXIT_DURATION = 240;
  const parse = key => { try { return JSON.parse(localStorage.getItem(key)); } catch { return null; } };
  // Dicas comparam pelo versionCode (só sobe), nunca pelo nome: a contagem do nome reinicia a cada versão grande
  // (0.1.3 → 0.1.1.0 "Sunshine Beta 1.0"), e "0.1.2" antigo colidiria com "0.1.2.0" novo.
  // Marcas antigas (nome) → código publicado. Dicas NOVAS devem usar o número do versionCode em `version`.
  const LEGACY = { "0.0.0": 0, "0.1": 1, "0.1.0": 1, "0.1.1": 4, "0.1.2": 5, "0.1.3": 8 };
  const versionParts = value => String(value || "0").split(/[.-]/).map(part => Number.parseInt(part, 10) || 0);
  const toCode = v => {
    if (typeof v === "number" && Number.isFinite(v)) return v;
    const s = String(v ?? "");
    if (s in LEGACY) return LEGACY[s];
    // nome antigo fora da tabela (ex.: "0.1.2" − 1 = "0.1.1", "0.0.99"): encaixa pela ordem da contagem antiga
    const [a, b, c] = versionParts(s);
    if (a > 0 || b > 1) return 8;
    if (b < 1) return 0;
    return c >= 3 ? 8 : c === 2 ? 5 : 4;
  };
  const readVersion = () => {
    const update = window.Sounos?.call("getUpdate");
    return Number(update?.current?.versionCode) || 8;
  };
  const compareVersion = (a, b) => Math.sign(toCode(a) - toCode(b));
  const previousVersion = value => toCode(value) - 1;
  const hasOldLibrary = () => {
    const history = window.Sounos?.call("getHistory");
    const playlists = parse("sounos.playlists") || window.SounosData?.getPlaylists?.();
    const searches = parse("sounos.searchHistory");
    const downloads = window.Sounos?.call("getDownloads");
    return (Array.isArray(history) && history.length > 0) || (Array.isArray(playlists) && playlists.length > 0) ||
      (Array.isArray(searches) && searches.length > 0) || !!(downloads && Object.keys(downloads).length);
  };
  let state = hub.state || parse(TIPS_KEY);
  if (!state || typeof state !== "object" || Array.isArray(state)) state = { seen: {}, newcomer: !hasOldLibrary() };
  state.seen = state.seen && typeof state.seen === "object" ? state.seen : {};
  const savedVersion = parse(VERSION_KEY);
  if (savedVersion && savedVersion.version != null) state.version = toCode(savedVersion.version);   // aceita o formato antigo (nome)
  else if (state.version == null) state.version = state.newcomer ? 0 : previousVersion(readVersion());
  else state.version = toCode(state.version);
  hub.state = state;
  const currentVersion = readVersion();

  function save() {
    try {
      localStorage.setItem(TIPS_KEY, JSON.stringify({ seen: state.seen, newcomer: !!state.newcomer }));
      localStorage.setItem(VERSION_KEY, JSON.stringify({ version: state.version }));
    } catch {}
  }
  function eligible(tip) {
    return !state.seen[tip.id] && compareVersion(tip.version, currentVersion) <= 0 &&
      (state.newcomer || compareVersion(tip.version, state.version) > 0) && (!tip.when || tip.when());
  }
  function visible(el) {
    if (!el || !el.isConnected || !el.getClientRects().length) return false;
    const css = getComputedStyle(el), r = el.getBoundingClientRect();
    return css.display !== "none" && css.visibility !== "hidden" && Number(css.opacity) !== 0 && r.width > 0 && r.height > 0 &&
      r.right > 0 && r.bottom > 0 && r.left < innerWidth && r.top < innerHeight;
  }
  function contextActive(screen, target) {
    if (document.hidden || !visible(target)) return false;
    if (window === top && top.__playerOpen) return false;
    if (window !== top && top.__playerShell && !top.__playerOpen) return false;
    if (screen === "home" || screen === "mini" || screen === "offline") {
      if (document.querySelector(".search-ov.open, .set-ov.open, .add-ov.open, .upd.open, .sheet.open")) return false;
      if (document.querySelector("#phone.has-page")) return false;
    }
    if (screen === "search-results" && !document.querySelector(".search-ov.open")) return false;
    if ((screen === "album" || screen === "playlist") && !target.closest(".pg.open")) return false;
    if ((screen === "player" || screen === "suggestions" || screen === "lyrics") && document.querySelector(".add-ov.open, .sheet.open")) return false;
    if (screen.startsWith("artist") && !target.closest(".pg.artist.open")) return false;
    if (target.closest("#phone.player-hidden")) return false;
    return true;
  }
  function finishTip(tip, bubble) {
    const active = hub.active?.bubble === bubble ? hub.active : null;
    if (bubble.classList.contains("saindo")) return;
    if (active?.timeout) clearTimeout(active.timeout);
    bubble.classList.add("saindo");
    if (active) hub.active = null;
    bubble.querySelector(".sonus-dica-fechar")?.setAttribute("disabled", "");
    setTimeout(() => bubble.remove(), EXIT_DURATION);
    if (tip) {
      state.seen[tip.id] = true;
      const unfinished = TIPS.some(item => !state.seen[item.id] && compareVersion(item.version, currentVersion) <= 0 &&
        (state.newcomer || compareVersion(item.version, state.version) > 0));
      if (!unfinished) { state.version = currentVersion; state.newcomer = false; }
      save();
    }
    hub.nextAt = Date.now() + TIP_GAP;
    pump();
  }
  function runTipTimer(active) {
    if (hub.active !== active || active.paused) return;
    active.startedAt = Date.now();
    active.timeout = setTimeout(() => finishTip(active.tip, active.bubble), active.remaining);
  }
  function pauseTip(active) {
    if (hub.active !== active || active.paused) return;
    clearTimeout(active.timeout);
    active.timeout = 0;
    active.remaining = Math.max(0, active.remaining - (Date.now() - active.startedAt));
    active.paused = true;
  }
  function resumeTip(active) {
    if (hub.active !== active || !active.paused) return;
    active.paused = false;
    if (active.remaining <= 0) finishTip(active.tip, active.bubble);
    else runTipTimer(active);
  }
  function placeTip(entry, target) {
    const bubble = document.createElement("div");
    bubble.className = "sonus-dica";
    bubble.setAttribute("role", "status");
    bubble.setAttribute("aria-live", "polite");
    bubble.innerHTML = `<span class="sonus-dica-text"></span><button class="sonus-dica-fechar" type="button" aria-label="Fechar dica">×</button>`;
    bubble.querySelector(".sonus-dica-text").textContent = entry.tip.text;
    document.body.appendChild(bubble);
    // Posiciona o balão SEMPRE dentro da janela deste documento (na tela inicial ou no quadro do player).
    // Mede com offsetWidth/offsetHeight: getBoundingClientRect devolvia o tamanho já encolhido pelo
    // transform inicial (scale .68), então o balão era posicionado como se fosse menor e vazava da tela.
    const reposition = () => {
      const vw = Math.min(innerWidth, document.documentElement.clientWidth || innerWidth);
      const vh = Math.min(innerHeight, document.documentElement.clientHeight || innerHeight);
      const gap = 12, pad = 12;
      bubble.style.width = `${Math.max(0, Math.min(280, vw - 2 * pad))}px`;
      bubble.style.maxWidth = `${Math.max(0, vw - 2 * pad)}px`;
      const tr = target.getBoundingClientRect();
      const bw = bubble.offsetWidth, bh = bubble.offsetHeight;      // já com o texto e sem o efeito do transform
      // o balão nunca cobre o mini-player: ele é o limite de baixo (se não couber embaixo, abre em cima mesmo cobrindo um pedaço do conteúdo)
      const mini = document.querySelector(".mini.has-track"), mr = mini && mini.getClientRects().length ? mini.getBoundingClientRect() : null;
      const limite = mr && mr.top > tr.bottom ? Math.min(vh, mr.top - 6) : vh;
      const spaceBelow = limite - tr.bottom - gap - pad, spaceAbove = tr.top - gap - pad;
      const below = spaceBelow >= bh || (spaceAbove < bh && spaceBelow >= spaceAbove);   // cabe embaixo; senão em cima; senão onde há mais espaço
      const left = Math.max(pad, Math.min(vw - bw - pad, tr.left + tr.width / 2 - bw / 2));
      const rawTop = below ? tr.bottom + gap : tr.top - bh - gap;
      const topY = Math.max(pad, Math.min(vh - bh - pad, rawTop));
      bubble.dataset.lado = below ? "baixo" : "cima";
      // alvo do lado direito da tela: o × vai para a esquerda (o balão "vira" e nada vaza)
      bubble.classList.toggle("x-esquerda", tr.left + tr.width / 2 > vw * .55);
      bubble.style.left = `${left}px`;
      bubble.style.top = `${topY}px`;
      // a seta acompanha o alvo, mas fica dentro do corpo do balão (longe das quinas arredondadas)
      const seta = Math.max(20, Math.min(bw - 20, tr.left + tr.width / 2 - left));
      bubble.style.setProperty("--dica-seta", `${seta}px`);
      bubble.style.setProperty("--dica-origem-y", below ? "top" : "bottom");
      bubble.style.setProperty("--dica-salto", below ? "-9px" : "9px");
    };
    reposition();
    if (!entry.previa) state.seen[entry.tip.id] = true;       // previa (auditoria): mostra o balão sem marcar como vista
    const active = { tip: entry.previa ? null : entry.tip, bubble, target, remaining: TIP_DURATION, timeout: 0, paused: false, startedAt: 0,
      close: () => finishTip(entry.previa ? null : entry.tip, bubble) };
    hub.active = active;
    bubble.querySelector(".sonus-dica-fechar").addEventListener("click", e => {
      e.stopPropagation();
      active.close();
    });
    active.reposition = reposition;
    bubble.addEventListener("pointerdown", () => pauseTip(active));
    bubble.addEventListener("pointerup", () => resumeTip(active));
    bubble.addEventListener("pointercancel", () => resumeTip(active));
    requestAnimationFrame(() => {
      if (hub.active !== active) return;
      reposition();                       // remede (fonte/layout podem ter mudado desde a criação)
      bubble.classList.add("visivel");
      runTipTimer(active);
    });
    if (!entry.previa) save();
  }
  function schedulePump(delay) {
    if (hub.timer) return;
    hub.timer = setTimeout(() => { hub.timer = 0; pump(); }, Math.max(0, delay));
  }
  function pump() {
    if (hub.active || !hub.pending.length) return;
    if (top.__sonusBoasVindas || top.__sonusNovidades) { schedulePump(500); return; }
    // durante a abertura animada nenhuma dica aparece; elas começam um pouco depois dela terminar
    if (top.Sounos?.native && top.__sonusAberturaFim === undefined) { schedulePump(600); return; }   // abertura ainda não decidiu
    if (top.document.body.classList.contains("sonus-opening")) { schedulePump(600); return; }
    if (Date.now() < (top.__sonusAberturaFim || 0) + 1200) { schedulePump(top.__sonusAberturaFim + 1200 - Date.now()); return; }
    const wait = Math.max(hub.nextAt - Date.now(), hub.lastTouch + 650 - Date.now(), hub.pointers.size ? 250 : 0);
    if (wait > 0) return schedulePump(wait);
    const entry = hub.pending.shift();
    hub.queued.delete(entry.tip.id);
    const target = entry.doc.querySelector(entry.tip.selector);
    if (!entry.api.contextActive(entry.tip.screen, target)) {
      if (hub.pending.length) schedulePump(0);
      return;
    }
    entry.api.placeTip(entry, target);
  }
  // a tela abriu: no máximo UMA dica (a primeira da lista ainda não vista); se já há uma esperando ou na tela, nada
  function offer(screen) {
    if (hub.active || hub.pending.length || Date.now() < hub.nextAt) return;   // uma dica por vez, com intervalo
    const tip = TIPS.find(t => t.screen === screen && eligible(t) && visible(document.querySelector(t.selector)));
    if (!tip) return;
    hub.queued.add(tip.id);
    hub.pending.push({ tip, doc: document, api, screen });
    hub.nextAt = Math.max(hub.nextAt, Date.now() + TIP_DELAY);
    pump();
  }
  // Auditoria visual: mostra o balão de uma dica (por id) SEM marcar como vista nem gravar nada.
  // Uso (DevTools): SounosTips.previa("busca-basica"). O alvo precisa existir na página atual (senão usa o telefone).
  function previa(id) {
    const tip = TIPS.find(t => t.id === id);
    if (!tip) return "dica desconhecida: " + TIPS.map(t => t.id).join(", ");
    if (hub.active) hub.active.close();
    const target = document.querySelector(tip.selector);
    if (!target) return "alvo não existe nesta tela: " + tip.selector;
    placeTip({ tip, doc: document, api, screen: tip.screen, previa: true }, target);
    return "ok";
  }
  const api = { offer, contextActive, placeTip, previa };
  document.addEventListener("pointerdown", e => {
    hub.pointers.add(`${window === top ? "top" : "frame"}:${e.pointerId}`);
    hub.lastTouch = Date.now();
    // fecha só quando a pessoa usa o que a dica mostra (tocar fora não some com ela)
    if (hub.active && hub.active.target?.contains?.(e.target)) hub.active.close();
  }, true);
  const releasePointer = e => {
    hub.pointers.delete(`${window === top ? "top" : "frame"}:${e.pointerId}`);
    if (hub.pending.length) pump();
  };
  window.addEventListener("pointerup", releasePointer, true);
  window.addEventListener("pointercancel", releasePointer, true);
  // girou a tela / mudou o tamanho da janela: reencaixa o balão aberto
  window.addEventListener("resize", () => { try { if (hub.active?.bubble.ownerDocument === document) hub.active.reposition?.(); } catch {} });
  window.SounosTips = api;
  save();
})();
