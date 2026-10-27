# Módulos do Sonus

Mapa de cada parte do app. **Versões de módulo são controle interno**: não aparecem no app nem no GitHub (para fora
sai só a versão, ex.: Sunshine Beta 1.1, e as melhorias em texto). Cada módulo tem um `MODULO.md` com o que faz,
os arquivos e o histórico.

**Versão só muda quando sai para o público.** Tudo o que é feito antes de um lançamento é UMA versão (a do próximo
lançamento). Cada módulo sobe no máximo uma vez por lançamento (Y = ajustes/novidades; X = redesenho), nunca a cada
mudança. Hoje em preparação: **Sunshine Beta 1.1** (ainda não lançada).

## Telas (interface, `prototype/`)
| Módulo | Versão | Pasta | O que é |
|---|---|---|---|
| Início | 1.0 | `prototype/modulos/inicio` | tela inicial: bloco dinâmico, Seus artistas, Suas playlists, Ver tudo |
| Mini player | 1.0 | `prototype/modulos/mini-player` | barra da música atual na tela inicial |
| Player | 1.0 | `prototype/modulos/player` | player completo (capa, letra, fila) |
| Busca | 1.1 | `prototype/modulos/busca` | pesquisa |
| Páginas | 1.1 | `prototype/modulos/paginas` | artista, álbum, playlist, baixadas |
| Configurações | 1.0 | `prototype/modulos/configuracoes` | engrenagem e "Apoie o Sonus" |
| Atualização | 1.0 | `prototype/modulos/atualizacao` | tela de atualização e clave que brilha |
| Abertura e boas-vindas | 1.0 | `prototype/modulos/abertura` | animação de abertura, "Antes de começar" |
| Dicas | 1.0 | `prototype/modulos/dicas` | balões de dica |
| Menus | 1.0 | `prototype/modulos/menus` | adicionar à playlist, menu ⋮ |
| (base) | — | `prototype/modulos/base` | ponte com o app, temas, transições (compartilhado) |

Páginas de entrada: `prototype/home.html` e `prototype/player.html`. Fontes em `prototype/fonts`.

## Motores e parte nativa (`android/`)
| Módulo | Versão | Onde | O que é |
|---|---|---|---|
| Motor de entendimento | 0.1 | `android/app/src/main/kotlin/app/sounos/motor` | aprende o gosto (escutas, artistas, gêneros); Novidades |
| Extrator (busca e áudio) | 1.1 | `android/core/extractor` | YouTube/YouTube Music via NewPipe + chamadas diretas |
| Player nativo | — | `android/app/.../player`, `.../engine` | fila, áudio, notificação |
| Dados | — | `android/app/.../data` | histórico, baixadas, letras, backups |
| Atualizador | — | `android/app/.../update` | baixa e instala versão nova |
| Ponte | — | `android/app/.../Bridge.kt`, `MainActivity.kt` | liga a interface ao app |
