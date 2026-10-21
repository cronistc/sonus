# Tarefa: busca — álbuns mais acima e lista que cresce ao rolar

Leia docs/TECNICO.md. Regras: comentários em português; NÃO mudar o visual (o design é do dono); não publicar,
não fazer push, não mexer em tools/publicar.sh, na chave nem no nome da versão. NÃO instale no celular.
Compilar: `cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=5` e
`node --check` nos JS alterados. Antes de começar: `git merge main`.

1. Resultados da busca (prototype/search.js, nativeResults): o bloco "Álbuns" hoje aparece no fim. Manter o MESMO
   layout do bloco, só SUBIR a posição: ele entra no meio dos resultados (ex.: depois das primeiras ~4 músicas,
   junto com o bloco de Artistas). Nada de mudar tamanho/estilo.
2. Lista infinita: hoje a lista de músicas para de crescer ao rolar ("trava"). Ao chegar perto do fim, buscar a
   PRÓXIMA página de músicas (NewPipe: SearchInfo.getMoreItems com nextPage) e acrescentar no fim, sem pular a
   rolagem e sem repetir músicas (dedupe por id/songKey, mesmo filtro só-músicas). Nativo: guardar o nextPage
   da última busca (por consulta) e comando Bridge `searchMore {q}` → {items, hasMore}. Pedir uma página por vez,
   parar quando acabar. Indicador de carregando no fim usando um estilo já existente (ex.: esqueleto .sk).

Escreva no fim deste arquivo o que mudou e o que testou. Commit no ramo (sem push).
