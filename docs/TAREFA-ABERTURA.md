# Tarefa: tela de abertura animada — "Sonus" se transforma na clave de sol

Leia docs/TECNICO.md. Não publicar, não push, não mexer em tools/publicar.sh, na chave nem no nome da versão.
Comentários em português. Regras do celular: só DENTRO do app (proibido monkey/wifi/sistema); pode compilar limpo
(`cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q clean :app:assembleDebug -PversionCode=7`), instalar
(`adb -s <serial> install -r -i app.sounos ...`), `am force-stop`/`am start -n app.sounos/.MainActivity`,
gravar a tela (`screenrecord --time-limit 6`) e tirar screenshots para conferir. Nunca apagar dados.

Pedido do dono (CRUCIAL para o design):
1. Hoje, ao abrir, o Android mostra o ícone do app (splash do sistema). Não pode: a abertura é nossa.
   Android 12+ sempre mostra um splash do sistema → deixe-o invisível: windowSplashScreenAnimatedIcon transparente,
   windowSplashScreenBackground = cor de fundo do app (tema escuro #0e0e10, ou a cor do tema salvo), sem ícone.
   A WebView só aparece quando a animação estiver pronta para começar (sem tela branca/preta piscando).
2. Animação, em CÓDIGO, quadro a quadro (canvas ou SVG + requestAnimationFrame SÓ durante a abertura, ~1,6–2,2 s):
   a palavra "Sonus" (fonte do app, prototype/fonts/, estilizada, mesmo traço/cor da marca) se TRANSFORMA na clave
   de sol do ícone (prototype/logo.svg — mesmo desenho). Sugestão de técnica (escolha a melhor):
   - gerar o contorno de "Sonus" em path a partir da fonte (ex.: opentype.js rodado UMA vez num script em tools/,
     salvando o path pronto num arquivo; o app não carrega opentype), amostrar N pontos ao longo dos contornos de
     "Sonus" e da clave (getPointAtLength), casar os pontos (ordem/rotação que minimize distância) e interpolar com
     easing (ex.: cubic-bezier .2,.85,.25,1), desenhando o contorno/preenchimento a cada quadro; ou
   - o "S" vira a clave e "onus" se recolhe para dentro dela com escala/opacidade, com o S morfando de verdade.
   Deve ser fluida (60 fps no A54), elegante, sem serrilhado, centralizada, e terminar EXATAMENTE na clave do
   cabeçalho da tela inicial (mesma posição/tamanho), que então assume — continuidade perfeita para a home.
3. Quando toca: na abertura "a frio" (app iniciado do zero / reaberto depois de fechado). NÃO tocar ao voltar do
   segundo plano. Toque na tela pula. Não atrasa o carregamento: a home carrega por baixo enquanto anima.
4. Respeitar tema claro/escuro/gradiente (cores da marca em cada um).
5. Grave um vídeo da abertura no celular (screenrecord) e salve quadros-chave em docs/abertura/ (PNG) para o dono ver.
Escreva no fim deste arquivo o que mudou. Commit; `git merge main`, compile limpo, commit; deixe instalado um build
que abre normal.
