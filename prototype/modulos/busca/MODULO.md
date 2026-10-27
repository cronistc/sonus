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
