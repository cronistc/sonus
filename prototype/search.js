// Busca do Sonus (protótipo) — compartilhada entre a tela inicial e o player.
// Uso: SounosSearch.attach(botaoDaLupa, elementoDoCelular)
//
// Como o ranking funciona (versão local do motor descrito no DESIGN.md, seção 5):
//   pontuação = quanto o texto bate (prefixo > contém > com erro de digitação)
//             + afinidade da pessoa (artistas seguidos, ouvidas recentemente, playlists dela)
// O "corretor" é o mesmo cálculo: com tolerância a erros, "neom asul" ainda encontra "Neon Azul",
// e entre vários candidatos parecidos ganha o que tem mais a ver com o gosto da pessoa.
(() => {
  // ---------- Catálogo de exemplo (o mesmo das outras telas) ----------
  const D = window.SounosData;
  const NATIVE = !!window.Sounos?.native;
  const { tracks, artists, followed, recentPlays } = D;
  const loadPlaylists = () => D.getPlaylists().map((p, i) => ({ ...p, i }));
  let playlists = loadPlaylists();
  const KEY = "sounos.searchHistory";
  const norm = s => String(s ?? "").normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().replace(/[^a-z0-9 ]/g, " ").replace(/\s+/g, " ").trim();
  const subscribers = new Set();
  function readHistory() {
    try { const v = JSON.parse(localStorage.getItem(KEY)); if (Array.isArray(v)) return v; } catch {}
    return [];
  }
  function sameEntry(a, b) {
    if (a.type !== b.type) return false;
    if (a.type === "q") return norm(a.text) === norm(b.text);
    if (a.type === "t") {
      const ref = x => x.track?.id ?? x.track?.i ?? x.i ?? `${norm(x.track?.title)}|${norm(x.track?.artist)}`;
      return ref(a) === ref(b);
    }
    if (a.type === "a") return norm(a.name) === norm(b.name);
    if (a.type === "p") {
      const id = x => x.id || (Number.isInteger(x.i) ? D.getPlaylists()[x.i]?.id : "");
      const left = id(a), right = id(b);
      return left && right ? left === right : norm(a.name) === norm(b.name);
    }
    if (a.type === "album") return (a.id || a.url || norm(a.name)) === (b.id || b.url || norm(b.name));
    return norm(a.id || a.name || a.text) === norm(b.id || b.name || b.text);
  }
  function writeHistory(history) {
    const saved = history.slice(0, 15), value = JSON.stringify(saved);
    try { localStorage.setItem(KEY, value); } catch {}
    if (NATIVE) try { window.__sounosSave?.(KEY, value); } catch {}
    subscribers.forEach(fn => fn(saved));
    return saved;
  }
  function rememberEntry(entry) {
    const history = readHistory();
    return writeHistory([entry, ...history.filter(h => !sameEntry(h, entry))]);
  }

  const artistAff = {}, trackAff = {};
  followed.forEach(a => artistAff[a] = (artistAff[a] || 0) + 1);
  recentPlays.forEach((t, k) => {
    const w = 1 - k / (recentPlays.length * 1.5);          // mais recente pesa mais
    trackAff[t] = (trackAff[t] || 0) + w;
    artistAff[tracks[t].artist] = (artistAff[tracks[t].artist] || 0) + w * .5;
  });
  playlists.forEach(p => p.tracks.forEach(t => trackAff[t] = (trackAff[t] || 0) + .2));
  const maxA = Math.max(...Object.values(artistAff)), maxT = Math.max(...Object.values(trackAff));
  const affArtist = a => (artistAff[a] || 0) / maxA;
  const affTrack = t => .6 * (trackAff[t.i] || 0) / maxT + .4 * affArtist(t.artist);

  // ---------- Texto: normalização e comparação tolerante a erros ----------
  // mesma regra das outras telas: minúsculo, sem acento, espaço vira "_", o resto sai
  const toHandle = name => String(name ?? "").trim();  // nome do artista como vem (sem @)
  const artistLine = t => toHandle(Sounos.artistLine?.(t) || t?.artist || "");
  function lev(a, b, max) {                                 // distância de edição (com transposição)
    if (Math.abs(a.length - b.length) > max) return max + 1;
    const d = Array.from({ length: a.length + 1 }, (_, i) => [i, ...Array(b.length).fill(0)]);
    for (let j = 1; j <= b.length; j++) d[0][j] = j;
    for (let i = 1; i <= a.length; i++) for (let j = 1; j <= b.length; j++) {
      d[i][j] = Math.min(d[i-1][j] + 1, d[i][j-1] + 1, d[i-1][j-1] + (a[i-1] === b[j-1] ? 0 : 1));
      if (i > 1 && j > 1 && a[i-1] === b[j-2] && a[i-2] === b[j-1]) d[i][j] = Math.min(d[i][j], d[i-2][j-2] + 1);
    }
    return d[a.length][b.length];
  }
  const tolerance = n => n >= 7 ? 2 : n >= 3 ? 1 : 0;
  // 0..1: quanto a busca bate com o texto do candidato
  function textScore(query, text) {
    const q = norm(query), t = norm(text);
    if (!q) return 0;
    if (t === q) return 1;
    if (t.startsWith(q)) return .95;
    const qt = q.split(" "), tt = t.split(" ");
    let sum = 0;
    for (let k = 0; k < qt.length; k++) {
      const w = qt[k], last = k === qt.length - 1, tol = tolerance(w.length);
      let best = 0;
      for (const c of tt) {
        let s = 0;
        if (c === w) s = 1;
        else if (last && c.startsWith(w)) s = .9;                         // ainda digitando a palavra
        else if (c.includes(w) && w.length >= 3) s = .6;
        else if (tol && lev(w, c, tol) <= tol) s = .7;                    // erro de digitação
        else if (last && tol && lev(w, c.slice(0, w.length), tol) <= tol) s = .6;
        best = Math.max(best, s);
      }
      if (!best) return 0;                                                // toda palavra precisa bater
      sum += best;
    }
    return .9 * sum / qt.length;
  }

  // ---------- Motor: sugestões e resultados ----------
  const W_TEXT = 1, W_TASTE = .35;
  function candidates(q) {
    const out = [];
    tracks.forEach(t => {
      const s = Math.max(textScore(q, t.title), .85 * textScore(q, t.title + " " + t.artist));
      if (s) out.push({ type: "t", item: t, label: t.title, sub: artistLine(t), text: s, score: W_TEXT * s + W_TASTE * affTrack(t) });
    });
    artists.forEach(a => {
      const s = textScore(q, a.name);
      if (s) out.push({ type: "a", item: a, label: toHandle(a.name), sub: "Artista", text: s, score: W_TEXT * s + W_TASTE * affArtist(a.name) + .03 });
    });
    playlists.forEach(p => {
      const s = textScore(q, p.name);
      if (s) out.push({ type: "p", item: p, label: p.name, sub: "Sua playlist", text: s, score: W_TEXT * s + W_TASTE * .6 });
    });
    return out.sort((a, b) => b.score - a.score);
  }

  // ---------- Histórico de pesquisas (no app: localStorage espelhado; no navegador: localStorage) ----------

  // ---------- Interface ----------
  const ICON = {
    back:   '<svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6"/></svg>',
    search: '<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>',
    x:      '<svg viewBox="0 0 24 24"><path d="M18 6 6 18M6 6l12 12"/></svg>',
    clock:  '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>',
    fill:   '<svg viewBox="0 0 24 24"><path d="M7 17 17 7M8 7h9v9"/></svg>',
  };
  const grad = c => `linear-gradient(135deg,${c[0]},${c[1]} 60%,${c[2]})`;
  const esc = s => s.replace(/[&<>"]/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[ch]));
  // destaca em negrito o que a pessoa ainda não digitou (como nos buscadores)
  function emphasize(label, q) {
    const qq = norm(q);
    if (label.startsWith("@")) {
      const qh = qq.replace(/ /g, "_");
      return qh && label.slice(1).startsWith(qh)
        ? `<span class="typed">${esc(label.slice(0, qh.length + 1))}</span><b>${esc(label.slice(qh.length + 1))}</b>` : `<b>${esc(label)}</b>`;
    }
    const n = norm(label);
    if (qq && n.startsWith(qq)) return `<span class="typed">${esc(label.slice(0, qq.length))}</span><b>${esc(label.slice(qq.length))}</b>`;
    return `<b>${esc(label)}</b>`;
  }
  function cover(type, item, extra = "") {
    if (type === "t") return `<div class="ic ${extra}" style="background:${grad(item.c)}">${esc(item.title[0])}</div>`;
    if (type === "a") return `<div class="ic round ${extra}" style="background:${grad(item.c)}">${esc(item.name[0])}</div>`;
    if (type === "p") {
      if (item.cover) return `<div class="ic ${extra}" style="background:url('${item.cover.replace(/'/g, "%27")}') center/cover no-repeat"></div>`;
      const first = item.tracks?.[0], song = typeof first === "object" ? first : tracks[first];
      return `<div class="ic ${extra}" style="background:${song?.c ? grad(song.c) : "var(--s-surface)"}">${esc(item.name?.[0] || "♪")}</div>`;
    }
    return `<div class="ic ${extra}">${ICON.clock}</div>`;
  }

  function attach(button, phone) {
    const ov = document.createElement("div");
    ov.className = "search-ov";
    ov.innerHTML = `
      <div class="veil"></div>
      <div class="sbar collapsed idle">
        <button class="s-back" aria-label="Fechar busca">${ICON.back}</button>
        <input type="search" placeholder="Músicas, artistas, playlists" autocomplete="off" enterkeyhint="search">
        <button class="s-clear" aria-label="Limpar">${ICON.x}</button>
        <button class="s-icon" aria-label="Buscar">${ICON.search}</button>
      </div>
      <div class="sbody"></div>`;
    phone.appendChild(ov);
    const bar = ov.querySelector(".sbar"), input = ov.querySelector("input"), body = ov.querySelector(".sbody");
    let isOpen = false, view = "", moreResults = null;
    let history = readHistory();
    if (!history.length && !NATIVE) history = [{ type: "q", text: "lofi para estudar" }, { type: "a", name: "MC Mockup" }, { type: "t", i: 3 }, { type: "q", text: "chuva" }];
    function syncHistory(next = readHistory()) {
      history = next;
      if (view === "recent" && isOpen) showRecent();
    }
    subscribers.add(syncHistory);
    window.addEventListener("storage", e => { if (e.key === KEY) syncHistory(); });
    function remember(entry) { history = rememberEntry(entry); }
    body.addEventListener("scroll", () => { if (view === "res") moreResults?.(); });
    body.addEventListener("wheel", () => moreResults?.resume?.(), { passive: true });
    body.addEventListener("touchstart", () => moreResults?.resume?.(), { passive: true });

    // posição da lupa dentro do celular (a barra nasce ali)
    // A lupa nunca se mexe: a barra é presa pela DIREITA e pelo CENTRO vertical da lupa e só cresce para a esquerda.
    // A lupa de dentro da barra fica pixel a pixel em cima da lupa da tela, então a troca entre as duas é invisível.
    function iconRect() {
      const p = phone.getBoundingClientRect(), b = button.getBoundingClientRect();
      return { right: p.left + phone.clientLeft + phone.clientWidth - b.right, cy: b.top - p.top - phone.clientTop + b.height / 2,
               width: b.width, height: b.height };
    }
    function place(r) {
      Object.assign(bar.style, { left: "auto", right: r.right + "px", top: (r.cy - r.height / 2) + "px", width: r.width + "px", height: r.height + "px" });
    }
    function syncIcon() {                                       // mesma largura e mesmo tamanho de ícone da lupa da tela
      const b = button.getBoundingClientRect(), svg = button.querySelector("svg")?.getBoundingClientRect();
      bar.style.setProperty("--icon-w", b.width + "px");
      if (svg) bar.style.setProperty("--icon-size", svg.width + "px");
    }

    function open() {
      if (isOpen) return;
      isOpen = true;
      playlists = loadPlaylists();
      const r = iconRect();
      syncIcon();
      place(r);
      bar.classList.remove("idle");
      bar.classList.add("collapsed");
      void bar.offsetWidth;                                     // aplica a posição inicial antes de animar
      ov.classList.add("open");
      bar.classList.remove("collapsed");
      place({ right: r.right, cy: r.cy, width: phone.clientWidth - r.right - 12, height: 46 });
      input.value = ""; bar.classList.remove("has-text");
      showRecent();
      setTimeout(() => input.focus({ preventScroll: true }), 200);
      history_push();
    }
    function close(fromPop = false) {
      if (!isOpen) return;
      isOpen = false;
      input.blur();
      ov.classList.remove("open");
      bar.classList.add("collapsed");
      place(iconRect());
      setTimeout(() => { if (!isOpen) bar.classList.add("idle"); }, 400);
      setTimeout(() => { window.SounosTips?.offer("home"); window.SounosTips?.offer("offline"); window.SounosTips?.offer("mini"); }, 430);
      if (!fromPop && pushed) { pushed = false; window.history.back(); }
    }
    // o "voltar" do celular fecha a busca antes de sair da tela
    let pushed = false;
    function history_push() { window.history.pushState({ search: true }, ""); pushed = true; }
    window.addEventListener("popstate", () => { if (isOpen && !window.history.state?.search) { pushed = false; close(true); } });

    // A animação de entrada só roda quando a tela muda de tipo (recentes → sugestões → resultados).
    // Atualizar a mesma tela (cada letra digitada) troca o conteúdo sem animar e sem piscar.
    let lastHtml = "";
    function setPanel(html, name) {
      if (view === name && html === lastHtml) return;
      if (name !== "res") moreResults = null;
      const same = view === name && body.firstElementChild;
      const anchor = same && name === "res"
        ? [...body.querySelectorAll('.s-row[data-pick="nt"]')].find(row => row.getBoundingClientRect().bottom > body.getBoundingClientRect().top)
        : null;
      const anchorId = anchor?.dataset.id, anchorTop = anchor?.getBoundingClientRect().top;
      view = name; lastHtml = html;
      if (same) morph(body.firstElementChild, html);
      else body.innerHTML = `<div class="spanel">${html}</div>`;
      if (anchorId !== undefined) {
        const next = body.querySelector(`.s-row[data-pick="nt"][data-id="${anchorId}"]`);
        if (next) body.scrollTop += next.getBoundingClientRect().top - anchorTop;
      }
      if (!same) [...body.firstElementChild.children].forEach((c, k) => c.style.setProperty("--k", Math.min(k, 12)));
      window.SounosTips?.offer(name === "res" ? "search-results" : "search");
    }
    // Atualiza a lista no lugar: linha igual fica como está (capa não recarrega), linha da mesma
    // música/sugestão só troca o texto que mudou; o resto entra ou sai. Nada pisca.
    const keyOf = el => el.nodeType === 1 ? (el.dataset.q ?? (el.dataset.pick ? el.dataset.pick + ":" + el.dataset.id : el.className)) : "#";
    function morph(panel, html) {
      panel.classList.add("still");
      const tpl = document.createElement("template"); tpl.innerHTML = html;
      const next = [...tpl.content.children], old = [...panel.children];
      next.forEach((n, i) => {
        const o = old[i];
        if (o && o.outerHTML === n.outerHTML) return;
        if (o && o.tagName === n.tagName && keyOf(o) === keyOf(n) && o.children.length === n.children.length) {
          [...n.children].forEach((nc, k) => { const oc = o.children[k]; if (oc.outerHTML !== nc.outerHTML) oc.replaceWith(nc.cloneNode(true)); });
          return;
        }
        if (o) o.replaceWith(n); else panel.appendChild(n);
      });
      old.slice(next.length).forEach(o => o.remove());
    }

    // ---- recentes ----
    function showRecent() {
      try { if (localStorage.getItem(KEY) !== null) history = readHistory(); } catch {}
      if (!history.length) return setPanel('<div class="s-empty">Suas pesquisas aparecem aqui.</div>', "recent");
      setPanel(`<div class="s-head"><h3>Pesquisas recentes</h3><button data-act="clear-all">Limpar</button></div>` +
        history.map((h, k) => {
          let ic, t, a;
          if (h.type === "q") { ic = cover("q"); t = esc(h.text); a = "Pesquisa"; }
          else if (h.type === "t" && h.track) { ic = realCover(h.track); t = esc(h.track.title); a = "Música · " + artistLine(h.track); }
          else if (h.type === "a" && NATIVE) { ic = realArtist(h); t = toHandle(h.name); a = "Artista"; }
          else if (h.type === "t") { const x = tracks[h.i]; ic = cover("t", x); t = esc(x.title); a = "Música · " + artistLine(x); }
          else if (h.type === "a") { const x = artists.find(y => y.name === h.name); ic = cover("a", x); t = toHandle(x.name); a = "Artista"; }
          else if (h.type === "p") {
            const x = D.getPlaylists().find(p => p.id === h.id) || playlists[h.i] || h;
            ic = cover("p", x); t = esc(x.name || h.name || "Playlist"); a = "Playlist";
          } else if (h.type === "album") {
            const url = h.thumb ? Sounos.coverUrl({ id: "album:" + (h.id || norm(h.name) || "x"), thumb: h.thumb }) : "";
            ic = `<div class="ic" style="${url ? `background:url('${url.replace(/'/g, "%27")}') center/cover no-repeat` : ""}">♪</div>`;
            t = esc(h.name || "Álbum"); a = "Álbum";
          } else { ic = cover("q"); t = esc(h.name || ""); a = "Pesquisa"; }
          return `<button class="s-row" data-hist="${k}">${ic}<div class="tx"><div class="t">${t}</div><div class="a">${a}</div></div>
            <span class="x" data-act="rm" data-k="${k}">${ICON.x}</span></button>`;
        }).join(""), "recent");
    }

    // ---- sugestões enquanto digita (corretor + gosto) ----
    function showSuggestions(q) {
      if (NATIVE) return nativeSuggestions(q);
      const pastQ = history.filter(h => h.type === "q" && norm(h.text).startsWith(norm(q)) && norm(h.text) !== norm(q)).slice(0, 2);
      const seen = new Set(), list = [];
      candidates(q).forEach(c => { const k = norm(c.label); if (!seen.has(k) && list.length < 7) { seen.add(k); list.push(c); } });
      if (!list.length && !pastQ.length) {
        return setPanel(`<button class="s-row sug" data-q="${esc(q)}">${cover("q")}<div class="tx"><div class="t"><b>${esc(q)}</b></div></div></button>`, "sug");
      }
      setPanel(
        pastQ.map(h => `<button class="s-row sug" data-q="${esc(h.text)}">${cover("q")}<div class="tx"><div class="t">${emphasize(h.text, q)}</div></div>
          <span class="fill" data-fill="${esc(h.text)}">${ICON.fill}</span></button>`).join("") +
        list.map(c => `<button class="s-row sug" data-pick="${c.type}" data-id="${c.type === "a" ? esc(c.item.name) : c.item.i}">
          ${cover(c.type, c.item)}<div class="tx"><div class="t">${emphasize(c.label, q)}</div><div class="a">${c.sub}</div></div>
          <span class="fill" data-fill="${esc(c.label)}">${ICON.fill}</span></button>`).join(""), "sug");
    }

    // ---- resultados ----
    function showResults(q) {
      if (NATIVE) return nativeResults(q);
      const all = candidates(q).filter(c => c.text >= .45);
      if (!all.length) return setPanel(`<div class="s-empty">Nada encontrado para “${esc(q)}”.</div>`, "res");
      const best = all[0];
      const songs = all.filter(c => c.type === "t").slice(0, 6);
      const arts = all.filter(c => c.type === "a").slice(0, 6);
      const pls = all.filter(c => c.type === "p").slice(0, 6);
      // artistas encontrados também puxam as músicas deles, ordenadas pelo gosto
      if (best.type === "a") tracks.filter(t => t.artist === best.item.name && !songs.some(s => s.item === t))
        .sort((x, y) => affTrack(y) - affTrack(x)).slice(0, 6 - songs.length)
        .forEach(t => songs.push({ type: "t", item: t }));
      const firstTrack = best.type === "p" ? best.item.tracks[0] : null;
      const firstSong = typeof firstTrack === "object" ? firstTrack : tracks[firstTrack];
      const bestCv = best.type === "p" ? firstSong?.c || ["#2f6bff", "#1d4ed8", "#60a5fa"] : best.item.c;
      const bestBg = best.type === "p" && best.item.cover ? `url('${best.item.cover.replace(/'/g, "%27")}') center/cover no-repeat` : grad(bestCv);
      const playlistTile = p => p.cover
        ? `<i style="grid-area:1/1/3/3;background:url('${p.cover.replace(/'/g, "%27")}') center/cover no-repeat"></i>`
        : p.tracks.length ? p.tracks.slice(0, 4).map(t => { const x = typeof t === "object" ? t : tracks[t]; return `<i style="background:${x?.c ? grad(x.c) : "var(--s-surface)"}"></i>`; }).join("")
          : `<i style="grid-area:1/1/3/3;background:var(--s-surface)"></i>`;
      setPanel(`
        <div class="s-head"><h3>Melhor resultado</h3></div>
        <button class="s-best" data-pick="${best.type}" data-id="${best.type === "a" ? esc(best.item.name) : best.item.i}">
          <div class="cv ${best.type === "a" ? "round" : ""}" style="background:${bestBg}">${esc((best.type === "a" ? best.item.name : best.label)[0])}</div>
          <div><div class="t">${esc(best.label)}</div><div class="a">${best.type === "t" ? "Música · " + best.sub : best.sub}</div></div>
        </button>` +
        (songs.length ? `<div class="s-head"><h3>Músicas</h3></div>` + songs.map(c => `
          <button class="s-row" data-pick="t" data-id="${c.item.i}">${cover("t", c.item)}
            <div class="tx"><div class="t">${esc(c.item.title)}</div><div class="a">${artistLine(c.item)}</div></div></button>`).join("") : "") +
        (arts.length ? `<div class="s-head"><h3>Artistas</h3></div><div class="s-hrow">` + arts.map(c => `
          <button class="s-art" data-pick="a" data-id="${esc(c.item.name)}"><div class="pic" style="background:${grad(c.item.c)}">${esc(c.item.name[0])}</div>
            <div class="n">${toHandle(c.item.name)}</div></button>`).join("") + `</div>` : "") +
        (pls.length ? `<div class="s-head"><h3>Playlists</h3></div><div class="s-hrow">` + pls.map(c => `
          <button class="s-pl" data-pick="p" data-id="${c.item.i}">${playlistTile(c.item)}
            <div class="meta"><div class="t">${esc(c.item.name)}</div></div></button>`).join("") + `</div>` : ""), "res");
    }

    function runSearch(q) {
      q = q.trim(); if (!q) return showRecent();
      input.value = q; bar.classList.add("has-text");
      remember({ type: "q", text: q });
      input.blur();
      showResults(q);
    }
    function rememberTerm() {
      const q = input.value.trim();
      if (q) remember({ type: "q", text: q });
    }
    function pick(type, id) {
      if (type === "nt") return playTrack(resultTracks[+id]);
      if (type === "na") {
        const a = resultArtists[+id]; if (!a) return;
        rememberTerm(); remember({ type: "a", name: a.name, thumb: a.thumb });
        return openArtist(a.name, { ...a, photoUrl: artistPic(a) }) || (input.value = toHandle(a.name), bar.classList.add("has-text"), showResults(a.name));
      }
      if (type === "nal") { const a = resultAlbums[+id]; if (!a) return; rememberTerm(); return openAlbum(a.url, a.name, a.artist, a.thumb); }
      if (type === "t") {
        const t = tracks[+id]; if (!t) return;
        rememberTerm(); remember({ type: "t", i: +id }); location.href = `player.html?t=${id}`;
      } else if (type === "p") {
        const p = playlists.find(x => x.i === +id) || playlists[+id]; if (!p) return;
        rememberTerm(); remember({ type: "p", id: p.id, i: p.i, name: p.name, cover: p.cover || "" });
        if (window.SounosPages && p.id) return window.SounosPages.openPlaylist(p.id);
        if (window.parent !== window && window.parent.SounosPages && p.id) return openPlaylist(p.id);
        location.href = `player.html?p=${p.i}`;
      } else {
        rememberTerm(); remember({ type: "a", name: id });
        input.value = toHandle(id); bar.classList.add("has-text"); showResults(id);
      }   // perfil do artista: próxima tela
    }

    // ---- eventos ----
    button.addEventListener("click", open);
    ov.querySelector(".s-back").addEventListener("click", () => close());
    ov.querySelector(".s-icon").addEventListener("click", () => runSearch(input.value));
    ov.querySelector(".s-clear").addEventListener("click", () => { input.value = ""; bar.classList.remove("has-text"); showRecent(); input.focus(); });
    input.addEventListener("input", () => {
      const q = input.value;
      bar.classList.toggle("has-text", !!q);
      q.trim() ? showSuggestions(q) : showRecent();
    });
    input.addEventListener("keydown", e => { if (e.key === "Enter") runSearch(input.value); if (e.key === "Escape") close(); });
    body.addEventListener("click", e => {
      const act = e.target.closest("[data-act]");
      if (act?.dataset.act === "clear-all") { history = writeHistory([]); return showRecent(); }
      if (act?.dataset.act === "rm") { history.splice(+act.dataset.k, 1); history = writeHistory(history); return showRecent(); }
      const fill = e.target.closest("[data-fill]");                 // seta: completa a barra sem buscar ainda
      if (fill) { input.value = fill.dataset.fill + " "; bar.classList.add("has-text"); showSuggestions(input.value); input.focus(); return; }
      const h = e.target.closest("[data-hist]");
      if (h) {
        const x = history[+h.dataset.hist];
        if (x.type === "q") return runSearch(x.text);
        if (x.type === "t" && x.track) return playTrack(x.track);
        if (x.type === "a" && NATIVE && openArtist(x.name, x)) return;
        if (x.type === "a" && NATIVE) { input.value = toHandle(x.name); bar.classList.add("has-text"); return showResults(x.name); }
        if (x.type === "p" && x.id) return openPlaylist(x.id);
        if (x.type === "album") return;
        return pick(x.type, x.type === "a" ? x.name : x.i);
      }
      const q = e.target.closest("[data-q]"); if (q) return runSearch(q.dataset.q);
      const p = e.target.closest("[data-pick]"); if (p) return pick(p.dataset.pick, p.dataset.id);
    });
    // ---------------- dentro do app: busca de verdade (NewPipe) + gosto da pessoa ----------------
    let resultTracks = [], resultArtists = [], resultAlbums = [], sugT = 0, lastServer = [], shownMine = new Set(), searchRun = 0;
    let cacheRefreshOff = [], suggestionCacheOff = null;
    const realCover = t => `<div class="ic" style="background:url('${Sounos.coverUrl(t)}') center/cover no-repeat, ${grad(Sounos.hashColors(t.id))}"></div>`;
    // foto de artista também passa pelo app (fica guardada; antes vinha da internet a cada busca)
    const artistKey = n => String(n || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^a-z0-9]/g, "");
    const artistPic = a => a.thumb ? Sounos.coverUrl({ id: "artist:" + (artistKey(a.name) || "x"), name: a.name, thumb: a.thumb }).replace(/'/g, "%27") : "";
    const realArtist = a => `<div class="ic round" style="background:${a.thumb ? `url('${artistPic(a)}') center/cover no-repeat,` : ""} ${grad(Sounos.hashColors(a.name))}"></div>`;
    // gosto: quanto você ouve cada artista e cada música (histórico do app, mais recente pesa mais).
    // Guardado por alguns segundos: antes era relido do app a cada letra digitada.
    let tasteCache = null, tasteAt = 0;
    if (NATIVE) Sounos.on("state", () => { tasteCache = null; });
    function taste() {
      if (tasteCache && performance.now() - tasteAt < 15000) return tasteCache;
      tasteAt = performance.now();
      return (tasteCache = buildTaste());
    }
    function buildTaste() {
      const h = Sounos.call("getHistory"), artists = {}, songs = {};
      (Array.isArray(h) ? h : []).forEach((e, k) => { const w = 1 / (1 + k / 15); artists[e.track.artist] = (artists[e.track.artist] || 0) + w; songs[e.track.id] = w; });
      const maxA = Math.max(1, ...Object.values(artists));
      const seen = new Set();
      return { artist: a => (artists[a] || 0) / maxA, song: id => songs[id] || 0,
               history: (Array.isArray(h) ? h.map(e => e.track) : []).filter(t => t && Sounos.isSong(t) && !seen.has(t.id) && seen.add(t.id)) };
    }
    // página do artista: recebe a mesma foto que apareceu no resultado da busca.
    function openArtist(name, artistInfo = {}) {
      if (window.SounosPages) { window.SounosPages.openArtist(name, artistInfo); return true; }
      const P = window.parent !== window && window.parent.SounosPages;
      if (!P) return false;
      const query = input.value.trim(), homeSearch = window.parent.homeSearch;
      if (!homeSearch?.isOpen() && query) homeSearch?.query(query);
      close(); window.parent.__playerShell.closeThen(() => P.openArtist(name, artistInfo)); return true;
    }
    function openPlaylist(id) {
      if (window.SounosPages) { window.SounosPages.openPlaylist(id); return true; }
      const P = window.parent !== window && window.parent.SounosPages;
      if (!P) return false;
      close(); window.parent.__playerShell.closeThen(() => P.openPlaylist(id)); return true;
    }
    // Álbum segue o mesmo caminho da página de artista, inclusive a partir da busca do player.
    function openAlbum(url, name, artist, thumb) {
      if (window.SounosPages) { window.SounosPages.openAlbum(url, name, artist, thumb); return true; }
      const P = window.parent !== window && window.parent.SounosPages;
      if (!P) return false;
      close(); window.parent.__playerShell.closeThen(() => P.openAlbum(url, name, artist, thumb)); return true;
    }
    function playTrack(t) {
      if (!t) return;
      rememberTerm();
      remember({ type: "t", track: t });
      Sounos.call("playSingle", { track: t, context: { type: "radio" } });
      if (location.pathname.endsWith("player.html")) close();
      else if (window.__playerShell) {                // a busca fica aberta debaixo do player
        input.blur(); window.__playerShell.open();
      } else location.href = "player.html";
    }
    function nativeSuggestions(q) {
      suggestionCacheOff?.();
      const T = taste();
      const pastQ = history.filter(h => h.type === "q" && norm(h.text).startsWith(norm(q)) && norm(h.text) !== norm(q)).slice(0, 2);
      // músicas que você já ouviu e batem com o que está digitando (com tolerância a erro), as que você mais ouve primeiro
      const mine = T.history.map(t => ({ t, s: Math.max(textScore(q, t.title), .85 * textScore(q, t.title + " " + t.artist)) }))
        // quem já estava na lista fica com uma régua mais baixa: não some e volta a cada letra digitada
        .filter(x => x.s >= (shownMine.has(x.t.id) ? .3 : .45)).map(x => ({ ...x, s: x.s + .35 * T.artist(x.t.artist) + .2 * T.song(x.t.id) }))
        .sort((a, b) => b.s - a.s).filter((x, i, arr) => arr.findIndex(y => y.t.id === x.t.id) === i).slice(0, 3);
      shownMine = new Set(mine.map(x => x.t.id));
      resultTracks = mine.map(x => x.t);
      const draw = server => setPanel(
        pastQ.map(h => `<button class="s-row sug" data-q="${esc(h.text)}">${cover("q")}<div class="tx"><div class="t">${emphasize(h.text, q)}</div></div>
          <span class="fill" data-fill="${esc(h.text)}">${ICON.fill}</span></button>`).join("") +
        mine.map((x, i) => `<button class="s-row sug" data-pick="nt" data-id="${i}">${realCover(x.t)}
          <div class="tx"><div class="t">${emphasize(x.t.title, q)}</div><div class="a">${artistLine(x.t)}</div></div></button>`).join("") +
        server.filter(t => !pastQ.some(h => norm(h.text) === norm(t))).slice(0, 6).map(t => `<button class="s-row sug" data-q="${esc(t)}"><div class="ic">${ICON.search}</div>
          <div class="tx"><div class="t">${emphasize(t, q)}</div></div><span class="fill" data-fill="${esc(t)}">${ICON.fill}</span></button>`).join("")
        || `<button class="s-row sug" data-q="${esc(q)}">${cover("q")}<div class="tx"><div class="t"><b>${esc(q)}</b></div></div></button>`, "sug");
      draw(view === "sug" ? lastServer : []);       // mantém as sugestões anteriores até as novas chegarem (sem piscar)
      suggestionCacheOff = Sounos.on("metadataUpdated", ev => {
        if (ev.method !== "suggest" || ev.cacheKey !== Sounos.cacheKey("suggest", q) || input.value !== q || view !== "sug" || window.__playerOpen) return;
        lastServer = ev.payload?.items || [];
        draw(lastServer);
      });
      clearTimeout(sugT);
      sugT = setTimeout(() => Sounos.ask("suggest", { q }).then(r => { if (input.value === q && view === "sug" && !window.__playerOpen) { lastServer = r.items || []; draw(lastServer); } }), 160);
    }
    function nativeResults(q) {
      cacheRefreshOff.forEach(off => off()); cacheRefreshOff = [];
      const run = ++searchRun;
      setPanel(`<div class="s-empty">Buscando “${esc(q)}”…</div>`, "loading");
      const stale = () => run !== searchRun || !isOpen || window.__playerOpen || norm(input.value.replace(/^@/, "")) !== norm(q) && norm(input.value) !== norm(toHandle(q));
      const pSongs = Sounos.ask("search", { q }), pArts = Sounos.ask("searchArtists", { q }), pAlbums = Sounos.ask("searchAlbums", { q });
      let arts = null, albums = null, songsR = null, moreHas = false, moreLoading = false, morePaused = false;
      let resultSongs = [];
      const seenSongs = new Set();
      const songKey = t => String(t?.id || t?.songKey || t?.url || `${t?.title || ""}|${t?.artist || ""}`);
      const appendSongs = (items, start = resultSongs.length) => {
        const fresh = [];
        (items || []).forEach(t => {
          const key = songKey(t);
          if (!key || seenSongs.has(key)) return;
          seenSongs.add(key);
          fresh.push(t);
        });
        const score = (t, i) => (1 - i / 25) + .5 * textScore(q, t.title + " " + t.artist) + .35 * taste().artist(t.artist) + .15 * taste().song(t.id);
        resultSongs.push(...fresh.map((t, i) => ({ t, s: score(t, start + i) })).sort((a, b) => b.s - a.s).map(x => x.t));
      };
      cacheRefreshOff.push(Sounos.on("metadataUpdated", ev => {
        if (stale()) return;
        if (ev.method === "search" && ev.cacheKey === Sounos.cacheKey("search", q)) {
          songsR = ev.payload || {};
          resultSongs = []; seenSongs.clear(); appendSongs(songsR.items, 0);
          moreHas = !!songsR.hasMore; morePaused = false; draw();
        } else if (ev.method === "searchArtists" && ev.cacheKey === Sounos.cacheKey("searchArtists", q)) {
          arts = ev.payload || {}; if (songsR) draw();
        } else if (ev.method === "searchAlbums" && ev.cacheKey === Sounos.cacheKey("searchAlbums", q)) {
          albums = ev.payload || {}; if (songsR) draw();
        }
      }));
      const more = () => {
        if (moreLoading || morePaused || !moreHas || stale() || view !== "res") return;
        if (body.scrollTop + body.clientHeight < body.scrollHeight - 420) return;
        moreLoading = true;
        draw();
        Sounos.ask("searchMore", { q }).then(r => {
          if (stale()) return;
          moreLoading = false;
          if (r.error) {
            morePaused = true;
            draw();
            return;
          }
          appendSongs(r.items, resultSongs.length);
          moreHas = !!r.hasMore;
          draw();
          if (moreHas) requestAnimationFrame(more);
        });
      };
      more.resume = () => { morePaused = false; more(); };
      moreResults = more;
      // As músicas aparecem assim que chegam; artistas e álbuns entram sem bloquear uma à outra.
      pSongs.then(r => {
        songsR = r;
        appendSongs(r.items, 0);
        moreHas = !!r.hasMore;
        if (!stale()) draw();
      });
      pArts.then(r => { arts = r; if (songsR && !stale()) draw(); });
      pAlbums.then(r => { albums = r; if (songsR && !stale()) draw(); });
      function draw() {
        const songs = songsR;
        if (songs.error && (!arts || arts.error) && (!albums || albums.error)) return arts || albums ? setPanel(`<div class="s-empty">Sem conexão agora. Tente de novo.</div>`, "res") : null;
        resultTracks = resultSongs;
        resultArtists = ((arts && arts.items) || []).filter(a => a.name);
        resultAlbums = ((albums && albums.items) || []).filter(a => a.name && a.url);
        if (!resultTracks.length && !resultArtists.length && !resultAlbums.length) {
          if (moreLoading || moreHas) {
            setPanel(`<div class="sk" aria-label="Carregando músicas"></div>`, "res");
            if (moreHas && !moreLoading && !morePaused) requestAnimationFrame(more);
          } else setPanel(`<div class="s-empty">Nada encontrado para “${esc(q)}”.</div>`, "res");
          return;
        }
        const topArtist = resultArtists[0] && (!resultTracks.length || textScore(q, resultArtists[0].name) >= .9) ? resultArtists[0] : null;
        const topAlbum = resultAlbums[0];
        const best = topArtist ? { type: "na", i: 0, title: toHandle(topArtist.name), sub: "Artista", look: realArtist(topArtist).replace('class="ic round"', 'class="cv round"') }
          : resultTracks.length ? { type: "nt", i: 0, title: esc(resultTracks[0].title), sub: "Música · " + artistLine(resultTracks[0]),
            look: realCover(resultTracks[0]).replace('class="ic"', 'class="cv"') }
          : { type: "nal", i: 0, title: esc(topAlbum.name), sub: "Álbum · " + toHandle(topAlbum.artist),
            look: `<div class="cv" style="background:${topAlbum.thumb ? `url('${Sounos.coverUrl({ id: "album:" + (artistKey(topAlbum.name) || "x"), thumb: topAlbum.thumb }).replace(/'/g, "%27")}') center/cover,` : ""} ${grad(Sounos.hashColors(topAlbum.name))}"></div>` };
        const songRows = (from, to = resultTracks.length) => resultTracks.slice(from, to).map((t, i) => `
            <button class="s-row" data-pick="nt" data-id="${from + i}">${realCover(t)}
              <div class="tx"><div class="t">${esc(t.title)}</div><div class="a">${artistLine(t)} · ${fmtDur(t.dur)}</div></div></button>`).join("");
        setPanel(`
          <div class="s-head"><h3>Melhor resultado</h3></div>
          <button class="s-best" data-pick="${best.type}" data-id="${best.i}">${best.look}
            <div><div class="t">${best.title}</div><div class="a">${best.sub}</div></div></button>` +
          (resultTracks.length ? `<div class="s-head"><h3>Músicas</h3></div>` + songRows(0, Math.min(4, resultTracks.length)) : "") +
          (resultArtists.length ? `<div class="s-head"><h3>Artistas</h3></div><div class="s-hrow">` + resultArtists.map((a, i) => `
            <button class="s-art" data-pick="na" data-id="${i}"><div class="pic" style="background:${a.thumb ? `url('${artistPic(a)}') center/cover,` : ""} ${grad(Sounos.hashColors(a.name))}"></div>
              <div class="n">${toHandle(a.name)}</div></button>`).join("") + `</div>` : "") +
          (resultAlbums.length ? `<div class="s-head"><h3>Álbuns</h3></div><div class="s-hrow">` + resultAlbums.map((a, i) => `
            <button class="s-album" data-pick="nal" data-id="${i}"><div class="pic" style="background:${a.thumb ? `url('${Sounos.coverUrl({ id: "album:" + (artistKey(a.name) || "x"), thumb: a.thumb }).replace(/'/g, "%27")}') center/cover,` : ""} ${grad(Sounos.hashColors(a.name))}"></div>
              <div class="n">${esc(a.name)}</div><div class="a">${toHandle(a.artist)}</div></button>`).join("") + `</div>` : "") +
          songRows(Math.min(4, resultTracks.length)) +
          (moreLoading ? `<div class="sk" aria-label="Carregando músicas"></div>` : ""), "res");
        if (moreHas && !moreLoading && !morePaused) requestAnimationFrame(more);
      }
    }
    const fmtDur = s => s > 0 ? `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}` : "";

    function query(q) { open(); input.value = q; bar.classList.add("has-text"); runSearch(q); }
    return { open, close, query, isOpen: () => isOpen };
  }

  window.SounosSearch = { attach, candidates, remember: rememberEntry };
})();
