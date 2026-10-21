// Tela de atualização do Sonus: ícone do app, versão nova, o que mudou e o andamento.
// O app procura, baixa e instala sozinho; esta tela só mostra (e deixa "Atualizar agora").
// Uso: SounosUpdate.attach(celular, () => estadoDaAtualizacao) → { open, close, render }
window.SounosUpdate = (() => {
  const esc = s => String(s ?? "").replace(/[&<>"]/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[ch]));
  const back = '<svg class="lu" viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6"/></svg>';
  const mb = b => b > 0 ? (b / 1048576).toFixed(1).replace(".", ",") + " MB" : "";
  const day = s => { if (!s) return ""; const d = new Date(s + "T12:00:00"); return isNaN(d) ? "" : d.toLocaleDateString("pt-BR", { day: "numeric", month: "long", year: "numeric" }); };

  let changelog = {};
  fetch("changelog.json").then(r => r.json()).then(j => { changelog = j || {}; }).catch(() => {});

  function attach(phone, get) {
    const el = document.createElement("div");
    el.className = "upd";
    el.innerHTML = `<div class="upd-head"><button class="close" aria-label="Voltar">${back}</button></div>
      <div class="upd-body">
        <img class="upd-icon" src="logo.svg" alt="">
        <div class="upd-name">Sonus</div>
        <div class="upd-ver"></div>
        <div class="upd-status"></div>
        <div class="upd-notes"></div>
      </div>
      <div class="upd-foot">
        <button class="upd-btn"><i class="fill"></i><span></span></button>
        <p class="upd-hint"></p>
      </div>`;
    phone.appendChild(el);
    const $ = s => el.querySelector(s);
    let isOpen = false;

    function render() {
      const u = get(); if (!u || !u.current) return;
      const nv = u.update, cur = u.current.versionName, pct = Math.round((u.progress || 0) * 100);
      const has = !!nv && ["available", "downloading", "ready", "installing", "error"].includes(u.status);
      $(".upd-ver").innerHTML = has ? `<span class="old">${esc(cur)}</span><span class="arrow">→</span><b>${esc(nv.versionName)}</b>` : `<b>${esc(cur)}</b>`;
      const status = {
        uptodate: u.justUpdated ? `Atualizado para a ${u.justUpdated}` : "Você está na versão mais recente",
        idle: "Você está na versão mais recente",
        checking: "Você está na versão mais recente",
        available: "Nova versão disponível",
        downloading: `Baixando… ${pct}%`,
        ready: "Pronta para instalar",
        installing: "Instalando… o Sonus abre de novo em instantes",
        error: u.error || "Não deu para procurar agora",
      }[u.status] || "";
      $(".upd-status").textContent = status;
      $(".upd-status").dataset.s = u.status;
      const meta = has ? [mb(nv.size), day(nv.date)].filter(Boolean).join(" · ") : "";
      const notes = (has ? nv.notes : (changelog[cur] || u.current.notes)) || [];
      $(".upd-notes").innerHTML = notes.length
        ? `<h3>${has ? "O que há de novo" : "Nesta versão"}</h3>${meta ? `<div class="meta">${esc(meta)}</div>` : ""}<ul>${notes.map(n => `<li>${esc(n)}</li>`).join("")}</ul>`
        : (meta ? `<div class="meta">${esc(meta)}</div>` : "");
      const btn = $(".upd-btn");
      btn.hidden = !has && u.status !== "error";          // na versão mais recente: só a mensagem e as novidades
      btn.querySelector("span").textContent = {
        available: "Atualizar agora", ready: "Atualizar agora", downloading: `Baixando ${pct}%`, installing: "Instalando…",
        checking: "Procurando…", error: has ? "Tentar de novo" : "Procurar de novo",
      }[u.status] || "Procurar de novo";
      btn.disabled = ["downloading", "installing", "checking"].includes(u.status);
      btn.classList.toggle("primary", has);
      btn.querySelector(".fill").style.transform = `scaleX(${u.status === "downloading" ? u.progress || 0 : 0})`;
      $(".upd-hint").textContent = !u.canInstall ? "Permita \"Atualizar sozinho\" nas configurações para instalar sem pedir nada."
        : has ? "Se você não tocar em nada, ela instala sozinha quando você sair do app e nada estiver tocando." : "O Sonus procura atualizações sozinho e se atualiza em segundo plano.";
      $(".upd-foot").classList.toggle("quiet", btn.hidden);
    }

    el.addEventListener("click", e => {
      if (e.target.closest(".close")) return close();
      const btn = e.target.closest(".upd-btn"); if (!btn || btn.disabled) return;
      const u = get();
      if (u.update && ["available", "ready", "error"].includes(u.status)) Sounos.call("installUpdate");
      else Sounos.call("checkUpdate");
    });

    function open() {
      if (isOpen) return;
      isOpen = true; render();
      el.classList.add("open");
      history.pushState({ settings: true, upd: true }, "");
      if (get()?.justUpdated) Sounos.call("seenUpdate");
    }
    function close(fromPop) {
      if (!isOpen) return;
      isOpen = false; el.classList.remove("open");
      if (!fromPop) history.back();
    }
    addEventListener("popstate", () => { if (isOpen && !history.state?.upd) close(true); });
    return { open, close, render, isOpen: () => isOpen };
  }
  return { attach };
})();
