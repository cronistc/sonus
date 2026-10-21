// Páginas de playlist e de artista (dentro do app). A capa toma o topo inteiro, sem ícones por cima.
// Fechar: "voltar" do celular, ou puxar a página para a direita.
// Uso: SounosPages.attach(celular, { onChange }) · .openPlaylist(id, { fresh }) · .openArtist(nome, dados)
window.SounosPages = (() => {
  const D = window.SounosData;
  const esc = s => String(s ?? "").replace(/[&<>"]/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[ch]));
  const handle = n => (window.Sounos?.handle || D.toHandle)(n);
  const cover = t => `url('${Sounos.coverUrl(t, true)}'), linear-gradient(135deg,${Sounos.hashColors(t.id).join(",")})`;
  const fmtTotal = s => { const m = Math.round(s / 60); return m >= 60 ? `${Math.floor(m / 60)} h ${m % 60} min` : `${m} min`; };
  const count = n => n === 1 ? "1 música" : `${n} músicas`;
  const remember = entry => {
    try { (window.SounosSearch || window.parent?.SounosSearch)?.remember(entry); } catch {}
  };
  const I = {
    play: '<svg viewBox="0 0 24 24"><polygon points="6 3 20 12 6 21 6 3"/></svg>',
    shuffle: '<svg viewBox="0 0 24 24"><path d="M2 18h1.4c1.3 0 2.5-.6 3.3-1.7l6.1-8.6c.7-1.1 2-1.7 3.3-1.7H22"/><path d="m18 2 4 4-4 4"/><path d="M2 6h1.9c1.5 0 2.9.9 3.6 2.2"/><path d="M22 18h-5.9c-1.3 0-2.6-.7-3.3-1.8l-.5-.8"/><path d="m18 14 4 4-4 4"/></svg>',
    dl: '<svg viewBox="0 0 24 24"><path d="M12 4v11"/><path d="m7 11 5 5 5-5"/><path d="M5 20h14"/></svg>',
    done: '<svg viewBox="0 0 24 24"><path d="m5 12 5 5 9-10"/></svg>',
    img: '<svg viewBox="0 0 24 24"><rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="9" cy="9" r="2"/><path d="m21 15-5-5L5 21"/></svg>',
    edit: '<svg viewBox="0 0 24 24"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>',
    grip: '<svg viewBox="0 0 24 24"><path d="M5 9h14M5 15h14"/></svg>',
    rm: '<svg viewBox="0 0 24 24"><path d="M5 12h14"/></svg>',
    note: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>',
  };

  let phone, opts = {}, stack = [];          // páginas abertas (uma por cima da outra)

  function attach(ph, o = {}) {
    phone = ph; opts = o;
    // "voltar": fecha as páginas que estavam acima do ponto do histórico onde se chegou
    addEventListener("popstate", () => { const cur = history.state?.d || 0; [...stack].reverse().forEach(p => p.d > cur && close(p, true)); });
    // a playlist mudou em outro lugar ("+" no player): a página aberta acompanha
    addEventListener("storage", e => { if (e.key === "sounos.playlists") stack.forEach(p => p.kind === "playlist" && p.refresh()); });
    // música atual vem no próprio evento (antes: cada página aberta pedia o estado inteiro ao app de novo)
    Sounos.on("state", st => { curId = st?.current?.id ?? null; stack.forEach(p => p.markNow()); });
    Sounos.on("download", ev => stack.forEach(p => p.onDownload(ev)));
    return api;
  }

  // ---------- base comum ----------
  function mount(kind) {
    const el = document.createElement("div");
    el.className = "pg " + kind;
    phone.appendChild(el);
    const page = { el, kind, closing: false, refresh() {}, markNow() {}, onDownload() {} };
    stack.push(page);
    phone.classList.add("has-page");
    page.d = (history.state?.d || 0) + 1;
    history.pushState({ page: kind, d: page.d }, "");
    requestAnimationFrame(() => requestAnimationFrame(() => el.classList.add("open")));
    swipeToClose(page);
    return page;
  }
  function close(page, fromPop) {
    if (!page || page.closing) return;
    page.closing = true;
    document.activeElement?.blur?.();
    page.el.classList.add("closing");
    page.el.style.transform = "";
    stack = stack.filter(p => p !== page);
    if (!stack.length) setTimeout(() => !stack.length && phone.classList.remove("has-page"), 320);
    setTimeout(() => page.el.remove(), 320);
    if (!fromPop) history.back();
    opts.onChange?.();
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
  function playFrom(list, i, ctx, shuffle) {
    if (!list.length) return;
    if (shuffle) Sounos.call("setShuffle", { on: true });     // sem pedir aleatório, o modo da pessoa fica como está
    const index = shuffle ? Math.floor(Math.random() * list.length) : i;
    remember({ type: "t", track: list[index] });
    Sounos.call("playQueue", { tracks: list, index, context: ctx });
    window.openPlayer?.();
  }
  const st = () => { try { return Sounos.call("getState") || {}; } catch { return {}; } };
  let curId;
  const nowId = () => { if (curId === undefined) { try { curId = Sounos.call("getState").current?.id ?? null; } catch { curId = null; } } return curId; };
  const rowHtml = (t, i, extra = "") => `<div class="tr" data-i="${i}" data-id="${esc(t.id)}">
      <div class="cv" style="background-image:${cover(t)}"></div>
      <div class="tx"><div class="t">${esc(t.title)}</div><div class="a">${handle(t.artist)}</div></div>${extra}</div>`;

  // ---------- playlist ----------
  function openPlaylist(id, o = {}) {
    const page = mount("playlist");
    const el = page.el;
    const get = () => D.getPlaylists().find(p => p.id === id);
    const opened = get();
    if (opened) remember({ type: "p", id: opened.id, name: opened.name, cover: opened.cover || "" });
    const save = mutate => { const all = D.getPlaylists(); const p = all.find(x => x.id === id); if (!p) return; mutate(p); D.savePlaylists(all); opts.onChange?.(); };
    const dls = {};                              // id → progresso do download desta página
    let dlQueue = [], dlBusy = null;

    el.innerHTML = `<div class="hero"></div>
      <div class="ph">
        <div class="name" contenteditable="plaintext-only" spellcheck="false" data-ph="Nome da playlist"></div>
        <div class="desc" contenteditable="plaintext-only" data-ph="Adicione uma descrição"></div>
        <div class="sub"></div>
      </div>
      <div class="acts">
        <button class="big" data-a="play" aria-label="Tocar">${I.play}</button>
        <button class="ab" data-a="shuffle" aria-label="Aleatório">${I.shuffle}</button>
        <button class="ab" data-a="dl" aria-label="Baixar playlist">${I.dl}<svg class="ring" viewBox="0 0 36 36"><circle cx="18" cy="18" r="15.9" pathLength="100"/></svg></button>
        <span class="sp"></span>
        <button class="ab" data-a="img" aria-label="Trocar capa">${I.img}</button>
        <button class="ab" data-a="edit" aria-label="Editar">${I.edit}</button>
      </div>
      <div class="list"></div>
      <input type="file" accept="image/*" hidden>`;
    const $ = s => el.querySelector(s);
    const nameEl = $(".name"), descEl = $(".desc"), list = $(".list"), file = $("input[type=file]");

    function hero(p) {
      const h = $(".hero");
      if (p.cover) h.innerHTML = `<div class="img" style="background-image:url('${p.cover}')"></div>`;
      else if (p.tracks.length) {
        const four = [0, 1, 2, 3].map(i => p.tracks[Math.min(i, p.tracks.length - 1)]);
        h.innerHTML = `<div class="mosaic">${four.map(t => `<i style="background-image:${cover(t)}"></i>`).join("")}</div>`;
      } else h.innerHTML = `<div class="empty">${I.note}</div>`;
    }
    function render() {
      const p = get(); if (!p) return close(page);
      hero(p);
      if (document.activeElement !== nameEl) nameEl.textContent = p.name || "";
      if (document.activeElement !== descEl) descEl.textContent = p.desc || "";
      $(".sub").textContent = p.tracks.length ? `${count(p.tracks.length)} · ${fmtTotal(p.tracks.reduce((a, t) => a + (t.dur || 0), 0))}` : "";
      $("[data-a=play]").disabled = !p.tracks.length;
      const done = downloaded();
      list.innerHTML = p.tracks.length
        ? p.tracks.map((t, i) => rowHtml(t, i, `${done[t.id] ? '<span class="dl"></span>' : ""}<button class="rm" aria-label="Tirar da playlist">${I.rm}</button><button class="grip" aria-label="Arrastar para ordenar">${I.grip}</button>`)).join("")
          + `<button class="del">Apagar playlist</button>`
        : `<div class="note">Playlist vazia.<br>No player, toque no + para adicionar a música que está tocando.</div><button class="del">Apagar playlist</button>`;
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
    }
    page.refresh = render; page.markNow = markNow;
    page.onDownload = ev => {
      if (!(ev.id in dls)) return;
      if (ev.error) { delete dls[ev.id]; } else dls[ev.id] = ev.done ? 1 : ev.p;
      if (ev.done || ev.error) { dlBusy = null; nextDl(); render(); } else renderDl();
    };
    function nextDl() {                          // uma por vez, para não brigar com a música tocando
      const t = dlQueue.shift(); if (!t) { dlBusy = null; renderDl(); return; }
      dlBusy = t.id; dls[t.id] = 0; Sounos.call("download", { track: t });
    }

    // nome e descrição: editáveis direto na página
    const saveText = () => save(p => { p.name = nameEl.textContent.trim() || "Nova playlist"; p.desc = descEl.textContent.trim(); });
    let textT = 0;
    [nameEl, descEl].forEach(x => {
      x.addEventListener("blur", saveText);
      x.addEventListener("input", () => { clearTimeout(textT); textT = setTimeout(saveText, 400); });
      x.addEventListener("keydown", e => { if (e.key === "Enter") { e.preventDefault(); x.blur(); } });
    });

    el.addEventListener("click", e => {
      const a = e.target.closest("[data-a]")?.dataset.a;
      const p = get(); if (!p) return;
      if (a === "play") return playFrom(p.tracks, 0, { type: "playlist", id, name: p.name });
      if (a === "shuffle") return playFrom(p.tracks, 0, { type: "playlist", id, name: p.name }, true);
      if (a === "dl") {
        const done = downloaded();
        dlQueue = p.tracks.filter(t => !done[t.id] && dls[t.id] !== 1 && t.id !== dlBusy);
        if (!dlBusy) nextDl(); renderDl(); return;
      }
      if (a === "img") return file.click();
      if (a === "edit") { el.classList.toggle("editing"); $("[data-a=edit]").classList.toggle("on", el.classList.contains("editing")); disarm(); return; }
      const del = e.target.closest(".del");
      if (del) {                                 // dois toques: o primeiro arma, o segundo apaga
        if (!del.classList.contains("armed")) { del.classList.add("armed"); del.textContent = "Toque de novo para apagar"; return; }
        D.savePlaylists(D.getPlaylists().filter(x => x.id !== id)); opts.onChange?.(); return close(page);
      }
      const rm = e.target.closest(".rm");
      if (rm) { const i = +rm.closest(".tr").dataset.i; save(p => p.tracks.splice(i, 1)); return render(); }
      if (e.target.closest(".grip")) return;
      const row = e.target.closest(".tr");
      if (row && !el.classList.contains("editing")) playFrom(p.tracks, +row.dataset.i, { type: "playlist", id, name: p.name });
    });
    const disarm = () => { const d = $(".del"); if (d) { d.classList.remove("armed"); d.textContent = "Apagar playlist"; } };

    // capa escolhida pela pessoa: reduzida para 900 px e guardada com a playlist
    file.addEventListener("change", () => {
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

  // ---------- álbum ----------
  function openAlbum(url, name, artist, thumb) {
    const page = mount("album");
    const el = page.el;
    let songs = [];
    const key = s => String(s || "").normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().replace(/[^a-z0-9]/g, "");
    const albumCover = (n, t) => t ? `url('${Sounos.coverUrl({ id: "album:" + (key(n) || "x"), thumb: t }, true)}')` : `linear-gradient(135deg,${Sounos.hashColors(n).join(",")})`;
    el.innerHTML = `<div class="hero"><div class="img" style="background-image:${albumCover(name, thumb)}"></div></div>
      <div class="ph"><div class="name"></div><div class="sub"></div></div>
      <div class="acts"><button class="big" data-a="play" aria-label="Tocar" disabled>${I.play}</button>
        <button class="ab" data-a="shuffle" aria-label="Aleatório">${I.shuffle}</button></div>
      <div class="list"><div class="sec">Músicas</div><div class="songs">${'<div class="sk"></div>'.repeat(6)}</div></div>`;
    const $ = s => el.querySelector(s);
    $(".name").textContent = name;
    $(".sub").textContent = handle(artist);
    Sounos.ask("album", { url }).then(r => {
      if (page.closing) return;
      if (r.error) { $(".songs").innerHTML = `<div class="note">Não deu para carregar agora.</div>`; return; }
      name = r.name || name; artist = r.artist || artist; songs = r.items || [];
      $(".name").textContent = name;
      $(".sub").textContent = `${handle(artist)}${songs.length ? ` · ${count(songs.length)}` : ""}`;
      if (r.thumb) $(".hero").innerHTML = `<div class="img" style="background-image:${albumCover(name, r.thumb)}"></div>`;
      $("[data-a=play]").disabled = !songs.length;
      $(".songs").innerHTML = songs.length ? songs.map((t, i) => rowHtml(t, i)).join("") : `<div class="note">Nenhuma música encontrada.</div>`;
      markNow();
    });
    function markNow() { const id = nowId(); el.querySelectorAll(".tr").forEach(r => r.classList.toggle("now", r.dataset.id === id)); }
    page.markNow = markNow;
    el.addEventListener("click", e => {
      const a = e.target.closest("[data-a]")?.dataset.a;
      const ctx = { type: "album", name };
      // play/aleatório da capa: só as músicas do álbum (sai do "repetir uma" para a fila do álbum aparecer)
      if (a === "play" || a === "shuffle") {
        if (!songs.length) return;
        if (st().repeat === "one") Sounos.call("setRepeat", { mode: "off" });
        if (a === "play") Sounos.call("setShuffle", { on: false });
        return playFrom(songs, 0, ctx, a === "shuffle");
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
    const key = s => String(s || "").normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().replace(/[^a-z0-9]/g, "");
    const photoUrl = Sounos.coverUrl({ id: "artist:" + (key(name) || "x"), name, thumb: artistInfo.thumb || "" }, true);
    remember({ type: "a", name, thumb: artistInfo.thumb || "" });
    const page = mount("artist");
    const el = page.el;
    let songs = [], albums = [];
    // primeiro o que já se sabe (histórico), depois o resto da rede
    const hist = (() => { try { return (Sounos.call("getHistory") || []).map(e => e.track).filter(Sounos.isSong); } catch { return []; } })();
    const mine = []; hist.forEach(t => { if (key(t.artist) === key(name) && !mine.some(m => m.id === t.id)) mine.push(t); });

    el.innerHTML = `<div class="hero" style="background:var(--surface)"><div class="img" style="background-image:url('${photoUrl.replace(/'/g, "%27")}')"></div></div>
      <div class="ph"><div class="name">${handle(name)}</div><div class="sub">Artista</div></div>
      <div class="acts">
        <button class="big" data-a="play" aria-label="Tocar" disabled>${I.play}</button>
        <button class="ab" data-a="shuffle" aria-label="Aleatório">${I.shuffle}</button>
      </div>
      <div class="list">${mine.length ? `<div class="sec">Você ouviu</div>` + mine.slice(0, 5).map((t, i) => rowHtml(t, "m" + i)).join("") : ""}
        <div class="albums"></div>
        <div class="sec">Músicas</div><div class="songs">${'<div class="sk"></div>'.repeat(6)}</div></div>`;
    const $ = s => el.querySelector(s);
    if (mine.length) $("[data-a=play]").disabled = false;

    Sounos.ask("artist", { name, thumb: artistInfo.thumb || "" }).then(r => {
      if (page.closing) return;
      if (r.error) { $(".songs").innerHTML = `<div class="note">Não deu para carregar agora.</div>`; return; }
      songs = r.items || [];
      $(".name").textContent = handle(String(r.name || name || "Artista").trim() || "Artista");
      $(".sub").textContent = songs.length ? `Artista · ${count(songs.length)}` : "Artista";
      $("[data-a=play]").disabled = !(songs.length || mine.length);
      $(".songs").innerHTML = songs.length ? songs.map((t, i) => rowHtml(t, i)).join("") : `<div class="note">Nenhuma música encontrada.</div>`;
      markNow();
    });
    // A busca de álbuns é independente das músicas, para a página aparecer sem esperar ambas.
    Sounos.ask("searchAlbums", { q: name }).then(r => {
      if (page.closing || r.error) return;
      const target = key(name);
      albums = (r.items || []).filter(a => {
        const by = key(a.artist);
        return by && (by === target || by.includes(target) || target.includes(by));
      });
      if (!albums.length) return;
      $(".albums").innerHTML = `<div class="sec">Álbuns</div><div class="s-hrow">` + albums.map((a, i) => {
        const u = a.thumb ? Sounos.coverUrl({ id: "album:" + (key(a.name) || "x"), thumb: a.thumb }, true).replace(/'/g, "%27") : "";
        return `<button class="s-album" data-album="${i}"><div class="pic" style="background:${u ? `url('${u}') center/cover,` : ""} linear-gradient(135deg,${Sounos.hashColors(a.name).join(",")})"></div><div class="n">${esc(a.name)}</div><div class="a">${handle(a.artist)}</div></button>`;
      }).join("") + `</div>`;
    });

    function markNow() { const id = nowId(); el.querySelectorAll(".tr").forEach(r => r.classList.toggle("now", r.dataset.id === id)); }
    page.markNow = markNow;
    const all = () => songs.length ? songs : mine;
    el.addEventListener("click", e => {
      const a = e.target.closest("[data-a]")?.dataset.a;
      const ctx = { type: "artist", name };
      if (a === "play") return playFrom(all(), 0, ctx);
      if (a === "shuffle") return playFrom(all(), 0, ctx, true);
      const album = e.target.closest("[data-album]"); if (album) { const x = albums[+album.dataset.album]; return openAlbum(x.url, x.name, x.artist, x.thumb); }
      const row = e.target.closest(".tr"); if (!row) return;
      const i = row.dataset.i;
      if (String(i).startsWith("m")) playFrom(mine, +i.slice(1), ctx); else playFrom(songs, +i, ctx);
    });
    markNow();
    return page;
  }

  const api = { attach, openPlaylist, openMine, openArtist, openAlbum, isOpen: () => stack.length > 0, closeTop: () => close(stack[stack.length - 1]) };
  return api;
})();
