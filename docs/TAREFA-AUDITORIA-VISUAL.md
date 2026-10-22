# Tarefa: auditoria visual automática (achar o que quebra a experiência sem ninguém precisar ver)

Contexto do dono: o NewPipe é só o motor; o produto é a parte visual. Capa que não carrega, foto errada, texto
cortado, tela que pisca/trava = app ruim. Os erros têm de ser achados por TESTE, não pelo dono rolando a tela.

Regras do celular (OBRIGATÓRIAS): só DENTRO do app. PROIBIDO `monkey`, `svc wifi`, configurações do sistema, tocar
fora do app, desbloquear. Pode: compilar (`cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug
-PversionCode=7`), `adb -s <serial> install -r -i app.sounos ...`, `am start -n app.sounos/.MainActivity`,
`am force-stop app.sounos`, `input swipe/tap` SÓ em coordenadas dentro do app aberto (confira antes com screenshot que
o Sonus está em primeiro plano: `dumpsys activity activities | grep topResumedActivity`), screenshots, logcat, e
DevTools da WebView (`adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>` + Runtime.evaluate com
timeout curto; nunca rode código que possa travar a página). Nunca apagar dados do app. Se o app travar: force-stop e
reabra. Antes de instalar algo seu, confirme que abre normal; se não abrir, reinstale o main.

## 1. Script de auditoria reutilizável (tools/auditoria/)
Um script (python ou node) que, via DevTools, percorre telas do app e reporta:
- toda imagem/fundo de capa (`/cover/...` em <img> ou background-image) que falhou (HTTP != 200, naturalWidth 0,
  ou caiu no gradiente de reserva) — com id, nome e tela;
- textos cortados sem reticências ou sobrepostos; elementos com largura 0; overflow horizontal da página;
- erros de console JS.
Telas: inicial (rolar TODOS os carrosséis até o fim), busca (3–4 termos, rolar resultados até carregar mais),
página de artista (todos os artistas de "Seus artistas"), álbum (2–3), playlist, Minhas músicas, player
(fila e sugestões), configurações, atualização.
Também chamar a rota de capas direto para TODOS os ids conhecidos (histórico, artistas, baixadas) e listar falhas.

## 2. Corrigir as causas (código nosso, não o NewPipe)
Para cada falha: causa raiz → correção → reinstalar → rodar a auditoria de novo até zerar (ou explicar o resto).
Conhecidos: (a) foto do artista errada — "Tribalistas" mostra a capa do álbum "Eletrotribalistas": a escolha do
canal na busca de artista precisa exigir nome igual (normalizado) e preferir canal de artista/oficial com
inscritos/foto de perfil, nunca capa de álbum; corrigir também as já salvas em files/covers/artist-urls.properties
(revalidar entradas suspeitas sem apagar dados do usuário). (b) URLs com sufixo de tamanho duplicado (já corrigido
no main: confira que não há outras).

Commits por correção. Relatório final no fim deste arquivo: tabela falha → causa → correção → status, e como rodar
o script de auditoria. Depois `git merge main`, compile, commit.
