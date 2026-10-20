// Janela do "+" (protótipo): adiciona a música atual a playlists ou cria uma nova.
// Uso: const add = SounosAdd.attach(celular, { onChange }); add.open(indiceDaFaixa)
(() => {
  const D = window.SounosData;
  const CHECK = '<svg viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>';
  const PLUS = '<svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>';
  const key = t => (t && typeof t === "object") ? t.id : t;
  const look = t => (t && typeof t === "object")
    ? `url('${Sounos.coverUrl(t, true)}') center/cover no-repeat, ${D.grad(Sounos.hashColors(t.id))}`
    : D.grad(D.tracks[t].c);
  const mosaic = p => {
    if (!p.tracks.length) return `<i style="grid-area:1/1/3/3;background:${D.grad(["#2f6bff", "#1d4ed8", "#60a5fa"])}"></i>`;
    const t = p.tracks.slice(0, 4);
    while (t.length < 4) t.push(t[t.length - 1]);
    return t.map(i => `<i style="background:${look(i)}"></i>`).join("");
  };
  const count = n => n === 1 ? "1 música" : `${n} músicas`;

  function attach(phone, { onChange } = {}) {
    const ov = document.createElement("div");
    ov.className = "add-ov";
    ov.innerHTML = `
      <div class="dim"></div>
      <div class="add-sheet" role="dialog" aria-label="Adicionar à playlist">
        <div class="add-grab"><i></i></div>
        <div class="add-head"></div>
        <div class="add-list">
          <div class="add-new" style="--i:0">
            <button class="open-btn"><span class="plus">${PLUS}</span>Nova playlist</button>
            <div class="form"><div><div class="fi">
              <input type="text" placeholder="Nome da playlist" maxlength="60" enterkeyhint="done">
              <button class="create" disabled>Criar</button>
            </div></div></div>
          </div>
          <div class="rows"></div>
        </div>
        <div class="add-foot"><button class="done">Pronto</button></div>
      </div>`;
    phone.appendChild(ov);
    const sheet = ov.querySelector(".add-sheet"), rows = ov.querySelector(".rows"), nw = ov.querySelector(".add-new");
    const input = nw.querySelector("input"), create = nw.querySelector(".create");
    let track = 0, lists = [], isOpen = false, pushed = false, changed = false;

    function rowHtml(p, k, born = false) {
      const on = p.tracks.some(x => key(x) === key(track));
      return `<button class="add-row ${on ? "on" : ""} ${born ? "born" : ""}" data-id="${p.id}" style="--i:${k + 1}">
        <div class="cv">${mosaic(p)}</div>
        <div class="tx"><div class="t">${D.esc(p.name)}</div><div class="a">${count(p.tracks.length)}</div></div>
        <span class="chk">${CHECK}</span></button>`;
    }
    function render() { rows.innerHTML = lists.map((p, k) => rowHtml(p, k)).join(""); }

    function open(trackIndex) {
      track = trackIndex; lists = D.getPlaylists(); changed = false;
      const t = typeof track === "object" ? track : D.tracks[track];
      ov.querySelector(".add-head").innerHTML = `
        <div class="cv" style="background:${look(track)}">${typeof track === "object" ? "" : D.esc(t.title[0])}</div>
        <div><div class="k">Adicionar à playlist</div><div class="t">${D.esc(t.title)}</div><div class="a">${D.toHandle(t.artist)}</div></div>`;
      nw.classList.remove("editing"); input.value = ""; create.disabled = true;
      render();
      sheet.style.transform = "";
      ov.classList.remove("open"); void ov.offsetWidth;            // reinicia as animações de entrada
      ov.classList.add("open"); isOpen = true;
      window.history.pushState({ addSheet: true }, ""); pushed = true;
    }
    function close(fromPop = false) {
      if (!isOpen) return;
      isOpen = false;
      input.blur();
      sheet.classList.remove("dragging"); sheet.style.transform = "";
      ov.classList.remove("open");
      if (changed) { D.savePlaylists(lists); onChange?.(lists); }
      if (!fromPop && pushed) { pushed = false; window.history.back(); }
    }
    window.addEventListener("popstate", () => { if (isOpen && !history.state?.addSheet) { pushed = false; close(true); } });

    // marcar / desmarcar
    rows.addEventListener("click", e => {
      const row = e.target.closest(".add-row"); if (!row) return;
      const p = lists.find(x => x.id === row.dataset.id);
      const has = p.tracks.some(x => key(x) === key(track));
      p.tracks = has ? p.tracks.filter(x => key(x) !== key(track)) : [...p.tracks, track];
      changed = true;
      row.classList.toggle("on", !has);
      row.querySelector(".a").textContent = count(p.tracks.length);
      if (navigator.vibrate) try { navigator.vibrate(10); } catch {}
    });

    // nova playlist: a linha abre um campo; ao criar, ela entra no topo já com a música marcada
    nw.querySelector(".open-btn").addEventListener("click", () => {
      const editing = nw.classList.toggle("editing");
      if (editing) setTimeout(() => input.focus({ preventScroll: true }), 250); else input.blur();
    });
    input.addEventListener("input", () => create.disabled = !input.value.trim());
    input.addEventListener("keydown", e => { if (e.key === "Enter" && input.value.trim()) create.click(); });
    create.addEventListener("click", () => {
      const name = input.value.trim(); if (!name) return;
      const p = { id: "p" + Date.now(), name, tracks: [track] };
      lists.unshift(p); changed = true;
      rows.insertAdjacentHTML("afterbegin", rowHtml(p, 0, true));
      rows.firstElementChild.classList.add("on");
      input.value = ""; create.disabled = true; input.blur();
      nw.classList.remove("editing");
    });

    ov.querySelector(".dim").addEventListener("click", () => close());
    ov.querySelector(".done").addEventListener("click", () => close());

    // arrastar a janela para baixo fecha (pela alça ou pelo cabeçalho)
    const dg = { y: 0, on: false, dy: 0 };
    ov.querySelectorAll(".add-grab, .add-head").forEach(el => el.addEventListener("pointerdown", e => {
      Object.assign(dg, { y: e.clientY, on: true, dy: 0 }); sheet.classList.add("dragging");
    }));
    window.addEventListener("pointermove", e => {
      if (!dg.on) return;
      dg.dy = Math.max(0, e.clientY - dg.y);
      sheet.style.transform = `translateY(${dg.dy}px)`;
    });
    ["pointerup", "pointercancel"].forEach(t => window.addEventListener(t, () => {
      if (!dg.on) return;
      dg.on = false; sheet.classList.remove("dragging");
      if (dg.dy > 110) close(); else sheet.style.transform = "";
    }));

    return { open, close, isOpen: () => isOpen };
  }

  window.SounosAdd = { attach };
})();
