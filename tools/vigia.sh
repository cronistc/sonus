#!/usr/bin/env bash
# Espera até algum Codex terminar ou bater no limite; imprime o que aconteceu e sai.
T=~/sonus-trab; SEEN=$T/.vistos; touch $SEEN
while true; do
  for f in $T/*.log; do
    n=$(basename $f .log)
    if grep -q "^fim: " $f && ! grep -qx "fim:$n" $SEEN; then echo "fim:$n" >> $SEEN; echo "TERMINOU: $n"; tail -c 1500 $f; exit 0; fi
    if grep -qiE "hit your usage limit|usage limit reached|insufficient_quota" $f && ! grep -qx "lim:$n" $SEEN; then echo "lim:$n" >> $SEEN; echo "LIMITE: $n"; grep -iE "hit your usage limit|usage limit reached|insufficient_quota" $f | tail -3; exit 0; fi
  done
  sleep 30
done
