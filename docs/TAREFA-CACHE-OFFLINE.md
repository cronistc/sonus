# Tarefa: cache de 3 dias / 5 GB, modo offline e "Minhas músicas"

Leia docs/TECNICO.md antes. Regras: comentários em português; NÃO mudar o visual existente (o design é do dono —
elementos novos devem copiar exatamente o estilo dos que já existem); não publicar, não fazer push, não mexer em
tools/publicar.sh, na chave (~/Sonus-chave) nem no nome da versão. NÃO instale no celular (o Claude instala).
Compilar: `cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=5` e
`node --check` nos JS alterados. Outro programador está mexendo em desempenho (prototype/*.html) e outro em
álbuns (search.js/pages.js): faça mudanças pequenas e localizadas nesses arquivos.

## 1. Cache de músicas (nativo: AudioCache/QueuePrefetcher/PlayerController — veja como o cache funciona hoje)
- Toda música ouvida fica no celular por 3 DIAS a partir da última vez que foi ouvida (ouviu de novo → recomeça).
- Venceu → apaga. Limpeza ao abrir o app e periodicamente (barato, sem loop pesado).
- Limite 5 GB. Passou → apaga as ouvidas há mais tempo (LRU) até caber. Nunca apaga a que está tocando, as
  protegidas da fila nem as BAIXADAS (baixadas ficam em outro lugar e nunca expiram).
- Só conta como "em cache" a música COMPLETA (arquivo inteiro). Guardar data do último uso em arquivo pequeno.
- Se Configurações tiver "Limpar cache"/tamanho de cache, manter funcionando e coerente.

## 2. Modo offline
- Detectar sem internet (ConnectivityManager.NetworkCallback). Emitir evento para a WebView ({offline:true/false}).
- Offline: tocar só o que está disponível (baixadas + completas em cache). Na fila, pular as indisponíveis
  (sem erro e sem travar). Rádio/sugestões offline = músicas disponíveis.
- Nada de exceção derrubando o app quando sem rede (procure launch/async sem tratamento de erro em todo o nativo).
- Voltou a internet → normal.

## 3. "Minhas músicas" na tela inicial
- Nova seção na tela inicial "Minhas músicas": as baixadas. Offline: baixadas + em cache. Mesma linguagem visual
  das seções/cartões existentes da tela inicial (reaproveite classes). A tela inicial não rola (scroll travado e
  as seções esticam para preencher — veja fitHome em home.html); a seção precisa caber nesse esquema.
- Tocar abre a lista como página (como playlist em pages.js) e toca com contexto {type:"mine"}.
- Bridge: comando para listar {downloaded:[tracks], cached:[tracks]}.

## 4. Serviço em primeiro plano
- O Android matou o app tocando em segundo plano (importance=400 no exit-info). Conferir em PlaybackService que,
  enquanto toca, o serviço está em primeiro plano (startForeground com tipo mediaPlayback, notificação de mídia) e
  que não sai do primeiro plano em pausa curta/troca de música. Corrigir se não estiver.

Escreva no fim deste arquivo o que mudou e o que testou. Commit no ramo (sem push).
