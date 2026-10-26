// Módulo Início — ligação com o app: a fonte de dados reais da tela inicial. Ver MODULO.md
// (o desenho e as animações estão em inicio.js; aqui só se diz DE ONDE vem cada coisa e o que cada toque faz)
(() => {
  const A = window.SonusApp; if (!A) return;
  const { bg, artistKey } = A;
  const pic = (u, resto) => `url('${u.replace(/'/g, "%27")}') center/cover no-repeat,${resto}`;

  function seguidos() {
    try { const v = JSON.parse(localStorage.getItem("sounos.following") || "[]"); return Array.isArray(v) ? v.filter(x => x?.name && x?.key) : []; }
    catch { return []; }
  }
  function marcaOffline() {
    $("brandLetter").hidden = A.offline;
    $("offlineMark").hidden = !A.offline;
  }

  // Novidades: lançamentos deste ano e do anterior de quem a pessoa segue (até 4 por artista), do mais novo ao mais antigo.
  async function novidades() {
    const lista = seguidos().slice(0, 12);
    if (!lista.length) return [];
    const ano = new Date().getFullYear();
    const porArtista = await Promise.all(lista.map(x => Sounos.ask("artistReleases", { name: x.name, url: x.url || "" })
      .then(r => (r?.items || []).map(i => ({ ...i, artist: x.name }))).catch(() => [])));
    return porArtista.flatMap(l => l.filter(r => (r.year || 0) >= ano - 1).slice(0, 4))
      .sort((a, b) => (b.year || 0) - (a.year || 0)).slice(0, 20);
  }

  SonusInicio.usar({
    dados() {
      const hist = Sounos.call("getHistory");
      const lista = (Array.isArray(hist) ? hist : []).filter(e => Sounos.isSong(e.track));     // vídeos ouvidos antes não aparecem
      window.__sounosHistoryTracks = lista.map(e => e.track);
      const agora = Date.now();
      // recentes: cada música vale RECENTES_MS desde a última vez que tocou (o histórico já sobe a música tocada de novo)
      const recentes = lista.filter(e => agora - (e.at || 0) < SonusInicio.RECENTES_MS).map(e => e.track);
      const mine = Sounos.call("getMineTracks");
      const baixadas = Array.isArray(mine.downloaded) ? mine.downloaded : [];
      const cache = A.offline && Array.isArray(mine.cached) ? mine.cached : [];
      const minhas = A.offline ? [...new Map([...baixadas, ...cache].map(t => [t.id, t])).values()] : [];
      playlists = SounosData.getPlaylists();
      marcaOffline();
      return {
        offline: A.offline, recentes, minhas,
        artistas: seguidos().map(x => ({ name: x.name, thumb: x.thumb || "", url: x.url || "" })),
        playlists: baixadas.length ? [...playlists, { id: "__downloads__", name: "Músicas baixadas", tracks: baixadas, downloads: true }] : playlists,
      };
    },
    capa: t => bg(t),
    foto: a => pic(Sounos.coverUrl({ id: "artist:" + (artistKey(a.name) || "x"), name: a.name, thumb: a.thumb || "" }), grad(Sounos.hashColors(a.name))),
    mosaico: p => p.cover ? `url('${p.cover.replace(/'/g, "%27")}') center/cover no-repeat` : null,
    capaDaPlaylist: (p, k) => bg(p.tracks[Math.min(k, p.tracks.length - 1)]),
    tocar(lista, id, i = 0) {
      Sounos.call("playQueue", { tracks: lista, index: i, context: { type: id === "minhas" ? "mine" : id } });
      openPlayer();
    },
    // Escute novamente: as 10 mais ouvidas de todo o histórico (n = vezes tocada; empate: a mais recente)
    escute() {
      const hist = Sounos.call("getHistory");
      return (Array.isArray(hist) ? hist : []).filter(e => Sounos.isSong(e.track))
        .map((e, k) => ({ t: e.track, n: e.n || 1, k })).sort((a, b) => b.n - a.n || a.k - b.k).slice(0, 10).map(x => x.t);
    },
    abrirArtista(a) {
      window.__sonusArtistIntentAt = performance.now();
      A.pages.openArtist(a.name, { thumb: a.thumb || "", url: a.url || "", __intentAt: window.__sonusArtistIntentAt });
    },
    abrirPlaylist(p) { p.downloads ? A.pages.openDownloads() : A.pages.openPlaylist(p.id); },
    novaPlaylist() { add.openCreate(); },
    buscar() { $("searchBtn").click(); },
    novidades,
    capaLancamento: r => r.thumb
      ? pic(Sounos.coverUrl({ id: "album:" + (artistKey(r.title) || "x"), name: r.title, artist: r.artist, thumb: r.thumb }), grad(Sounos.hashColors(r.title)))
      : grad(Sounos.hashColors(r.title)),
    abrirLancamento(r) { if (r?.url) A.pages.openAlbum(r.url, r.title, r.artist, r.thumb || ""); },
  });

  // seguir/deixar de seguir na página do artista muda "Seus artistas"
  addEventListener("sounos:following", () => SonusInicio.render());
  // as músicas de "recentes" vencem com o tempo: confere ao voltar para o app e de tempos em tempos
  addEventListener("sounos:visibility", e => { if (e.detail?.active) SonusInicio.render(); });
  setInterval(() => { if (homeCanAnimate()) SonusInicio.render(); }, 5 * 60e3);
})();
