# Tarefa: player sem os ícones do topo; capas da fila um pouco maiores

Leia docs/TECNICO.md. Não publicar, não push, não mexer em tools/publicar.sh, na chave nem no nome da versão.
NÃO instale nem mexa no celular. Compilar: `cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug
-PversionCode=7`; `node --check`. Comentários em português. Outro programador mexe em player.html (desempenho da
letra): mudanças pequenas e localizadas.

Pedido do dono (prototype/player.html + player-native.js):
1. Dentro do PLAYER, remover os ícones do topo: configurações (engrenagem), a clave de sol e a pesquisa (lupa).
   Eles continuam só na tela inicial. Remover também o código que só servia a eles no player (busca dentro do player
   etc.), sem quebrar nada que a tela inicial usa.
2. Com o espaço que sobra, as capas das músicas da lista (fila/sugestões embaixo do player) crescem UM POUCO
   (uns 10–15%, "nem isso tudo"), mantendo o resto do layout (capa grande, anel, título, controles) equilibrado e
   sem sobreposição em telas de 1080×2340 e menores (360×740 css). O tamanho do player continua constante.
Escreva no fim deste arquivo o que mudou. Commit; `git merge main`, compile, commit.
