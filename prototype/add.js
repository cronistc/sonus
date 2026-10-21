// Janela do "+": adiciona a música atual a uma playlist ou abre o fluxo de criação.
// Uso: const add = SounosAdd.attach(celular, { onChange }); add.open(indiceDaFaixa) · add.openCreate()
(() => {
  const D = window.SounosData;
  const CHECK = '<svg viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>';
  const PLUS = '<svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>';
  const key = t => String(t && typeof t === "object" ? t.id ?? t.i ?? `${t.title}|${t.artist}` : t);
  const trackAt = t => typeof t === "object" ? t : D.tracks[t];
  const look = t => {
    const x = trackAt(t);
    if (x?.id && Sounos.native) return `url('${Sounos.coverUrl(x, true)}') center/cover no-repeat, ${D.grad(Sounos.hashColors(x.id))}`;
    return D.grad(x?.c || D.tracks[x?.i]?.c || ["#2f6bff", "#1d4ed8", "#60a5fa"]);
  };
  const mosaic = p => {
    if (p.cover) return `<i class="add-full-cover" style="background-image:url('${p.cover.replace(/'/g, "%27")}')"></i>`;
    if (!p.tracks.length) return `<i style="grid-area:1/1/3/3;background:${D.grad(["#2f6bff", "#1d4ed8", "#60a5fa"])}"></i>`;
    const t = p.tracks.slice(0, 4);
    while (t.length < 4) t.push(t[t.length - 1]);
    return t.map(i => `<i style="background:${look(i)}"></i>`).join("");
  };
  const count = n => n === 1 ? "1 música" : `${n} músicas`;
  const esc = D.esc;

  function attach(phone, { onChange } = {}) {
    const ov = document.createElement("div");
    ov.className = "add-ov";
    ov.innerHTML = `
      <div class="dim"></div>
      <div class="add-sheet" role="dialog" aria-label="Adicionar à playlist">
        <div class="add-grab"><i></i></div>
        <div class="add-head"></div>
        <div class="add-list">
          <div class="list-mode">
            <div class="add-new" style="--i:0"><button class="open-btn"><span class="plus">${PLUS}</span>Nova playlist</button></div>
            <div class="rows"></div>
          </div>
          <div class="create-mode" hidden>
            <div class="add-create-fields">
              <input class="playlist-name" type="text" placeholder="Nome da playlist" maxlength="60" autocomplete="off">
              <button class="cover-button" type="button"><span class="cover-preview">${PLUS}</span><span>Escolher capa</span></button>
              <input class="cover-file" type="file" accept="image/*" hidden>
            </div>
            <div class="add-section">Escolher músicas</div>
            <div class="add-search"><input class="song-search" type="search" placeholder="Buscar músicas" autocomplete="off" enterkeyhint="search"></div>
            <div class="add-section">Músicas recentes</div>
            <div class="recent-rows"></div>
            <div class="add-section search-title" hidden>Resultados da busca</div>
            <div class="search-rows"></div>
          </div>
        </div>
        <div class="add-foot"><button class="done">Pronto</button><button class="confirm" hidden disabled>Criar playlist</button></div>
      </div>`;
    phone.appendChild(ov);
    const sheet = ov.querySelector(".add-sheet"), rows = ov.querySelector(".rows"), nw = ov.querySelector(".add-new");
    const listMode = ov.querySelector(".list-mode"), createMode = ov.querySelector(".create-mode");
    const nameInput = ov.querySelector(".playlist-name"), coverButton = ov.querySelector(".cover-button");
    const coverFile = ov.querySelector(".cover-file"), songSearch = ov.querySelector(".song-search");
    const recentRows = ov.querySelector(".recent-rows"), searchRows = ov.querySelector(".search-rows");
    const searchTitle = ov.querySelector(".search-title"), confirm = ov.querySelector(".confirm");
    let track = null, lists = [], isOpen = false, pushed = false, changed = false;
    let selected = new Map(), playlistCover = "", recent = [], queryTimer = 0, searchSeq = 0;

    function playlistRow(p, k) {
      const on = track != null && p.tracks.some(x => key(x) === key(track));
      return `<button class="add-row ${on ? "on" : ""}" data-id="${esc(p.id)}" style="--i:${k + 1}">
        <div class="cv">${mosaic(p)}</div>
        <div class="tx"><div class="t">${esc(p.name)}</div><div class="a">${count(p.tracks.length)}</div></div>
        <span class="chk">${CHECK}</span></button>`;
    }
    function renderLists() { rows.innerHTML = lists.map((p, k) => playlistRow(p, k)).join(""); }
    function songRow(t) {
      const id = key(t), on = selected.has(id);
      const title = t?.title || "Música", artist = Sounos.artistLine(t);
      return `<button class="add-row song-row ${on ? "on" : ""}" data-song="${esc(id)}">
        <div class="cv" style="background:${look(t)}"></div>
        <div class="tx"><div class="t">${esc(title)}</div><div class="a">${esc(artist)}</div></div>
        <span class="chk">${CHECK}</span></button>`;
    }
    function renderSongs() {
      recentRows.innerHTML = recent.length ? recent.map(songRow).join("") : `<div class="add-empty">Suas músicas recentes aparecem aqui.</div>`;
      searchRows.innerHTML = searchRows.dataset.items ? JSON.parse(searchRows.dataset.items).map(songRow).join("") : "";
      updateConfirm();
    }
    function updateConfirm() {
      const valid = !!nameInput.value.trim() && selected.size > 0;
      confirm.disabled = !valid;
      confirm.textContent = selected.size ? `Criar playlist · ${count(selected.size)}` : "Criar playlist";
    }
    function recentTracks() {
      if (Sounos.native) {
        const history = Sounos.call("getHistory");
        const seen = new Set();
        return (Array.isArray(history) ? history : []).map(e => e.track).filter(t => {
          if (!t || (Sounos.isSong && !Sounos.isSong(t)) || seen.has(key(t))) return false;
          seen.add(key(t)); return true;
        }).slice(0, 30);
      }
      return D.recentPlays.map(i => D.tracks[i]).filter((t, i, all) => all.findIndex(x => key(x) === key(t)) === i);
    }
    function openHeader() {
      const t = trackAt(track);
      if (t) {
        ov.querySelector(".add-head").innerHTML = `<div class="cv" style="background:${look(t)}"></div>
          <div><div class="k">Nova playlist</div><div class="t">${esc(t.title || "Escolha as músicas")}</div><div class="a">${esc(Sounos.artistLine(t) || "Esta música já está selecionada")}</div></div>`;
      } else {
        ov.querySelector(".add-head").innerHTML = `<div class="cv">${PLUS}</div>
          <div><div class="k">Nova playlist</div><div class="t">Escolha nome e músicas</div><div class="a">A playlist só será salva ao confirmar</div></div>`;
      }
    }
    function startCreate(initialTrack = null) {
      searchSeq++;
      track = initialTrack;
      selected = new Map();
      if (track != null) { const t = trackAt(track); if (t) selected.set(key(t), t); }
      playlistCover = "";
      nameInput.value = ""; songSearch.value = "";
      coverFile.value = "";
      coverButton.querySelector("span:last-child").textContent = "Escolher capa";
      coverButton.querySelector(".cover-preview").innerHTML = PLUS;
      coverButton.querySelector(".cover-preview").style.backgroundImage = "";
      searchRows.dataset.items = ""; searchRows.innerHTML = ""; searchTitle.hidden = true;
      recent = recentTracks();
      openHeader(); renderSongs();
      listMode.hidden = true; createMode.hidden = false;
      ov.querySelector(".done").hidden = true; confirm.hidden = false;
      ov.classList.add("creating");
    }
    function open(trackIndex) {
      searchSeq++;
      track = trackIndex; lists = D.getPlaylists(); changed = false;
      const t = trackAt(track);
      ov.querySelector(".add-head").innerHTML = t ? `<div class="cv" style="background:${look(t)}"></div>
        <div><div class="k">Adicionar à playlist</div><div class="t">${esc(t.title || "Música")}</div><div class="a">${esc(Sounos.artistLine(t))}</div></div>` : "";
      nw.classList.remove("editing"); renderLists();
      listMode.hidden = false; createMode.hidden = true;
      ov.querySelector(".done").hidden = false; confirm.hidden = true;
      ov.classList.remove("creating"); sheet.style.transform = "";
      ov.classList.remove("open"); void ov.offsetWidth;
      ov.classList.add("open"); isOpen = true;
      window.history.pushState({ addSheet: true }, ""); pushed = true;
    }
    function openCreate(options = {}) {
      searchSeq++;
      track = options.track ?? null; lists = D.getPlaylists(); changed = false;
      startCreate(track);
      sheet.style.transform = "";
      ov.classList.remove("open"); void ov.offsetWidth;
      ov.classList.add("open"); isOpen = true;
      window.history.pushState({ addSheet: true }, ""); pushed = true;
      setTimeout(() => nameInput.focus({ preventScroll: true }), 250);
    }
    function close(fromPop = false) {
      if (!isOpen) return;
      isOpen = false; nameInput.blur(); songSearch.blur();
      sheet.classList.remove("dragging"); sheet.style.transform = "";
      ov.classList.remove("open", "creating");
      listMode.hidden = false; createMode.hidden = true;
      if (changed) { D.savePlaylists(lists); onChange?.(lists); }
      changed = false; searchSeq++;
      if (!fromPop && pushed) { pushed = false; window.history.back(); }
    }
    window.addEventListener("popstate", () => { if (isOpen && !history.state?.addSheet) { pushed = false; close(true); } });

    rows.addEventListener("click", e => {
      const row = e.target.closest(".add-row"); if (!row) return;
      const p = lists.find(x => x.id === row.dataset.id); if (!p || track == null) return;
      const has = p.tracks.some(x => key(x) === key(track));
      p.tracks = has ? p.tracks.filter(x => key(x) !== key(track)) : [...p.tracks, track];
      changed = true;
      row.classList.toggle("on", !has);
      row.querySelector(".a").textContent = count(p.tracks.length);
      if (navigator.vibrate) try { navigator.vibrate(10); } catch {}
    });
    nw.querySelector(".open-btn").addEventListener("click", () => startCreate(track));

    function toggleSong(id) {
      const t = [...recent, ...(JSON.parse(searchRows.dataset.items || "[]"))].find(x => key(x) === id);
      if (!t) return;
      if (selected.has(id)) selected.delete(id); else selected.set(id, t);
      renderSongs();
    }
    [recentRows, searchRows].forEach(box => box.addEventListener("click", e => {
      const row = e.target.closest("[data-song]"); if (row) toggleSong(row.dataset.song);
    }));
    nameInput.addEventListener("input", updateConfirm);
    nameInput.addEventListener("keydown", e => { if (e.key === "Enter") { e.preventDefault(); songSearch.focus(); } });
    songSearch.addEventListener("input", () => {
      clearTimeout(queryTimer);
      const q = songSearch.value.trim(), seq = ++searchSeq;
      if (!q) { searchRows.dataset.items = ""; searchRows.innerHTML = ""; searchTitle.hidden = true; return; }
      queryTimer = setTimeout(async () => {
        let found = [];
        if (Sounos.native) {
          const result = await Sounos.ask("search", { q });
          found = result.items || [];
        } else {
          const normalized = q.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();
          found = D.tracks.filter(t => `${t.title} ${t.artist}`.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().includes(normalized));
        }
        if (seq !== searchSeq || !isOpen || songSearch.value.trim() !== q) return;
        searchRows.dataset.items = JSON.stringify(found);
        searchRows.innerHTML = found.length ? found.map(songRow).join("") : `<div class="add-empty">Nenhuma música encontrada.</div>`;
        searchTitle.hidden = false;
      }, 250);
    });
    songSearch.addEventListener("keydown", e => { if (e.key === "Enter") { e.preventDefault(); songSearch.dispatchEvent(new Event("input")); } });
    coverButton.addEventListener("click", () => coverFile.click());
    coverFile.addEventListener("change", () => {
      const f = coverFile.files?.[0]; if (!f) return;
      const img = new Image();
      img.onload = () => {
        const s = Math.min(img.width, img.height), out = Math.min(900, s), c = document.createElement("canvas");
        c.width = c.height = out;
        c.getContext("2d").drawImage(img, (img.width - s) / 2, (img.height - s) / 2, s, s, 0, 0, out, out);
        playlistCover = c.toDataURL("image/jpeg", .86);
        coverButton.querySelector(".cover-preview").style.backgroundImage = `url('${playlistCover}')`;
        coverButton.querySelector(".cover-preview").innerHTML = "";
        coverButton.querySelector("span:last-child").textContent = "Trocar capa";
        URL.revokeObjectURL(img.src);
      };
      img.src = URL.createObjectURL(f); coverFile.value = "";
    });
    confirm.addEventListener("click", () => {
      if (!isOpen) return;
      const name = nameInput.value.trim(); if (!name || !selected.size) return;
      const p = { id: "pl" + Date.now().toString(36), name, desc: "", cover: playlistCover, tracks: [...selected.values()], created: Date.now() };
      lists = [...D.getPlaylists(), p];
      D.savePlaylists(lists); onChange?.(lists);
      close();
    });
    ov.querySelector(".dim").addEventListener("click", () => close());
    ov.querySelector(".done").addEventListener("click", () => close());

    const dg = { y: 0, on: false, dy: 0 };
    ov.querySelectorAll(".add-grab, .add-head").forEach(el => el.addEventListener("pointerdown", e => {
      Object.assign(dg, { y: e.clientY, on: true, dy: 0 }); sheet.classList.add("dragging");
    }));
    window.addEventListener("pointermove", e => {
      if (!dg.on) return;
      dg.dy = Math.max(0, e.clientY - dg.y); sheet.style.transform = `translateY(${dg.dy}px)`;
    });
    ["pointerup", "pointercancel"].forEach(t => window.addEventListener(t, () => {
      if (!dg.on) return;
      dg.on = false; sheet.classList.remove("dragging");
      if (dg.dy > 110) close(); else sheet.style.transform = "";
    }));

    return { open, openCreate, close, isOpen: () => isOpen };
  }

  window.SounosAdd = { attach };
})();
