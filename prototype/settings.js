// Configurações do Sonus (protótipo). Salvas no aparelho; no app: DataStore.
// Uso: SounosSettings.attach(botaoDaEngrenagem, celular)  ·  SounosSettings.get()  ·  evento "sounos:settings"
(() => {
  const KEY = "sounos.settings";
  const DEFAULTS = {
    theme: "gradient",          // gradient | dark | light
    quality: "auto",            // auto | high | saver
    normalize: true,            // volume igual entre músicas
    crossfade: 0,               // segundos
    lyricsOffset: 0,            // ajuste fino da letra, em segundos (+ = letra adiantada)
    wifiOnlyDownloads: false,
    country: "BR", language: "pt-BR",
  };
  let s = { ...DEFAULTS };
  try { s = { ...DEFAULTS, ...JSON.parse(localStorage.getItem(KEY)) }; } catch {}
  const save = () => { try { localStorage.setItem(KEY, JSON.stringify(s)); } catch {} };
  const toApp = () => window.Sounos?.native && Sounos.call("setSettings", s);
  const emit = () => { toApp(); dispatchEvent(new CustomEvent("sounos:settings", { detail: { ...s } })); };
  toApp();

  // aplica o tema já no carregamento (antes de pintar)
  document.documentElement.dataset.theme = s.theme;
  function setTheme(v) {
    const root = document.documentElement;
    root.classList.add("theming");
    root.dataset.theme = v;
    clearTimeout(setTheme.t); setTheme.t = setTimeout(() => root.classList.remove("theming"), 550);
  }

  const ICON = {
    back: '<svg class="lu" viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6"/></svg>',
  };
  const COUNTRIES = [["BR", "Brasil"], ["PT", "Portugal"], ["US", "Estados Unidos"]];
  const LANGS = [["pt-BR", "Português (Brasil)"], ["pt-PT", "Português (Portugal)"], ["en", "English"]];
  const name = (list, v) => list.find(x => x[0] === v)?.[1] ?? v;

  function attach(button, phone) {
    const ov = document.createElement("div");
    ov.className = "set-ov";
    phone.appendChild(ov);
    let isOpen = false, pushed = false;

    const sw = (k) => `<button class="sw" role="switch" aria-checked="${s[k]}" data-sw="${k}"></button>`;
    const seg = (k, opts) => `<div class="seg" data-seg="${k}"><span class="knob"></span>${opts.map(([v, l]) =>
      `<button data-v="${v}" class="${s[k] === v ? "on" : ""}">${l}</button>`).join("")}</div>`;

    function render() {
      ov.innerHTML = `
        <div class="veil"></div>
        <div class="set-panel" role="dialog" aria-label="Configurações">
          <div class="set-head"><button class="close" aria-label="Voltar">${ICON.back}</button><h2>Configurações</h2></div>
          <div class="set-body">
            <section class="set-sec" style="--i:0"><h3>Aparência</h3><div class="set-card">
              <div class="themes">${[["gradient", "Gradiente"], ["dark", "Escuro"], ["light", "Claro"]].map(([v, l]) => `
                <button class="theme ${s.theme === v ? "on" : ""}" data-v="${v}"><div class="pv"><i class="c"></i><i class="l1"></i><i class="l2"></i></div><span>${l}</span></button>`).join("")}
              </div></div>
              <p class="set-foot" style="text-align:left;padding:8px 4px 0">No Gradiente, o fundo acompanha as cores da capa que está tocando.</p>
            </section>

            <section class="set-sec" style="--i:1"><h3>Reprodução</h3><div class="set-card">
              <div class="set-row stack"><div class="tx"><div class="t">Qualidade do áudio</div>
                <div class="d" data-desc="quality"></div></div>
                ${seg("quality", [["auto", "Automática"], ["high", "Alta"], ["saver", "Economia"]])}</div>
              <div class="set-row"><div class="tx"><div class="t">Volume equilibrado</div>
                <div class="d">Deixa todas as músicas no mesmo volume.</div></div>${sw("normalize")}</div>
              ${window.Sounos?.native ? "" : /* ainda não existe no player do app: não aparece lá */ `
              <div class="set-row stack"><div class="tx" style="display:flex;justify-content:space-between"><div class="t">Transição entre músicas</div>
                <div class="val" data-out="crossfade"></div></div>
                <input class="rng" type="range" min="0" max="8" step="1" value="${s.crossfade}" data-rng="crossfade" aria-label="Transição entre músicas"></div>`}
            </div></section>

            <section class="set-sec" style="--i:2"><h3>Armazenamento</h3><div class="set-card">
              <div class="set-row stack"><div class="tx" style="display:flex;justify-content:space-between;align-items:baseline">
                <div class="t">Usado pelo Sonus</div><div class="val" data-out="storage-total"></div></div>
                <div class="store-bar"><i class="dl" data-bar="dl"></i><i class="ch" data-bar="cache"></i></div>
                <div class="store-legend"><span><i class="dl"></i>Músicas baixadas</span><span><i class="ch"></i>Cache</span></div></div>
              <div class="set-row"><div class="tx"><div class="t">Músicas baixadas</div>
                <div class="d" data-out="downloads"></div></div><div class="val" data-out="downloads-size"></div></div>
              <div class="set-row"><div class="tx"><div class="t">Cache</div>
                <div class="d" data-cache>Trechos e capas de músicas que você só viu na busca. As das suas músicas ficam.</div></div>
                <button class="pill" data-act="clear-cache">Limpar</button></div>
              <div class="set-row"><div class="tx"><div class="t">Baixar só no Wi-Fi</div>
                <div class="d">As músicas baixadas ficam só dentro do app.</div></div>${sw("wifiOnlyDownloads")}</div>
            </div></section>

            <section class="set-sec" style="--i:3"><h3>Conteúdo</h3><div class="set-card">
              <button class="set-row" data-cycle="country"><div class="tx"><div class="t">País do conteúdo</div></div><div class="val">${name(COUNTRIES, s.country)}</div></button>
              <button class="set-row" data-cycle="language"><div class="tx"><div class="t">Idioma</div></div><div class="val">${name(LANGS, s.language)}</div></button>
            </div></section>

            <section class="set-sec" style="--i:4">
              <img src="logo.svg" alt="" style="display:block;margin:0 auto 10px;width:44px;height:44px;border-radius:12px">
              <p class="set-foot">Sonus 0.1<br>Músicas via NewPipe Extractor v0.26.5 · Licença GPLv3</p>
            </section>
          </div>
        </div>`;
      refresh();
    }

    // atualiza só os textos/indicadores (sem refazer a tela)
    function refresh() {
      const desc = { auto: "Ajusta sozinha: alta no Wi-Fi, mais leve nos dados móveis.", high: "Sempre a melhor (Opus ~160 kbps).", saver: "Gasta menos internet (~70 kbps)." };
      ov.querySelector('[data-desc="quality"]').textContent = desc[s.quality];
      const cf = ov.querySelector('[data-out="crossfade"]'); if (cf) cf.textContent = s.crossfade ? `${s.crossfade} s` : "Desligada";
      renderStorage();
      ov.querySelectorAll(".seg").forEach(placeKnob);
    }
    // Armazenamento (no protótipo os tamanhos são estimados; no app vêm dos arquivos reais)
    let cacheMB = 86;
    function renderStorage() {
      let n = Object.keys(window.SounosData?.getDownloads?.() || {}).length, dlMB = n * 3.6;
      if (window.Sounos?.native) {                       // números reais dos arquivos no celular
        const st = Sounos.call("getStorage");
        n = st.downloadsCount || 0; dlMB = (st.downloadsBytes || 0) / 1048576; cacheMB = (st.cacheBytes || 0) / 1048576;
      }
      const total = dlMB + cacheMB, fmt = mb => mb >= 1000 ? (mb / 1024).toFixed(1) + " GB" : Math.round(mb) + " MB";
      const q = sel => ov.querySelector(sel);
      if (!q('[data-out="downloads"]')) return;
      q('[data-out="downloads"]').textContent = n ? `${n} ${n === 1 ? "música" : "músicas"} · tocam sem internet` : "Nenhuma ainda. Toque em ↓ no player.";
      q('[data-out="downloads-size"]').textContent = fmt(dlMB);
      q('[data-out="storage-total"]').textContent = fmt(total);
      const scale = Math.max(total, 1);
      q('[data-bar="dl"]').style.width = (dlMB / scale * 100) + "%";
      q('[data-bar="cache"]').style.width = (cacheMB / scale * 100) + "%";
    }
    function placeKnob(segEl) {
      const on = segEl.querySelector("button.on"), k = segEl.querySelector(".knob");
      if (!on) return;
      k.style.left = on.offsetLeft + "px"; k.style.width = on.offsetWidth + "px";
    }
    function set(k, v) { s[k] = v; save(); emit(); refresh(); }

    ov.addEventListener("click", e => {
      if (e.target.closest(".close") || e.target.classList.contains("veil")) return close();
      const th = e.target.closest(".theme");
      if (th) {
        ov.querySelectorAll(".theme").forEach(b => b.classList.toggle("on", b === th));
        setTheme(th.dataset.v); return set("theme", th.dataset.v);
      }
      const swEl = e.target.closest("[data-sw]");
      if (swEl) { const k = swEl.dataset.sw; swEl.setAttribute("aria-checked", !s[k]); return set(k, !s[k]); }
      const sb = e.target.closest(".seg button");
      if (sb) { const g = sb.parentElement; g.querySelectorAll("button").forEach(b => b.classList.toggle("on", b === sb)); return set(g.dataset.seg, sb.dataset.v); }
      const cy = e.target.closest("[data-cycle]");
      if (cy) {
        const k = cy.dataset.cycle, list = k === "country" ? COUNTRIES : LANGS;
        const next = list[(list.findIndex(x => x[0] === s[k]) + 1) % list.length];
        cy.querySelector(".val").textContent = next[1]; return set(k, next[0]);
      }
      if (e.target.closest('[data-act="clear-cache"]')) {
        cacheMB = 0; if (window.Sounos?.native) Sounos.call("clearCache"); else renderStorage();
        setTimeout(renderStorage, 600);
        ov.querySelector("[data-cache]").textContent = "Limpo. As capas e músicas da sua biblioteca continuam aqui.";
      }
    });
    ov.addEventListener("input", e => { if (e.target.dataset.rng) set(e.target.dataset.rng, +e.target.value); });

    function open() {
      if (isOpen) return;
      render();
      void ov.offsetWidth;
      ov.classList.add("open"); isOpen = true;
      button.animate?.([{ transform: "rotate(0)" }, { transform: "rotate(90deg)" }], { duration: 480, easing: "cubic-bezier(.2,.85,.25,1)" });
      requestAnimationFrame(refresh);
      window.history.pushState({ settings: true }, ""); pushed = true;
    }
    function close(fromPop = false) {
      if (!isOpen) return;
      isOpen = false;
      ov.classList.remove("open");
      button.animate?.([{ transform: "rotate(90deg)" }, { transform: "rotate(0)" }], { duration: 480, easing: "cubic-bezier(.2,.85,.25,1)" });
      if (!fromPop && pushed) { pushed = false; window.history.back(); }
    }
    window.addEventListener("popstate", () => { if (isOpen && !history.state?.settings) { pushed = false; close(true); } });
    button.addEventListener("click", open);
    return { open, close, isOpen: () => isOpen };
  }

  window.SounosSettings = { attach, get: () => ({ ...s }) };
})();
