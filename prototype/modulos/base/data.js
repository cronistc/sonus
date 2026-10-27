// Dados de exemplo compartilhados (catálogo fictício + playlists da conta do app).
// No app: catálogo vem do NewPipe; playlists e gosto ficam no banco local (Room).
window.SounosData = (() => {
  const NAMES = [["Luz da Cidade","Banda Exemplo"],["Maré Alta","Coletivo Demo"],["Faixa Sem Letra","Artista Teste"],
   ["Neon Azul","Os Placeholders"],["Poeira de Estrada","Duo Fictício"],["Noite Lenta","Lo-Fi Amostra"],
   ["Verde Musgo","Grupo Modelo"],["Último Trem","Banda Exemplo"],["Sal e Areia","Coletivo Demo"],
   ["Céu de Vidro","Trio Protótipo"],["Fim de Tarde","Lo-Fi Amostra"],["Chuva Fina","Duo Fictício"],
   ["Rádio Velho","Os Placeholders"],["Asfalto Quente","MC Mockup"],["Lua Cheia","Grupo Modelo"],
   ["Vento Norte","Trio Protótipo"],["Café Frio","Lo-Fi Amostra"],["Ponte Aérea","MC Mockup"],
   ["Girassol","Coletivo Demo"],["Garoa","Banda Exemplo"],["Mar Aberto","Duo Fictício"],["Eco","Os Placeholders"],
   ["Outono","Artista Teste"],["Faísca","MC Mockup"]];
  const FIXED = [["#7c3aed", "#ec4899", "#f59e0b"], ["#06b6d4", "#22c55e", "#a3e635"]];
  const tracks = NAMES.map(([title, artist], i) => {
    const h = ((i - 2) * 47 + 20 + 3600) % 360;
    return { i, title, artist, c: FIXED[i] || [`hsl(${h} 70% 42%)`, `hsl(${(h + 40) % 360} 75% 52%)`, `hsl(${(h + 80) % 360} 85% 60%)`] };
  });
  const artists = [...new Set(tracks.map(t => t.artist))].map(name => ({ name, c: tracks.find(t => t.artist === name).c }));
  const followed = ["Banda Exemplo", "Lo-Fi Amostra", "Coletivo Demo", "Duo Fictício", "MC Mockup", "Trio Protótipo", "Os Placeholders"];
  const recentPlays = [0, 13, 5, 1, 20, 9, 16, 3, 0, 5, 10];

  const DEFAULT_PLAYLISTS = [
    { id: "p0", name: "Minha playlist", tracks: [0,1,2,3,4,5,6,7,8,9] },
    { id: "p1", name: "Noite",          tracks: [5,10,16,11,21] },
    { id: "p2", name: "Estrada",        tracks: [4,13,17,20,7,15] },
    { id: "p3", name: "Foco",           tracks: [16,10,5,22] },
    { id: "p4", name: "Chuva",          tracks: [11,19,14,1,8] },
  ];
  const KEY = "sounos.playlists";
  let unsaved = null;   // última lista que não coube no localStorage (vale até a próxima gravação que couber)
  function getPlaylists() {
    if (unsaved) return structuredClone(unsaved);
    try { const v = JSON.parse(localStorage.getItem(KEY)); if (Array.isArray(v) && (v.length || window.Sounos?.native)) return v; } catch {}
    // no app começa vazio (são as playlists da pessoa); no navegador, as de exemplo
    return window.Sounos?.native ? [] : structuredClone(DEFAULT_PLAYLISTS);
  }
  function savePlaylists(list) {
    const json = JSON.stringify(list);
    try { localStorage.setItem(KEY, json); unsaved = null; }
    catch (e) {
      unsaved = list;
      // cota do localStorage estourada (capas grandes etc.): o espelho nativo vira a fonte da verdade, nada se perde em silêncio
      console.warn("[sounos] localStorage cheio; playlists guardadas só no espelho nativo", e);
      try { window.__sounosSave?.(KEY, json); } catch {}
    }
  }

  // Nome do artista → @ estilo Instagram: minúsculo, sem acento, espaço vira "_"
  const toHandle = name => String(name ?? "").trim();  // nome do artista como vem (sem @)
  const grad = c => `linear-gradient(135deg,${c[0]},${c[1]} 60%,${c[2]})`;
  const esc = s => String(s).replace(/[&<>"]/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[ch]));

  // Músicas baixadas (no app: arquivo de áudio + capa + metadados + letra, tudo no armazenamento privado)
  const DL_KEY = "sounos.downloads";
  function getDownloads() { try { return JSON.parse(localStorage.getItem(DL_KEY)) || {}; } catch { return {}; } }
  function markDownloaded(i) { const d = getDownloads(); d[i] = { at: Date.now() }; try { localStorage.setItem(DL_KEY, JSON.stringify(d)); } catch {} }

  return { tracks, artists, followed, recentPlays, getPlaylists, savePlaylists, getDownloads, markDownloaded, toHandle, grad, esc };
})();
