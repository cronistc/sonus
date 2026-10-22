# Tarefa: letreiro (texto que anda) e fonte um pouco menor

Leia docs/TECNICO.md. Regras: comentários em português; não mudar o resto do visual; não publicar, não push, não
mexer em tools/publicar.sh, na chave nem no nome da versão. NÃO instale nem mexa no celular.
Compilar: `cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=7`; `node --check`.

1. BUG letreiro: quando um texto não cabe (título/artista no player, mini-player, cartões), ele anda para mostrar o
   nome inteiro, mas trava depois de uma passada e só volta a andar quando a pessoa toca na tela. Provável causa: a
   otimização de CPU recente (timers/animações pausados até haver interação). Correto: anda, pausa ~2 s no fim,
   volta ao início, pausa, repete SEMPRE enquanto o texto estiver visível e a tela acesa (pode parar com app em
   segundo plano/tela apagada/elemento oculto). Use animação CSS (transform) e não timer JS por quadro.
2. Fonte um pouco menor no app todo (pedido do dono, fonte Inter em prototype/fonts/fonte.css): reduzir ~6% todos
   os tamanhos de texto (home, player, busca, páginas, configurações, adicionar, atualização), proporcionalmente,
   sem mexer em tamanhos de ícones, capas, botões e espaçamentos. Preferir um fator único fácil de ajustar
   (ex.: variável CSS --fs aplicada aos font-size) para o Claude poder afinar depois.
Escreva no fim deste arquivo o que mudou. Commit no ramo; depois `git merge main`, resolva, compile, commit.
