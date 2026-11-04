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
    compartilhar: '<svg viewBox="0 0 24 24"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="m8.6 13.5 6.8 4M15.4 6.5l-6.8 4"/></svg>',
    artist: '<svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="4"/><path d="M4.5 20c.9-3.6 3.8-5.5 7.5-5.5s6.6 1.9 7.5 5.5"/></svg>',
    disco: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="2.5"/></svg>',
    seguir: '<svg viewBox="0 0 24 24"><path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z"/></svg>',
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
  // ⋮ de artista (Seguir / Deixar de seguir, Ver artista) e de álbum (Ver álbum, Ir para o artista) — melhor resultado da busca
  // a mesma chave da página do artista (pages.js key), inclusive para nomes sem letras latinas
  const chaveArtista = n => { const f = String(n || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/ø/g, "o").replace(/æ/g, "ae").replace(/œ/g, "oe").replace(/ß/g, "ss").replace(/đ/g, "d").replace(/ł/g, "l").replace(/þ/g, "th");
    const a = f.replace(/[^a-z0-9]/g, ""); if (a) return a;
    const h = [...f].filter(c => /[\p{L}\p{N}]/u.test(c)).map(c => c.codePointAt(0).toString(16)).join("").slice(0, 80); return h ? "u" + h : ""; };
  function btnArtista(a) {
    if (!a?.name) return "";
    const id = "artist:" + chaveArtista(a.name);
    reg.set(id, { kind: "artista", a });
    return `<span class="tr-more" role="button" tabindex="0" aria-label="Mais opções" data-tm="${esc(id)}">${I.more}</span>`;
  }
  function btnAlbum(al) {
    if (!al?.name) return "";
    const id = "album:" + chaveArtista(al.name) + "|" + chaveArtista(al.artist);
    reg.set(id, { kind: "album", al });
    return `<span class="tr-more" role="button" tabindex="0" aria-label="Mais opções" data-tm="${esc(id)}">${I.more}</span>`;
  }
  const seguidos = () => { try { const v = JSON.parse(localStorage.getItem("sounos.following") || "[]"); return Array.isArray(v) ? v.filter(x => x && x.name && x.key) : []; } catch { return []; } };
  // mesma gravação da página do artista (localStorage + app + aviso às outras telas)
  function alternarSeguir(a) {
    const k = chaveArtista(a.name), list = seguidos(), i = list.findIndex(x => x.key === k);
    if (i >= 0) list.splice(i, 1);
    else list.unshift({ name: a.name, key: k, thumb: a.thumb || "", since: new Date().toISOString() });
    try { localStorage.setItem("sounos.following", JSON.stringify(list)); } catch {}
    Sounos.call("setFollowingArtists", { items: list });
    window.dispatchEvent(new CustomEvent("sounos:following", { detail: list }));
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
      if (cur.kind === "artista" || cur.kind === "album") {
        const { a, al } = cur;
        window.__sonusArtistIntentAt = performance.now();
        if (act === "seguir") alternarSeguir(a);
        else if (act === "ver-artista") setTimeout(() => window.SounosPages?.openArtist(a?.name || al.artist, { __intentAt: window.__sonusArtistIntentAt, url: a?.url || "" }), 30);
        else if (act === "ver-album") setTimeout(() => window.SounosPages?.openAlbum(al.url, al.name, al.artist, al.thumb), 30);
        return;
      }
      if (act === "add") setTimeout(() => window.__sounosAdd?.open(t), 120);
      else if (act === "dl") Sounos.call("download", { track: t });
      else if (act === "rm") Sounos.call("removeDownload", { id: t.id });
      else if (act === "compartilhar") setTimeout(() => window.SounosCompartilhar?.musica(t), 120);
      else if (act === "artist") {
        window.__sonusArtistIntentAt = performance.now();
        const nome = b.dataset.nome || t.artist, primeiro = nome === (t.artists?.[0] || t.artist);
        setTimeout(() => window.SounosPages?.openArtist(nome,
          { __intentAt: window.__sonusArtistIntentAt, url: primeiro ? t.artistUrl || "" : "" }), 30);
      }
    });
    window.addEventListener("popstate", () => { if (isOpen && !history.state?.trackMenu) { pushed = false; close(true); } });
  }

  function open(id) {
    const c = reg.get(id); if (!c) return;
    if (!ov) build();
    cur = c;
    if (c.kind === "artista" || c.kind === "album") return abrirOutro(c);
    const t = c.t;
    const cv = `url('${Sounos.coverUrl(t).replace(/'/g, "%27")}') center/cover no-repeat, ${D.grad(Sounos.hashColors(t.id))}`;
    ov.querySelector(".add-head").innerHTML = `<div class="cv" style="background:${cv}"></div>
      <div><div class="t">${esc(t.title || "Música")}</div><div class="a">${c.artist ? Sounos.artistHtml(t) : esc(Sounos.artistLine(t))}</div></div>`;   // na página do artista o nome dele não vira link
    const done = isDown(t.id);
    const rows = [["add", I.add, "Adicionar à playlist"]];
    if (done) rows.push(["rm", I.rm, "Remover download"]);
    else if (!(wifiOnly() && metered())) rows.push(["dl", I.dl, "Baixar"]);   // "Baixar só no Wi-Fi" nos dados móveis: não oferece
    rows.push(["compartilhar", I.compartilhar, "Compartilhar"]);
    // música com mais de um artista: um "Ir para …" para cada um (antes ia sempre para o primeiro)
    const nomes = (t.artists || []).map(x => String(x || "").trim()).filter(Boolean);
    if (c.artist && nomes.length > 1) nomes.slice(0, 4).forEach(n => rows.push(["artist", I.artist, `Ir para ${esc(n)}`, n]));
    else if (c.artist && (t.artist || nomes.length)) rows.push(["artist", I.artist, "Ir para o artista", nomes[0] || t.artist]);
    ov.querySelector(".tm-list").innerHTML = rows.map(([a, ic, label, nome], k) =>
      `<button class="add-row tm-row" data-act="${a}"${nome ? ` data-nome="${esc(nome)}"` : ""} style="--i:${k}">${ic}<span>${label}</span></button>`).join("");
    sheet.style.transform = "";
    ov.classList.remove("open"); void ov.offsetWidth;
    ov.classList.add("open"); isOpen = true;
    history.pushState({ ...(history.state || {}), trackMenu: true }, ""); pushed = true;
  }
  function abrirOutro(c) {
    const artista = c.kind === "artista", nome = artista ? c.a.name : c.al.name;
    const thumb = artista ? c.a.foto || c.a.thumb : c.al.foto || c.al.thumb;     // foto: endereço da rota (exibição)
    const cv = `${thumb ? `url('${String(thumb).replace(/'/g, "%27")}') center/cover no-repeat, ` : ""}${D.grad(Sounos.hashColors(nome))}`;
    ov.querySelector(".add-head").innerHTML = `<div class="cv${artista ? " round" : ""}" style="background:${cv}"></div>
      <div><div class="t">${esc(nome)}</div><div class="a">${artista ? "Artista" : "Álbum · " + esc(c.al.artist || "")}</div></div>`;
    const segue = artista && seguidos().some(x => x.key === chaveArtista(c.a.name));
    const rows = artista
      ? [["seguir", I.seguir, segue ? "Deixar de seguir" : "Seguir"], ["ver-artista", I.artist, "Ver artista"]]
      : [["ver-album", I.disco, "Ver álbum"], ...(c.al.artist ? [["ver-artista", I.artist, "Ir para o artista"]] : [])];
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

  return { btn, btnArtista, btnAlbum, open, close, isOpen: () => isOpen };
})();
