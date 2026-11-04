# Módulo Busca — versão 1.1
Controle interno.

## O que é
A busca (lupa do topo): pesquisas recentes, sugestões, resultados (melhor resultado, músicas, artistas, álbuns),
mais páginas ao rolar. Músicas são ordenadas também pelo gosto (histórico) em `search.js`.

## Arquivos
| Arquivo | Papel |
|---|---|
| `search.js` / `search.css` | tela e regras de ordenação na interface |
| nativo | `NewPipeSource.searchArtists` (artistas), `search`, `searchAlbums` em `android/core/extractor`; `Bridge.kt` |

## Regras de artistas (nativo)
Só perfis de **artista** do YouTube Music com público ≥ 1.000 (ouvintes mensais ou inscritos) — contas aleatórias
não aparecem. Ordem:
1. artistas que a pessoa já ouve e cujo nome começa com o que foi digitado (mesmo que o YouTube Music não os traga);
2. nome igual → começa com as letras → palavra que começa com elas → próximos do nicho;
3. dentro de cada grupo, quem a pessoa mais ouve (afinidade do motor de entendimento), depois maior público.

## Histórico
- **1.0** — busca existente.
- **1.1** — Sunshine Beta 1.1 (em preparação, ainda não lançada) — artistas filtrados por público e ordenados por nome e pelo gosto; campo de texto com a fonte do Sonus.
  Auditoria visual 28/10: mini-player some com o teclado aberto; lista rola acima do mini-player; áreas de toque >= 44px (Limpar, apagar recente).
  Auditoria 100% (28/10): playlist no histórico com mosaico 2x2 das capas (antes quadrado cinza).
  Nome do artista toca e abre a página dele (29/10): nome do artista abre a página dele no histórico, sugestões, melhor resultado, músicas e cartões de álbum.
- **Próximo lançamento (em preparação; versão não subiu)** — encostar o dedo num resultado de música já começa a buscar
  o áudio (`SounosPrefetchToque`); o app também deixa prontos os 3 primeiros resultados.
  Linhas de resultado usam a capinha (`coverUrl(..., mini)`, ~160 px/12 KB, rota `mini=1`): a lista aparece em ~1 s
  mesmo com internet fraca. Resultados entram com respiro de 90 ms e passo de 35 ms (`.spanel.res`). No Wi-Fi, a capa no
  tamanho da fila vem depois, aos poucos (Bridge `capasDaBusca`); nos dados móveis só as capinhas.
  Resultados: Melhor resultado (com ⋮) → Músicas (5 + "Ver mais", +10 por toque, busca mais no app quando precisa) →
  Artistas → Álbuns. A lista sem fim embaixo (carregava sozinha ao rolar) saiu.
