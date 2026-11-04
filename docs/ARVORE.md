# Árvore do Sonus — mapa completo para quem chega (Claude ou outra IA)

> **Para que serve:** entender o app inteiro **sem precisar testar nada no celular para descobrir como ele funciona**.
> Tudo aqui foi **varrido no código e conferido no Galaxy A54** em 27/10/2026 (versão instalada = GitHub `main`
> `50e18f1`, Sunshine Beta 1.1, `0.1.1.1`, versionCode 12). Se mudar algo listado aqui, atualize esta árvore no mesmo commit.
>
> Ordem de leitura: este arquivo → `CLAUDE.md` (regras do dono) → `MODULOS.md` → `MODULO.md` do módulo que vai mexer.

---

## 1. Visão em uma tela

```
Sonus (app.sounos, GPLv3)
├── Interface = páginas HTML/CSS/JS (prototype/) dentro de um WebView
│   ├── home.html ........ tela inicial + busca + páginas + configurações + atualização (TUDO numa página)
│   └── player.html ...... player completo, carregado num <iframe> por cima da home (fica escondido/parado)
├── Ponte JS ⇄ Kotlin
│   ├── JS → app:  SounosNative.call(método, json)    (síncrono; métodos de rede respondem depois com "reply")
│   └── app → JS:  window.__sounos.emit(evento, dados) (a home repassa a TODOS os iframes)
└── Nativo (android/)
    ├── app/ ............. Activity, ponte, player (fila), motor de áudio, dados, atualizador, motor de entendimento
    └── core/extractor/ .. YouTube/YouTube Music (NewPipe Extractor 0.26.5 + chamadas diretas InnerTube WEB_REMIX),
                           capas, cache de áudio e de metadados, Deezer (reserva de capa/foto, gênero)
```

Desenho de referência: **384 px de largura** (Galaxy A54). `MainActivity` liga `useWideViewPort` e a meta viewport
`width=384` amplia a tela inteira em outros celulares; `textZoom = 100` (fonte do sistema não deforma).
`SounosEscala` (native.js) = quanto 1 px da interface vale na tela real (A54 = 1).

---

## 2. Árvore de arquivos (o que cada um faz)

### 2.1 Raiz
| Arquivo | O que é |
|---|---|
| `CLAUDE.md` / `AGENTS.md` | regras do dono e como trabalhar (mesmo conteúdo para Claude e outras IAs) |
| `MODULOS.md` | tabela dos módulos e versões internas |
| `DESIGN.md` | linguagem visual (cores, tipografia, espaçamentos) |
| `NOVIDADES.md`, `README.md` | texto público (GitHub) |
| `.claude/serve_nocache.py` | servidor local: `python3 .claude/serve_nocache.py 5173 prototype` → abre `home.html` com dados de exemplo |

### 2.2 `prototype/` — interface
```
prototype/
├── home.html                 página principal (cabeçalho, bloco dinâmico, Seus artistas, Suas playlists, mini player)
├── player.html               página do player (estrutura); roda no iframe
├── changelog.json            novidades por versão (lido pela tela de atualização)
├── logo.svg, fonts/          Sora (títulos) + Urbanist (resto); fonte.css
└── modulos/
    ├── base/                 compartilhado (sem versão)
    │   ├── native.js         ponte (Sounos.call/ask/on, coverUrl, isSong, artistLine, hashColors), espelho do
    │   │                     localStorage em arquivo, correções de metadados (trackFixes), SounosVersao, SounosEscala
    │   ├── home-native.js    window.SonusApp: estado, páginas (SounosPages.attach), botão voltar (__sonusBack),
    │   │                     offline, player embutido (__playerShell: open/close/peek/drag/closeThen)
    │   ├── data.js           SounosData (playlists do localStorage; exemplos no navegador)
    │   ├── theme.css         temas: light / dark / gradient (data-theme no #phone)
    │   ├── transitions.css, marquee.js (letreiro de texto comprido)
    ├── inicio/               Início 1.0
    │   ├── inicio.js         SonusInicio: bloco dinâmico (janelas), Seus artistas (selo Seguir), Suas playlists,
    │   │                     "Ver tudo" (artistas → Novidades + Escute novamente; playlists → grade), fitHome()
    │   ├── inicio-app.js     FONTE real: histórico, seguidos, baixadas, Novidades (cache 6 h), o que cada toque faz
    │   └── inicio.css
    ├── mini-player/          Mini player 1.0 — mini-player.js (gestos, relógio da barra), mini-player-app.js (estado nativo)
    ├── player/               Player 1.0 — player.js (capa, anel, letra, fila, fitCover), player-native.js (ligação),
    │                         player-voltar.js (puxar p/ baixo, voltar), player.css
    ├── busca/                Busca 1.1 — search.js (recentes, sugestões, resultados, ordenação pelo gosto), search.css
    ├── paginas/              Páginas 1.1 — pages.js: SounosPages (artista, álbum, playlist, baixadas, minhas músicas;
    │                         pilha, arrastar p/ fechar, playFrom), pages.css
    ├── configuracoes/        Configurações 1.0 — settings.js (SounosSettings, SounosSyncSystemBars), support.js ("Apoie")
    ├── atualizacao/          Atualização 1.0 — update.js (SounosUpdate, tela + novidades em blocos), clave-atualizacao.js
    ├── abertura/             Abertura 1.0 — opening.js (canvas "Sonus"→clave), opening-pontos.js (GERADO por
    │                         tools/abertura/gera.py — não editar), boas-vindas.js/.css ("Antes de começar")
    ├── dicas/                Dicas 1.0 — dicas.js (SounosTips.offer(contexto)), dicas.css
    └── menus/                Menus 1.0 — add.js (SounosAdd: adicionar/criar playlist), track-menu.js (menu ⋮), add.css
```

**Ordem de carga — `home.html`:** native.js → data.js → add.js → track-menu.js → dicas.js → update.js → support.js →
settings.js → pages.js → inicio.js → mini-player.js → home-native.js → inicio-app.js → mini-player-app.js →
clave-atualizacao.js → search.js → marquee.js → opening-pontos.js → opening.js → boas-vindas.js.
(inicio.js precisa de `SounosData`; home-native.js precisa de `SounosPages` e `SonusInicio`; inicio-app.js precisa de `SonusApp`.)

**Ordem de carga — `player.html`:** native.js → data.js → add.js → dicas.js → settings.js → player.js → player-voltar.js →
player-native.js → marquee.js.

Padrão de cada módulo de tela: arquivo de **desenho** (funciona sozinho no navegador com dados de exemplo) + arquivo
`*-app.js`/`*-native.js` que troca a fonte de dados pela do app (`SonusInicio.usar(fonte)`, sobrescreve funções no player).

### 2.3 `android/app/src/main/kotlin/app/sounos/` — app
| Arquivo | Papel | Funções-chave |
|---|---|---|
| `SounosApp.kt` | Application: cria tudo uma vez (`SounosApp.instance`) | `library, userData, lyrics, player, motor, updater, covers, audio, metadata, cacheBudget, resolver, source`; `io` (escopo que nunca derruba o app); `applyStartTheme`, `reconcilePinnedCovers` |
| `MainActivity.kt` | WebView em `https://appassets.androidplatform.net/home.html`; splash e palavra "Sonus" nativa; rota `/cover/<id>`; voltar do Android | `onBackPressed` → `window.__sonusBack()`; `shouldInterceptRequest` (assets + `/cover`); `applyWindowTheme` (barras do sistema); `notifyCoverReady` |
| `Bridge.kt` | ponte JS ⇄ app (lista completa na seção 3) | `call` → `sync` (leituras) ou `command` (na main thread); `async`/`cachedAsync` respondem com `reply` |
| `player/PlayerController.kt` | fila, índice, repetir/aleatório, rádio, sessão (continuar de onde parou), offline, downloads, eventos para a UI | `playQueue, playIndex, playSuggestion, next(auto, afterError), prev, seek, setRepeat, setShuffle, move, download, state(), progress(), saveSession` |
| `player/PlaybackService.kt` | serviço em primeiro plano + notificação | `update(ctx)` |
| `player/MediaSessionHolder.kt` | MediaSession (fone, carro, tela de bloqueio) | `sync`, `coverFor` |
| `engine/AudioEngine.kt` | MediaExtractor + MediaCodec **assíncrono** + AudioTrack (CPU ~6–8 %) | `load(Source, startMs, playWhenReady, knownGainDb)`, `play/pause/seekTo`, callbacks `onReady/onEnded/onError/onBuffering` |
| `engine/GrowingFileSource.kt` | lê o arquivo enquanto baixa (erro em 12 s se nada chegou; 25 s se travou) | `readAt` |
| `engine/SplicedWebmSource.kt` | seek longe do que já baixou (WebM recortado) | — |
| `engine/Loudness.kt` | volume equilibrado: −9 a +18 dB com limitador | `process`, `analyzeFile` |
| `data/Library.kt` | SharedPreferences `sounos.library`: histórico (300, com contagem `n`), baixadas, ganho, sessão, ajustes, correções | `addHistory, history, markDownloaded, removeDownload, saveSession, session, settings, fixes` |
| `data/UserData.kt` | espelho em arquivo do localStorage (`files/userdata/<chave>.json` + 3 cópias `.1..3`) | `save, load` |
| `data/Backups.kt` | cópia de segurança dos prefs por versão (`files/backups`) | `snapshot, restoreIfEmpty` |
| `data/MetadataRepair.kt` | conserta metadados salvos (uma vez por `VERSION`) | `start` |
| `data/Lyrics.kt`, `LyricsSources.kt` | letras (LRCLIB etc.), cache em `files/lyrics` | `get, cached` |
| `data/CoverColors.kt` | cores do fundo a partir da capa (cache em `cores2/`) | `of` |
| `data/Json.kt` | Track ⇄ JSON (formato da UI) | `track, tracks` |
| `motor/Motor.kt` | **motor de entendimento 0.1** (seção 6) | `comecou, marcar, trocando, terminou, retomar, afinidadeArtistas, gostoGeneros, novidades` |
| `update/Updater.kt` | lê `update.json`, baixa, confere SHA-256 e assinatura, instala | `check, download, install, state` |

### 2.4 `android/core/extractor/src/main/kotlin/app/sounos/` — extrator
| Arquivo | Papel |
|---|---|
| `extractor/NewPipeSource.kt` | **porta de entrada**: `search, searchArtists (filtro de público ≥1000 + ordem), searchArtist, searchAlbums, album, suggestions, resolve, current, artistTracks, artistReleases, newReleases, releaseTracks, radio` |
| `extractor/YtMusicArtist.kt` | InnerTube WEB_REMIX direto: página do artista (`tracks`), `releases`, `newReleases` (FEmusic_new_releases_albums), `albumPlaylistTracks` (VL+OLAK…), `releaseTracks` (MPRE…), `searchArtists` (filtro Artistas), `publico("5,45 mi ouvintes")` |
| `extractor/YtMusicRadio.kt` | rádio do YouTube Music (próximas indicadas), `track` |
| `extractor/MusicPicks.kt` | tira lixo (cover, karaokê, vídeos longos) |
| `extractor/TitleNormalizer.kt` | limpa título/artistas ("feat.", vírgulas, produtores no fim) |
| `extractor/StreamResolveCache.kt`, `QueuePrefetcher.kt` | resolver o link do áudio (com prazo) e adiantar as próximas |
| `extractor/AudioCache.kt` | cache de áudio em `cache/audio` (**até 6 GB**, deixa ≥2 GB ou 10 % livres — `EspacoLivre.kt`) |
| `extractor/CoverStore.kt` | capas/fotos: `cache/covers` (navegação) e `files/covers` (fixadas = baixadas/playlists); `artistKey` (igual ao JS) |
| `extractor/CapaDaMusica.kt` | capa oficial de um clipe: tipo no YouTube Music (`next`: ATV/OMV), counterpart, ou busca de músicas (título-base + artista + ±4 s); `chaveTitulo` |
| `extractor/LinkDeMusica.kt` | link de outro app (Spotify, Deezer, Apple Music, Tidal, YouTube…) → `Pedido{tipo, titulo, artista, durSec}`; segue links curtos |
| `extractor/DeezerAlbum.kt`, `DeezerArtist.kt` | reserva de capa e foto de artista |
| `extractor/DiskCacheBudget.kt` | `DiskMetadataCache` (`cache/metadata`, respostas de busca/artista/álbum) |
| `extractor/WebmIndex.kt` | índice de Cues do WebM (seek) |
| `extractor/OkHttpDownloader.kt`, `Tunepipe.kt` (`object Sounos`: http, região) | rede |
| `color/AccentColorSelector.kt` | cor de destaque da capa (a que mais contrasta) |

### 2.5 Ferramentas e documentos
| Caminho | Uso |
|---|---|
| `tools/estresse/bateria.py <serial>` | bateria geral no celular (38 verificações; rodar com repetir = "all"; devolve o repetir da pessoa no fim) |
| `tools/medicoes/cdp.py <serial> home\|player "<js>" [seg]` | roda JS na página pelo DevTools e mostra erros/console (o mais útil para testar sem tocar) |
| `tools/medicoes/tela.py` | versão mínima do anterior (só a home) |
| `tools/medicoes/*.py` | medições de áudio (gap entre faixas, seek, volume, CPU) |
| `tools/auditoria/auditar.py` | auditoria visual |
| `tools/publicar.sh` | publica versão (**só com decisão do dono**) |
| `docs/TECNICO.md` | detalhes técnicos; `docs/VERSOES.md` (nomes de versão); `docs/SONUSPIPE-GUIA.md` (motor próprio, guardado) |
| `docs/TAREFA-*.md` | pedidos antigos do dono (histórico de decisões) |

---

## 3. A ponte (Bridge.kt) — todos os métodos

`Sounos.call(m, args)` devolve JSON na hora. Os marcados **⟳** respondem depois: use `Sounos.ask(m, args)` (Promise;
a ponte manda `reply` com o mesmo `reqId`). Os marcados **💾** respondem primeiro com o cache do disco (`isCached:true`)
e, se a rede trouxer algo diferente, mandam o evento `metadataUpdated` (chave `Sounos.cacheKey(m, valor)`).

| Método | Tipo | Quem chama na UI | O que faz |
|---|---|---|---|
| `getState` | leitura | home-native, pages, player-native | fila, índice, atual, tocando, repetir, aleatório, contexto, sugestões, cores, offline |
| `getProgress` / `getLyrics` | leitura | mini-player-app, player | posição/duração; letra (sincronizada ou simples) |
| `getHistory` | leitura | inicio-app, busca, add | histórico `[{track, source, at, n}]` (mais recente primeiro, até 300) |
| `getOffline`, `getNetwork` | leitura | home-native, pages / track-menu, player | sem internet? / rede medida (dados móveis)? |
| `getMineTracks` | leitura (lenta) | inicio-app (offline), pages | `{downloaded, cached}` — confere o cache no disco |
| `getDownloadedTracks` | leitura | inicio-app (online) | só baixadas (rápido) |
| `getDownloads` | leitura | dicas, track-menu, pages | metadados das baixadas |
| `getStorage` | leitura | settings | bytes do aparelho, livres, baixadas, cache |
| `getUpdate` | leitura | settings, clave, boas-vindas | estado do atualizador |
| `getBatteryOptimization`, `getNotificationPermission` | leitura | settings, boas-vindas | permissões |
| `saveData` / `loadData` | leitura | native.js (espelho) | grava/lê `files/userdata` |
| `trackFixes` | leitura | native.js | correções de metadados (só manda se `rev` mudou) |
| `openingReady` | comando | opening.js | mostra o WebView, tira a palavra nativa |
| `search` | ⟳💾 | busca, add | músicas (com `hasMore`) |
| `searchMore` | ⟳ | busca | próxima página |
| `searchArtists` | ⟳💾 | busca | artistas (até 8): primeiro até 3 que a pessoa já ouve (sem `url`), depois YouTube Music filtrado |
| `searchAlbums` | ⟳💾 | busca, pages | álbuns (até 12) |
| `album` | ⟳💾 | pages | página do álbum (faixas pelo YouTube Music, artista creditado) |
| `artist` | ⟳💾 | pages | catálogo do artista (mais novo → mais antigo) + foto + álbuns |
| `novidades` | ⟳ | inicio-app | `{lancamentos[20], musicas[], peso}` montado pelo Motor |
| `mix` | ⟳ | inicio-app | **Seu Mix**: `{musicas[~23], artistas[3], cores[3], partes}` (Motor.mix; cores = cor principal das capas) |
| `suggest` | ⟳💾 | busca | sugestões de texto |
| `playQueue` / `playSingle` / `playIndex` / `playSuggestion` | comando | todos | tocar (`context.type`: artist, album, playlist, recentes, novidades, escute, mine, radio…) |
| `play` / `pause` / `next` / `prev` / `seek{sec}` | comando | mini/player | controles |
| `setRepeat{mode: off\|all\|one}` / `setShuffle{on}` / `move{from,to}` | comando | mini/player | fila |
| `download{track}` / `removeDownload{id}` / `removeAllDownloads` | comando | track-menu, player, settings | baixadas |
| `setSettings{...}` | comando | settings | grava ajustes, aplica no player, tema da abertura e barras |
| `setSystemBars{theme,statusColor,navigationColor}` | comando | settings (SounosSyncSystemBars) | cor das barras do sistema |
| `setFollowingArtists{items}` | comando | inicio-app, pages | seguidos (vai para o rádio também) |
| `checkUpdate` / `installUpdate` / `allowInstalls` / `seenUpdate` | comando | update, settings, clave | atualização |
| `requestBatteryOptimization` / `requestNotificationPermission` | comando | settings, boas-vindas | permissões |
| `openUrl{url https}` | comando | support | abre no navegador |
| `getPendingLink` | leitura | home-native (`tratarLink`) | link de música recebido pela Activity (entrega uma vez) |
| `getLinksStatus` | leitura | settings, boas-vindas | `{ligado, ativos, total}` dos 22 endereços em "Abrir por padrão" (Android 12+) |
| `openLinkSettings` | comando | settings, boas-vindas | abre "Abrir por padrão" do Sonus (reserva: detalhes do app) |
| `abrirLink{texto}` | ⟳ | home-native | link → `{tipo:"musica", track}` ou `{tipo:"album", url, titulo, artista, thumb}` ou `{erro:"link"\|"nao_achei"}` |
| `clearCache` | comando | settings | limpa cache de áudio e de capas |
| `artistReleases{name,url}` | ⟳💾 | pages (artista) | discografia do próprio artista (álbuns, EPs, singles; mais novo primeiro) — seção "Álbuns" da página |
| `prefetch`, `toggle`, `getCoverArtistKeys` (só debug), `setUpdateUrl` (teste) | — | **ninguém na UI** | existem, mas a interface não usa |

### Eventos app → JS (`Sounos.on(nome, fn)`)
| Evento | Quando | Quem escuta |
|---|---|---|
| `state` | mudou fila/música/tocando/repetir… | mini-player-app, player-native, pages, home |
| `progress` | 4×/s tocando (não envia com tela apagada; a home não repassa ao player fechado) | mini-player-app, player-native |
| `lyrics` | letra chegou | player-native |
| `download` | progresso/fim/erro de download | home, track-menu, pages |
| `library` | baixadas/cache mudaram | home, pages |
| `offline` | caiu/voltou a internet | home-native |
| `network` | Wi-Fi ⇄ dados móveis | track-menu/player |
| `storage` | depois de limpar/remover | settings |
| `update` | estado do atualizador | update, clave |
| `metadataUpdated` | cache 💾 ficou velho | busca, pages |
| `coverReady` | capa melhor guardada (native.js troca a URL depois de carregar) | native.js |
| `reply` | resposta de `ask` | native.js |
| `error` | música não carregou / erro no áudio | **ninguém** (o player pula sozinho; a UI não mostra) |

### Eventos internos da UI
`sounos:following` (seguiu/deixou), `sounos:settings`, `sounos:visibility` (página ativa), `storage` (outra página
mudou o localStorage). Ganchos globais úteis: `window.__sonusBack()` (voltar), `__playerShell`, `__playerOpen`,
`SonusInicio.render()`, `SonusApp.pages.openArtist(nome, {url, thumb})`, `.openAlbum(url, título, artista, capa)`,
`.openPlaylist(id)`, `.openDownloads()`, `.closeTop()`, `.isOpen()`.

---

## 4. Dados — onde mora cada coisa

### 4.1 No WebView (`localStorage`)
Chaves `sounos.*` têm **espelho em arquivo** (`files/userdata/<chave>.json` + `.1 .2 .3`); localStorage válido sempre
vence; se sumir, volta do espelho (native.js). Espelhadas: `sounos.playlists, searchHistory, settings, downloads,
following, artistPageCache, tips, tipsVersion, boasVindas`.
| Chave | Conteúdo |
|---|---|
| `sounos.settings` | `{theme: light\|dark\|gradient, quality, normalize, skipSilence, crossfade, lyricsOffset, wifiOnlyDownloads, country, language}` |
| `sounos.playlists` | playlists da pessoa (faixas completas) |
| `sounos.following` | `[{name, key, thumb, url?, since}]` |
| `sounos.searchHistory`, `sounos.artistPageCache` | buscas recentes; catálogo de artista já visto |
| `sounos.tips`, `sounos.tipsVersion`, `sounos.boasVindas`, `sounos.support` | dicas vistas; "Antes de começar"; apoio |
| `sonus.novidades` | (**sonus**, sem "o" — NÃO espelhada, é só cache) lista de Novidades `{lancamentos, musicas, at}`, vale 6 h |
| `sonus.mix` | (cache, não espelhada) Seu Mix `{musicas, artistas, cores, at}`, vale 12 h (preservado enquanto toca dele) |

### 4.2 No app (`/data/data/app.sounos`)
| Lugar | Conteúdo |
|---|---|
| `shared_prefs/sounos.library.xml` | `history`, `downloads`, `session` (fila para continuar), `settings`, ganhos, `fixes`/`fixesRev`, `cacheTracks` |
| `shared_prefs/sounos.update.xml`, `sounos.repair.xml`, `sounos_battery.xml` | atualizador; versão do conserto de metadados; pedido de bateria |
| `files/downloads/` | músicas baixadas |
| `files/covers/` | capas fixadas (baixadas e playlists) |
| `files/lyrics/`, `files/userdata/`, `files/backups/`, `files/update/` | letras; espelho do localStorage; cópias dos prefs; APK novo |
| `files/motor/` | `escutas.jsonl` (gira em 2 MB → `.1`), `perfil.json` (somas), `atual.json` (escuta em andamento) |
| `cache/audio/` | músicas ouvidas (até 6 GB; no A54 em 27/10: ~1,3 GB) |
| `cache/covers/`, `cache/metadata/`, `cache/cores2/` + `files/cores2/` | capas de navegação; respostas da rede; cores das capas |
| `files/userdata/sounos.updAviso.json` | **resto de versão antiga** (nenhum código lê mais) |

---

## 5. Fluxos principais (o caminho de cada ação)

**Abrir o app (frio):** `SounosApp.onCreate` (cria tudo; `PlayerController` restaura a sessão; `motor.retomar`) →
`MainActivity` (splash + palavra nativa) → `home.html` → `native.js` restaura localStorage do espelho → `inicio-app.js`
desenha com histórico/seguidos e pede `novidades` (se a lista guardada passou de 6 h) → `opening.js` anima e chama
`openingReady` → 350 ms depois o iframe `player.html` começa a carregar.

**Tocar uma música (qualquer tela):** UI → `playQueue{tracks,index,context}` → `PlayerController.playQueue` →
`startCurrent` (motor.trocando fecha a escuta anterior) → resolve link (`StreamResolveCache` → `NewPipeSource.resolve`)
ou usa cache/baixada → `AudioEngine.load(GrowingFileSource|LocalFile)` → `addHistory` (+1 em `n`) → `motor.comecou` →
capa grande + cores em segundo plano → eventos `state`/`progress`.
**Próxima aquecida (27/10/2026):** 1,5 s depois de cada música começar (e quando a fila muda ou a próxima termina de
baixar), `PlayerController.aquece` pede ao motor `AudioEngine.aquecer(arquivo, volume)` da música que o "próxima" tocaria,
se ela estiver inteira no celular. O motor abre uma sessão parada que decodifica, pula o silêncio do começo e enche a
saída de áudio (~0,5 s). Na troca, `load()` com o mesmo arquivo, do começo e com o mesmo volume só dá o play nela
(`Session.ativar`); senão descarta e abre do jeito normal. Histórico, músicas no cache, sessão e cores da capa são
gravados/calculados em segundo plano (`grava`, fila única em ordem). Medido: trabalho na linha principal por troca
~180 → ~25 ms; até o som andar 220–400 → 160–255 ms (o resto é o Android ligar a saída de som).
**Volume equilibrado (27/10/2026):** medido em LUFS (`MedidorLufs`, ITU-R BS.1770 com filtro K e portões −70/−10;
testes em `MedidorLufsTest` com os casos do EBU 3341), alvo −14 LUFS, ganho entre −12 e +12 dB, limitador igual.
Ganho por música em `lufs:<id>` (os `gain:` antigos, medida crua, são apagados ao abrir). Medido: arquivo inteiro em
segundo plano com prioridade baixa (próxima do cache e, agora, também a próxima baixada, antes de aquecer); ao tocar
até o fim; ao pular, se ouviu ≥ metade. Música ainda sem medida começa no palpite (mediana dos ganhos da pessoa,
`Library.palpiteGanho`) em vez de 0 dB. Pular silêncio: conferido, sem defeito (começo até 10 s, fim ≥ 1 s nos 15 s finais).
Fim da faixa: `onEnded` → `next(auto=true)` (repetir uma: volta a 0; fim da fila sem repetir: pausa no início).
Erro: `onError` → `next(afterError=true)` (pula mesmo com repetir uma) + evento `error` (sem aviso na UI).

**Página do artista:** toque → `SonusApp.pages.openArtist(nome,{url,thumb})` → `ask("artist")` 💾 →
`NewPipeSource.artistTracks` (canal pelo url ou por busca de nome exato) → `YtMusicArtist.tracks` (discografia, do mais
novo ao mais antigo, 5 álbuns em paralelo) → mostra 5 músicas + "Ver mais" (+10) + álbuns no fim (`artistReleases`: a
discografia do canal — antes era busca de texto pelo nome, que para artista grande só trazia discos de outros).
Play = catálogo no modo aleatório do player; tocar numa música = ela primeiro e o resto no mesmo modo. Nenhuma página
tem botão de aleatório: arrastar o play para cima/baixo liga/desliga (`pages.js` `gestoAleatorio`, como no player).

**Novidades (tela inicial):** `inicio-app.js` → `ask("novidades",{seguidos})` → `Motor.novidades`: até 3 lançamentos
(ano atual e anterior) de cada seguido (até 10) e de até 4 preferidos pelo uso + lançamentos do país; 20 cartões
(peso pessoal 15 %→85 % conforme segue/ouve; dos genéricos, 70 % pelo gosto e 30 % sorteados); músicas = single
inteiro (até 3) ou 2 primeiras do álbum, embaralhadas. Guardado em `sonus.novidades` por 6 h.

**Seu Mix (tela inicial, bloco dinâmico):** `inicio-app.js` → `ask("mix")` → `Motor.mix`: 9 das mais ouvidas
(sorteio com peso: vezes que tocou + gosto do artista) · até 5 novidades (este ano/anterior) dos 4 artistas mais
ouvidos, só inéditas para a pessoa · até 7 inéditas de artistas que ela já ouve (rádio do YouTube Music de 3 favoritas)
· 2 descobertas (artista desconhecido, mesmo rádio). Mistura sem repetir artista em seguida. Capa própria
(`htmlMix` em inicio.js): gradiente das cores das capas (avivadas: saturação ≥ 55 %) + fotos dos 3 artistas
principais. "+" salva como playlist da pessoa ("Seu Mix · dia mês", em `sounos.playlists`, com espelho) e abre a
página dela (lá tem baixar tudo).

**Páginas (desenho único):** artista, álbum e playlist (também "Músicas baixadas" e Seu Mix) = capa limpa no topo;
embaixo nome (+ descrição/"N músicas · tempo"), aleatório e play à direita. Playlist: ações discretas (baixar, capa,
editar; no Mix: baixar e "+ salvar") numa linha abaixo, e as músicas COM capa (álbum não: é tudo do mesmo disco).
Seu Mix: tocar no cartão abre a página (`SounosPages.openMix`, playlist "virtual" que não se edita); só o ▶ toca.

**Link de música (Spotify, Deezer, Apple Music, YT Music…):** o manifesto aceita VIEW de 22 endereços e SEND de texto →
`MainActivity.receberLink` guarda em `linkPendente` (não repete ao recriar a Activity nem ao reabrir pelos recentes) e
chama `__sonusLinkChegou` → `home-native.js` `tratarLink` (espera a abertura) → `abrirLink` → `LinkDeMusica.ler`
(YouTube: o próprio vídeo · Spotify: metadados da página · Deezer: API · Apple: iTunes lookup, `?i=` ou `/song/` é faixa ·
outros: og:title) → `NewPipeSource.encontrar` (mesmo título-base + artista + ±6 s; pedido "ao vivo"/acústico prefere
essa versão pelo nome cru do resultado, já que `toTrack` tira "(Ao Vivo)" do título) ou `encontrarAlbum` → toca com o
rádio em seguida (`playSingle`) e abre o player, ou abre o álbum. Aviso pequeno `.sonus-aviso` mostra o andamento.
Para o Android abrir os links no Sonus, a pessoa marca os endereços em "Abrir por padrão" (item nas boas-vindas e
linha "Abrir links de música" nas configurações, que some quando todos estão marcados).

**Busca:** lupa → `search.js` → em paralelo `search` + `searchArtists` + `searchAlbums` (+ `suggest` ao digitar);
ordena músicas pelo gosto (histórico); resultados do cache aparecem primeiro e são trocados por `metadataUpdated`.

**Voltar do Android:** `MainActivity.onBackPressed` → `__sonusBack()` na ordem: boas-vindas → telas por cima
(configurações/atualização/adicionar, pelo history) → página aberta → coisas dentro do player (letra, fila) →
fecha o player → fecha a busca → sai do "Ver tudo" → (false) o Android fecha o app.

**Atualização:** procura ao voltar ao app (se a última busca tem > 30 min) e de hora em hora com o app aberto
(antes: só ao voltar e só depois de 6 h — muita gente ficava horas sem o aviso da clave). `Updater.check` lê `https://raw.githubusercontent.com/cronistc/sonus/updates/update.json` →
compara **versionCode** → baixa → confere SHA-256 + mesma assinatura → instala só com toque da pessoa.

---

## 5b. Desempenho — regras que evitam engasgos (aprendidas medindo, 27/10/2026)
- **Nunca animar variáveis CSS herdadas no `:root`** (`--c1..--c3`): recalcula a página inteira a cada quadro.
  A troca suave de cor é `SounosCores.aplicar(cores)` (native.js): cores novas de uma vez + o fundo antigo numa camada
  `.cor-fade` que some por opacidade. (Medido: 145 → 18 quadros longos em 10 trocas.)
- O player nativo **agrupa estados** (`PlayerController.emitState`, 30 ms): antes eram 5 por troca, ~90 KB cada.
- A tela inicial **não se redesenha coberta** (player aberto/app no fundo): `SonusInicio.render` marca pendente;
  `reaparecer()` ao fechar o player. O histórico é lido uma vez e atualizado localmente a cada música.
- O player escondido não mede nada (ResizeObserver e letreiro esperam `playerVisible`).
- `getMineTracks` e `getStorage` ficam prontos em memória na ponte (refeitos em segundo plano quando a biblioteca muda).
- Página já vista há < 10 min (artista, álbum…) não é refeita na rede (`cachedAsync`, `atualizadoEm`).
- Barras do sistema: cor calculada em JS a partir das cores FINAIS (`settings.js barColors`), segue o player aberto
  (Escuro/Claro: topo do `--phone-bg`), ressincroniza ao voltar ao app; o nativo ignora pedido igual ao anterior.
- Abertura: o player só começa a carregar depois que a abertura termina (evento `sonus:abertura-fim`). A clave sobe
  desenhada no canvas e continua desenhada até o fim; quando chega, o ícone de verdade aparece POR BAIXO dela
  (`body.sonus-clave-chegou`), então a saída da camada não pisca (conferido quadro a quadro por screencast do
  DevTools: nenhum quadro sem a clave depois que ela chega). Barras do sistema: ficam na cor do splash até o fundo
  do app aparecer (`__sonusBarrasProntas`); `setSettings` só repinta a janela se o tema mudou.
Medido depois (A54): troca de música 46 % → 5,6 % de quadros com atraso (p99 250 → 44 ms); baixadas 1,4 s → 44 ms;
processador na tela inicial 104 % → 32 % de um núcleo; bateria 38/38.

**Player (diagnóstico de 27/10/2026, noite — `scratchpad/t/diag_player.py`, `diag_gestos.py`, `perfil_troca.py`):**
- Nada de `el.getAnimations()`, `getComputedStyle(...).getPropertyValue` nem `getPointAtLength` em caminho de troca
  de música: cada um força o recálculo da página inteira (~60 ms cada, várias vezes por troca). O anel usa
  `pontoNoAnel` (conta) e lê `--c4` do estilo do `<html>`; o redesenho por mudança de estilo espera o próximo quadro.
- Player escondido mede uma vez, 400 ms depois de a tela parar de mudar, sem a transição de `--cover-size`
  (`medirEscondido`/`semTransicao`). Deixar para a hora de abrir fazia a capa animar de tamanho junto com a subida.
- `box.scrollTop = 0` da letra só com a letra aberta (força layout). Blocos da fila: `content-visibility: auto`.
- Capa nova do player só entra decodificada (`aplicaCapa`, limite 0,7 s); antes aparecia o gradiente por baixo.
- Capa da notificação/tela de bloqueio decodificada em segundo plano (`MediaSessionHolder.coverFor`).
- De ponta a ponta, a cor pedida para as barras não mexe na janela (`applyWindowTheme` só reage ao tema): reaplicar
  fazia o Android reorganizar a janela (relayoutWindow) a cada música.
Medido: 1ª abertura do player depois de iniciar o app ~10 quadros de 33–75 ms → 2–3 de 34 ms; toque → 1º quadro
~30 ms; linha principal do Android lenta numa troca 20–26 → 10–12 quadros. Sem piscadas nos quadros gravados.

## 6. Motor de entendimento 0.1 (`motor/Motor.kt`)
Ganchos no `PlayerController`: `startCurrent → trocando(id, pos, dur, fimPendente)`, carregou → `comecou`,
a cada 10 s tocando e em `saveSession` → `marcar`, ao abrir → `retomar`, `next` grava `fimPendente`
(`completa | pulou | erro`), `prev` grava `voltou`; o resto é `trocou`; app fechado no meio = `saiu`.
Cada escuta vai para `escutas.jsonl` e soma em `perfil.json` (música, artista, gênero do Deezer, faixas de duração).
`pontos = minutos ouvidos + 2×completas − 1,5×puladas`. Usado em: Novidades e ordem da busca de artistas.
**Regra do dono:** não acrescentar regra de recomendação sem combinar.

---

## 7. Como testar sem adivinhar (receitas conferidas)
1. **adb sem fio** (do Termux/proot no próprio celular): `adb-celular` (varre portas 30000–50000). O serial muda a cada rede.
2. **Antes de qualquer toque/tecla:** `adb -s $S shell dumpsys activity activities | grep topResumed` tem que mostrar
   `app.sounos/.MainActivity`. Se não estiver, **não toque** (um toque de teste já atendeu uma chamada do WhatsApp).
   Trazer para frente sem tocar: `adb -s $S shell monkey -p app.sounos -c android.intent.category.LAUNCHER 1`.
3. **Preferir JS pelo DevTools** a toques: as versões publicadas até agora são *debuggable* (WebView com DevTools).
   `python3 tools/medicoes/cdp.py $S home 'JSON.stringify(Sounos.call("getState"))'`.
   O player é outra página: use `player` no lugar de `home`.
4. **Medidas de layout só com o app em primeiro plano:** em segundo plano as transições CSS (`--rec/--art/--pl`)
   ficam paradas e a tela parece "estourar" ~16 px — é falso positivo.
5. **Preferências do dono a devolver depois de testar:** guarde `Sounos.call("getState")` (fila, índice, repetir,
   aleatório) e `localStorage["sounos.settings"]` antes; devolva no fim. A bateria pede repetir = "all".
6. **Compilar:** `cd android && ANDROID_HOME=… ./gradlew :app:assembleDebug -PversionCode=<maior que o instalado>`
   (debug assina com a mesma chave se `~/Sonus-chave/sonus.properties` existir). Testes: `:core:extractor:test`.
7. **Nunca:** mexer em configurações do celular, publicar versão, commitar a chave (`Sonus-chave/`).

---

## 8. Estado conferido em 27/10/2026 (A54, versionCode 12)
| O quê | Resultado |
|---|---|
| Testes do extrator (`:core:extractor:test`) | 31/31 |
| Bateria no celular (`tools/estresse/bateria.py`) | 38/38 antes da correção e 38/38 depois |
| Tela inicial | cabe sem rolar (blocos terminam em 702 px, o mini player começa em 707 px); `--rec 229 / --art 106 / --pl 155` |
| Ver tudo › artistas | Novidades (20) + Escute novamente (10); rola 4 px (achado 4) |
| Busca de artistas | 6–9 ms vinda do cache e ~0,7–1 s da rede; "en" → Enygma e Engenheiros (já ouvidos) primeiro |
| Página do artista (Djavan) | 5 músicas + Ver mais (+10 → 15); álbuns no fim; Seguir/Seguindo |
| Página de álbum (lançamento) | abre em ~1 s, com 14 faixas creditadas a "Djavan" |
| Novidades guardadas | 20 lançamentos, todos com url e capa; 30 músicas, todas com capa |
| CPU com o app parado | **antes ~30 % + 17 % (WebView) → depois ~12 % + 4–6 %** |
| Busca da foto de Tribalistas depois da bateria | **antes, centenas na fila (mais de 4 min) → depois, 1** |

## 9. Problemas conhecidos (achados nesta varredura)

### Corrigidos em 27/10/2026 (no código; conferidos no A54)
1. **Foto de artista em laço, CPU alta com o app parado.** Cada pedido da tela por uma foto que ainda não existe
   (404 não fica no cache do WebView) abria outra busca. Todas esperavam o mesmo cadeado (`CoverStore.lockFor`, fila
   justa), e o passo de download voltava para o fim da fila. Depois da bateria havia **mais de 4 minutos de buscas
   enfileiradas** da foto de Tribalistas (~2,5 por segundo, cada uma uma consulta ao YouTube Music). O app ficou em
   ~30 % de um núcleo, mais 17 % do WebView, com a música pausada.
   Correção: `MainActivity.umaBusca` deixa uma busca por imagem de cada vez. Em `CoverStore`, a regra fixa
   "conferir Tribalistas" passa a rodar uma vez só (antes rodava a cada abertura), e uma foto marcada para conferir
   aceita a nova validada mesmo com menos pixels (antes a marca nunca saía).
   Depois: uma busca, foto em 2,5 s; parado ~12 % + 4 % (o resto é o bloco dinâmico, que passa a cada 6 s).
2. **`native.js` (`refreshCoverUrls`) regravava o fundo de todos os elementos** a cada capa nova. Agora só regrava o
   que muda (regravar fazia o WebView pedir de novo toda imagem que tinha falhado).

3. **Capa errada: quadro de vídeo e capa de outra edição.** Muitas faixas são clipes, e a imagem deles era um
   quadro 16:9 recortado ou uma imagem quadrada de outra edição do álbum. Regra nova:
   - `CoverStore.resolution` faz imagem não quadrada valer 1/4, então quadro de vídeo nunca passa por capa boa;
   - `CapaDaMusica` (novo) pergunta ao YouTube Music se a faixa é música (ATV) ou clipe. Se for clipe, usa a capa da
     versão em música: a "counterpart", ou a busca de músicas com o mesmo título-base, o mesmo artista e ±4 s;
   - a resposta fica guardada uma vez por faixa em `files/covers/capas-conferidas.properties`
     (`m` = música · `c <url>` = capa oficial · `v` = clipe sem versão · `ok.<id>` = já instalada);
   - capas antigas são conferidas quando aparecem na tela (rota `/cover` + `precisaConferir`) e trocadas nos três
     arquivos da faixa. As cores do fundo são recalculadas (`aoTrocarCapa` → `CoverColors.forget`);
   - o quadro de vídeo fica só como último recurso, quando não há imagem quadrada nenhuma.
   Conferido: "40 Graus De Amor / A Carta / Fica" passou do quadro recortado para a capa MOLHO (Rio de Janeiro),
   1200×1200. Nos primeiros 79 conferidos: 32 músicas, 31 clipes com capa oficial, 16 clipes sem versão em música.

3b. **`ReferenceError: artistKey` em `native.js`** (`Sounos.cacheKey`), já na versão publicada: os eventos
   `metadataUpdated` de artista quebravam e a página do artista não trocava o cache pela resposta nova. Corrigido.
3c. **Seu Mix**: a mesma música com dois ids no histórico (clipe e áudio) podia entrar duas vezes; o "+" do cartão e o
   "+" da página do Mix criavam duas playlists iguais no mesmo dia. Corrigidos.

3d. **Página de artista vazia ou sem álbuns (regra, não artista):** os álbuns vinham de busca de texto pelo nome
   (artista grande → só discos de outros com o nome no título) e o canal era achado só com nome idêntico ("Henrique e
   Juliano" ≠ "Henrique & Juliano" → página sem músicas). Agora: álbuns = `artistReleases` (discografia do canal) e
   nomes comparados com `CoverStore.mesmoNome` ("e"/"&"/"and"/"y"/"+" iguais; teste `NomeArtistaTest`). Cache:
   resposta vazia nunca é "recente" e não substitui uma cheia. Conferido em 44 artistas (duplas, bandas, estrangeiros):
   todos com músicas só deles e com lançamentos.

3e. **Letra em "…" até dar play:** com a música da sessão anterior pausada, a letra só era buscada no `startCurrent`.
   Agora `PlayerController.lyricsJson` busca quando a tela pede e a da música atual ainda não veio. Escolher trecho da
   letra redesenhado (círculo/visto por linha, barra flutuante ✕ · quantas · Compartilhar), letra no lugar de sempre.

### Em aberto (decisão do dono ou correção futura)
4. **Busca "Ø"**: `artistKey` troca ø por o, então artistas já ouvidos que começam com "O" (Os Paralamas, Olivia Dean)
   aparecem antes de Ø.
5. **"Ver tudo" de artistas rola 4 px** (`scrollHeight` 787 contra 783 de altura). A regra é que nenhuma tela role.
6. **Novidades não tentam de novo.** Se o primeiro pedido falhar (por exemplo, app aberto sem internet), a lista só
   volta ao reabrir o app. Com o app aberto por mais de 6 h, a lista também não se renova (`inicio-app.js`, `pronta`
   calculada uma vez).
7. **O evento `error` não tem ouvinte**: música que não carrega é pulada sem aviso nenhum.
8. ~~**Tocar no artista liga o aleatório do player e deixa ligado**~~ (resolvido com o aleatório dentro do play) (`playFrom(..., "aleatorio")` manda
   `shuffleMode: true`). Depois, as playlists também tocam embaralhadas. Confirmar se é o desejado.
9. **Curadoria das Novidades**: entram coletâneas ("Gal Costa - As Mais Românticas", 2025) e lançamentos genéricos
   do país de qualquer tipo (inclusive single político). As regras do motor são do dono.
10. **A foto de Tribalistas é a capa "Eletrotribalistas"**: é a imagem do perfil no YouTube Music, não um erro do app.
11. **Artista sem número de público** (`publico()` = 0) some da busca (filtro ≥ 1.000). Só a reserva do NewPipe usa
    −1 (desconhecido).
12. **Motor**: gêneros consultados (`generoDe`, `generoLanc`, `semGenero`) só vão para o disco na próxima escuta
    gravada. Se o app fechar antes, a consulta ao Deezer se repete.
13. **Documentação desencontrada**: `MODULOS.md`, `CLAUDE.md` e os `MODULO.md` dizem que a Beta 1.1 "ainda não foi
    lançada", mas o `update.json` já publica o versionCode 12. `inicio/MODULO.md` cita `artistReleases` como usado
    pela tela, e não é (seção 3). `files/userdata/sounos.updAviso.json` é sobra de versão antiga.
