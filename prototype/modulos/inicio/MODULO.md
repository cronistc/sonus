# Módulo Início — versão 1.0

Controle interno (não aparece no app nem no GitHub; lá sai só a versão Sunshine Beta e as melhorias em texto).

## O que é
A tela inicial do Sonus, sempre numa tela só (sem rolar):
1. **Bloco dinâmico** — janelas que passam para o lado. Hoje: *Ouvidos recentemente* (mosaico parado das últimas
   6 músicas; tocar = ouvir a lista inteira). Cada música vale **6 horas** na lista, contadas da última vez que tocou
   (regra interna, não aparece na tela). Offline: *Minhas músicas*. Próximas janelas (mix do dia, novidades…) entram
   em `janelas()` de `inicio.js`.
2. **Seus artistas** — quem a pessoa segue e, depois, quem ela ouve muito sem seguir (≥ 3 reproduções em 14 dias,
   até 6), com o selo **"+ Seguir"** no canto da foto. Tocar no selo: vira "Seguindo" com um pulinho e se recolhe; o
   artista passa a ser seguido sem trocar de lugar. Vazio: convite "Seguir artistas" (abre a busca).
3. **Suas playlists** — como sempre, mais "Músicas baixadas" e "Nova playlist".

**Ver tudo** (à direita de Seus artistas e Suas playlists) transforma a própria tela, com animação (nada abre por cima):
- Artistas: a fileira sobe para o topo; embaixo, os carrosséis **Novidades** (álbuns/singles/EPs deste ano e do
  anterior de quem a pessoa segue; etiquetas Álbum novo / Single / EP / Ao vivo) e **Escute novamente** (as 10 mais
  ouvidas de todo o histórico). *Recomendações* entram quando o motor de entendimento existir.
- Playlists: os blocos deslizam da fileira para uma grade de 2 colunas.
- "Voltar" (ou o voltar do Android) desfaz tudo na ordem inversa.

## Arquivos
| Arquivo | Papel |
|---|---|
| `inicio.css` | aparência da tela inicial |
| `inicio.js` | desenho, bloco dinâmico, Ver tudo, encaixe na altura (`fitHome`), dados de exemplo do navegador |
| `inicio-app.js` | fonte de dados reais no app (histórico, seguidos, playlists, novidades) e o que cada toque faz |
| `../../home.html` | a página: cabeçalho, seções e a ordem dos scripts (ligação com o app: `../base/home-native.js`) |
| nativo | `Bridge.kt` (`novidades`, `mix`), `Motor.kt` (`novidades`, `mix`), `Library.addHistory` (contagem `n`) |

## Histórico
- **1.0** — Sunshine Beta 1.1 (em preparação, ainda não lançada) — módulo criado. Bloco dinâmico com Ouvidos recentemente (6 h),
  Seus artistas com seguidos e ouvidos muito (selo Seguir animado), Ver tudo animado (artistas: Novidades + Escute novamente; playlists: grade). Lista de Novidades vale 6 h (mesmo reabrindo o app).
  Seu Mix no bloco dinâmico (capa própria: gradiente das capas + fotos dos artistas; "+" salva como playlist).
  Auditoria visual 28/10: "Ver tudo" com área de toque >= 44px; legenda dos blocos de playlist com sombra mais forte (capa clara).
  Auditoria 100% (28/10): `SonusInicio.previa({...})` desenha a tela com dados de teste sem gravar (só auditoria).
  Nome do artista toca e abre a página dele (29/10): Novidades, Escute novamente e Seu Mix com o nome do artista tocável.
