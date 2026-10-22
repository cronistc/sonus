# Tarefa: tirar as travadas (letra e troca de tema)

Leia docs/TECNICO.md. NÃO mudar o visual (design do dono): só deixar as transições fluidas. Não publicar, não push,
não mexer em tools/publicar.sh, na chave nem no nome da versão. Comentários em português.
Regras do celular (OBRIGATÓRIAS): só DENTRO do app. PROIBIDO `monkey`, `svc wifi`, configurações do sistema, tocar fora do app, desbloquear. Pode: compilar (`cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=7`), `adb -s <serial> install -r -i app.sounos ...`, `am start -n app.sounos/.MainActivity`, `am force-stop app.sounos`, `input tap/swipe` só com o Sonus em primeiro plano (confira `dumpsys activity activities | grep topResumedActivity`), screenshots, logcat, `dumpsys gfxinfo app.sounos framestats`, DevTools da WebView (adb forward localabstract:webview_devtools_remote_<pid>, Runtime.evaluate com timeout curto; Tracing/Performance para medir). Nunca apagar dados do app. Se o app travar: force-stop e reabra; antes de terminar deixe instalado um build que ABRE normal.

Relatos do dono: (1) ATIVAR e DESATIVAR o modo de letra no player trava/engasga; (2) trocar o tema (escuro → claro →
gradiente, em Configurações) tem atraso e trava.
Fazer: medir (quadros perdidos e duração da troca, via gfxinfo/DevTools Performance) → achar a causa (ex.: layout
forçado, render de todas as linhas da letra de uma vez, recálculo de estilos em toda a página, canvas/blur, imagens
recodificadas, trabalho síncrono no clique) → corrigir (ex.: preparar a letra antes, animar só transform/opacity,
trocar variáveis CSS num único passo, evitar repaint de tudo) → medir de novo. Meta: troca sem engasgo perceptível
(<100 ms de resposta, sem quadros >50 ms). Relatório antes/depois no fim deste arquivo. Commit; `git merge main`,
compile, commit.
