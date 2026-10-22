# Tarefa: deixar o Sonus leve (CPU) SEM mudar o visual

Leia docs/TECNICO.md antes. Regras:
- O VISUAL É DO DONO: nada pode mudar de aparência (cores, tamanhos, posições, animações, design). Só o custo.
  Animação contínua pode ter menos quadros/s se a olho nu ficar igual. Na dúvida, NÃO mude.
- Não publicar, não fazer push, não mexer em tools/publicar.sh, na chave (~/Sonus-chave) nem no nome da versão.
- Comentários em português, no estilo do código existente.

## Situação (medida com `top`, 100% = 1 núcleo, tocando música)
Tela inicial ~85%, player aberto ~160%, tela apagada ~36%. Meta: player aberto ≤30%, inicial ≤20%, tela apagada ≤8%.
Causa principal: a WebView renderiza ~120 quadros/s mesmo parada (loops requestAnimationFrame + "dano" periódico
na página). O NewPipe e o áudio são leves.

## O que fazer
1. Achar TODO loop de requestAnimationFrame / setInterval / animações CSS infinitas em prototype/*.html e *.js
   (player.html: anel em canvas drawRingFill, equalizador drawEq, relógio de render; home.html: mini-player, barra;
   letras com preenchimento progressivo). Trocar por relógio por tempo (ex.: setTimeout a ~10–15 quadros/s para
   anel/barra/eq; letra pode usar CSS transition entre marcos) e PARAR tudo quando invisível
   (document.hidden, player fechado, overlay oculto, tela apagada, música pausada).
2. Nada de redesenho quando o valor não mudou (ex.: barra/anel sem avanço de pixel → não desenhar).
3. Evitar camadas caras: will-change desnecessário, filter/blur animados, box-shadow animado, elementos ocultos
   que continuam animando. Preferir transform/opacity.
4. Nativo: conferir se eventos de progresso do PlayerController/Bridge vêm em excesso (ex.: >4/s) e reduzir;
   com tela apagada não mandar eventos para a WebView.
5. MEDIR no celular (adb já conectado; se cair: `adb mdns services` e `adb connect` na porta _adb-tls-connect):
   compilar `cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=7`,
   instalar `adb install -r -i app.sounos android/app/build/outputs/apk/debug/app-debug.apk`,
   tocar uma música e medir `adb shell top -b -n 5 -d 2 | grep app.sounos` em: player aberto, tela inicial,
   tela apagada (`adb shell input keyevent 26`). Quadros: `adb shell dumpsys gfxinfo app.sounos` (reset antes).
   Medir ANTES de mudar e DEPOIS. Pode haver música tocando no celular do dono; tudo bem instalar.
6. Comparar screenshots antes/depois (`adb exec-out screencap -p > x.png`) do player aberto, inicial e letras:
   precisam ser iguais.
7. Escrever no fim deste arquivo: o que mudou, números antes/depois. Commit no ramo (sem push).
