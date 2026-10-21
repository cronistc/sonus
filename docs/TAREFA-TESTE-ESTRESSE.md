# Tarefa: teste de estresse no celular (achar e corrigir bugs)

Leia docs/TECNICO.md. Regras: comentários em português; NÃO mudar o visual; não publicar, não push, não mexer em
tools/publicar.sh, na chave nem no nome da versão. Celular: Galaxy A54 por adb sem fio (`adb devices`; se cair:
`adb mdns services` e `adb connect` na porta _adb-tls-connect; use sempre `-s <ip:porta>`). PODE instalar:
`cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=5` e
`adb -s <serial> install -r -i app.sounos android/app/build/outputs/apk/debug/app-debug.apk`.
NUNCA apagar dados do app (nada de `pm clear`, desinstalar, apagar files/): playlists/baixadas do dono são sagradas.
Se o celular estiver bloqueado, NÃO tente desbloquear: escreva no log "PRECISO DO CELULAR DESBLOQUEADO" e siga
testando o que der por código.

## Testes (brutos, repetidos, rápidos)
Use `adb shell input tap/swipe/keyevent`, `monkey -p app.sounos --throttle 150 -v 3000` (várias rodadas, sementes
diferentes, `--pct-syskeys 0` para não sair do app à toa), screenshots (`exec-out screencap`) e logcat.
- Navegação: abrir/fechar player, busca, páginas (artista, álbum, playlist, Minhas músicas), configurações,
  atualização; VOLTAR (keyevent 4) em cada tela deve fechar só a de cima e nunca fechar o app fora da tela inicial.
- Player: play/pause/próxima/anterior muito rápido, trocar modos (repetir/aleatório), letras, fila, sugestões.
- Busca: digitar/apagar rápido, rolar até o fim (lista infinita), tocar resultados, abrir artista/álbum.
- Rede: ligar/desligar Wi-Fi (`svc wifi disable/enable`) no meio da música, várias vezes: nada pode fechar o app;
  modo offline correto; ao voltar, tudo normal. (Deixe o Wi-Fi LIGADO ao terminar e reconecte o adb.)
- Tela apagada/acesa (keyevent 26/224) tocando; app em segundo plano (HOME) e voltar.
- Rotação não existe? conferir que não quebra.
Após cada rodada: `adb shell dumpsys activity exit-info app.sounos` (mortes), `dumpsys dropbox --print data_app_crash`,
logcat com `AndroidRuntime|FATAL|Uncaught|chromium.*Error|ANR`.

## Para cada bug
Reproduzir, corrigir no código (causa raiz, mínimo), compilar, instalar, confirmar. Commit por correção.
Escreva no fim deste arquivo: tabela bug → causa → correção → confirmado?; e o que não conseguiu corrigir.
