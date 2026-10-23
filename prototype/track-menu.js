// Menu "⋮" das linhas de música: adicionar à playlist, baixar / remover download, ir para o artista.
// Uso: SounosTrackMenu.btn(faixa, { artist: false }) devolve o botão (HTML) para pôr na linha;
// o clique no botão abre o menu e não chega à linha (a música não toca).
window.SounosTrackMenu = (() => {
  const D = window.SounosData;
  const esc = s => String(s ?? "").replace(/[&<>"]/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[ch]));
  const I = {
    more: '<svg viewBox="0 0 24 24"><circle cx="12" cy="5.5" r="1.1"/><circle cx="12" cy="12" r="1.1"/><circle cx="12" cy="18.5" r="1.1"/></svg>',
    add: '<svg viewBox="0 0 24 24"><path d="M3 6h12M3 12h8M3 18h8"/><path d="M17 14v6M14 17h6"/></svg>',
    dl: '<svg viewBox="0 0 24 24"><path d="M12 4v11M7.5 10.5 12 15l4.5-4.5"/><path d="M5 19.5h14"/></svg>',
    rm: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M8 12h8"/></svg>',
    artist: '<svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="4"/><path d="M4.5 20c.9-3.6 3.8-5.5 7.5-5.5s6.6 1.9 7.5 5.5"/></svg>',
  };
  const reg = new Map();                      // id → faixa (o botão só leva o id)
  const wifiOnly = () => { try { return !!JSON.parse(localStorage.getItem("sounos.settings") || "{}").wifiOnlyDownloads; } catch { return false; } };
  const metered = () => { try { return !!Sounos.call("getNetwork").metered; } catch { return false; } };
  const isDown = id => { try { return !!(Sounos.call("getDownloads") || {})[id]; } catch { return false; } };

  function btn(t, o = {}) {
    if (!t?.id) return "";
    reg.set(t.id, { t, artist: o.artist !== false });
    return `<span class="tr-more" role="button" tabindex="0" aria-label="Mais opções" data-tm="${esc(t.id)}">${I.more}</span>`;
  }

  let ov, sheet, cur = null, isOpen = false, pushed = false;
  function build() {
    const phone = document.getElementById("phone") || document.body;
    ov = document.createElement("div");
    ov.className = "add-ov tm-ov";
    ov.innerHTML = `<div class="dim"></div><div class="add-sheet" role="dialog" aria-label="Opções da música">
      <div class="add-grab"><i></i></div><div class="add-head"></div><div class="tm-list"></div></div>`;
    phone.appendChild(ov);
    sheet = ov.querySelector(".add-sheet");
    ov.querySelector(".dim").addEventListener("click", () => close());
    ov.querySelector(".tm-list").addEventListener("click", e => {
      const b = e.target.closest("[data-act]"); if (!b || !cur) return;
      const { t } = cur, act = b.dataset.act;
      close();
      if (act === "add") setTimeout(() => window.__sounosAdd?.open(t), 120);
      else if (act === "dl") Sounos.call("download", { track: t });
      else if (act === "rm") Sounos.call("removeDownload", { id: t.id });
      else if (act === "artist") {
        window.__sonusArtistIntentAt = performance.now();
        setTimeout(() => window.SounosPages?.openArtist(Sounos.artistLine(t).split(",")[0].trim() || t.artist,
          { __intentAt: window.__sonusArtistIntentAt, url: t.artistUrl || "" }), 30);
      }
    });
    window.addEventListener("popstate", () => { if (isOpen && !history.state?.trackMenu) { pushed = false; close(true); } });
  }

  function open(id) {
    const c = reg.get(id); if (!c) return;
    if (!ov) build();
    cur = c; const t = c.t;
    const cv = `url('${Sounos.coverUrl(t).replace(/'/g, "%27")}') center/cover no-repeat, ${D.grad(Sounos.hashColors(t.id))}`;
    ov.querySelector(".add-head").innerHTML = `<div class="cv" style="background:${cv}"></div>
      <div><div class="t">${esc(t.title || "Música")}</div><div class="a">${esc(Sounos.artistLine(t))}</div></div>`;
    const done = isDown(t.id);
    const rows = [["add", I.add, "Adicionar à playlist"]];
    if (done) rows.push(["rm", I.rm, "Remover download"]);
    else if (!(wifiOnly() && metered())) rows.push(["dl", I.dl, "Baixar"]);   // "Baixar só no Wi-Fi" nos dados móveis: não oferece
    if (c.artist && (t.artist || t.artists?.length)) rows.push(["artist", I.artist, "Ir para o artista"]);
    ov.querySelector(".tm-list").innerHTML = rows.map(([a, ic, label], k) =>
      `<button class="add-row tm-row" data-act="${a}" style="--i:${k}">${ic}<span>${label}</span></button>`).join("");
    sheet.style.transform = "";
    ov.classList.remove("open"); void ov.offsetWidth;
    ov.classList.add("open"); isOpen = true;
    history.pushState({ ...(history.state || {}), trackMenu: true }, ""); pushed = true;
  }
  function close(fromPop = false) {
    if (!isOpen) return;
    isOpen = false; ov.classList.remove("open");
    document.querySelectorAll(".tr-more.tm-on").forEach(b => b.classList.remove("tm-on"));
    if (!fromPop && pushed) { pushed = false; history.back(); }
  }

  // o clique no ⋮ não chega à linha (que tocaria a música)
  document.addEventListener("click", e => {
    const b = e.target.closest?.(".tr-more"); if (!b) return;
    e.stopPropagation(); e.preventDefault();
    // a linha fica acesa enquanto o menu dela está aberto
    document.querySelectorAll(".tr-more.tm-on").forEach(x => x.classList.remove("tm-on"));
    b.classList.add("tm-on");
    open(b.dataset.tm);
  }, true);

  return { btn, open, close, isOpen: () => isOpen };
})();
