#!/usr/bin/env bash
# Põe uma conta do Codex para trabalhar numa tarefa, num ramo separado (não mexe no principal).
#   tools/codex.sh <conta 1|2|3> <nome-curto> <arquivo-da-tarefa.md>
# Resultado: ramo codex/<nome> em ../sonus-trab/<nome>; log em ../sonus-trab/<nome>.log
set -euo pipefail
N="$1"; NOME="$2"; TAREFA="$(realpath "$3")"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; TRAB="$ROOT/../sonus-trab"; mkdir -p "$TRAB"
W="$TRAB/$NOME"
[ -d "$W" ] || git -C "$ROOT" worktree add -q -b "codex/$NOME" "$W"
[ -f "$ROOT/android/local.properties" ] && cp -n "$ROOT/android/local.properties" "$W/android/"
cd "$W"
CODEX_HOME="$HOME/.codex-conta$N" codex exec -m gpt-5.6-terra --dangerously-bypass-approvals-and-sandbox \
  "Você trabalha no app Sonus (diretório atual). Faça a tarefa descrita abaixo. Ao terminar, faça git commit no ramo atual (não faça push).

$(cat "$TAREFA")" </dev/null > "$TRAB/$NOME.log" 2>&1
echo "fim: $NOME (conta $N)" >> "$TRAB/$NOME.log"
