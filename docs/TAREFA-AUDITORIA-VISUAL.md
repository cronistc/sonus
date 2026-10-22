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

## Relatório da execução

Passe completo em APK debug no aparelho `192.168.1.100:38401`, 21/10/2026 às 21:37. O script percorreu 25 telas: inicial, quatro buscas, os 12 artistas de “Seus artistas”, três álbuns, Minhas músicas, configurações, atualização e player (fila e sugestões). Foram chamadas 313 rotas únicas de capa/foto, incluindo as 49 chaves encontradas no índice persistente de artistas.

| Falha / verificação | Causa | Correção | Status |
| --- | --- | --- | --- |
| Tribalistas usava a capa de “Eletrotribalistas” | A busca aceitava canais com nome aproximado; índice e cache antigos podiam vencer uma busca nova. | Exigir nome normalizado idêntico; escolher canal com selo/inscritos e imagem de perfil; revalidar o índice suspeito e substituir a imagem em arquivo temporário, preservando a antiga como `.unverified` até a nova baixar. | Corrigido. A rota `artist:tribalistas` respondeu HTTP 200, largura 400; marcador de revisão removido. |
| Sufixos de tamanho repetidos em URLs de artista | URLs antigas acumulavam sufixos Google (`=...=...`) e deixavam de baixar. | Normalizador remove todos os sufixos finais; registros suspeitos são revalidados sem apagar a cópia anterior. | Corrigido. 49 chaves revisadas; 0 marcadores `_recheck.*` restantes; 313 rotas com 0 falhas. |
| Cortes, sobreposições, largura zero e overflow horizontal | Verificação automatizada nas telas percorridas. | O auditor mede texto renderizado, caixas, viewport e conteúdo cortado. | 0 ocorrências em 25 telas. |
| Erro de JavaScript | Verificação de exceções, `console.error` e logs JavaScript do WebView. | Captura de console e exceções no CDP. | 0 erros JavaScript. |
| 404 em `updates/support.json` | O JSON de apoio remoto é opcional e não existe no ramo `updates`; sem ele, a opção de apoio fica oculta. | Separado de erros JavaScript e rotas de capa para não mascarar a causa nem reportá-lo como falha visual. | Permanece como recurso opcional ausente; sem efeito nas telas auditadas. |
| Tela de playlist | Não existe playlist salva neste perfil. | Nenhuma playlist foi criada nem os dados do usuário foram apagados. | Não foi possível abrir uma playlist; registrado pelo auditor. |

### Como rodar

Deixe o Sonus aberto em primeiro plano e execute na raiz do repositório:

```sh
python3 tools/auditoria/auditar.py --serial <serial-adb> --output /tmp/sonus-auditoria.json
```

O serial pode ser consultado com `adb devices -l`. O script recusa iniciar se o Sonus não estiver em primeiro plano, não instala nem limpa dados, percorre as quatro buscas padrão e salva o relatório JSON no caminho indicado. Para trocar os termos, passe exatamente quatro após `--terms`.

Uma repetição posterior foi interrompida enquanto outro processo executava `am force-stop` no Sonus. O auditor parou quando a atividade saiu do primeiro plano; ao voltar, confirmou que o histórico local e o espelho nativo foram restaurados. Um teste curto das novas rotas canceláveis respondeu HTTP 200 (400 px para Tribalistas e 1200 px para a faixa atual).
