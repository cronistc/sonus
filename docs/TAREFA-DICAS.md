# Tarefa: dicas na hora certa (sem tutorial) + pedido de bateria

Leia docs/TECNICO.md. Não publicar, não push, não mexer em tools/publicar.sh, na chave nem no nome da versão.
Comentários em português. NÃO instale nem mexa no celular. Compilar: `cd android && JAVA_HOME=~/Android/jdk17
./gradlew -q clean :app:assembleDebug -PversionCode=7`; `node --check`.

## 1. Dicas contextuais (prototype/: novo dicas.js + dicas.css, usado por home.html e player.html)
Pedido do dono: nada de tutorial. Enquanto a pessoa usa o app, aparece uma janelinha (balão pequeno apontando para o
elemento) explicando aquele recurso — UMA VEZ só, no momento oportuno (quando o elemento aparece na tela pela 1ª vez
e a pessoa não está no meio de um gesto). Toque em qualquer lugar fecha. Nunca duas ao mesmo tempo; no máximo uma a
cada ~20 s.
- Registro de dicas: { id, versão em que o recurso surgiu, seletor/tela, texto curto }. Guardar as vistas em
  localStorage "sounos.tips" (já espelhado em arquivo pelo native.js: confira que a chave entra no espelho).
- Novato (sem nada salvo): mostra as dicas básicas aos poucos. Quem já usava (tem histórico/playlists de antes) ou
  depois de vistas: só aparecem dicas de recursos com versão MAIOR que a última versão vista ("sounos.tipsVersion"),
  ou seja, a cada atualização só o que é novo. A versão atual vem do app (Sounos.call("getUpdate").current.versionName
  ou equivalente).
- Dicas (escreva textos curtos e claros em português; descubra os gestos REAIS lendo o código):
  * botão grande do player: TODAS as funções (toque, segurar, arrastar/deslizar, trocar modo repetir/aleatório,
    o que cada ícone significa) — é a mais importante; pode ser uma dica em 2–3 passos.
  * mini-player (tocar abre o player; "+" adiciona à playlist; deslizar?), botão de letra, fila (arrastar para
    reordenar?), sugestões, busca (álbuns/artistas), "Nova playlist", página do artista, "Minhas músicas"/offline.
  * Versão 0.1.3 (novidades): álbuns, vários artistas, modo offline/Minhas músicas, "+" no mini-player.
- Visual: balão discreto no estilo do app (mesmas cores/tema via variáveis de theme.css, fonte do app, cantos
  arredondados, seta), animação suave de entrada/saída, sem bloquear o uso. Não pode pesar: sem loops/observers
  globais (já tivemos travamento por MutationObserver no body) — acione nos pontos de abertura de tela.
## 2. Pedido para ficar fora da otimização de bateria
Na PRIMEIRA vez que a pessoa toca uma música, se o app não estiver liberado
(PowerManager.isIgnoringBatteryOptimizations), pedir com o diálogo do sistema
(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS + permissão REQUEST_IGNORE_BATTERY_OPTIMIZATIONS no manifesto).
Uma vez só (guardar que já pediu). Em Configurações, uma linha "Tocar sem interrupções" que mostra o estado e
reabre o pedido se estiver desligado (mesmo estilo das linhas existentes).
Escreva no fim deste arquivo o que mudou. Commit; `git merge main`, compile, commit.
