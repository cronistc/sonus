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

  // Novidades: o motor monta a lista (lançamentos + músicas) e ela vale NOV_MS (24 h), mesmo que o app seja fechado e
  // aberto de novo. Passado esse tempo, uma lista nova é feita ao abrir — a menos que o que está tocando venha da
  // lista atual (aí ela é preservada). Fica guardada para a janela do topo e o Ver tudo usarem a mesma.
  const NOV = "sonus.novidades", NOV_MS = 24 * 3600e3;          // um dia (pedido do dono, 04/11)
  let nov = null;
  try { nov = JSON.parse(localStorage.getItem(NOV) || "null"); } catch {}
  const tocandoDaLista = () => A.S?.context?.type === "novidades" || (!!A.cur() && !!nov?.musicas?.some(t => t.id === A.cur().id));
  const valeAinda = () => !!nov?.musicas?.length && Date.now() - (nov.at || 0) < NOV_MS;
  const pronta = (nov && (valeAinda() || tocandoDaLista())) ? Promise.resolve(nov) : Sounos.ask("novidades", {
    seguidos: seguidos().map(x => ({ name: x.name, url: x.url || "" })),
  }).then(r => {
    if (r?.error || !r?.musicas?.length) return nov;         // falhou (sem internet…): fica a última que existia
    nov = { lancamentos: r.lancamentos || [], musicas: r.musicas, at: Date.now() };
    try { localStorage.setItem(NOV, JSON.stringify(nov)); } catch {}
    SonusInicio.render();
    return nov;
  }).catch(() => nov);
  const novidades = () => pronta.then(n => n?.lancamentos || []);

  // Seu Mix: o motor mistura as mais ouvidas, novidades bem próximas, inéditas de artistas que a pessoa já ouve e
  // 1–2 descobertas do mesmo estilo. Um mix novo a cada 12 h (preservado enquanto toca dele).
  const MIX = "sonus.mix", MIX_MS = 12 * 3600e3;
  let mix = null;
  try { mix = JSON.parse(localStorage.getItem(MIX) || "null"); } catch {}
  const tocandoDoMix = () => A.S?.context?.type === "mix";
  if (!(mix?.musicas?.length && (Date.now() - (mix.at || 0) < MIX_MS || tocandoDoMix()))) {
    Sounos.ask("mix", {}).then(r => {
      if (r?.error || !r?.musicas?.length) return;
      mix = { musicas: r.musicas, artistas: r.artistas || [], cores: r.cores || [], at: Date.now() };
      try { localStorage.setItem(MIX, JSON.stringify(mix)); } catch {}
      SonusInicio.render();
    }).catch(() => {});
  }

  // Quem a pessoa ouve muito mas não segue também aparece em Seus artistas, depois dos seguidos, com o selo "Seguir":
  // pelo menos OUVIU_MIN reproduções nos últimos OUVIU_DIAS dias (cada música conta até 3), no máximo OUVIU_MAX artistas.
  const OUVIU_DIAS = 14, OUVIU_MIN = 3, OUVIU_MAX = 6;
  function ouvidosMuito(lista, agora) {
    const seg = new Set(seguidos().map(x => x.key)), conta = new Map();
    for (const e of lista) {
      if (agora - (e.at || 0) > OUVIU_DIAS * 864e5) continue;
      const nome = e.track.artist, k = artistKey(nome);
      if (!k || seg.has(k)) continue;
      const c = conta.get(k) || { name: nome, n: 0 }; c.n += Math.min(e.n || 1, 3); conta.set(k, c);
    }
    return [...conta.values()].filter(c => c.n >= OUVIU_MIN).sort((a, b) => b.n - a.n).slice(0, OUVIU_MAX)
      .map(c => ({ name: c.name, thumb: "", url: "", sugerido: true }));
  }
  // seguir pelo selo: mesmo registro da página do artista; entra no FIM dos seguidos (o artista não troca de lugar na tela)
  function seguir(a) {
    const list = seguidos(), k = artistKey(a.name);
    if (list.some(x => x.key === k)) return;
    list.push({ name: a.name, key: k, thumb: a.thumb || "", since: new Date().toISOString() });
    const json = JSON.stringify(list);
    try { localStorage.setItem("sounos.following", json); } catch {}
    window.__sounosSave?.("sounos.following", json);
    Sounos.call("setFollowingArtists", { items: list });
    window.dispatchEvent(new CustomEvent("sounos:following", { detail: list }));
  }

  // Histórico (centenas de KB) e baixadas lidos do app só quando podem ter mudado: o histórico ganha a música nova
  // quando ela termina de carregar (chave = música atual + carregando); baixadas, nos eventos library/download.
  // Ler o histórico custa ~80 ms (centenas de KB): lê uma vez e, a cada música que começa, aplica aqui a mesma regra
  // do app (Library.addHistory: a música sobe para o topo, conta +1, no máximo 300). Lê de novo ao voltar para o app.
  let histCache = null, histUltimo = "", baixCache = null;
  const historico = () => {
    if (!histCache) { const h = Sounos.call("getHistory"); histCache = Array.isArray(h) ? h : []; histUltimo = histCache[0]?.track?.id || ""; }
    return histCache;
  };
  Sounos.on("state", st => {
    const t = st?.current;
    if (!histCache || !t || st.loading || t.id === histUltimo) return;
    histUltimo = t.id;
    const i = histCache.findIndex(e => e.track?.id === t.id), antes = i >= 0 ? histCache.splice(i, 1)[0] : null;
    histCache.unshift({ track: t, source: st.context || {}, at: Date.now(), n: (antes?.n || 0) + 1 });
    if (histCache.length > 300) histCache.length = 300;
  });
  const mudouBiblioteca = () => { baixCache = null; };
  Sounos.on("library", mudouBiblioteca);
  Sounos.on("download", ev => { if (ev.done || ev.error) mudouBiblioteca(); });
  Sounos.on("offline", mudouBiblioteca);

  SonusInicio.usar({
    dados() {
      const hist = historico();
      const lista = (Array.isArray(hist) ? hist : []).filter(e => Sounos.isSong(e.track));     // vídeos ouvidos antes não aparecem
      window.__sounosHistoryTracks = lista.map(e => e.track);
      const agora = Date.now();
      // recentes: cada música vale RECENTES_MS desde a última vez que tocou (o histórico já sobe a música tocada de novo)
      const recentes = lista.filter(e => agora - (e.at || 0) < SonusInicio.RECENTES_MS).map(e => e.track);
      // online só as baixadas (consulta rápida); offline também o que está inteiro no cache (consulta mais lenta)
      const mine = baixCache?.offline === A.offline ? baixCache.v : (baixCache = { offline: A.offline, v: Sounos.call(A.offline ? "getMineTracks" : "getDownloadedTracks") }).v;
      const baixadas = Array.isArray(mine.downloaded) ? mine.downloaded : [];
      const cache = A.offline && Array.isArray(mine.cached) ? mine.cached : [];
      const minhas = A.offline ? [...new Map([...baixadas, ...cache].map(t => [t.id, t])).values()] : [];
      playlists = SounosData.getPlaylists();
      marcaOffline();
      return {
        offline: A.offline, recentes, minhas,
        artistas: [...seguidos().map(x => ({ name: x.name, thumb: x.thumb || "", url: x.url || "" })), ...ouvidosMuito(lista, agora)],
        playlists: baixadas.length ? [...playlists, { id: "__downloads__", name: "Músicas baixadas", tracks: baixadas, downloads: true }] : playlists,
        novidades: nov,
        mix,
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
      const hist = historico();
      return (Array.isArray(hist) ? hist : []).filter(e => Sounos.isSong(e.track))
        .map((e, k) => ({ t: e.track, n: e.n || 1, k })).sort((a, b) => b.n - a.n || a.k - b.k).slice(0, 10).map(x => x.t);
    },
    abrirArtista(a) {
      window.__sonusArtistIntentAt = performance.now();
      A.pages.openArtist(a.name, { thumb: a.thumb || "", url: a.url || "", __intentAt: window.__sonusArtistIntentAt });
    },
    seguir,
    abrirPlaylist(p) { p.downloads ? A.pages.openDownloads() : A.pages.openPlaylist(p.id); },
    novaPlaylist() { add.openCreate(); },
    buscar() { $("searchBtn").click(); },
    novidades,
    capaLancamento: r => r.thumb
      ? pic(Sounos.coverUrl({ id: "album:" + (artistKey(r.title) || "x"), name: r.title, artist: r.artist, thumb: r.thumb }), grad(Sounos.hashColors(r.title)))
      : grad(Sounos.hashColors(r.title)),
    abrirMix(j) {
      const nomes = j.mix.artistas || [];
      A.pages.openMix({ name: "Seu Mix", desc: `Feito pelo Sonus para você${nomes.length ? " · " + nomes.join(", ") : ""}`,
        tracks: [...j.lista], hero: () => SonusInicio.capaMix(j.mix), salvar: () => this.salvarMix(j.lista, false) });
    },
    salvarMix(lista, abrir = true) {
      const hoje = new Date().toLocaleDateString("pt-BR", { day: "numeric", month: "short" }).replace(".", "");
      const pl = SounosData.getPlaylists();
      // o mesmo mix já salvo (pelo "+" do cartão ou pela página do Mix): não cria outra igual
      const ids = lista.map(t => t.id).join(",");
      const ja = pl.find(x => x.name === `Seu Mix · ${hoje}` && (x.tracks || []).map(t => t.id).join(",") === ids);
      if (ja) { if (abrir) A.pages.openPlaylist(ja.id); return; }
      const p = { id: "pl" + Date.now().toString(36), name: `Seu Mix · ${hoje}`, desc: "Feito pelo Sonus para você", tracks: [...lista], created: Date.now() };
      pl.push(p);
      SounosData.savePlaylists(pl);
      SonusInicio.render();
      if (abrir) A.pages.openPlaylist(p.id);      // a página da playlist tem o botão de baixar tudo
    },
    abrirLancamento(r) { if (r?.url) A.pages.openAlbum(r.url, r.title, r.artist, r.thumb || ""); },
  });

  // seguir/deixar de seguir na página do artista muda "Seus artistas"
  addEventListener("sounos:following", () => SonusInicio.render());
  // as músicas de "recentes" vencem com o tempo: confere ao voltar para o app e de tempos em tempos
  addEventListener("sounos:visibility", e => { if (e.detail?.active) { histCache = null; SonusInicio.render(); } });
  setInterval(() => { if (homeCanAnimate()) SonusInicio.render(); }, 5 * 60e3);
})();
