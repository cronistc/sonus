import sys
p=sys.argv[1]; s=open(p).read()
a="        @Volatile var running = true\n"
assert s.count(a)==1
s=s.replace(a,a+'''        private val monTemp = Thread {
            var st = false; var en = false
            while (running && !en) {
                val t = track
                if (t != null) {
                    val h = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
                    if (!st && h > 0) { st = true; android.util.Log.i("Sonus", "AUD-INICIO ${android.os.SystemClock.elapsedRealtime()}") }
                    if (st && eosReached && h >= framesWritten - 32) { en = true; android.util.Log.i("Sonus", "AUD-FIM ${android.os.SystemClock.elapsedRealtime()}") }
                }
                try { Thread.sleep(5) } catch (_: InterruptedException) {}
            }
        }.also { it.isDaemon = true; it.start() }
''')
open(p,'w').write(s)
