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

## Resumo da tarefa: foto de artista rápida e persistente

- A rota `/cover/artist:*` agora usa a chave normalizada do artista, guarda a imagem em `files/covers` e mantém o índice nome→URL em `artist-urls.properties`. O perfil e a tela inicial consultam o mesmo cache; buscas simultâneas do mesmo nome compartilham a resolução e o download.
- Quando o índice e a imagem ainda não existem, o app busca o artista mais próximo em `searchArtists`, redimensiona o pedido do avatar para cerca de 400 px e persiste o resultado. A resposta da rota informa `Cache-Control: public, max-age=31536000, immutable` ao WebView.
- A seção de artistas da tela inicial e o topo do perfil usam a mesma rota. A busca de músicas do perfil continua paralela à resolução da foto.
- Medição antes/depois: antes não havia tempos por etapa nem cache persistente de foto de artista, portanto não há baseline numérico confiável. Foram adicionados logs para `NewPipe.searchArtists_ms`, espera e consulta ao índice/disco, busca, download, rota `/cover/artist:*` e `Bridge.artist`; eles registram valores em ms e distinguem cache de rede. Não foi coletado tempo de execução depois da mudança porque a tarefa proíbe instalar no celular.
- Verificado: `node --check` em `prototype/native.js`, `prototype/home-native.js`, `prototype/pages.js` e `prototype/search.js`; compilação `JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=5`; `git diff --check`.
