// "Apoie o Sonus": as formas de apoio vêm de um arquivo no GitHub (support.json, ramo "updates"),
// então dá para trocar/adicionar sem lançar versão nova. Sem arquivo (ou vazio) a opção não aparece.
// support.json: { "title": "...", "text": "...", "options": [ { "label": "...", "desc": "...", "type": "link"|"copy", "value": "..." } ] }
window.SounosSupport = (() => {
  const URL = "https://raw.githubusercontent.com/cronistc/sonus/updates/support.json";
  const KEY = "sounos.support";
  const esc = s => String(s ?? "").replace(/[&<>"]/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[ch]));
  let data = null;
  try { data = JSON.parse(localStorage.getItem(KEY)); } catch {}
  const listeners = [];
  const valid = d => d && Array.isArray(d.options) && d.options.some(o => o && o.label && o.value);
  // busca a versão mais nova (sem bloquear nada)
  fetch(URL, { cache: "no-store" }).then(r => r.ok ? r.json() : null).then(d => {
    data = valid(d) ? d : null;
    try { data ? localStorage.setItem(KEY, JSON.stringify(data)) : localStorage.removeItem(KEY); } catch {}
    listeners.forEach(f => f());
  }).catch(() => {});

  function attach(phone) {
    const el = document.createElement("div");
    el.className = "upd sup";
    phone.appendChild(el);
    let isOpen = false;
    function render() {
      const d = data; if (!valid(d)) return;
      el.innerHTML = `<div class="upd-head"><button class="close" aria-label="Voltar"><svg class="lu" viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6"/></svg></button></div>
        <div class="upd-body">
          <img class="upd-icon" src="logo.svg" alt="">
          <div class="upd-ver"><b>${esc(d.title || "Apoie o Sonus")}</b></div>
          <div class="upd-status">${esc(d.text || "O Sonus é feito por uma pessoa só, sem anúncios. Se ele te ajuda, você pode apoiar.")}</div>
          <div class="sup-opts">${d.options.filter(o => o && o.label && o.value).map((o, i) => `
            <button class="sup-opt" data-i="${i}"><div class="tx"><div class="t">${esc(o.label)}</div>${o.desc ? `<div class="d">${esc(o.desc)}</div>` : ""}</div>
              <span class="go">${o.type === "copy" ? "Copiar" : "Abrir"}</span></button>`).join("")}</div>
        </div>`;
    }
    el.addEventListener("click", e => {
      if (e.target.closest(".close")) return close();
      const b = e.target.closest(".sup-opt"); if (!b) return;
      const o = data.options.filter(x => x && x.label && x.value)[+b.dataset.i];
      if (o.type === "copy") {
        (navigator.clipboard?.writeText(o.value) || Promise.reject()).catch(() => {});
        const go = b.querySelector(".go"); go.textContent = "Copiado"; setTimeout(() => go.textContent = "Copiar", 1600);
      } else if (/^https:\/\//.test(o.value)) window.Sounos?.native ? Sounos.call("openUrl", { url: o.value }) : window.open(o.value, "_blank");
    });
    function open() { if (isOpen || !valid(data)) return; isOpen = true; render(); el.classList.add("open"); history.pushState({ settings: true, upd: true }, ""); }
    function close(fromPop) { if (!isOpen) return; isOpen = false; el.classList.remove("open"); if (!fromPop) history.back(); }
    addEventListener("popstate", () => { if (isOpen && !history.state?.upd) close(true); });
    return { open, close };
  }
  return { attach, available: () => valid(data), onChange: f => listeners.push(f) };
})();
