# Tarefa: mini-player — tirar letra/"A" e colocar o "+" (adicionar à playlist)

Leia docs/TECNICO.md. Regras: comentários em português; não mudar o resto do visual; não publicar, não push, não
mexer em tools/publicar.sh, na chave nem no nome da versão. NÃO instale nem mexa no celular.
Compilar: `cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=5`; `node --check`.
Outro programador mexe em home.html (fonte/letreiro): mudanças pequenas e localizadas.

Pedido do dono (prototype/home.html + home-native.js):
1. Remover do mini-player o botão "A" (letras) e o modo de letra no mini-player (a linha da letra que aparece nele),
   incluindo timers/código que só serviam a isso (menos CPU).
2. No lugar do "A", o botão "+" de adicionar a música atual a uma playlist — o MESMO ícone e o MESMO fluxo do "+"
   do player (add.js / SounosAdd ou equivalente; reaproveitar, não duplicar). Mesmo tamanho/posição/estilo que o "A"
   ocupava. Some quando não há música.
3. As letras continuam funcionando no player grande, como hoje.
Escreva no fim deste arquivo o que mudou. Commit; `git merge main`, resolva, compile, commit.

## Alterações realizadas

- Removidos do mini-player o botão “A”, a linha de letra, a expansão de altura e o código de
  sincronização/animação de letra que rodava junto do relógio de progresso.
- Colocado no espaço do “A” o botão “+” com o ícone do player. Ele abre o mesmo fluxo
  `SounosAdd` e some quando não há música atual.
- Removido o “+” sobre a capa, que agora só exibe a capa. As letras no player grande continuam
  usando o fluxo nativo de letras.
- Após `git merge main` (`Already up to date.`), passaram `node --check prototype/home-native.js`, a
  verificação dos scripts inline de `home.html` e `JAVA_HOME=~/Android/jdk17 ./gradlew -q
  :app:assembleDebug -PversionCode=5`.
