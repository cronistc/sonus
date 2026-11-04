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
- **Próximo lançamento (em preparação; versão não subiu)** — capa de clipe ao vivo/pot-pourri: aceita a versão em
  música com outra duração (a mais próxima) e a 1ª música do pot-pourri; os "v" antigos são conferidos de novo uma vez
  (ficam "v2"). Pré-carga baixa a capa GRANDE da próxima. Início da música não espera mais o rádio (até 300 ms).
  Capa grande em 1000 px (tamanho da capa no A54; 1200 pesava ~30 % a mais). Capinha de lista não espera mais a
  consulta "clipe ou música?" (`get(conferirAntes = false)`; a rota confere depois e avisa se trocar) — a busca ficava
  sem capa por segundos. Pré-carga: capa grande das 3 próximas da fila e dos 3 primeiros da busca (5 s depois).
  Capinha (`miniFile`/`localMini`/`getMini`, 120–160 px) para linhas pequenas; serve qualquer capa guardada que baste.
  Capa grande da fila por grupos no PlayerController (`capasGrandesDaFila`): 2 próximas já, 8 seguintes, depois o resto,
  2 de cada vez; dados móveis = janela das 10 próximas. (Saiu da QueuePrefetcher.)
  Artistas parecidos (04/11): `YtMusicArtist.related(id)` lê o carrossel de páginas de artista da página do artista
  ("Os fãs também podem gostar de..."), reconhecido pelo tipo de página e não pelo título; `NewPipeSource.relatedArtists(url)`.
