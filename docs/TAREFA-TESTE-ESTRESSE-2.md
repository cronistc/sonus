# Tarefa: teste de estresse — parte 2 (o que faltou)

Mesmas regras de docs/TAREFA-TESTE-ESTRESSE.md (leia). Celular: `adb -s 192.168.1.100:43263` (se cair:
`adb mdns services` + `adb connect`). NUNCA use `svc wifi disable` (derruba o adb). Para simular sem internet SÓ no app:
`adb shell cmd connectivity set-package-networking-enabled false app.sounos` (e `true` para voltar).
SEMPRE deixe `true` no fim. Nunca apagar dados do app. Celular bloqueado: não desbloquear; anotar.

Fazer:
1. Rede: com música tocando, cortar/voltar a internet do app 10× em intervalos variados (2–20 s). Nada pode
   fechar; offline correto (fila só com disponíveis, "Minhas músicas"); ao voltar, normal.
2. 5 rodadas `monkey -p app.sounos --throttle 150 --pct-syskeys 0 -s <semente> -v 3000` (sementes diferentes).
3. Tela apagar/acender (26/224) tocando, 10×; HOME e voltar ao app, 10×.
4. Após cada rodada: exit-info, dropbox data_app_crash, logcat (AndroidRuntime|FATAL|Uncaught|ANR|chromium.*Error).
Para cada bug: reproduzir, corrigir (causa raiz), compilar, instalar, confirmar, commit. Relatório no fim deste arquivo.
