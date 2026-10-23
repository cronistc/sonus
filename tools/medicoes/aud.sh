S=192.168.1.102:43609; cd $(dirname $0)
for v in antes-cpu final antes-cpu final; do
  adb -s $S install -r aud-$v.apk >/dev/null; adb -s $S logcat -c
  timeout 400 python3 -I gap2.py $S 4 10 > /dev/null 2>&1
  echo "== $v"; adb -s $S logcat -d -s Sonus:I | grep -o "AUD-[A-Z]* [0-9]*" | python3 -c "
import sys
ev=[l.split() for l in sys.stdin]; out=[]
for i,(k,t) in enumerate(ev):
    if k=='AUD-FIM':
        nx=[int(t2) for k2,t2 in ev[i+1:] if k2=='AUD-INICIO']
        if nx: out.append(nx[0]-int(t))
print('silêncio entre músicas (ms):', out)"
done
