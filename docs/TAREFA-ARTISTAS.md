# Tarefa: músicas com mais de um artista

Leia docs/TECNICO.md. Regras: comentários em português; não mudar o resto do visual; não publicar, não push, não
mexer em tools/publicar.sh, na chave nem no nome da versão. NÃO instale nem mexa no celular.
Compilar: `cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=7`; `node --check`.

Pedido: quando uma música é de mais de um artista (ex.: Menos É Mais com Camisa 10), mostrar "Menos É Mais,
Camisa 10" (separados por vírgula) em todo lugar onde aparece o artista. NÃO quebrar nome de banda/dupla que é um
artista só (ex.: "Henrique & Juliano", "Menos É Mais").
- Fontes de dados: YouTube Music já separa os artistas em "runs" (byline com vários links de artista — usar os runs
  com navigationEndpoint de artista/canal, não o texto livre). Rádio YtMusicRadio: longBylineText tem vários runs.
  NewPipe (busca/resolve): ver o que vem; títulos com "(feat. X)", "(part. X)", "(com X)", "ft." → mover X para os
  artistas e limpar do título (TitleNormalizer).
- Modelo: Track ganha `artists: List<String>` (o primeiro = principal; `artist` continua sendo o principal para
  compatibilidade, dados salvos antigos continuam funcionando). Json/Bridge mandam `artists`.
- Interface: onde mostra artista, usar artists.join(", ") se existir. Página do artista/“Seus artistas”/
  gostos continuam pelo artista principal. Tocar no nome do artista no player abre o principal.
- Teste com o smoke (android/tools/smoke) ou testes unitários do extrator para alguns exemplos reais.
Escreva no fim deste arquivo o que mudou. Commit no ramo; depois `git merge main`, resolva, compile, commit.

## Resumo da implementação

- `Track` agora leva `artists`; `artist` continua sendo o artista principal. A ponte serializa a lista e preenche-a com o artista principal ao ler dados antigos.
- O rádio do YouTube Music extrai artistas apenas de runs ligados a páginas de artista/canal. O NewPipe preserva os nomes do resultado ao resolver a faixa; `TitleNormalizer` move participações indicadas por `feat.`, `part.`, `com` e `ft.` para a lista e limpa o título.
- Busca, histórico, playlists, início, player, notificações e filas mostram a lista separada por vírgulas. Página do artista, gostos e toque no nome do player continuam usando o artista principal.
- Testes offline cobrem participações, nomes de duplas/bandas e bylines com runs de artista e texto não ligado. Passaram `:core:extractor:test`, `node --check` nos arquivos JavaScript alterados e a compilação `:app:assembleDebug -PversionCode=7`.
- Após o commit inicial, `git merge main` informou `Already up to date.`. A compilação e os comandos `node --check` foram executados novamente com sucesso; não houve conflitos.
