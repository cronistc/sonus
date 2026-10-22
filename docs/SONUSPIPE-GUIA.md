# Sonus Pipe: guia de diagnóstico de quebras do YouTube

Levantamento em **21/10/2026**, com exemplos do histórico recente do [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor). Nesta amostra, os grupos de reparos que mais se repetem são player JS (`sig`/`n`), cliente/resposta InnerTube e mudança dos renderers/continuations JSON. PoToken/BotGuard e restrição etária costumam aparecer como bloqueios de acesso ou reprodução. O YouTube faz testes A/B por cliente, região e conta; uma falha pode aparecer só em parte dos vídeos. O método que se repete nos reparos upstream é: obter um caso reproduzível, identificar a camada que falhou, comparar a resposta real com os testes gravados e corrigir a menor peça possível.

Os tempos abaixo são aproximados e medem abertura → merge do PR quando há datas disponíveis. Issue aberta não significa que exista solução upstream.

## Mapa do código

| Etapa | Código principal |
| --- | --- |
| Player JS, URL `base.js`, timestamp de assinatura | [`YoutubeJavaScriptExtractor`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeJavaScriptExtractor.java), [`YoutubeJavaScriptPlayerManager`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeJavaScriptPlayerManager.java), [`YoutubeSignatureUtils`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeSignatureUtils.java) |
| Parâmetros `n` e `sig` | [`YoutubeThrottlingParameterUtils`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeThrottlingParameterUtils.java), [`YoutubeStreamExtractor`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubeStreamExtractor.java) |
| Clientes InnerTube, versões, `visitorData` e cabeçalhos | [`ClientsConstants`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/ClientsConstants.java), [`InnertubeClientRequestInfo`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/InnertubeClientRequestInfo.java), [`YoutubeParsingHelper`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeParsingHelper.java), [`YoutubeStreamHelper`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeStreamHelper.java) |
| PoToken e estado do player | [`PoTokenProvider`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/PoTokenProvider.java), `YoutubeStreamExtractor.setPoTokenProvider()` |
| Busca / busca do YouTube Music | [`YoutubeSearchExtractor`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubeSearchExtractor.java), [`YoutubeMusicSearchExtractor`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubeMusicSearchExtractor.java) |
| Playlist / canal / abas do canal | [`YoutubePlaylistExtractor`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubePlaylistExtractor.java), [`YoutubeChannelExtractor`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubeChannelExtractor.java), [`YoutubeChannelTabExtractor`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubeChannelTabExtractor.java) |

## Falhas frequentes: sintoma, confirmação e reparo

| Tipo | Sintoma e confirmação | Onde mexer; exemplo upstream e tempo |
| --- | --- | --- |
| **Assinatura `sig` / regex do nome da função JS** | `ParsingException: Could not parse deobfuscation function`, seguida por `Could not find deobfuscation function with any of the known patterns`; busca por uma regex conhecida falha no `base.js` baixado para o player do vídeo. Compare o nome/forma da função no JS atual com as regexes, sem trocar cliente às cegas. | `YoutubeSignatureUtils` acha a função; `YoutubeJavaScriptPlayerManager` executa o trecho extraído; `YoutubeJavaScriptExtractor` obtém o JS. [Issue #1287](https://github.com/TeamNewPipe/NewPipeExtractor/issues/1287) e [PR #1290](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1290) adicionaram regex para player novo: cerca de **102 dias** até merge (31/03–11/07/2025). O trabalho depois recebeu outro padrão em [PR #1302](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1302), exemplo de regexes evoluindo conforme o player muda. |
| **Throttling `n`** | O extrator lista formatos, mas a reprodução/baixada falha com **HTTP 403**; a URL tem `n` ausente ou sem a transformação esperada. Confirme se a transformação de entrada/saída do `n` muda e se o JS do player usado é o atual. Um 403 sozinho não prova que seja `n`. | `YoutubeThrottlingParameterUtils` extrai e aplica a função; `YoutubeStreamExtractor` integra o resultado. [PR #1545](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1545) atualizou o parser de `n` e levou cerca de **42 dias** (08/09–20/10/2026). [PR #1443](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1443) mostra um ajuste de `nsig` resolvido em aproximadamente **1 dia** (27–28/01/2026). |
| **Cliente, versão ou cabeçalhos** | Pode haver `LOGIN_REQUIRED`, resposta sem `streamingData`, lista vazia, somente 360p, erro de recarga ou 403. Compare a mesma URL e região entre clientes e registre `clientName`, versão e status. A requisição é um conjunto: `context.client`, `X-YouTube-Client-Name`, `X-YouTube-Client-Version`, `User-Agent`, `Origin`, `Referer`, `visitorData` e, quando aplicável, `signatureTimestamp`. | Veja `ClientsConstants`, `YoutubeParsingHelper`, `InnertubeClientRequestInfo` e os criadores de DASH. `WEB_REMIX` é o cliente de YouTube Music; `WEB`, `ANDROID`, `IOS`, `TVHTML5` e outros não são intercambiáveis. O conjunto ativo muda: [PR #1529](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1529) removeu clientes quebrados/sem uso (`ANDROID`, `IOS`, `WEB_EMBEDDED_PLAYER`) em **14 dias** (06–20/08/2026); [PR #1438](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1438) removeu `TVHTML5` ao corrigir erro de recarga. [PR #1520](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1520) passou a extrair a versão WEB dos dados do service worker: aproximadamente **113 dias** (29/06–20/10/2026). |
| **PoToken / BotGuard / “Sign in to confirm you're not a bot”** | A extração pode lançar `SignInConfirmNotBotException` ou não trazer formatos reproduzíveis, embora o JSON tenha metadados. Separe a mensagem `playabilityStatus` do erro de decodificação JS e confira qual cliente/token foi usado. O PoToken é vinculado ao contexto; no cliente WEB, a documentação separa o token de player (minter: `videoId`) do token colocado na URL de streaming (minter: `visitorData`). Cabeçalho ou versão aleatórios não substituem o token. | O extractor oferece `PoTokenProvider` e `YoutubeStreamExtractor.setPoTokenProvider()` para integrar um provedor. [PR #1272](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1272) adicionou suporte e refatorou os pedidos dos clientes depois de relatos de 403 (issue [#11803](https://github.com/TeamNewPipe/NewPipe/issues/11803), aberta em 19/12/2024 e encerrada em 05/02/2025: cerca de **48 dias** do relato à resolução). [PR #1352](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1352) passou a identificar a mensagem de bot como exceção própria em **31 dias** (28/07–28/08/2025); isso melhora o diagnóstico, não cria um token. A [issue #1392](https://github.com/TeamNewPipe/NewPipeExtractor/issues/1392) sobre token vinculado ao vídeo seguia aberta na data deste levantamento. |
| **JSON de busca, playlist ou canal** | Resultados vazios/incompletos, `ParsingException`, NPE ou perda de título, duração, views e datas. Compare o JSON gravado com o ramo percorrido: YouTube migra itens para novos renderers, como `lockupViewModel`, e muda continuations. Confirme separadamente a primeira página e a paginação. | Busca: `YoutubeSearchExtractor` / `YoutubeMusicSearchExtractor`; playlist: `YoutubePlaylistExtractor`; canal: `YoutubeChannelTabExtractor` e extratores de itens. [PR #1518](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1518) corrigiu continuations de playlists em **18 dias** (25/06–13/07/2026). [PR #1503](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1503) adaptou itens de playlist ao modelo lockup em **9 dias** (31/05–09/06/2026). Para abas de canal, [PR #1492](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1492) corrigiu data/views de `lockupViewModel` em cerca de **1 dia** (22–23/05/2026). |
| **403 no link de áudio/vídeo** | O player pode apresentar `ERROR_CODE_IO_BAD_HTTP_STATUS`; a extração de metadados funciona, mas o servidor nega a URL de mídia. Compare uma URL recém-extraída com a que o player tenta baixar. Confira `n`, `sig`/`signature`, validade da URL, cliente que a gerou, cabeçalhos de reprodução e se a resposta veio por SABR. Remova tokens/cookies do log compartilhado. | A causa pode ser `n`, PoToken, assinatura, URL expirada/sessão ou mudança de protocolo; não classifique todo 403 como “erro de rede”. Veja `YoutubeStreamExtractor`, `YoutubeStreamHelper` e criadores DASH/HLS. Além da resolução da issue 403 [#11803 / PR #1272](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1272), a [PR #1508](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1508) contornou respostas SABR sem formatos diretos usando outro cliente em **1 dia** (08–09/06/2026). |
| **Age-restricted** | `AgeRestrictedContentException: This age-restricted video cannot be watched anonymously`, ou `Could not get any stream`. Veja `playabilityStatus.reason` e confirme que o mesmo vídeo é realmente assistível sem login/idade no contexto testado. “Membros”, privado, indisponível por país e bloqueio de idade são estados diferentes. | `YoutubeStreamExtractor.checkPlayabilityStatus()` classifica o estado. O teste [`YoutubeStreamExtractorAgeRestrictedTest`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/test/java/org/schabi/newpipe/extractor/services/youtube/stream/YoutubeStreamExtractorAgeRestrictedTest.java) está marcado `@Disabled`: upstream registra que atualmente não consegue extrair esse conteúdo. Portanto, o sinal útil é a exceção e o teste desativado, não uma correção recente a portar. Um relato próximo é [NewPipe #11382](https://github.com/TeamNewPipe/NewPipe/issues/11382) (ago/2024); [PR #1272](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1272) cobre PoTokens, mas não equivale a suporte geral a vídeos com restrição etária. |

## Como os testes funcionam

O extractor mantém pares gravados de requisição/resposta em `extractor/src/test/resources/mocks/v1/`. `MockDownloader` reproduz esses JSON sem rede; `RecordingDownloader` faz as requisições reais e grava novos `generated_mock_N.json`. Os testes YouTube estão em [`extractor/src/test/java/.../services/youtube`](https://github.com/TeamNewPipe/NewPipeExtractor/tree/dev/extractor/src/test/java/org/schabi/newpipe/extractor/services/youtube), inclusive `YoutubeJavaScriptExtractorTest`, `YoutubeSignaturesTest`, `YoutubeThrottlingParameterDeobfuscationTest`, `YoutubeSearchExtractorTest`, `YoutubePlaylistExtractorTest` e testes de `stream/` e `search/`.

```bash
cd /caminho/NewPipeExtractor

# Compilar o extractor com os testes usando fixtures gravadas
./gradlew :extractor:build -Ddownloader=MOCK

# Só testes YouTube, reproduzindo os mocks gravados e sem depender do estado atual do site
./gradlew :extractor:test \
  --tests 'org.schabi.newpipe.extractor.services.youtube.*' \
  --tests 'org.schabi.newpipe.extractor.services.youtube.search.*' \
  --tests 'org.schabi.newpipe.extractor.services.youtube.stream.*' \
  -Ddownloader=MOCK

# Exemplo: isolar um teste de throttling
./gradlew :extractor:test \
  --tests 'org.schabi.newpipe.extractor.services.youtube.YoutubeThrottlingParameterDeobfuscationTest' \
  -Ddownloader=MOCK
```

O tipo de downloader padrão é `REAL`. Para atualizar fixtures, rode a classe de teste inteira com `-Ddownloader=REC`; o recorder substitui `generated_mock_N.json` daquele teste e a ordem/requisições precisam corresponder. Revise `git diff` e rode de novo com `MOCK`. Evite gravar só um método de uma classe cujos testes compartilham estado ou mocks. O guia upstream explica o [processo local de testes e publicação do artefato](https://github.com/TeamNewPipe/NewPipeExtractor#testing-changes); as implementações estão em [`DownloaderFactory`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/test/java/org/schabi/newpipe/downloader/DownloaderFactory.java), [`RecordingDownloader`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/test/java/org/schabi/newpipe/downloader/RecordingDownloader.java) e [`MockDownloader`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/test/java/org/schabi/newpipe/downloader/MockDownloader.java).

Nota: `YoutubeStreamExtractorAgeRestrictedTest` está desativado; passar a suíte não comprova que esse caso funciona ao vivo.

## Gerar e ligar um build do Sonus Pipe ao Sonus

No NewPipe Extractor, `./gradlew :extractor:build -Ddownloader=MOCK` compila e executa build/testes do módulo com fixtures. Para instalar a versão local no Maven, o README upstream documenta `./gradlew install` e o uso de `mavenLocal()`. Para uso iterativo, o README também mostra `includeBuild(...)` com substituição de dependência.

No Sonus deste ramo, [`android/gradle/libs.versions.toml`](../android/gradle/libs.versions.toml) aponta para `com.github.teamnewpipe:NewPipeExtractor:v0.26.5`; [`android/core/extractor/build.gradle.kts`](../android/core/extractor/build.gradle.kts) expõe essa dependência para o app, e `android/settings.gradle.kts` já declara JitPack. Para consumir um fork remoto, use a coordenada JitPack do fork, por exemplo `com.github.<dono>:NewPipeExtractor:<tag-ou-SHA>`, na entrada `newpipe-extractor` do catálogo de versões. Para compilar contra o checkout local, siga o exemplo `includeBuild` do README do extractor e substitua o módulo `com.github.teamnewpipe:NewPipeExtractor` pelo projeto `:extractor` do fork.

Depois da troca, compile pelo checkout do Sonus:

```bash
cd /caminho/sonuspipe-estudo/android
./gradlew :app:assembleDebug
```

Para uma versão que será distribuída, o Sonus tem [`tools/publicar.sh`](../tools/publicar.sh): `tools/publicar.sh <versão> <código> <arquivo-de-novidades.txt>`. Esse script compila o release assinado e envia APK + `update.json` ao ramo `updates`, que os celulares consultam. É uma publicação real; execute apenas quando houver decisão explícita de soltar a versão.

## Acompanhar quebras e buscar uma correção antes do upstream

- [Issues abertas do NewPipe Extractor marcadas `YouTubeService`](https://github.com/TeamNewPipe/NewPipeExtractor/issues?q=is%3Aissue+is%3Aopen+label%3AYouTubeService), [PRs](https://github.com/TeamNewPipe/NewPipeExtractor/pulls) e [commits recentes](https://github.com/TeamNewPipe/NewPipeExtractor/commits/dev/). Na busca, procure termos como `n-sig`, `PoToken`, `SABR`, `continuation`, `lockupViewModel` e `403`. No repositório do app, os rótulos `requires extractor change` e `ASAPIssue` ajudam a separar relatos que dependem do extractor.
- [Issues do app NewPipe que pedem mudança no extractor](https://github.com/TeamNewPipe/NewPipe/issues?q=is%3Aissue+is%3Aopen+label%3A%22requires+extractor+change%22). O app costuma receber primeiro o relato de reprodução; o patch fica no repositório `NewPipeExtractor`.
- Chat de desenvolvedores/comunidade: sala Matrix [`#newpipe:matrix.newpipe-ev.de`](https://matrix.to/#/#newpipe:matrix.newpipe-ev.de); canal IRC `#newpipe` na Libera.Chat, bridged à sala Matrix. O [guia de contribuição](https://github.com/TeamNewPipe/NewPipe/blob/dev/.github/CONTRIBUTING.md) recomenda esses canais.
- [Histórico de commits do extractor YouTube no yt-dlp](https://github.com/yt-dlp/yt-dlp/commits/master/yt_dlp/extractor/youtube/), [issues](https://github.com/yt-dlp/yt-dlp/issues) e [guia de PO Token](https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide). O código atual divide YouTube em `yt_dlp/extractor/youtube/` (`_base.py`, `_video.py`, `_tab.py`, `_search.py`, `pot/`); compare patch, fixtures e testes antes de adaptar.

Para consultar commits pela API sem login, use `curl` e aponte `path` para o arquivo ou diretório:

```bash
curl -fsSL 'https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/commits?path=extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeThrottlingParameterUtils.java&per_page=30'
curl -fsSL 'https://api.github.com/repos/yt-dlp/yt-dlp/commits?path=yt_dlp/extractor/youtube&per_page=30'
```

Para portar a ideia do yt-dlp: leia o diff e o teste que o acompanha; escreva em uma frase qual campo ou transformação mudou; encontre o ponto equivalente no fluxo Java; implemente só essa mudança; crie/atualize fixture e teste de regressão; valide o fallback e os outros clientes. Código Python, helpers de rede e interpretadores JS não são tradução direta. Para função do player, adapte a identificação/extração à estrutura `YoutubeSignatureUtils` / `YoutubeThrottlingParameterUtils` e cubra a execução em `YoutubeJavaScriptPlayerManager`.

## Plantão: “música não toca” ou “busca quebrou”

Os **10 minutos** são para classificar e localizar a camada, não para prometer um bypass universal. Registre uma URL de exemplo, horário, país/rede, versão do Sonus/extractor e a exceção exata; remova cookies, `poToken`, chaves e query values sensíveis de qualquer log compartilhado.

1. **Minutos 0–2 — separar o sintoma.** Busca não retorna itens? Falha em playlist/canal ou só na continuação? Metadados chegam, mas nenhum stream de áudio é extraído? Ou o player recebeu URL e o servidor respondeu 403? Anote se ocorre em um único item ou em vários.
2. **Minutos 2–4 — localizar a evidência.** Procure o texto da exceção e os termos acima nas issues/PRs do NewPipe. Para áudio, identifique cliente, código HTTP e se falhou `sig`, `n`, PoToken ou a obtenção do JSON. Para busca/paginação, compare o fixture da primeira resposta com a continuação que falhou.
3. **Minutos 4–6 — olhar a mudança mais recente.** Verifique os commits de `yt-dlp` na pasta YouTube e os arquivos/fixtures alterados no PR. Separe um achado confirmado de uma hipótese; não misture correção de JSON com mudança de cliente no mesmo patch.
4. **Minutos 6–8 — reproduzir com um caso mínimo.** No fork Sonus Pipe, adicione/regere o mock necessário, crie um teste que falha antes da alteração e mexa no helper/extractor correspondente. Preserve o caso antigo no teste.
5. **Minutos 8–10 — validar em camadas.** Rode o teste YouTube focal em `MOCK`, depois a suíte YouTube com mocks, `./gradlew :extractor:build` e o build do Sonus `:app:assembleDebug`. Se só o teste online falhar, registre cliente, status e horário: respostas A/B podem variar.
6. **Preparar uma versão.** Fixe o fork por tag/SHA no catálogo do Sonus, compile e revise o APK. Para distribuir, após a decisão de release, use `tools/publicar.sh <versão> <código> <arquivo-de-novidades.txt>`; o script publica no ramo `updates` e dispara a atualização dos aparelhos. A investigação/build local não exige executar esse script.

## Referências upstream

- [NewPipe Extractor: README, testes, build local e JitPack](https://github.com/TeamNewPipe/NewPipeExtractor#testing-changes)
- [Histórico de releases do extractor](https://github.com/TeamNewPipe/NewPipeExtractor/releases)
- [Exemplo de exceção de assinatura](https://github.com/TeamNewPipe/NewPipeExtractor/issues/1287)
- [Exemplo de issue PoToken ainda em acompanhamento](https://github.com/TeamNewPipe/NewPipeExtractor/issues/1392)
- [Sala Matrix / IRC no site NewPipe](https://newpipe.net/press/contact/)
- [yt-dlp: arquitetura dos extractors](https://github.com/yt-dlp/yt-dlp/tree/master/yt_dlp/extractor/youtube)
