# Módulo Mini player — versão 1.0

Controle interno (não aparece no app nem no GitHub).

## O que é
A barra fixa embaixo da tela inicial com a música atual: capa, nome, "+", voltar/play/avançar e a barra de
progresso. Gestos do play: toque = tocar/pausar · segurar e soltar = repetir · segurar e arrastar para cima/baixo =
aleatório · segurar e arrastar para o lado = avançar/voltar na música. Deslizar para cima abre o player completo.

## Arquivos
| Arquivo | Papel |
|---|---|
| `mini-player.css` | aparência |
| `mini-player.js` | gestos, botões, relógio da barra de progresso, abrir o player (e os dados de exemplo do navegador) |
| `mini-player-app.js` | ligação com o player nativo: estado, progresso, comandos |

## Histórico
- **1.0** — Sunshine Beta 1.1 (em preparação, ainda não lançada) — separado da tela inicial, sem mudança de comportamento.
  Auditoria visual 28/10: fundo do tema Gradiente sem transparência (texto não vaza); áreas de toque >= 48px; some com o teclado da busca.
  Auditoria 100% (28/10): aviso de aleatório/repetir desligado em ícone sólido com traço (antes opacity .35).
  Nome do artista toca e abre a página dele (29/10): nome do artista no mini-player abre a página dele sem abrir o player.
- **Próximo lançamento (em preparação; versão não subiu)** — com o player aberto, a tela inicial não se refaz a cada
  estado: cores, mini player e Início ficam para quando o player começa a descer (`__homeEmDia`). Barra sem `offsetWidth`.
- **Próximo lançamento (em preparação; versão não subiu)** — aleatório/repetir desligado volta a ser CINZA (#8e8e93),
  igual ao player; o ícone riscado de 28/10 saiu (decisão do dono, 04/11).
