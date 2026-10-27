# Base (compartilhado) — não é um módulo com versão

Peças que todos os módulos usam:

| Arquivo | Papel |
|---|---|
| `native.js` | ponte com o app (`Sounos.call/ask/on`), `SounosEscala`, nomes de versão (`SounosVersao`), capas |
| `data.js` | dados de exemplo do navegador e playlists (`SounosData`) |
| `theme.css` | cores dos temas (claro, escuro, gradiente) |
| `transitions.css` | transições entre telas |
| `marquee.js` | letreiro de textos compridos |
| `home-native.js` | ligação da tela inicial com o app: estado do player, páginas, botão voltar, offline, player embutido |

Fora daqui, na raiz de `prototype/`: `home.html` e `player.html` (as duas páginas), `fonts/` (Sora, Urbanist),
`logo.svg`, `changelog.json`.
