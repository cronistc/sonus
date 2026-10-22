# Sonus — documentação técnica

Player de música para Android, 100% no aparelho (sem servidor próprio obrigatório).
Interface e player de áudio feitos do zero; músicas via NewPipe Extractor (YouTube / YouTube Music).

> Versões: quem define é o Guilherme. A versão atual em desenvolvimento é a **0.1**.

---

## 1. Estrutura do repositório

```
prototype/          Interface (HTML/CSS/JS feitos do zero). Roda no navegador com dados de
                    exemplo e, dentro do app, ligada ao player nativo. Entra no APK como assets.
android/
  app/              App Android (Kotlin): WebView da interface, ponte JS↔Kotlin, player, serviço
  core/extractor/   "Motor": NewPipe Extractor + busca, resolução de áudio, cache, capas
  tools/smoke/      Teste ao vivo contra o YouTube (busca, resolver, baixar, capas)
docs/               Esta documentação
DESIGN.md           Decisões de design (telas, identidade, requisitos)
```

## 2. Como compilar

Requisitos: JDK 17, Android SDK 34. O Gradle wrapper (8.7) já está no projeto.

```bash
cd android
./gradlew :app:assembleDebug          # APK em app/build/outputs/apk/debug/
./gradlew :core:extractor:test        # testes offline
./gradlew :tools:smoke:run --args="nome da música"   # teste ao vivo
```

### Chave de assinatura (NUNCA vai para o repositório)

Todo APK precisa sair assinado com a chave definitiva do Sonus — é o que permite atualizar
por cima (inclusive sozinho) nos celulares. O Gradle lê `~/Sonus-chave/sonus.properties`
(fora do projeto). Sem esse arquivo, o build sai com a chave de teste do Android e **não**
instala por cima da versão dos usuários.

Cópias da chave: `~/Sonus-chave/` no PC e `Documents/Sonus-chave/` no celular (com LEIA-ME).
Impressão digital SHA-256: `B7:DF:87:56:A7:A4:64:75:2F:70:47:CA:FE:08:EE:3C:FE:9E:4A:49:C7:4B:45:D0:CF:06:E8:E0:3A:47:2B:A8`

ID interno do app: `app.sounos` (não muda — mudar faria o Android tratar como outro app).

## 3. Arquitetura do app

```
WebView (prototype/*.html)  ──SounosNative.call(método, json)──▶  Bridge.kt
          ▲                                                        │
          └──── window.__sounos.emit(evento, dados) ◀──────────────┘
                (state, progress, lyrics, download, reply, storage…)

Bridge ─▶ PlayerController ─▶ AudioEngine (MediaExtractor → MediaCodec → ganho → AudioTrack)
                │                     ▲
                │                     └── GrowingFileSource: toca o arquivo enquanto ele baixa
                ├─▶ StreamResolveCache ─▶ NewPipeSource (busca, resolver, rádio)
                ├─▶ AudioCache (um arquivo por música, retomada por Range, troca URL no 403/410)
                ├─▶ QueuePrefetcher (próxima música inteira; as 2 seguintes só o começo)
                ├─▶ CoverStore (capas fixadas + navegação; sempre a maior; nunca somem)
                └─▶ Lyrics (LRCLIB, casando pela duração ±3 s)
```

- **Telas**: `home.html` (tela inicial + páginas de playlist/artista em `pages.js`) e
  `player.html`. No app, o player fica carregado num quadro por cima da tela inicial
  (abrir/fechar só desliza, sem recarregar). Eventos do app chegam na página principal e
  são repassados ao quadro (`native.js`).
- **Início instantâneo**: o áudio começa pelos primeiros bytes; o resto baixa junto.
  A posição vem de `AudioTrack.getTimestamp` (o que já saiu no fone) → letra no tempo certo.
- **Volume equilibrado**: alvo −14 dBFS RMS, ganho −9…+6 dB, limitador suave; ganho
  guardado por música.
- **Indicadas / rádio**: rádio do YouTube Music (`RDAMVM<id>`), filtrado por `MusicPicks`
  (sem covers, karaokê, reações, coletâneas; sem repetir a mesma música em outro upload).
- **Downloads**: pasta privada do app (`files/downloads`), invisível para outros apps.

## 4. Auto-recuperação — plano (em construção)

Problema: quando o YouTube muda algo, o NewPipe quebra e hoje só um APK novo conserta.
Objetivo: o app se consertar sozinho, em segundo plano, sem o usuário perceber.

### 4.1 Motor trocável
- O app se divide em **app** (telas, player, cache, playlists — muda pouco) e **motor**
  (NewPipe Extractor + busca/resolver — é o que quebra).
- O motor é um arquivo à parte (dex/jar, ~3–4 MB) carregado em tempo de execução
  (`DexClassLoader`), atrás de uma interface estável (`SourceEngine`: buscar, resolver,
  sugestões, rádio, artista).
- O APK sempre traz um **motor de reserva**; o app guarda o **último motor que funcionou**.
- **Assinatura**: cada motor sai assinado (Ed25519). O app só carrega motor com assinatura
  válida da chave do Sonus (a chave pública vai embutida no app; a privada nunca sai do PC).
- Antes de adotar um motor novo: teste rápido no próprio aparelho (buscar + resolver uma
  música). Falhou → volta para o anterior.

### 4.2 Quando procurar motor novo
- Ao abrir o app, a cada poucas horas e **na hora em que uma música falha**.
- A consulta é um arquivo pequeno (`motor.json`: versão, URL, tamanho, SHA-256, assinatura);
  o motor só é baixado quando muda.

### 4.3 Segundo motor (yt-dlp)
- yt-dlp (projeto independente do NewPipe, corrigido rápido, se atualiza sozinho) entra
  **só quando o NewPipe falha** ao resolver o áudio. Busca continua no NewPipe.
- Custo: APK ~25–30 MB maior; resolver leva ~2–4 s (só no caso de falha).
- Também na fila de tentativas: pedir o áudio por outro cliente do YouTube e trocar
  o formato (opus ↔ m4a).

### 4.4 Atualização do app inteiro, sem tela
- Android 12+: um app pode atualizar a si mesmo sem confirmação quando é o "instalador
  de registro" de si mesmo (`PackageInstaller` + `USER_ACTION_NOT_REQUIRED`).
- Usuário novo: instala o APK (tela normal do Android), autoriza uma vez "instalar apps",
  o Sonus se reinstala uma vez e, daí em diante, atualiza sozinho.
- Só atualiza com nada tocando (atualizar fecha o app por um instante).

### 4.5 Publicação
- Arquivos de atualização (`motor.json`, motores, APKs) num endereço fixo (GitHub Releases
  ou servidor próprio). A segurança vem da assinatura, não do endereço.
- **Nenhuma versão é publicada sem o Guilherme decidir.**

## 5. Testes no celular

- ADB por Wi-Fi (Depuração sem fio). WebView depurável (build debug):
  `adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>` e Chrome DevTools/CDP.
- `tools/smoke`: busca ~1,4 s · resolver ~2 s · começo do áudio ~0,1 s · capa 1200×1200.

## 6. Licença

O NewPipe Extractor é GPLv3; por isso o Sonus também é distribuído sob GPLv3.

---

## Resumo da tarefa: busca e página de álbuns

- O extrator agora busca álbuns pelo filtro do YouTube Music e carrega a página de um álbum com faixas e capas quadradas.
- A ponte recebeu os comandos assíncronos `searchAlbums` e `album`.
- A busca nativa exibe álbuns, abre sua página a partir da tela inicial ou do player, e a página de artista passou a mostrar os álbuns correspondentes.
- Validado com `node --check prototype/search.js`, `node --check prototype/pages.js` e `JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=5`.

## Resumo desta tarefa: busca com álbuns e mais páginas

- O bloco de álbuns mantém o mesmo layout e agora aparece após as quatro primeiras músicas, junto aos artistas.
- A busca nativa guarda a próxima página por consulta e oferece `searchMore`; a interface busca uma página por vez ao chegar perto do fim, acrescenta faixas sem repetir IDs e mostra um esqueleto enquanto carrega.
- Verificado com `node --check prototype/search.js`, `git diff --check` e `cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=5`.
## Resumo da tarefa: foto de artista rápida e persistente

- A rota `/cover/artist:*` agora usa a chave normalizada do artista, guarda a imagem em `files/covers` e mantém o índice nome→URL em `artist-urls.properties`. O perfil e a tela inicial consultam o mesmo cache; buscas simultâneas do mesmo nome compartilham a resolução e o download.
- Quando o índice e a imagem ainda não existem, o app busca o artista mais próximo em `searchArtists`, redimensiona o pedido do avatar para cerca de 400 px e persiste o resultado. A resposta da rota informa `Cache-Control: public, max-age=31536000, immutable` ao WebView.
- A seção de artistas da tela inicial e o topo do perfil usam a mesma rota. A busca de músicas do perfil continua paralela à resolução da foto.
- Medição antes/depois: antes não havia tempos por etapa nem cache persistente de foto de artista, portanto não há baseline numérico confiável. Foram adicionados logs para `NewPipe.searchArtists_ms`, espera e consulta ao índice/disco, busca, download, rota `/cover/artist:*` e `Bridge.artist`; eles registram valores em ms e distinguem cache de rede. Não foi coletado tempo de execução depois da mudança porque a tarefa proíbe instalar no celular.
- Verificado: `node --check` em `prototype/native.js`, `prototype/home-native.js`, `prototype/pages.js` e `prototype/search.js`; compilação `JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=5`; `git diff --check`.

## 7. Redução de CPU da interface (21/10/2026)

- Tela inicial: barra, tempo e letra do mini-player usam timer de 100 ms enquanto a tela inicial
  está visível e a música toca; no gesto de busca, o relógio acompanha o dedo. O timer para com
  player, página ou overlay cobrindo a tela, ou quando a música pausa. A barra só escreve quando
  sua ponta avança um pixel.
- Player: anel, equalizador e relógio usam timer de 66 ms só enquanto o player está visível,
  tocando e sem overlay. O anel só redesenha quando sua ponta avança um pixel; as linhas da letra
  só são sincronizadas quando a tela de letra está aberta.
- As animações de preenchimento da letra, seta de download e esqueleto param quando ficam ocultas
  ou quando a música pausa. Foram removidos `will-change` do anel, capa, equalizador e quadro do
  player.
- A ponte nativa mantém o ticker de progresso em 250 ms (4 eventos/s). Progresso não é enviado à
  WebView quando a tela está apagada ou a Activity está pausada; o quadro do player também deixa de
  receber progresso quando está fechado.

### Medições no Galaxy A54

Medições anteriores à instalação, com a faixa “Tempo de Aprender” tocando; CPU do processo
`app.sounos` em `top` (100% = um núcleo), 5 amostras separadas por 2 s:

| Estado | CPU antes (5 amostras) | Média | Quadros `gfxinfo` após reset |
| --- | --- | ---: | --- |
| Tela inicial | 70,0 · 69,5 · 50,5 · 77,0 · 82,5% | 69,9% | 907; 784 janky (86,4%) |
| Player aberto | 62,0 · 76,0 · 75,0 · 78,5 · 79,0% | 74,1% | 499; 476 janky (95,4%) |
| Tela apagada | 43,3 · 44,0 · 46,0 · 61,0 · 58,5% | 50,7% | 83; 79 janky (95,2%) |

O APK com `versionCode=5` foi compilado e instalado por cima. Depois da instalação, o aparelho
ficou no AOD/lockscreen sem sessão de áudio; nesse estado o processo ocioso marcou 0,0% nas cinco
amostras. Esse número não é comparável com o baseline reproduzindo. A autenticação do aparelho
impediu reabrir o Sonus para medir home/player após a instalação e capturar a tela de letras; essas
medições e a comparação visual pós-instalação ficaram pendentes.

## Registro do teste de estresse no Galaxy A54 (21/10/2026)

| Bug | Causa | Correção | Confirmado no aparelho? |
| --- | --- | --- | --- |
| Voltar na página de artista fechava também a busca que estava por baixo. | `history` local era a lista do histórico de buscas e sombreava `window.history` na verificação de `popstate`; a busca interpretava o retorno da página como pedido para fechar. | A verificação agora lê `window.history.state`. | Sim: compilado, instalado por cima e reproduzido de novo; Voltar fechou o artista e manteve os resultados da busca. |
| Voltar com a letra aberta fechava o player inteiro. | O modo de letra não era tratado pelo botão Voltar da Activity. | O handler da tela inicial delega o Voltar ao player; o player fecha a letra antes de devolver o comando à Activity. | Sim: compilado, instalado por cima e confirmado; Voltar fechou a letra e manteve o player. |

Cobertura executada enquanto o ADB estava conectado: navegação entre início, player, configurações,
atualização, busca, artista, álbum e Minhas músicas; busca com rolagem extensa; play/pause, próxima,
anterior, repetir, aleatório e seleção de sugestão. As páginas de artista, álbum e Minhas músicas
abriram e fecharam; não havia playlist existente para abrir, então nenhuma playlist foi criada ou
alterada. A lista de downloads continuou com 5 músicas e 19 MB após as instalações.

Não apareceu crash novo em `exit-info`, no Logcat filtrado ou no Dropbox durante a parte conectada.
O histórico do Dropbox já continha registros anteriores ao teste (20/10 e 21/10 às 12:53). O teste
de Wi-Fi começou com áudio ativo, mas o primeiro `svc wifi disable` derrubou o ADB; o serviço
`_adb-tls-connect` anunciado em `192.168.1.100:34555` recusou conexão. O IP respondeu a ping, mas
não foi possível recolher os logs após a queda nem confirmar que a sequência de alternâncias terminou.
Não tentei desbloquear o aparelho. Se a tela estiver bloqueada: **PRECISO DO CELULAR DESBLOQUEADO**.

Ficaram pendentes por causa da perda do ADB: rodadas `monkey -p app.sounos --throttle 150 -v 3000`
com sementes diferentes, nova confirmação de logs após a rede, tela apagar/acender e HOME/retorno.
A Activity declara orientação `portrait`; não foi feita rotação física. Nenhum bug reproduzido ficou
sem correção; falta completar essas rodadas no aparelho.

Sincronização final: `git pull origin main` informou `Already up to date.`. Após a sincronização,
`cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=5`
concluiu com sucesso.

## Resumo da tela inicial online e offline (21/10/2026)

- Online, a tela inicial mostra Ouvidos recentemente, Seus artistas e Suas playlists; Minhas músicas não ocupa mais uma seção separada.
- Offline, o primeiro carrossel passa a mostrar as músicas baixadas e em cache como cartões do mesmo tamanho dos recentes. Tocar em um cartão inicia a fila com contexto `mine`.
- A indicação Offline substitui a clave de sol no cabeçalho e volta à clave quando a conexão retorna. A mudança atualiza a seção sem remontar a tela, e o `fitHome` continua usando os três carrosséis visíveis.
- Após o commit inicial, `git pull origin main` informou `Already up to date.`; não houve conflitos. A validação final passou com `node --check prototype/home-native.js`, `git diff --check` e `JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=5`.

## Resumo da tarefa: letreiro e fonte (21/10/2026)

- Títulos e artistas truncados agora percorrem o texto com animação CSS `transform`, fazem uma pausa de cerca de 2 s em cada ponta e repetem enquanto estão visíveis. A animação pausa quando a página fica inativa, o quadro do player se oculta ou o texto sai da área visível; a distância só é recalculada quando o conteúdo ou o tamanho muda.
- O letreiro cobre player, mini-player, fila, cartões da tela inicial, busca, páginas e lista de adicionar à playlist.
- Os tamanhos tipográficos do app usam o fator único `--fs: .94`, aplicado proporcionalmente em home, player, busca, páginas, configurações, adição e atualização. Ícones, capas, botões e espaçamentos mantêm suas dimensões.
- Após integrar `main` sem conflitos, `git pull origin main` informou `Already up to date.`. Verificado novamente com `node --check` em todos os arquivos `prototype/*.js`, `git diff --check` e `cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=5`. Não houve instalação no celular.

## Resumo do topo do player e das capas da fila (21/10/2026)

- O player deixou de exibir os botões de configurações e busca e a clave de sol no topo. A ligação e os estilos da busca, e a ligação e os estilos do modal de configurações, saíram do player; a tela inicial conserva os dois recursos. O módulo de preferências continua carregado no player porque fornece o tema e o ajuste de sincronização da letra.
- O cálculo da capa principal preserva o tamanho anterior. As capas da fila passam a ter 10% a mais que o tamanho calculado anteriormente para cada tela; o espaço restante continua livre entre a fila e os controles.
- Após integrar `main`, `node --check prototype/player-native.js` e `JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=7` passaram. Não houve instalação no celular.

## Cache rápido de capas e metadados (21/10/2026)

- `/cover/<id>` procura primeiro no disco e responde sem `runBlocking`; buscas, promoções de `pin` e atualização de qualidade ficam em segundo plano. Ao terminar um download, a interface renova a URL da capa sem remontar a tela.
- Um índice por URL normalizada reutiliza a mesma imagem entre IDs diferentes e URLs do YouTube que pedem tamanhos distintos. Listas, busca, páginas e fila usam miniaturas de até cerca de 400–480 px; só a capa principal do player pede a versão grande. A versão pequena e a grande têm arquivos separados no cache descartável.
- Capas de downloads e playlists são fixadas em `files/covers`; ao salvar uma playlist, capas que já estão no cache são promovidas sem fazer download. Ao remover uma música da última playlist e dos downloads, ela volta ao LRU. Na atualização, capas antigas de músicas apenas ouvidas e fotos de artista são migradas para o LRU.
- Resultados de busca, sugestões, páginas de artista e álbum e rádio do player são salvos como JSON em `cache/metadata`. A próxima abertura mostra o arquivo salvo e atualiza em segundo plano; falha de rede mantém o valor salvo. Ao ler ou gravar, o arquivo atualiza sua posição no LRU.
- O cache descartável de imagens e metadados compartilha limite de 2 GiB, sem prazo de validade; a limpeza remove o uso mais antigo. Capas fixadas ficam fora dessa limpeza. O cache de áudio passa a 3 GiB e conserva a regra de três dias desde a última reprodução, com LRU.

### Medições e logs

- Antes: o ramo `pin` podia chamar `get` e fazer upgrade síncrono quando a capa local era pequena; o arquivo não tinha tempos por etapa nessa rota, então não existe baseline numérico comparável do aparelho. Não medi nem instalei no celular, conforme pedido para deixá-lo com os outros testes.
- Depois, nos testes unitários JDK 17 em diretório temporário: consulta de uma capa já no disco `1 ms`; gravação de metadados `6 ms`; leitura `1 ms`. São amostras locais do host, não tempos da WebView nem do celular.
- O app registra `cover rota etapa=disco_ms`, `resposta_ms`, `preload_ms` e `upgrade_ms`; o `CoverStore` registra espera de trava, disco, download e LRU; metadados registram disco, rede e gravação. Esses tempos permitirão comparar no aparelho sem bloquear a rota de capa.
- Testes unitários cobrem LRU entre imagens/metadados, cache JSON, reaproveitamento de URL, miniaturas de lista, limite de 3 GiB do áudio e resposta local sem rede. `node --check` passou para todos os `prototype/*.js`.
- Validação local: `:core:extractor:test`, `node --check` em todos os scripts e `JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=7` passaram. Nenhum APK foi instalado.
- Após o commit, `git merge main` integrou sem conflitos a limpeza dos nomes de álbuns e o mini-player sobre a busca; as duas mudanças foram mantidas junto ao cache. A verificação seguinte informou `Already up to date.`. Repeti testes unitários, `node --check` e a compilação `versionCode=7` depois da integração; todos passaram.

## Tarefa: letra e troca de tema — relatório antes/depois (21/10/2026)

- A troca de tema atualiza primeiro o painel de Configurações e persiste a escolha sem redesenhar
  os indicadores de armazenamento. O contêiner da tela recebe o novo tema depois que o painel
  termina de fechar; assim a mudança de variáveis não recalcula a página enquanto ela está visível.
- As variáveis de tema ficam no contêiner do app. O player oculto não recebe alterações de tema;
  ao aparecer, consulta a preferência salva. A tela inicial sincroniza o tema depois que o player
  fecha.
- Foram removidas as transições globais de cor e as transições por linha da letra. Em Configurações,
  permanecem as transições dos cartões e da prévia do tema; a entrada e a saída do painel continuam
  animadas por transformação.
- A letra é criada com as classes da linha atual. Durante a reprodução, só as linhas entre a posição
  anterior e a nova recebem atualização; a seleção também altera apenas os trechos selecionados. O
  sincronismo da letra e o ajuste da capa ficam no próximo quadro, fora do handler do toque.

### Medições — Galaxy A54

Quadros medidos com `dumpsys gfxinfo app.sounos` após zerar os contadores. A resposta é o tempo
síncrono do handler no DevTools. Na medição da letra, o player estava pausado e a letra já estava
montada antes do toque.

| Ação | Antes | Depois |
| --- | --- | --- |
| Tema Escuro → Claro → Gradiente | 210 ms de resposta; recálculo de estilos 782 ms; 8/14 quadros janky; p95 85 ms; quadros acima de 50 ms | 2,4 ms e 1,2 ms; recálculo 64 ms no conjunto; 3/73 quadros janky; p95 21 ms; nenhum acima de 50 ms |
| Fechar Configurações após trocar o tema | atualização global durante o painel aberto | aplicação ao contêiner após o fechamento; 2/83 quadros janky; p95 26 ms; nenhum acima de 50 ms |
| Ativar/desativar letra | 6,7 ms; 4/50 quadros janky; p95 13 ms; até 31 ms; a entrada visual esperava 280 ms | 5,2 ms / 2,0 ms com 68 linhas; 12/59 quadros janky; p95 28 ms; nenhum acima de 50 ms |

O número de linhas variou de 41 no baseline para 68 na medição final. As duas ações responderam
abaixo de 100 ms e não produziram quadros acima de 50 ms na medição final.

Na primeira integração com `main`, o conflito em `player.html` foi resolvido preservando o player
sem o topo de configurações/busca e as otimizações da letra. Uma revisão posterior de `main`, com
cache e auditoria visual, também foi integrada; a execução final de `git merge main` retornou
`Already up to date.`. A compilação final `JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=7`
passou. O APK integrado foi instalado no Galaxy A54 e abriu normalmente na tela inicial.

## Letra linha por linha e máscara nas bordas (21/10/2026)

- A linha atual fica inteira acesa e as demais esmaecidas por opacidade. As marcas de tempo por
  palavra são removidas do texto, sem animação de preenchimento ou spans por palavra.
- Ao chegar à primeira linha, a rolagem fica em `scrollTop=0`; isso mantém o topo sem máscara no
  começo da música. As linhas seguintes continuam com rolagem suave. A máscara inferior fica
  sempre ativa e a superior surge com transição curta quando a área realmente sai do início.
- `lyricsMode` continua ativo ao carregar outra faixa: `loadCurrent()` reconstrói a letra sem
  redefinir o modo.

### Validação no Galaxy A54

- O APK foi recompilado com `JAVA_HOME=~/Android/jdk17 ./gradlew clean :app:assembleDebug
  -PversionCode=7`, instalado por cima e aberto normalmente.
- A letra de “Genius”, de LSD, Sia, Diplo & Labrinth, mostrou 67 linhas e nenhum `span` por
  palavra. Na captura de `0:00`, `scrollTop=0` e a classe `scrolled` estava ausente; em `1:43`,
  a linha atual estava inteira acesa e a máscara superior já aparecia. O esmaecimento inferior
  aparece nas duas capturas.
- A persistência foi conferida no código: `loadCurrent()` não limpa `lyricsMode` nem a classe
  `lyr-mode`. No aparelho, o comando Avançar manteve a faixa atual no contexto local, então a
  persistência entre duas faixas distintas foi verificada no fluxo de código, não nessa fila.
- Um toque usado para pausar a faixa abriu por engano a tela `InstalledAppDetails`; voltei ao
  Sonus com `am start` imediatamente e não alterei configurações do sistema.
- `git merge main` integrou sem conflitos pela estratégia `ort`. Depois da integração, o build
  limpo com `-PversionCode=7` passou; o APK integrado foi instalado e abriu com a `MainActivity`
  em primeiro plano.

Capturas da instalação final:

- Início, `0:00`: [letra-inicio.png](capturas/letra-inicio.png)
- Meio, `1:43`: [letra-meio.png](capturas/letra-meio.png)

## Abertura animada: Sonus vira clave de sol (21/10/2026)

- Android 12+ usa o fundo escuro do app (`#0e0e10`) no splash do sistema e um drawable totalmente transparente no lugar do ícone. A Activity mantém a WebView oculta enquanto prepara o primeiro quadro; uma palavra Sonus na fonte Inter do app fica visível durante esse preparo.
- Na abertura fria, `prototype/opening.js` renderiza a palavra e interpola o contorno do `S` até o mesmo path SVG da clave no cabeçalho. “onus” recua e desaparece. A animação usa `requestAnimationFrame` por 1,9 s e termina na posição e tamanho reais do ícone do cabeçalho; tocar na tela pula para a clave. Voltar do segundo plano não inicia outra abertura.
- Tema claro usa fundo e texto claros/escuros correspondentes; Escuro usa `#0e0e10`; Gradiente começa no azul profundo da marca e acompanha o fundo da tela inicial. O splash estático de Android mantém `#0e0e10`.
- A gravação e os quadros do Galaxy A54 ficam em `docs/abertura/abertura.mp4` e `docs/abertura/01-palavra.png` a `05-home.png`.
- A primeira compilação limpa `JAVA_HOME=~/Android/jdk17 ./gradlew -q clean :app:assembleDebug -PversionCode=7` passou. O aparelho já tinha um APK com `versionCode=8`; a instalação de validação do código 7 foi feita com `adb install -r -d -i app.sounos`, sem limpar dados.
