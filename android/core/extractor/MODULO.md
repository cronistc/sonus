# Extrator (motor de busca e de áudio) — versão 1.1
Controle interno.

## O que é
Tudo que fala com YouTube / YouTube Music: busca, artista, álbum, lançamentos, rádio, resolver o áudio, capas, cache.
Base: NewPipe Extractor (GPLv3). A ideia de trocar por um motor próprio ("Sonus Pipe") está guardada — ver
`docs/SONUSPIPE-GUIA.md`; não começar sem o dono pedir.

## Arquivos principais (`src/main/kotlin/app/sounos/extractor`)
| Arquivo | Papel |
|---|---|
| `NewPipeSource.kt` | porta de entrada: busca (músicas, artistas filtrados por público, álbuns), artista, lançamentos, rádio |
| `YtMusicArtist.kt` | YouTube Music direto: página do artista, lançamentos, lançamentos do país, busca só de artistas |
| `YtMusicRadio.kt` | rádio (indicadas) |
| `MusicPicks.kt` | filtro das indicadas (sem cover, karaokê…) |
| `StreamResolveCache.kt`, `AudioCache.kt`, `QueuePrefetcher.kt` | resolver e guardar o áudio |
| `CoverStore.kt`, `Deezer*.kt` | capas e fotos (Deezer é reserva) |
| `TitleNormalizer.kt` | limpar títulos e nomes |

## Histórico
- **1.0** — extrator existente.
- **1.1** — Sunshine Beta 1.1 (em preparação, ainda não lançada) — lançamentos (artista e país), faixas de lançamento, busca só de artistas com público; catálogo do artista do lançamento mais novo ao mais antigo; faixas de álbum pelo YouTube Music (artista creditado); faixas de página de álbum herdam a capa quadrada do álbum (antes caíam na miniatura 16:9 do vídeo) — um download por álbum, as faixas usam o mesmo arquivo (índice por endereço em CoverStore).
