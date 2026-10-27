// Páginas de playlist e de artista (dentro do app). A capa toma o topo inteiro, sem ícones por cima.
// Fechar: "voltar" do celular, ou puxar a página para a direita.
// Uso: SounosPages.attach(celular, { onChange }) · .openPlaylist(id, { fresh }) · .openArtist(nome, dados)
window.SounosPages = (() => {
  const D = window.SounosData;
  const esc = s => String(s ?? "").replace(/[&<>"]/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[ch]));
  const handle = n => (window.Sounos?.handle || D.toHandle)(n);
  const artistLine = t => window.Sounos?.artistLine?.(t) || t?.artist || "";
  const cover = (t, pin = false) => `url('${Sounos.coverUrl(t, pin)}'), linear-gradient(135deg,${Sounos.hashColors(t.id).join(",")})`;
  const key = n => { const f = String(n || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/ø/g, "o").replace(/æ/g, "ae").replace(/œ/g, "oe").replace(/ß/g, "ss").replace(/đ/g, "d").replace(/ł/g, "l").replace(/þ/g, "th");
    const a = f.replace(/[^a-z0-9]/g, ""); if (a) return a;
    // nome sem letras latinas (아이유, 宇多田ヒカル): código das letras, senão todos viram o mesmo artista
    const h = [...f].filter(c => /[\p{L}\p{N}]/u.test(c)).map(c => c.codePointAt(0).toString(16)).join("").slice(0, 80); return h ? "u" + h : ""; };
  const canonicalUrl = raw => {
    try { const u = new URL(String(raw), location.href); u.hash = ""; u.hostname = u.hostname.toLowerCase(); u.searchParams.sort(); return u.href; }
    catch { return String(raw || "").trim(); }
  };
  const imageKey = raw => {
    try { const u = new URL(canonicalUrl(raw)); u.pathname = u.pathname.replace(/=(?:s\d+|w\d+(?:-h\d+)?)(?:-[a-z0-9-]+)*$/i, ""); return u.href; }
    catch { return canonicalUrl(raw); }
  };
  const ARTIST_PAGE_CACHE = "sounos.artistPageCache";
  const compactArtistPageCache = cache => {
    let changed = false;
    for (const [id, item] of Object.entries(cache)) if (Array.isArray(item?.items)) {
      const items = item.items.map(t => {
        if (!Object.hasOwn(t, "thumbs")) return t;
        changed = true;
        const { thumbs, ...track } = t;
        return track;
      });
      if (changed) cache[id] = { ...item, items };
    }
    return changed;
  };
  let artistPageCache = {};
  try {
    artistPageCache = JSON.parse(localStorage.getItem(ARTIST_PAGE_CACHE) || "{}");
    if (!artistPageCache || Array.isArray(artistPageCache) || typeof artistPageCache !== "object") artistPageCache = {};
    if (compactArtistPageCache(artistPageCache)) localStorage.setItem(ARTIST_PAGE_CACHE, JSON.stringify(artistPageCache));
  } catch { artistPageCache = {}; }
  const readArtistPageCache = name => {
    const item = artistPageCache[key(name)];
    return item && Array.isArray(item.items) ? { ...item, isCached: true, fromLocalCache: true } : null;
  };
  const saveArtistPageCache = (name, result) => {
    if (!Array.isArray(result?.items)) return;
    try {
      const id = key(name);
      const items = result.items.map(t => { const { thumbs, ...track } = t; return track; });
      artistPageCache[id] = { name: result.name || name, thumb: result.thumb || "", items, at: Date.now() };
      compactArtistPageCache(artistPageCache);
      artistPageCache = Object.fromEntries(Object.entries(artistPageCache).sort((a, b) => (b[1]?.at || 0) - (a[1]?.at || 0)).slice(0, 4));
      localStorage.setItem(ARTIST_PAGE_CACHE, JSON.stringify(artistPageCache));
    } catch {}
  };
  const fmtTotal = s => { const m = Math.round(s / 60); return m >= 60 ? `${Math.floor(m / 60)} h ${m % 60} min` : `${m} min`; };
  const count = n => n === 1 ? "1 música" : `${n} músicas`;
  const remember = entry => {
    try { (window.SounosSearch || window.parent?.SounosSearch)?.remember(entry); } catch {}
  };
  const I = {
    play: '<svg viewBox="0 0 24 24"><polygon points="6 3 20 12 6 21 6 3"/></svg>',
    pause: '<svg viewBox="0 0 24 24"><rect x="5" y="4" width="5" height="16" rx="1"/><rect x="14" y="4" width="5" height="16" rx="1"/></svg>',
    shuffle: '<svg viewBox="0 0 24 24"><path d="M2 18h1.4c1.3 0 2.5-.6 3.3-1.7l6.1-8.6c.7-1.1 2-1.7 3.3-1.7H22"/><path d="m18 2 4 4-4 4"/><path d="M2 6h1.9c1.5 0 2.9.9 3.6 2.2"/><path d="M22 18h-5.9c-1.3 0-2.6-.7-3.3-1.8l-.5-.8"/><path d="m18 14 4 4-4 4"/></svg>',
    dl: '<svg class="dl-icon" viewBox="0 0 24 24"><circle class="dl-disc" cx="12" cy="12" r="9.5"/><path class="dl-mark" d="M12 7.5L12 15.7M8.5 12.2L12 15.7M15.5 12.2L12 15.7"/></svg>',
    done: '<svg class="dl-icon" viewBox="0 0 24 24"><circle class="dl-disc" cx="12" cy="12" r="10.6"/><path class="dl-mark" d="m7.8 12.2 2.8 2.8 5.9-6.1"/></svg>',
    baixada: '<svg class="dl-icon" viewBox="0 0 24 24"><circle class="dl-disc" cx="12" cy="12" r="10.6"/><path class="dl-mark" d="m7.8 12.2 2.8 2.8 5.9-6.1"/></svg>',   // círculo preenchido: música já está no aparelho
    img: '<svg viewBox="0 0 24 24"><rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="9" cy="9" r="2"/><path d="m21 15-5-5L5 21"/></svg>',
    edit: '<svg viewBox="0 0 24 24"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>',
    grip: '<svg viewBox="0 0 24 24"><path d="M5 9h14M5 15h14"/></svg>',
    rm: '<svg viewBox="0 0 24 24"><path d="M5 12h14"/></svg>',
    note: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>',
    back: '<svg viewBox="0 0 24 24"><path d="m15 18-6-6 6-6"/></svg>',
    follow: '<svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>',
    following: '<svg viewBox="0 0 24 24"><path d="m5 12 4 4L19 6"/></svg>',
  };

  let phone, opts = {}, stack = [], latestState = null; // páginas abertas (uma por cima da outra)
  let playRequest = 0;

  function attach(ph, o = {}) {
    phone = ph; opts = o;
    // "voltar": fecha as páginas que estavam acima do ponto do histórico onde se chegou
    addEventListener("popstate", () => { const cur = history.state?.d || 0; [...stack].reverse().forEach(p => p.d > cur && close(p, true)); });
    // a playlist mudou em outro lugar ("+" no player): a página aberta acompanha
    addEventListener("storage", e => { if (e.key === "sounos.playlists") stack.forEach(p => p.kind === "playlist" && p.refresh()); });
    // música atual vem no próprio evento (antes: cada página aberta pedia o estado inteiro ao app de novo)
    Sounos.on("state", st => { latestState = st || {}; stack.forEach(p => p.markNow(latestState)); });
    Sounos.on("download", ev => stack.forEach(p => p.onDownload(ev)));
    Sounos.on("library", () => stack.forEach(p => p.onLibrary()));
    return api;
  }

  // ---------- base comum ----------
  function mount(kind) {
    const el = document.createElement("div");
    el.className = "pg " + kind;
    phone.appendChild(el);
    const page = { el, kind, closing: false, refresh() {}, markNow() {}, onDownload() {}, onLibrary() {} };
    stack.push(page);
    phone.classList.add("has-page");
    page.d = (history.state?.d || 0) + 1;
    history.pushState({ ...(history.state || {}), page: kind, d: page.d }, "");
    if (window.__playerOpen) window.__playerShell?.lowerForPage?.();
    void el.offsetWidth; // fixa o estado fora da tela; a próxima moldura já inicia a animação
    requestAnimationFrame(() => el.classList.add("open"));
    swipeToClose(page);
    return page;
  }
  function close(page, fromPop) {
    if (!page || page.closing) return;
    page.closing = true;
    (page.cacheOff || []).forEach(off => off());
    document.activeElement?.blur?.();
    page.el.classList.add("closing");
    page.el.style.transform = "";
    stack = stack.filter(p => p !== page);
    if (!stack.length) setTimeout(() => {
      if (stack.length) return;
      phone.classList.remove("has-page");
      window.__playerShell?.restoreAfterPage?.();
    }, 320);
    setTimeout(() => page.el.remove(), 320);
    if (!fromPop) history.back();
    opts.onChange?.();
  }
  function addBack(page) {
    const b = document.createElement("button");
    b.className = "pg-back"; b.type = "button"; b.setAttribute("aria-label", "Voltar"); b.innerHTML = I.back;
    b.addEventListener("click", e => { e.stopPropagation(); close(page); });
    page.el.appendChild(b);
  }
  // puxar para a direita fecha (como voltar)
  function swipeToClose(page) {
    const el = page.el, s = { x: 0, y: 0, on: false, go: false };
    el.addEventListener("pointerdown", e => {
      if (e.target.closest(".grip, [contenteditable], input")) return;
      Object.assign(s, { x: e.clientX, y: e.clientY, on: true, go: false });
    });
    el.addEventListener("pointermove", e => {
      if (!s.on) return;
      const dx = e.clientX - s.x, dy = e.clientY - s.y;
      if (!s.go) { if (Math.abs(dy) > 12) { s.on = false; return; } if (dx > 14) { s.go = true; el.classList.add("dragging"); } else return; }
      el.style.transform = `translateX(${Math.max(0, dx)}px)`;
    });
    const end = e => {
      if (!s.on) return; s.on = false;
      if (!s.go) return;
      el.classList.remove("dragging");
      if (e.clientX - s.x > 110) close(page); else el.style.transform = "";
    };
    el.addEventListener("pointerup", end); el.addEventListener("pointercancel", end);
  }
  // shuffle: true = tudo embaralhado · false = em ordem · "aleatorio" = a escolhida primeiro e o resto embaralhado
  // (com o aleatório do player ligado) · sem valor = segue o aleatório do player
  function playFrom(list, i, ctx, shuffle) {
    if (!list.length) return;
    const aleatorio = shuffle === "aleatorio";
    if (aleatorio) shuffle = undefined;
    const activeShuffle = aleatorio || shuffle === true || (shuffle !== false && !!playerState().shuffle);
    let tracks = [...list], index = i;
    if (aleatorio) {
      const escolhida = tracks[index], resto = tracks.filter((_, k) => k !== index);
      for (let n = resto.length - 1; n > 0; n--) { const j = Math.floor(Math.random() * (n + 1)); [resto[n], resto[j]] = [resto[j], resto[n]]; }
      tracks = [escolhida, ...resto]; index = 0;
    } else if (shuffle === true) {
      for (let n = tracks.length - 1; n > 0; n--) { const j = Math.floor(Math.random() * (n + 1)); [tracks[n], tracks[j]] = [tracks[j], tracks[n]]; }
      index = 0;
    } else if (activeShuffle) {
      const head = tracks.slice(0, index + 1), rest = tracks.slice(index + 1);
      for (let n = rest.length - 1; n > 0; n--) { const j = Math.floor(Math.random() * (n + 1)); [rest[n], rest[j]] = [rest[j], rest[n]]; }
      tracks = head.concat(rest);
    }
    remember({ type: "t", track: tracks[index] });
    const shuffleMode = aleatorio || shuffle === true ? true : shuffle === false ? false : null;
    window.__playerShell?.prepareQueue?.(tracks, index, ctx, activeShuffle, shuffleMode);
    window.openPlayer?.();
    const request = ++playRequest;
    requestAnimationFrame(() => requestAnimationFrame(() => {
      if (request !== playRequest) return;
      const args = { tracks, index, context: ctx, shufflePrepared: activeShuffle };
      if (shuffleMode !== null) args.shuffleMode = shuffleMode;
      Sounos.call("playQueue", args);
    }));
  }
  const st = () => { try { return Sounos.call("getState") || {}; } catch { return {}; } };
  const playerState = () => {
    if (latestState === null) latestState = st();
    return latestState;
  };
  const nowId = () => playerState()?.current?.id ?? null;
  function syncPagePlayback(page, type, name) {
    const state = playerState(), context = state.context || {};
    const matches = !!state.current && context.type === type && key(context.name) === key(name);
    const playing = !!state.playing || (!!state.loading && !!state.playWhenReady);
    const button = page.el.querySelector('[data-a="play"]');
    if (button) {
      const pause = matches && playing;
      button.innerHTML = pause ? I.pause : I.play;
      button.setAttribute("aria-label", pause ? "Pausar" : "Tocar");
    }
    return { matches, playing };
  }
  function togglePagePlayback(page, type, name, list, context, shuffle) {
    const state = syncPagePlayback(page, type, name);
    if (state.matches) return Sounos.call(state.playing ? "pause" : "play");
    return playFrom(list, 0, context, shuffle);
  }
  const rowHtml = (t, i, extra = "", pin = false, artist = true) => `<div class="tr" data-i="${i}" data-id="${esc(t.id)}">
      <div class="cv" style="background-image:${cover(t, pin)}"></div>
      <div class="tx"><div class="t">${esc(t.title)}</div><div class="a">${handle(artistLine(t))}</div></div>${extra}${window.SounosTrackMenu?.btn(t, { artist }) || ""}</div>`;

  // ---------- playlist ----------
  function openPlaylist(id, o = {}) {
    const downloads = !!o.downloads;
    const page = mount("playlist");
    const el = page.el;
    const downloadTracks = () => {
      try {
        const mine = Sounos.call("getMineTracks");
        return Array.isArray(mine.downloaded) ? mine.downloaded : [];
      } catch { return []; }
    };
    const get = () => downloads
      ? { id: "__downloads__", name: "Músicas baixadas", desc: "", tracks: downloadTracks() }
      : D.getPlaylists().find(p => p.id === id);
    const opened = get();
    if (opened && !downloads) remember({ type: "p", id: opened.id, name: opened.name, cover: opened.cover || "" });
    const save = mutate => {
      if (downloads) return;
      const all = D.getPlaylists(); const p = all.find(x => x.id === id); if (!p) return;
      mutate(p); D.savePlaylists(all); opts.onChange?.();
    };
    const dls = {};                              // id → progresso do download desta página
    let dlQueue = [], dlBusy = null;

    el.innerHTML = `<div class="hero"></div>
      <div class="ph">
        <div class="name" ${downloads ? "" : 'contenteditable="plaintext-only" spellcheck="false" data-ph="Nome da playlist"'}></div>
        ${downloads ? "" : '<div class="desc" contenteditable="plaintext-only" spellcheck="false" data-ph="Adicione uma descrição"></div>'}
        <div class="sub"></div>
      </div>
      <div class="acts">
        <button class="big" data-a="play" aria-label="Tocar">${I.play}</button>
        <button class="ab" data-a="shuffle" aria-label="Aleatório">${I.shuffle}</button>
        <button class="ab" data-a="dl" aria-label="Baixar playlist">${I.dl}<svg class="ring" viewBox="0 0 36 36"><circle cx="18" cy="18" r="15.9" pathLength="100"/></svg></button>
        <span class="sp"></span>
        ${downloads ? `<button class="ab" data-a="edit" aria-label="Editar músicas">${I.edit}</button>` : `<button class="ab" data-a="img" aria-label="Trocar capa">${I.img}</button>
        <button class="ab" data-a="edit" aria-label="Editar playlist">${I.edit}</button>`}
      </div>
      <div class="list"></div>
      ${downloads ? "" : '<input type="file" accept="image/*" hidden>'}`;
    addBack(page);
    const $ = s => el.querySelector(s);
    const nameEl = $(".name"), descEl = $(".desc"), list = $(".list"), file = $("input[type=file]");

    function hero(p) {
      const h = $(".hero");
      if (p.cover) h.innerHTML = `<div class="img" style="background-image:url('${p.cover}')"></div>`;
      else if (p.tracks.length) {
        const four = [0, 1, 2, 3].map(i => p.tracks[Math.min(i, p.tracks.length - 1)]);
        h.innerHTML = `<div class="mosaic">${four.map(t => `<i style="background-image:${cover(t, true)}"></i>`).join("")}</div>`;
      } else h.innerHTML = `<div class="empty">${I.note}</div>`;
    }
    function render() {
      const p = get(); if (!p) return close(page);
      hero(p);
      if (document.activeElement !== nameEl) nameEl.textContent = p.name || "";
      if (descEl && document.activeElement !== descEl) descEl.textContent = p.desc || "";
      $(".sub").textContent = p.tracks.length ? `${count(p.tracks.length)} · ${fmtTotal(p.tracks.reduce((a, t) => a + (t.dur || 0), 0))}` : "";
      $("[data-a=play]").disabled = !p.tracks.length;
      const done = downloaded();
      const edits = p.tracks.length ? p.tracks.map((t, i) => rowHtml(t, i,
        `${done[t.id] ? `<span class="dl" aria-label="Baixada">${I.baixada}</span>` : ""}<button class="rm" aria-label="${downloads ? "Remover música baixada" : "Tirar da playlist"}">${I.rm}</button>${downloads ? "" : `<button class="grip" aria-label="Arrastar para ordenar">${I.grip}</button>`}`, true)).join("") : "";
      const del = downloads ? "" : '<button class="del">Apagar playlist</button>';
      list.innerHTML = p.tracks.length
        ? edits + del
        : downloads ? '<div class="note">Baixe músicas para adicioná-las aqui.</div>'
          : `<div class="note">Playlist vazia.<br>No player, toque no + para adicionar a música que está tocando.</div>${del}`;
      markNow(); renderDl();
    }
    const downloaded = () => { try { return Sounos.call("getDownloads") || {}; } catch { return {}; } };
    function markNow() { const id = nowId(); list.querySelectorAll(".tr").forEach(r => r.classList.toggle("now", r.dataset.id === id)); }
    function renderDl() {
      const p = get(); if (!p) return;
      const done = downloaded(), b = $("[data-a=dl]");
      const n = p.tracks.length, have = p.tracks.filter(t => done[t.id] || dls[t.id] === 1).length;
      const part = p.tracks.reduce((a, t) => a + (done[t.id] ? 1 : dls[t.id] || 0), 0);
      const all = n > 0 && have === n;
      b.classList.toggle("done", all); b.classList.toggle("on", !!dlBusy && !all);
      b.firstElementChild.outerHTML = all ? I.done : I.dl;
      b.querySelector(".ring circle").style.strokeDasharray = `${dlBusy && !all ? Math.round(part / n * 100) : 0} 100`;
      b.setAttribute("aria-label", all ? "Playlist baixada" : dlBusy ? "Baixando playlist" : "Baixar playlist");
    }
    page.refresh = render; page.markNow = markNow;
    page.onDownload = ev => {
      if (downloads) { if (ev.done || ev.error) render(); return; }
      if (!(ev.id in dls)) return;
      if (ev.error) { delete dls[ev.id]; } else dls[ev.id] = ev.done ? 1 : ev.p;
      if (ev.done || ev.error) { dlBusy = null; nextDl(); render(); } else renderDl();
    };
    page.onLibrary = () => { if (downloads) render(); };
    function nextDl() {                          // uma por vez, para não brigar com a música tocando
      const t = dlQueue.shift(); if (!t) { dlBusy = null; renderDl(); return; }
      dlBusy = t.id; dls[t.id] = 0; Sounos.call("download", { track: t });
    }

    // nome e descrição: editáveis direto na página
    const saveText = () => save(p => { p.name = nameEl.textContent.trim() || "Nova playlist"; p.desc = descEl.textContent.trim(); });
    let textT = 0;
    if (!downloads) [nameEl, descEl].forEach(x => {
      x.addEventListener("blur", saveText);
      x.addEventListener("input", () => { clearTimeout(textT); textT = setTimeout(saveText, 400); });
      x.addEventListener("keydown", e => { if (e.key === "Enter") { e.preventDefault(); x.blur(); } });
    });

    el.addEventListener("click", e => {
      const a = e.target.closest("[data-a]")?.dataset.a;
      const p = get(); if (!p) return;
      const context = { type: "playlist", id: downloads ? "__downloads__" : id, name: p.name };
      if (a === "play") return playFrom(p.tracks, 0, context);
      if (a === "shuffle") return playFrom(p.tracks, 0, context, true);
      if (a === "dl") {
        const done = downloaded();
        dlQueue = p.tracks.filter(t => !done[t.id] && dls[t.id] !== 1 && t.id !== dlBusy);
        if (!dlBusy) nextDl(); renderDl(); return;
      }
      if (a === "img") return file?.click();
      if (a === "edit") { el.classList.toggle("editing"); $("[data-a=edit]").classList.toggle("on", el.classList.contains("editing")); disarm(); return; }
      const del = e.target.closest(".del");
      if (del) {                                 // dois toques: o primeiro arma, o segundo apaga
        if (!del.classList.contains("armed")) { del.classList.add("armed"); del.textContent = "Toque de novo para apagar"; return; }
        D.savePlaylists(D.getPlaylists().filter(x => x.id !== id)); opts.onChange?.(); return close(page);
      }
      const rm = e.target.closest(".rm");
      if (rm) {
        const i = +rm.closest(".tr").dataset.i;
        if (downloads) {
          const t = p.tracks[i]; if (t) Sounos.call("removeDownload", { id: t.id });
          return;
        }
        save(p => p.tracks.splice(i, 1)); return render();
      }
      if (e.target.closest(".grip")) return;
      const row = e.target.closest(".tr");
      if (row && !el.classList.contains("editing")) playFrom(p.tracks, +row.dataset.i, context);
    });
    const disarm = () => { const d = $(".del"); if (d) { d.classList.remove("armed"); d.textContent = "Apagar playlist"; } };

    // capa escolhida pela pessoa: reduzida para 900 px e guardada com a playlist
    file?.addEventListener("change", () => {
      const f = file.files?.[0]; if (!f) return;
      const img = new Image();
      img.onload = () => {
        const s = Math.min(img.width, img.height), out = Math.min(900, s), c = document.createElement("canvas");
        c.width = c.height = out;
        c.getContext("2d").drawImage(img, (img.width - s) / 2, (img.height - s) / 2, s, s, 0, 0, out, out);
        save(p => p.cover = c.toDataURL("image/jpeg", .86)); render(); URL.revokeObjectURL(img.src);
      };
      img.src = URL.createObjectURL(f); file.value = "";
    });

    // ordenar: arrastar pela alça (≡)
    list.addEventListener("pointerdown", e => {
      const grip = e.target.closest(".grip"); if (!grip) return;
      e.preventDefault();
      const row = grip.closest(".tr"), rows = [...list.querySelectorAll(".tr")];
      const from = rows.indexOf(row), h = row.offsetHeight, y0 = e.clientY;
      let to = from;
      grip.setPointerCapture(e.pointerId);
      row.classList.add("lift");
      rows.forEach(r => r !== row && r.classList.add("shift"));
      const move = ev => {
        const dy = ev.clientY - y0;
        row.style.transform = `translateY(${dy}px)`;
        to = Math.max(0, Math.min(rows.length - 1, from + Math.round(dy / h)));
        rows.forEach((r, k) => { if (r === row) return;
          r.style.transform = k > from && k <= to ? `translateY(${-h}px)` : k < from && k >= to ? `translateY(${h}px)` : ""; });
      };
      const up = () => {
        grip.removeEventListener("pointermove", move); grip.removeEventListener("pointerup", up); grip.removeEventListener("pointercancel", up);
        if (to !== from) save(p => p.tracks.splice(to, 0, p.tracks.splice(from, 1)[0]));
        render();
      };
      grip.addEventListener("pointermove", move); grip.addEventListener("pointerup", up); grip.addEventListener("pointercancel", up);
    });

    render();
    if (o.fresh) setTimeout(() => {              // playlist nova: já com o nome selecionado para digitar
      nameEl.focus();
      const r = document.createRange(); r.selectNodeContents(nameEl); const s = getSelection(); s.removeAllRanges(); s.addRange(r);
    }, 420);
    return page;
  }

  function openDownloads() { return openPlaylist("__downloads__", { downloads: true }); }

  // ---------- álbum ----------
  function openAlbum(url, name, artist, thumb) {
    const page = mount("album");
    const el = page.el;
    let songs = [];
    const albumCover = (n, t) => t ? `url('${Sounos.coverUrl({ id: "album:" + (key(n) || "x"), name: n, artist, thumb: t }, false, true)}')` : `linear-gradient(135deg,${Sounos.hashColors(n).join(",")})`;
    el.innerHTML = `<div class="hero"><div class="img" style="background-image:${albumCover(name, thumb)}"></div></div>
      <div class="ph"><div class="name"></div><div class="sub"></div></div>
      <div class="acts"><button class="big" data-a="play" aria-label="Tocar" disabled>${I.play}</button>
        <button class="ab" data-a="shuffle" aria-label="Aleatório">${I.shuffle}</button></div>
      <div class="list"><div class="sec">Músicas</div><div class="songs">${'<div class="sk"></div>'.repeat(6)}</div></div>`;
    addBack(page);
    const $ = s => el.querySelector(s);
    $(".name").textContent = name;
    $(".sub").textContent = handle(artist);
    const applyAlbum = r => {
      if (page.closing) return;
      if (r.error) { $(".songs").innerHTML = `<div class="note">Não deu para carregar agora.</div>`; return; }
      name = r.name || name; artist = r.artist || artist; songs = r.items || [];
      $(".name").textContent = name;
      $(".sub").textContent = `${handle(artist)}${songs.length ? ` · ${count(songs.length)}` : ""}`;
      if (r.thumb) $(".hero").innerHTML = `<div class="img" style="background-image:${albumCover(name, r.thumb)}"></div>`;
      $("[data-a=play]").disabled = !songs.length;
      $(".songs").innerHTML = songs.length ? songs.map((t, i) => rowHtml(t, i)).join("") : `<div class="note">Nenhuma música encontrada.</div>`;
      markNow();
    };
    page.cacheOff = [Sounos.on("metadataUpdated", ev => {
      if (ev.method === "album" && ev.cacheKey === Sounos.cacheKey("album", url)) applyAlbum(ev.payload || {});
    })];
    Sounos.ask("album", { url }).then(applyAlbum);
    function markNow() { const id = nowId(); el.querySelectorAll(".tr").forEach(r => r.classList.toggle("now", r.dataset.id === id)); syncPagePlayback(page, "album", name); }
    page.markNow = markNow;
    markNow();
    el.addEventListener("click", e => {
      const a = e.target.closest("[data-a]")?.dataset.a;
      const ctx = { type: "album", name };
      // Play/aleatório da capa usa só as músicas do álbum; o repetir selecionado continua valendo.
      if (a === "play") {
        if (!songs.length) return;
        const state = syncPagePlayback(page, "album", name);
        if (state.matches) return Sounos.call(state.playing ? "pause" : "play");
        return playFrom(songs, 0, ctx, false);
      }
      if (a === "shuffle") {
        if (!songs.length) return;
        return playFrom(songs, 0, ctx, true);
      }
      // música específica: com repetir (álbum ou faixa) fica no álbum; no aleatório vira rádio a partir dela
      const row = e.target.closest(".tr"); if (!row) return;
      const s = st(), i = +row.dataset.i;
      if (s.shuffle && s.repeat === "off") { Sounos.call("playSingle", { track: songs[i], context: { type: "radio" } }); window.openPlayer?.(); }
      else playFrom(songs, i, ctx);
    });
    return page;
  }

  // ---------- minhas músicas ----------
  function openMine(tracks) {
    const list = [...new Map((tracks || []).map(t => [t.id, t])).values()];
    const page = mount("mine"), el = page.el;
    const art = list.length
      ? `<div class="mosaic">${[0, 1, 2, 3].map(i => list[Math.min(i, list.length - 1)]).map(t => `<i style="background:${cover(t)}"></i>`).join("")}</div>`
      : `<div class="empty">${I.note}</div>`;
    el.innerHTML = `<div class="hero">${art}</div>
      <div class="ph"><div class="name">Minhas músicas</div>
      <div class="sub">${count(list.length)} · baixadas${Sounos.call("getOffline").offline && list.length ? " e em cache" : ""}</div></div>
      <div class="acts">
        <button class="big" data-a="play" aria-label="Tocar" ${list.length ? "" : "disabled"}>${I.play}</button>
        <button class="ab" data-a="shuffle" aria-label="Aleatório" ${list.length ? "" : "disabled"}>${I.shuffle}</button>
      </div>
      <div class="list">${list.length ? list.map((t, i) => rowHtml(t, i)).join("") : '<div class="note">Baixe músicas ou conecte à internet para encontrar músicas em cache.</div>'}</div>`;
    addBack(page);
    const markNow = () => { const id = nowId(); el.querySelectorAll(".tr").forEach(r => r.classList.toggle("now", r.dataset.id === id)); };
    page.markNow = markNow;
    el.addEventListener("click", e => {
      const action = e.target.closest("[data-a]")?.dataset.a;
      if (action === "play") return playFrom(list, 0, { type: "mine" });
      if (action === "shuffle") return playFrom(list, 0, { type: "mine" }, true);
      const row = e.target.closest(".tr");
      if (row) playFrom(list, +row.dataset.i, { type: "mine" });
    });
    markNow();
    return page;
  }

  // ---------- artista ----------
  function openArtist(name, artistInfo = {}) {
    if (name && typeof name === "object") { artistInfo = name; name = artistInfo.name; }
    name = String(name || "").trim() || "Artista";
    const intentAt = Number(artistInfo.__intentAt || window.__sonusArtistIntentAt) || performance.now();
    window.__sonusArtistIntentAt = 0;
    const photoUrl = Sounos.coverUrl({ id: "artist:" + (key(name) || "x"), name, thumb: artistInfo.thumb || "" });
    remember({ type: "a", name, thumb: artistInfo.thumb || "", url: artistInfo.url || "" });
    const page = mount("artist");
    const el = page.el;
    let songs = [], albums = [], showAllSongs = false, artistThumb = artistInfo.thumb || "", artistCatalogSignature = "";
    const localCatalog = readArtistPageCache(name);
    // primeiro o que já se sabe (histórico), depois o resto da rede
    const hist = (() => {
      try {
        const tracks = window.__sounosHistoryTracks;
        return (Array.isArray(tracks) ? tracks : (Sounos.call("getHistory") || []).map(e => e.track)).filter(Sounos.isSong);
      } catch { return []; }
    })();
    const mine = []; hist.forEach(t => { if (key(t.artist) === key(name) && !mine.some(m => m.id === t.id)) mine.push(t); });

    el.innerHTML = `<div class="hero" style="background:var(--surface)"><div class="img" style="background-image:url('${photoUrl.replace(/'/g, "%27")}')"></div></div>
      <div class="art-topo">
        <div class="art-txt">
          <div class="art-linha"><div class="name">${esc(handle(name))}</div><button class="ab follow" data-a="follow" aria-label="Seguir artista"></button></div>
        </div>
        <button class="big" data-a="play" aria-label="Tocar" disabled>${I.play}</button>
      </div>
      <div class="list">${mine.length ? `<div class="sec">Você ouviu</div>` + mine.slice(0, 5).map((t, i) => rowHtml(t, "m" + i, "", false, false)).join("") : ""}
        <div class="albums"></div>
        <div class="sec">Músicas</div><div class="songs">${'<div class="sk"></div>'.repeat(6)}</div>
        <button class="all-songs" hidden></button></div>`;
    addBack(page);
    const $ = s => el.querySelector(s);
    const followingKey = key(name);
    const readFollowing = () => {
      try { const v = JSON.parse(localStorage.getItem("sounos.following") || "[]"); return Array.isArray(v) ? v.filter(x => x && x.name && x.key) : []; } catch { return []; }
    };
    const paintFollow = () => {
      const b = $("[data-a=follow]"), on = readFollowing().some(x => x.key === followingKey);
      b.classList.toggle("on", on); b.innerHTML = `${on ? I.following : I.follow}<span>${on ? "Seguindo" : "Seguir"}</span>`;
      b.setAttribute("aria-label", on ? "Deixar de seguir artista" : "Seguir artista");
    };
    const sameSong = (a, b) => {
      const normTitle = t => String(t?.title || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^a-z0-9]/g, "");
      if (!normTitle(a) || normTitle(a) !== normTitle(b)) return false;
      const x = Number(a.dur || 0), y = Number(b.dur || 0);
      return x > 0 && y > 0 && Math.abs(x - y) <= Math.max(5, Math.max(x, y) * .03);
    };
    const heard = mine.slice(0, 5);
    const all = () => {
      const out = [...heard];
      for (const t of songs) if (!out.some(x => x.id === t.id || sameSong(x, t))) out.push(t);
      return out;
    };
    const drawSongs = () => {
      const shown = showAllSongs ? songs : songs.slice(0, 20);
      $(".songs").innerHTML = songs.length ? shown.map((t, i) => rowHtml(t, i, "", false, false)).join("") : `<div class="note">Nenhuma música encontrada.</div>`;
      const more = $(".all-songs");
      more.hidden = songs.length <= 20;
      more.textContent = showAllSongs ? "Mostrar principais" : `Ver todas · ${count(all().length)}`;
      markNow();
    };
    paintFollow();
    if (mine.length) $("[data-a=play]").disabled = false;

    const applyArtist = r => {
      if (page.closing) return;
      if (r.error) { $(".songs").innerHTML = `<div class="note">Não deu para carregar agora.</div>`; return; }
      const signature = (r.items || []).map(t => t.id).join("\u0000");
      if (signature && signature === artistCatalogSignature) return;
      artistCatalogSignature = signature;
      if (!r.fromLocalCache) saveArtistPageCache(name, r);
      songs = (r.items || []).filter(t => !heard.some(x => x.id === t.id || sameSong(x, t)));
      artistThumb = r.thumb || artistThumb;
      $(".name").textContent = handle(String(r.name || name || "Artista").trim() || "Artista");
      const total = all().length;
      $("[data-a=play]").disabled = !(songs.length || mine.length);
      drawSongs();
      if (r.isCached) {
        const metric = { name, cached: true, cacheMs: Math.round(performance.now() - intentAt), tracks: total };
        (window.__sonusArtistMetrics ||= []).push(metric); window.__sonusLastArtistMetric = metric;
      }
    };
    const applyAlbums = r => {
      if (page.closing || r.error) return;
      const target = key(name);
      const seenUrls = new Set(), seenNameCovers = new Set();
      albums = (r.items || []).filter(a => {
        if (!target || key(a.artist) !== target) return false;
        const albumUrl = a.url ? canonicalUrl(a.url) : "";
        const thumb = a.thumb ? imageKey(a.thumb) : "";
        const nameCover = thumb ? `${key(a.name)}|${thumb}` : "";
        if ((albumUrl && seenUrls.has(albumUrl)) || (nameCover && seenNameCovers.has(nameCover))) return false;
        if (albumUrl) seenUrls.add(albumUrl);
        if (nameCover) seenNameCovers.add(nameCover);
        return true;
      });
      if (!albums.length) { $(".albums").innerHTML = ""; return; }
      setTimeout(() => { if (!page.closing) window.SounosTips?.offer("artist-albums"); }, 450);
      $(".albums").innerHTML = `<div class="sec">Álbuns</div><div class="s-hrow">` + albums.map((a, i) => {
        const u = a.thumb ? Sounos.coverUrl({ id: "album:" + (key(a.name) || "x"), name: a.name, artist: a.artist, thumb: a.thumb }).replace(/'/g, "%27") : "";
        return `<button class="s-album" data-album="${i}"><div class="pic" style="background:${u ? `url('${u}') center/cover,` : ""} linear-gradient(135deg,${Sounos.hashColors(a.name).join(",")})"></div><div class="n">${esc(a.name)}</div><div class="a">${handle(a.artist)}</div></button>`;
      }).join("") + `</div>`;
    };
    page.cacheOff = [
      Sounos.on("metadataUpdated", ev => {
        if (ev.method === "artist" && ev.cacheKey === Sounos.cacheKey("artist", name)) applyArtist(ev.payload || {});
        if (ev.method === "searchAlbums" && ev.cacheKey === Sounos.cacheKey("searchAlbums", name)) applyAlbums(ev.payload || {});
      }),
    ];
    if (localCatalog) applyArtist(localCatalog);
    Sounos.ask("artist", { name, thumb: artistInfo.thumb || "", url: artistInfo.url || "" }).then(r => {
      applyArtist(r);
      if (!window.__sonusLastArtistMetric || window.__sonusLastArtistMetric.name !== name) requestAnimationFrame(() => {
        const metric = { name, cached: false, readyMs: Math.round(performance.now() - intentAt), tracks: all().length };
        (window.__sonusArtistMetrics ||= []).push(metric); window.__sonusLastArtistMetric = metric;
      });
    });
    // A busca de álbuns é independente das músicas, para a página aparecer sem esperar ambas.
    Sounos.ask("searchAlbums", { q: name }).then(applyAlbums);

    function markNow() { const id = nowId(); el.querySelectorAll(".tr").forEach(r => r.classList.toggle("now", r.dataset.id === id)); syncPagePlayback(page, "artist", name); }
    page.markNow = markNow;
    markNow();
    setTimeout(() => { if (!page.closing) window.SounosTips?.offer("artist"); }, 450);
    requestAnimationFrame(() => requestAnimationFrame(() => {
      if (page.closing) return;
      const metric = { name, visibleMs: Math.round(performance.now() - intentAt), heard: heard.length, placeholders: !songs.length };
      (window.__sonusArtistMetrics ||= []).push(metric); window.__sonusLastArtistMetric = metric;
    }));
    el.addEventListener("click", e => {
      const a = e.target.closest("[data-a]")?.dataset.a;
      const ctx = { type: "artist", name };
      // no artista tudo toca em aleatório (por isso não há botão de aleatório): o play embaralha o catálogo inteiro
      if (a === "play") return togglePagePlayback(page, "artist", name, all(), ctx, true);
      if (a === "follow") {
        const list = readFollowing(), index = list.findIndex(x => x.key === followingKey);
        if (index >= 0) list.splice(index, 1);
        else list.unshift({ name, key: followingKey, thumb: artistThumb || artistInfo.thumb || "", since: new Date().toISOString() });
        const json = JSON.stringify(list);
        try { localStorage.setItem("sounos.following", json); } catch { window.__sounosSave?.("sounos.following", json); }
        window.__sounosSave?.("sounos.following", json);
        Sounos.call("setFollowingArtists", { items: list });
        window.dispatchEvent(new CustomEvent("sounos:following", { detail: list }));
        paintFollow(); return;
      }
      if (e.target.closest(".all-songs")) { showAllSongs = !showAllSongs; return drawSongs(); }
      const album = e.target.closest("[data-album]"); if (album) { const x = albums[+album.dataset.album]; return openAlbum(x.url, x.name, x.artist, x.thumb); }
      const row = e.target.closest(".tr"); if (!row) return;
      // a música tocada vem primeiro; depois o resto do catálogo do artista, embaralhado
      const i = row.dataset.i, t = String(i).startsWith("m") ? mine[+i.slice(1)] : songs[+i];
      const lista = [t, ...all().filter(x => x.id !== t.id)];
      playFrom(lista, 0, ctx, "aleatorio");
    });
    markNow();
    return page;
  }

  const api = { attach, openPlaylist, openDownloads, openMine, openArtist, openAlbum, isOpen: () => stack.length > 0, closeTop: () => close(stack[stack.length - 1]) };
  return api;
})();
