// Ligação da tela inicial com o app: estado do player, páginas (artista/playlist/álbum), botão voltar, offline
// e o player embutido. O que aparece na tela mora nos módulos: modulos/inicio (Início) e modulos/mini-player.
window.SonusApp = (() => {
  if (!Sounos.native) return null;
  const esc = s => String(s ?? "").replace(/[&<>"]/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[ch]));
  const thumbLayer = () => "";   // a capa vem só pela rota local (/cover), que já busca e guarda; nada de baixar duas vezes
  const bg = t => `url('${Sounos.coverUrl(t)}') center/cover no-repeat, ${thumbLayer(t)}linear-gradient(135deg,${Sounos.hashColors(t.id).join(",")})`;
  const artistKey = n => { const f = String(n || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/ø/g, "o").replace(/æ/g, "ae").replace(/œ/g, "oe").replace(/ß/g, "ss").replace(/đ/g, "d").replace(/ł/g, "l").replace(/þ/g, "th");
    const a = f.replace(/[^a-z0-9]/g, ""); if (a) return a;
    // nome sem letras latinas (아이유, 宇多田ヒカル): código das letras, senão todos viram o mesmo artista
    const h = [...f].filter(c => /[\p{L}\p{N}]/u.test(c)).map(c => c.codePointAt(0).toString(16)).join("").slice(0, 80); return h ? "u" + h : ""; };
  const app = {
    S: Sounos.call("getState"),
    offline: !!Sounos.call("getOffline").offline,
    esc, bg, artistKey,
    cur: () => app.S && app.S.current ? app.S.current : null,
  };
  const cur = app.cur;
  const pages = app.pages = SounosPages.attach($("phone"), { onChange: () => {
    // depois da animação da página (abrir/fechar): redesenhar no meio dela travava o deslize e a volta saía seca
    setTimeout(() => SonusInicio.render(), 380);
    setTimeout(() => { SounosTips?.offer("home"); if (app.offline) SounosTips?.offer("offline"); if (cur()) SounosTips?.offer("mini"); }, 350);
  } });
  // botão voltar do Android: fecha a tela interna mais alta antes de sair do player ou da página
  // (nunca depende só do histórico do navegador: se ele não tiver a entrada, o app fechava em vez de voltar)
  window.__sonusBack = () => {
    if (window.__sonusBoasVindas) { window.__sonusBoasVindasFechar?.(); return true; }
    // telas por cima (configurações, novidades/atualização, apoio, "adicionar à playlist"): fecham pelo histórico
    const st = history.state;
    if (st && (st.settings || st.upd || st.addSheet)) { history.back(); return true; }
    if (pages.isOpen()) { pages.closeTop(); return true; }
    if (window.__playerOpen) {
      if (window.__playerBack?.()) return true;            // o player fecha o que tiver dentro dele (letra, fila…)
      window.__playerShell?.close?.(); return true;        // senão, desce o player
    }
    if (window.homeSearch?.isOpen?.()) { window.homeSearch.close(); return true; }
    if (SonusInicio.modo) { SonusInicio.fecharVerTudo(); return true; }   // "Ver tudo" volta para a tela inicial
    return false;
  };
  Sounos.on("offline", ev => {
    const next = !!ev.offline;
    if (next === app.offline) return;
    app.offline = next;
    SonusInicio.render();
    if (app.offline) SounosTips?.offer("offline");
  });
  Sounos.on("library", () => SonusInicio.render());
  Sounos.on("download", ev => { if (ev.done || ev.error) SonusInicio.render(); });
  document.querySelectorAll(".row").forEach(row => row.addEventListener("scroll", () => SounosTips?.offer("home"), { passive: true }));
  SounosTips?.offer("home");
  if (app.offline) SounosTips?.offer("offline");

// ---------- player embutido ----------
// O player fica carregado num quadro por cima da tela inicial (escondido, parado). Abrir/fechar é só
// deslizar esse quadro: sem carregar página, sem montar nada na hora — por isso não engasga.
{
  const f = document.createElement("iframe");
  window.__playerOpen = false;
  function syncTheme() {
    try {
      const theme = JSON.parse(localStorage.getItem("sounos.settings"))?.theme;
      if (theme) $("phone").dataset.theme = theme;
    } catch {}
  }
  f.title = "Player";
  f.style.cssText = "position:fixed;left:0;top:0;width:100%;height:100%;border:0;z-index:60;background:#0e0e10;" +
                    "visibility:hidden;transform:translateY(100%)";
  let afterBack = null;
  let y = null, isOpen = false, pushed = false, anim = null, loaded = false, onLoad = null;
  const H = () => window.innerHeight;
  const win = () => f.contentWindow;
  window.__playerBack = () => win().__sonusBack?.() || false;
  function place(v) { y = v; f.style.transform = `translateY(${v}px)`; }
  function slide(to, ms, done) {
    const from = y ?? H();
    anim?.cancel();
    f.style.visibility = "visible";
    place(to);
    anim = f.animate([{ transform: `translateY(${from}px)` }, { transform: `translateY(${to}px)` }],
                     { duration: ms, easing: "cubic-bezier(.2,.85,.25,1)" });
    anim.onfinish = () => { anim = null; done?.(); };
  }
  const shell = window.__playerShell = {
    prepareQueue(list, index, context, shufflePrepared, shuffleMode) {
      if (!loaded) return false;
      return !!win().__preparePlayerQueue?.(list, index, context, shufflePrepared, shuffleMode);
    },
    open(opts = {}) {
      if (!cur()) return;
      if (!loaded) { onLoad = () => shell.open(opts); return; }
      if (!isOpen) {
        const d = history.state?.d || 0;
        if (opts.replace) { history.replaceState({ player: true, d }, ""); pushed = true; }
        else if (!pushed) { history.pushState({ player: true, d: d + 1 }, ""); pushed = true; }
      }
      isOpen = true; window.__playerOpen = true; $("phone").classList.add("player-open"); homeClockChanged();
      win().__playerShown?.();
      slide(0, 420);
    },
    // fecha e, depois que o "voltar" do histórico terminar, faz outra coisa (ex.: abrir a página do artista)
    closeThen(fn) { if (isOpen && pushed) { afterBack = fn; shell.close(); } else { shell.close(); fn(); } },
    lowerForPage() { if (isOpen) f.style.zIndex = "55"; },
    restoreAfterPage() {
      if (!isOpen) return;
      setTimeout(() => {
        if (!isOpen || pages.isOpen()) return;
        f.style.zIndex = $("phone").querySelector(".search-ov.open") ? "49" : "60";
      }, 330);
    },
    raiseAfterSearch() { if (isOpen && !pages.isOpen()) f.style.zIndex = "60"; },
    close(fromPop) {
      const wasOpen = isOpen;
      isOpen = false;
      // continua desenhado logo abaixo da tela (pronto para subir sem atraso), só que parado
      slide(H(), 340, () => { if (!isOpen) { win().__playerHidden?.(); y = null; window.__playerOpen = false; $("phone").classList.remove("player-open"); syncTheme(); homeClockChanged(); SounosTips?.offer("home"); if (app.offline) SounosTips?.offer("offline"); if (cur()) SounosTips?.offer("mini"); } });
      if (wasOpen && !fromPop && pushed) { pushed = false; history.back(); }
    },
    // arrastando o mini-player para cima: o player aparece por baixo do dedo
    peek(dy) { anim?.cancel(); anim = null; if (loaded) { f.style.visibility = "visible"; if (!isOpen) win().__playerShown?.(); if (!window.__playerOpen) { window.__playerOpen = true; $("phone").classList.add("player-open"); homeClockChanged(); } place(Math.max(0, H() - dy * 1.1)); } },
    // puxando o player para baixo
    drag(dy) { anim?.cancel(); anim = null; place(dy); },
    snap() { if (isOpen) slide(0, 300); },
  };
  openPlayer = () => shell.open();
  addEventListener("popstate", () => {
    if (isOpen && !history.state?.player) { pushed = false; shell.close(true); }
    const f = afterBack; afterBack = null; f?.();
  });
  f.addEventListener("load", () => { loaded = true; f.style.visibility = "visible"; const g = onLoad; onLoad = null; g?.(); });
  // carrega depois que a tela inicial já apareceu
  setTimeout(() => { f.src = "player.html"; document.body.appendChild(f); }, 350);
  // o "+" ou as configurações mudaram algo no player → a tela inicial acompanha
  addEventListener("storage", e => {
    if (e.key === "sounos.settings") { if (!window.__playerOpen) syncTheme(); }
    else SonusInicio.render();
  });
}
  return app;
})();
