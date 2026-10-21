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
