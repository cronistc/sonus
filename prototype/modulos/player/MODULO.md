# Módulo Player — versão 1.0
Controle interno (não aparece no app nem no GitHub).

## O que é
O player completo: capa grande com o anel de progresso, nome/artista, letra (botão "A"), baixar, "+", fila em
carrossel e os controles. A capa tem o tamanho do A54 (351 px); em tela mais baixa (botões de navegação) cedem antes a
fila e o respiro (`fitCover` em `player.js`). No app ele fica carregado num quadro por cima da tela inicial.

## Arquivos
| Arquivo | Papel |
|---|---|
| `../../player.html` | a página (estrutura) |
| `player.css` | aparência |
| `player.js` | capa, anel, letra, fila, botões, gestos, `fitCover` |
| `player-voltar.js` | voltar para a tela inicial (puxar para baixo, voltar do celular, Esc) |
| `player-native.js` | ligação com o player nativo |
| nativo | `android/.../player/PlayerController.kt` (fila, repetir, aleatório), `engine/AudioEngine.kt` (áudio) |

## Histórico
- **1.0** — Sunshine Beta 1.1 (em preparação, ainda não lançada) — separado em módulo. Capa fixa no tamanho do A54 quando falta altura.
