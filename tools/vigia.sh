#!/usr/bin/env bash
# Espera até algum Codex terminar ou bater no limite; imprime o que aconteceu e sai.
T=~/sonus-trab; SEEN=$T/.vistos; touch $SEEN
while true; do
  for f in $T/*.log; do
    n=$(basename $f .log)
    if grep -q "^fim: " $f && ! grep -qx "fim:$n" $SEEN; then echo "fim:$n" >> $SEEN; echo "TERMINOU: $n"; tail -c 1500 $f; exit 0; fi
    if grep -qiE "usage limit|rate limit|limit reached|quota|429 Too Many" $f && ! grep -qx "lim:$n" $SEEN; then echo "lim:$n" >> $SEEN; echo "LIMITE: $n"; grep -iE "usage limit|rate limit|limit reached|quota|429" $f | tail -3; exit 0; fi
  done
  sleep 30
done
