#!/usr/bin/env bash
# Publica uma versão do Sonus para os celulares se atualizarem sozinhos.
#
#   tools/publicar.sh <nome-da-versão> <código> <arquivo-de-novidades.txt>
#   ex.: tools/publicar.sh 0.1.2 2 novidades.txt
#
# - Compila o APK "release" assinado com a chave definitiva (~/Sonus-chave).
# - Escreve update.json (versão, tamanho, SHA-256, data, novidades — uma por linha do .txt).
# - Envia APK + update.json para o ramo "updates" do GitHub (só esses arquivos ficam lá).
# Os celulares leem https://raw.githubusercontent.com/cronistc/sonus/updates/update.json
# A versão só sai quando o Guilherme decide — este script nunca roda sozinho.
set -euo pipefail

NAME="${1:?nome da versão (ex.: 0.1.2)}"
CODE="${2:?código numérico, sempre maior que o anterior (ex.: 2)}"
NOTES="${3:?arquivo .txt com as novidades, uma por linha}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO_RAW="https://raw.githubusercontent.com/cronistc/sonus/updates"

test -f "$HOME/Sonus-chave/sonus.properties" || { echo "Chave não encontrada em ~/Sonus-chave"; exit 1; }
test -f "$NOTES" || { echo "Arquivo de novidades não existe: $NOTES"; exit 1; }

echo "» Compilando Sonus $NAME (código $CODE)…"
(cd "$ROOT/android" && ./gradlew -q :app:assembleRelease -PversionName="$NAME" -PversionCode="$CODE")
APK="$ROOT/android/app/build/outputs/apk/release/app-release.apk"
FILE="Sonus-$NAME.apk"
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
SIZE=$(stat -c %s "$APK")

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"
git clone -q --branch updates --depth 1 git@github-sonus:cronistc/sonus.git pub 2>/dev/null || {
  git clone -q --depth 1 git@github-sonus:cronistc/sonus.git pub && (cd pub && git checkout -q --orphan updates && git rm -rqf . >/dev/null 2>&1 || true)
}
cd pub
rm -f Sonus-*.apk Sonus.apk                                   # só a versão atual fica guardada
cp "$APK" "$FILE"
cp "$APK" Sonus.apk                                # link fixo para baixar sempre a última versão
python3 - "$NAME" "$CODE" "$REPO_RAW/$FILE" "$SIZE" "$SHA" "$NOTES" > update.json <<'EOF'
import json, sys, datetime
name, code, url, size, sha, notes = sys.argv[1:]
lines = [l.strip().lstrip("-•* ").strip() for l in open(notes, encoding="utf-8")]
print(json.dumps({"versionCode": int(code), "versionName": name, "apk": url, "size": int(size), "sha256": sha,
                  "date": datetime.date.today().isoformat(), "notes": [l for l in lines if l]}, ensure_ascii=False, indent=2))
EOF
git add -A
git -c user.name="Guilherme Aguiar" -c user.email="gdjaguiar@gmail.com" commit -qm "Sonus $NAME"
git push -q origin updates
echo "✓ Publicado: Sonus $NAME ($((SIZE/1024)) KB). Os celulares atualizam sozinhos nas próximas horas."
