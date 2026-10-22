# Tarefa: tela inicial online x offline

Leia docs/TECNICO.md. Regras: comentários em português; não mudar o resto do visual; não publicar, não push, não
mexer em tools/publicar.sh, na chave nem no nome da versão. NÃO instale no celular.
Compilar: `cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=7`; `node --check`.

Pedido do dono (prototype/home.html + home-native.js; evento nativo "offline" e Sounos.call("getOffline") já existem):
1. ONLINE: tela inicial = "Ouvidos recentemente", "Seus artistas", "Suas playlists". O bloco "Minhas músicas" NÃO
   aparece (hoje aparece embaixo).
2. OFFLINE: "Minhas músicas" ocupa o LUGAR de "Ouvidos recentemente" (no topo, mesmo formato/estilo do carrossel de
   "Ouvidos recentemente": as músicas baixadas + em cache como cartões, tocar uma toca a lista com contexto
   {type:"mine"}). Depois "Seus artistas", "Suas playlists".
3. OFFLINE: no topo, onde fica a clave de sol, aparece a indicação "Offline" (texto pequeno no mesmo estilo/cor do
   cabeçalho, no lugar da clave). Voltou a internet → clave de novo.
4. A troca acontece na hora (evento "offline"), sem piscar, e o fitHome (seções esticam para preencher, sem rolagem)
   continua funcionando nos dois estados, com e sem mini-player.
Escreva no fim deste arquivo o que mudou. Commit no ramo.
