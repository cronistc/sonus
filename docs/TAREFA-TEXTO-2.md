# Tarefa: consertar o ramo codex/texto (letreiro + fonte menor) que TRAVOU o app

Trabalhe neste ramo (já contém o seu trabalho anterior: commits 3295c20/bf61107/a2e03dd). Rode `git merge main` antes.
Problema: com essas mudanças instaladas, o app abriu com TELA PRETA e a página travada (o DevTools não respondia a
Runtime.evaluate → JS em loop infinito no thread principal). Antes disso, capas e fotos de artista apareciam
CORTADAS pela metade (metade de cima imagem, metade de baixo o gradiente) na tela inicial.
Suspeitos: medição do letreiro (marquee) em laço/ResizeObserver que se realimenta, `--fs` mexendo em tamanho dentro
de fitHome (que mede e reajusta alturas → loop), ou CSS que mudou o tamanho de fundo das capas.

Regras do celular (OBRIGATÓRIAS): só DENTRO do app. Proibido `monkey`, `svc wifi`, configurações do sistema, tocar
fora do app. Pode: compilar, `adb -s <serial> install -r -i app.sounos ...`, `am start -n app.sounos/.MainActivity`,
`am force-stop app.sounos`, screenshots, logcat e DevTools da WebView
(`adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>`, Runtime.evaluate com timeout).
Nunca apagar dados do app. Se travar, `am force-stop` e reinstale.

Corrigir até: app abre normal, capas inteiras, letreiro anda/pausa/repete sem travar, fonte ~6% menor, CPU sem
subir (compare `top` com o app parado na tela inicial antes/depois). Commit, `git merge main`, compile, commit.
Escreva a causa encontrada no fim deste arquivo.
