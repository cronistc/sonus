// Módulo Player — voltar para a tela inicial (puxar para baixo, voltar do celular, Esc). Ver MODULO.md
// ---- Voltar para a tela inicial: puxar o player para baixo, "voltar" do celular ou Esc ----
// (com a transição entre telas, o player encolhe de volta para o mini-player)
const EMBED = window.parent !== window && !!window.parent.__playerShell;
function goHome() {
  if (EMBED) return window.parent.__playerShell.close();
  if (document.referrer.includes("home.html") && window.history.length > 1) window.history.back();
  else location.href = "home.html";
}
const phoneEl = document.getElementById("phone");
const pd = { y: 0, on: false, dy: 0 };
phoneEl.addEventListener("pointerdown", e => {
  if (add.isOpen() || e.target.closest(".queue, .controls, .lyr, .hit, .sheet, .add-ov, button")) return;
  Object.assign(pd, { y: e.screenY, on: true, dy: 0 });
});
window.addEventListener("pointermove", e => {
  if (!pd.on) return;
  pd.raw = e.screenY - pd.y;                   // screenY: continua certo enquanto o quadro do player se move
  pd.dy = Math.max(0, pd.raw);
  if (pd.dy < 6 || isImmersive()) return;
  if (EMBED) return window.parent.__playerShell.drag(pd.dy);
  // o player desce acompanhando o dedo, encolhendo de leve
  phoneEl.style.transition = "none";
  phoneEl.style.transform = `translateY(${pd.dy * .9}px) scale(${1 - Math.min(pd.dy, 500) / 5000})`;
});
["pointerup", "pointercancel"].forEach(t => window.addEventListener(t, () => {
  if (!pd.on) return;
  pd.on = false;
  if (t === "pointerup" && pd.raw < -40 && !isImmersive()) { immersiveByLyrics = false; setImmersive(true); }
  else if (t === "pointerup" && pd.raw > 40 && isImmersive()) { immersiveByLyrics = false; setImmersive(false); pd.raw = 0; return; }
  else if (pd.dy > 140 && t === "pointerup" && !isImmersive()) return goHome();
  pd.raw = 0;
  if (EMBED) { if (pd.dy >= 6) window.parent.__playerShell.snap(); return; }
  phoneEl.style.transition = "transform .4s cubic-bezier(.2,.85,.25,1)";   // não passou do ponto: volta para cima
  phoneEl.style.transform = "";
}));
document.addEventListener("keydown", e => {
  if (e.key !== "Escape") return;
  if (add.isOpen()) return add.close();
  goHome();
});
