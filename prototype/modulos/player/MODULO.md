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
  Auditoria visual 28/10: botões A/baixar/+ e anterior/próxima com área de toque >= 46-48px (desenho igual).
  Auditoria 100% (28/10): aviso de aleatório/repetir desligado em ícone sólido com traço; artista na fila com escape; sem cores do app não quebra a capa.
  Nome do artista toca e abre a página dele (29/10): nome da capa e da fila abre a página do artista (cada nome do "A, B" o seu); o tratador antigo da área toda saiu, vale só o texto.
- **Próximo lançamento (em preparação; versão do módulo ainda não subiu)** — botão multifunção: ícone do modo some
  sozinho também com a música pausada (antes ficava preso) e fica cinza ao desligar; toque responde na hora
  (`.press`/`.hold`) e solta com quique. Troca de música mais leve: ponta do anel por conta (sem `getPointAtLength`),
  animações sem `getAnimations`, cor do anel sem `getComputedStyle`, rolagem da letra no quadro seguinte.
  Letra: "A" escondido enquanto procura; a da próxima música já vem guardada (PlayerController).
  Troca de música sem medir a tela no meio: fila centraliza no quadro seguinte; nativo grava histórico, sessão e
  "guardadas" fora da linha principal (pior quadro ~300 → ~150 ms no A54).
  Capa grande que chega depois entra por cima da miniatura (`.cover-hi`, fade 250 ms); toque num bloco da fila já pede
  áudio e capa grande. Fundo do player passa por trás das barras do sistema (`--tela-fundo`).
