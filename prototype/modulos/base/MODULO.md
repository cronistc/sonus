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
  Auditoria 100% (28/10): cores de capa quase cinza ganham um toque do azul da marca (SounosCores.viva); `SonusApp.aviso()` exposto; ícones sempre sólidos (tokens --icon/--icon-dim em theme.css).
  Nome do artista toca e abre a página dele (29/10): mecanismo único em native.js (`Sounos.artistHtml/artistLink/openArtist` + um tratador de toque delegado em `.art-nome`; o toque não chega à linha); retorno de toque discreto em theme.css.
- **Próximo lançamento (em preparação; versão não subiu)** — barras do sistema transparentes: o app desenha por trás
  delas, `.phone` recua `--barra-topo`/`--barra-base` (vindas do app, `__sonusBarras`/`getBars`) e o fundo da tela
  (`--tela-fundo`, no body) passa por trás — sem a emenda da barra de cor lisa. Tema também no `<html>`.
  Capa nova só troca o endereço do MESMO tamanho (a grande não recarrega mais a miniatura: era o pisca);
  `__capaSuave` deixa o elemento fazer a troca. Toque numa música também pede a capa grande.
  `.phone` com `overflow-clip-margin: 48px`: sombras (capa, play, mini) passam por trás das barras (antes: linha reta).
  `html.native, body { overflow: hidden }`: com o vazamento de 48 px das sombras, uma fileira na borda alargava a página
  e o fim do deslize (ex.: álbuns da busca) levava a tela inteira para o lado.
