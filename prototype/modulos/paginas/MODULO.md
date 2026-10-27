# Módulo Páginas — versão 1.1
Controle interno.

## O que é
As páginas que deslizam por cima da tela inicial: artista (catálogo, seguir), álbum, playlist, Músicas baixadas,
Minhas músicas. Voltar desliza a página para fora (a tela inicial só se redesenha depois da animação).

## Arquivos
| Arquivo | Papel |
|---|---|
| `pages.js` / `pages.css` | as páginas, pilha, voltar, arrastar para fechar |
| nativo | `Bridge.kt` (`artist`, `album`), `YtMusicArtist.kt` |

## Histórico
- **1.0** — páginas existentes.
- **1.1** — Sunshine Beta 1.1 (em preparação, ainda não lançada) — volta animada de verdade; mini player por cima da página aberta a partir da busca.
  Página do artista em teste: foto limpa (nada por cima); nome embaixo da foto com "Seguir" ao lado (sem "Artista ·
  N músicas"); play à direita;
  sem botão de aleatório — no artista tudo toca em aleatório (play = catálogo embaralhado; tocar numa música = ela
  primeiro e o resto embaralhado, com o aleatório do player ligado).
