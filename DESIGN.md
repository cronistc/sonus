# Projeto: Player de música estilo Spotify com NewPipe Extractor

> Nome provisório: **Sonus**. Documento de design v0.1 (2026-10-19)

## 1. Visão geral

App de música com streaming de áudio vindo do YouTube / YouTube Music, SoundCloud e Bandcamp
através do **NewPipe Extractor**. Não usa API oficial nem login. A diferença em relação a um
cliente comum é a **camada de personalização**: busca, autocomplete, "Para você" e rádio
infinita são reordenados com base no histórico e nos gostos do usuário. Tudo roda no próprio
aparelho (on-device), sem servidor e sem telemetria.

### Plataforma recomendada
| Camada | Escolha | Motivo |
|---|---|---|
| Linguagem | Kotlin | O NewPipe Extractor é Java/JVM, então a integração é nativa |
| UI | **Feita do zero** (sem kits nem templates prontos) | Decisão do projeto |
| Player | **Feito do zero**: download/buffer próprio → `MediaCodec` (decodificação) → `AudioTrack` (saída PCM) | Decisão do projeto: controle total de buffer, gapless, crossfade e EQ |
| HTTP | OkHttp (implementa o `Downloader` do NewPipe) | Padrão, com cache e cookies |
| Banco | Room (SQLite) + FTS4 | Histórico, biblioteca, perfil de gosto e busca local |
| Injeção de dependências | Hilt | |
| Assíncrono | Coroutines + Flow | |
| Imagens | Coil | |

> ⚠️ **Licença:** o NewPipe Extractor é **GPLv3**, então o app também precisa ser GPLv3.
> Ele não será aceito na Play Store; a distribuição seria por F-Droid ou APK.

---

## 2. Arquitetura

```
┌────────────────────────── UI (Compose) ──────────────────────────┐
│ Home │ Busca │ Biblioteca │ Player (mini + tela cheia) │ Fila      │
└───────────────┬──────────────────────────────────────────────────┘
                │ ViewModels (StateFlow)
┌───────────────▼──────────── Domínio / Use cases ─────────────────┐
│ SearchUseCase  RecommendUseCase  PlayUseCase  LibraryUseCase      │
│        │               │                                          │
│  ┌─────▼──────── Motor de personalização ──────────┐              │
│  │ TasteProfile  Ranker  QueryExpander  Dedup  Radio │             │
│  └──────────────────────┬───────────────────────────┘             │
└─────────────┬───────────┴──────────────┬─────────────────────────┘
              │                          │
┌─────────────▼──────────┐  ┌────────────▼───────────────┐
│ Repositório remoto      │  │ Repositório local (Room)   │
│ NewPipeSource           │  │ tracks, plays, searches,    │
│ (YouTube/SC/Bandcamp)   │  │ likes, playlists, afinidades│
│ + StreamUrlCache (TTL)  │  └────────────────────────────┘
└─────────────┬──────────┘
              │
┌─────────────▼─────────── Playback (Media3) ──────────────────────┐
│ MusicService (MediaSessionService) ← ResolvingDataSource          │
│ resolve a URL de áudio só na hora de tocar, emite PlaybackEvents  │
└──────────────────────────────────────────────────────────────────┘
```

### Módulos Gradle
```
:app                 → navegação, DI, tema
:core:model          → data classes puras (Track, Artist, Album...)
:core:database       → Room, DAOs, migrações
:core:extractor      → wrapper do NewPipe Extractor + Downloader OkHttp
:core:player         → MusicService, fila, resolver de stream
:core:personalization→ TasteProfile, Ranker, sinais (Kotlin puro, testável)
:feature:home | :feature:search | :feature:library | :feature:player
```

---

## 3. Modelo de dados (Room)

```kotlin
@Entity data class TrackEntity(
    @PrimaryKey val id: String,          // "yt:dQw4w9WgXcQ", "sc:123", "bc:..."
    val url: String,
    val title: String,
    val normTitle: String,                // título limpo (sem "Official Video", "Lyrics"...)
    val artistId: String?, val artistName: String,
    val album: String?, val durationSec: Int,
    val thumbnailUrl: String?,
    val service: Int,                     // ServiceList id
    val firstSeenAt: Long
)

@Entity data class ArtistEntity(@PrimaryKey val id: String, val name: String,
    val channelUrl: String?, val thumbnailUrl: String?)

// Cada reprodução: é a fonte principal de sinal implícito
@Entity data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String, val startedAt: Long,
    val listenedMs: Long, val durationMs: Long,
    val endReason: EndReason,             // COMPLETED, SKIPPED, USER_STOP, ERROR
    val source: PlaySource                // SEARCH, RADIO, PLAYLIST, HOME, LIBRARY
)

@Entity data class SearchEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String, val normQuery: String, val at: Long,
    val clickedTrackId: String?,          // em qual resultado clicou (null = nenhum)
    val clickedPosition: Int?
)

@Entity data class LikeEntity(@PrimaryKey val trackId: String, val at: Long)
@Entity data class DislikeEntity(@PrimaryKey val key: String, val at: Long) // track ou artista ("não recomendar")
@Entity data class PlaylistEntity(...) ; @Entity data class PlaylistTrackCrossRef(...)

// Perfil materializado, recalculado de forma incremental
@Entity data class ArtistAffinity(@PrimaryKey val artistKey: String,
    val score: Double, val updatedAt: Long)
@Entity data class TermAffinity(@PrimaryKey val term: String,     // tokens/gêneros ("lofi", "sertanejo")
    val score: Double, val updatedAt: Long)

// FTS para busca local instantânea (histórico + biblioteca)
@Fts4(contentEntity = TrackEntity::class) data class TrackFts(val title: String, val artistName: String)
```

---

## 4. Camada de extração (NewPipe)

```kotlin
interface MusicSource {
    suspend fun search(query: String, filter: SearchFilter, page: Page? = null): SearchPage
    suspend fun suggestions(prefix: String): List<String>
    suspend fun trackDetails(url: String): TrackDetails          // metadados + relacionados
    suspend fun resolveAudio(url: String): ResolvedAudio          // URL do stream + mime + bitrate
    suspend fun related(url: String): List<Track>                 // StreamInfo.relatedItems
    suspend fun mix(videoId: String): List<Track>                 // playlist "RD<id>" do YouTube
    suspend fun artist(channelUrl: String): ArtistPage
    suspend fun playlist(url: String, page: Page? = null): PlaylistPage
    suspend fun trending(): List<Track>                           // kiosk de música
}

enum class SearchFilter(val ytFilter: String) {
    SONGS("music_songs"), VIDEOS("music_videos"), ALBUMS("music_albums"),
    PLAYLISTS("music_playlists"), ARTISTS("music_artists"), ALL("all")
}
```

Pontos de implementação:
- `NewPipe.init(OkHttpDownloader, Localization("pt","BR"), ContentCountry("BR"))` no `Application`.
- Busca: `service.searchQHFactory.fromQuery(q, listOf(filter.ytFilter), "")` →
  `SearchInfo.getInfo(...)`; para paginar, `SearchInfo.getMoreItems(service, handler, nextPage)`.
  Aproveitar `searchSuggestion` / `isCorrectedSearch` ("Você quis dizer…").
- Áudio: em `StreamInfo.getInfo(url).audioStreams`, escolher o melhor stream conforme a
  preferência (Opus/WebM > M4A, ou bitrate limitado em dados móveis).
- **URLs de stream expiram (~6h)**: nunca guardar no banco, usar `StreamUrlCache`
  (em memória, TTL de 5h) e resolver no `ResolvingDataSource` do ExoPlayer.
- Tudo roda em `Dispatchers.IO`, com retry/backoff e erro tipado (`ExtractionError`,
  `ContentUnavailable`, `AgeRestricted`, `ReCaptcha`).
- Pré-resolver a próxima faixa da fila para a transição ficar sem pausa.

---

## 5. Motor de personalização ⭐

### 5.1 Sinais implícitos → pesos
| Evento | Peso para artista/termos da faixa |
|---|---|
| Ouviu ≥ 80% (COMPLETED) | **+1.0** |
| Ouviu 30s–80% | +0.4 |
| Pulou < 30s | **−0.6** |
| Curtiu (like) | **+3.0** |
| Adicionou a playlist | +2.0 |
| Repetiu (tocou de novo em < 24h) | +1.5 |
| Clicou no resultado de uma busca | +0.5 |
| "Não recomendar" | bloqueio forte (−∞ / filtro) |

### 5.2 Afinidade com decaimento temporal
```
affinity(artista) = Σ peso_e · exp(−(agora − t_e) / τ)        τ ≈ 30 dias
```
Os gostos de hoje pesam mais que os de seis meses atrás. Atualização incremental: ao
registrar um evento, `score = score · exp(−Δt/τ) + peso`, sem reprocessar o histórico.

A **afinidade por termos** funciona da mesma forma, sobre tokens do título e da busca
(depois de remover stopwords), e captura gêneros e vibes ("lofi", "acústico", "funk", "remix").

### 5.3 Reranking da busca
O NewPipe devolve os resultados na ordem do YouTube. Esses resultados são reordenados localmente:

```
score(r) = w1 · posBase(r)            // 1/(1+posição): respeita a relevância original
         + w2 · textMatch(q, r)       // similaridade de tokens entre a busca e o título/artista
         + w3 · norm(affinity(artista))
         + w4 · termAffinity(r)
         + w5 · clickHistory(q, r)    // o usuário já clicou nesse item numa busca parecida?
         + w6 · inLibrary(r)          // curtida ou em playlist
         − penalidades(r)
```
**Penalidades:** duração > 12 min (exceto quando a busca pede "mix"/"full album"), live,
"reaction", "karaoke", "8D", "sped up" (a menos que a busca contenha o termo), artista ou faixa bloqueados.

**Pesos iniciais:** w1=1.0, w2=0.8, w3=0.6, w4=0.3, w5=1.2, w6=0.5. Ficam em um `RankerConfig`
para ajustar depois. Mais adiante dá para aprender os pesos a partir de cliques por posição.

**Deduplicação:** agrupar por `(normArtist, normTitle)`. O `normTitle` remove parênteses e
colchetes com "official", "video", "audio", "lyrics", "legendado", "HD"... Cada grupo mantém a
melhor versão (preferindo "Topic"/Art Track > áudio oficial > clipe), e as outras ficam em "outras versões".

**Busca híbrida:** em paralelo, consulta FTS local (biblioteca + histórico) e remoto (NewPipe).
Os resultados locais aparecem na hora; os remotos chegam depois e são mesclados.

### 5.4 Autocomplete inteligente
```
sugestões = merge(
   histórico local com prefixo igual (peso por frequência × recência),
   sugestões do serviço (service.suggestionExtractor),
   artistas com alta afinidade que casam com o prefixo
) → deduplica → top 8
```

### 5.5 Recomendações ("Para você")
Prateleiras da Home:
1. **Continue ouvindo**: últimas faixas e playlists.
2. **Mix do [Artista Top N]**: playlist RD/Mix do YouTube a partir de uma faixa do artista.
3. **Porque você ouviu X**: `related(X)` reordenado pelo Ranker.
4. **Descobertas**: candidatos de artistas que você **ainda não ouviu**, vindos de relacionados de
   relacionados (2 saltos), filtrados por termAffinity. Exploração ε ≈ 15%.
5. **Em alta no Brasil**: kiosk de trending.

Geração de candidatos → ranking → diversificação (no máximo 2 faixas seguidas do mesmo
artista, estilo MMR) → cache diário no Room, para a Home abrir instantânea e funcionar offline.

### 5.6 Rádio / autoplay infinito
Quando a fila acaba: pega a faixa atual, busca `related` + `mix`, remove o que tocou nas
últimas ~50 reproduções e os bloqueados, reordena pela afinidade, adiciona 10 faixas.
Pulos durante a rádio ajustam a sessão em tempo real: um *session boost* que aumenta ou reduz
os termos e artistas da sessão atual, além do perfil de longo prazo.

### 5.7 Cold start (onboarding)
Na primeira abertura: escolher gêneros e 3+ artistas (busca de artistas via NewPipe), o que
gera afinidades iniciais (+5). Opcional: importar uma playlist pública do YouTube ou SoundCloud por URL.

---

## 6. Funções / funcionalidades

### MVP (v0.1)
- [ ] Busca com abas (Músicas, Artistas, Álbuns, Playlists) + autocomplete
- [ ] Tocar em segundo plano, com notificação e tela de bloqueio
- [ ] Mini-player + player em tela cheia (seek, shuffle, repeat)
- [ ] Fila (reordenar, remover, "tocar a seguir")
- [ ] Curtir, histórico, playlists locais
- [ ] Registro de sinais + reranking da busca (5.1–5.3)

### v0.2
- [ ] Home personalizada (5.5) + rádio infinita (5.6)
- [ ] Página de artista (top músicas, álbuns) e de álbum/playlist
- [ ] Onboarding (5.7), importar playlist por URL
- [ ] Deduplicação de versões

### v0.3+
- [ ] Download offline (escolha de qualidade)
- [ ] Letras (LRCLIB API, sincronizadas)
- [ ] Sleep timer, equalizador, normalização de volume, crossfade
- [ ] Backup/restauração (JSON) e exportar o perfil de gosto
- [ ] Android Auto, widget
- [ ] Tela "Seu gosto" (transparência: top artistas e termos, com opção de editar ou zerar)

---

## 7. Use cases principais (assinaturas)

```kotlin
class SearchUseCase(source: MusicSource, local: LocalRepo, ranker: Ranker) {
    fun search(query: String, filter: SearchFilter): Flow<SearchResultState> // local primeiro, depois remoto
    suspend fun loadMore(): List<RankedTrack>
    suspend fun onResultClicked(query: String, track: Track, position: Int)
}
class SuggestUseCase { suspend fun suggest(prefix: String): List<Suggestion> }
class RecommendUseCase { suspend fun homeShelves(forceRefresh: Boolean = false): List<Shelf>
                         suspend fun radioBatch(seed: Track, exclude: Set<String>): List<Track> }
class PlaybackTracker {       // escuta o Player.Listener do Media3
    fun onTrackStart(track: Track, source: PlaySource)
    fun onTrackEnd(listenedMs: Long, durationMs: Long, reason: EndReason)   // → SignalProcessor
}
class SignalProcessor(dao: AffinityDao) { suspend fun apply(signal: Signal) }   // atualiza afinidades (5.2)
class Ranker(config: RankerConfig, profile: TasteProfile) {
    fun rank(query: String?, candidates: List<Track>, ctx: RankContext): List<RankedTrack>
}
object TitleNormalizer { fun normalize(title: String, artist: String): NormalizedTitle }
```

---

## 8. Riscos e mitigação
| Risco | Mitigação |
|---|---|
| O YouTube muda e o extractor quebra | Isolar em `:core:extractor`, atualizar a dependência com frequência (JitPack/tag), fallback para outros serviços |
| ReCaptcha / bloqueio de IP | Detectar `ReCaptchaException` e avisar o usuário, rate-limit nas buscas |
| Metadados ruins (o artista é o canal) | Preferir resultados "Topic"/music_songs, que trazem o artista correto; o normalizer separa "Artista - Música" |
| Licença GPLv3 / ToS | Código aberto, distribuição fora da Play Store, sem anúncios |
| Bateria e dados | Pré-resolver só 1 faixa à frente, cache de imagens, modo de economia de dados |

---

## 9. Próximos passos
1. Confirmar as decisões em aberto (abaixo)
2. Criar o esqueleto do projeto Gradle com os módulos
3. `:core:extractor` + teste de busca e resolução de áudio (prova de conceito)
4. Player Media3 tocando uma URL resolvida
5. Room + PlaybackTracker + Ranker com testes unitários

### Decisões em aberto
- ~~Plataforma~~ → **Android** (confirmado)
- Serviços: só YouTube/YouTube Music no MVP, ou já incluir SoundCloud e Bandcamp?
- Nome e identidade visual

---

## 10. Requisitos prioritários (definidos em 2026-10-19)

Plataforma confirmada: **Android**. Interface e player feitos do zero.

| # | Requisito | Prioridade |
|---|---|---|
| R1 | A música começa a tocar **quase instantaneamente** | Crítica |
| R2 | Capas e metadados em cache de longa duração; **as capas nunca podem sumir** | Crítica |
| R3 | Playlists locais (criar, editar, reordenar) | Alta, logo depois do player |
| R4 | Downloads **privados**: só aparecem dentro do app, não no celular | Alta |
| R5 | Perfis + playlists compartilhadas | Depois (já previsto no modelo de dados) |

### 10.1 R1: Início instantâneo

Meta: **< 300 ms** quando a faixa foi pré-carregada e **< 1,5 s** quando não foi.

Começar a tocar exige três etapas lentas, e o app adianta cada uma antes do play:

1. **Resolver a URL do áudio** (a chamada mais lenta, ~0,5–1,5 s)
   - `StreamResolveCache`: guarda a URL resolvida até o `expire=` que vem na própria URL, menos uma margem de 10 min.
   - Resolve com antecedência a próxima faixa da fila, os 3 primeiros resultados de cada busca e
     a faixa em que o dedo **encostou** (resolve no *press down*, antes de o toque terminar, o que ganha ~100–200 ms).
2. **Baixar os primeiros bytes**
   - `HeadCache` em disco: para as faixas prováveis, baixa os primeiros **~384 KB** (≈ 15–20 s de áudio)
     com HTTP `Range: bytes=0-393215`.
   - Ao dar play, o decodificador lê do HeadCache **enquanto** o restante é baixado em paralelo a partir
     do byte 393216. Não há espera.
   - O HeadCache é um LRU de ~150 MB.
3. **Conexão e decodificação**
   - O cliente HTTP mantém conexão **HTTP/2 já aberta** (keep-alive) com `*.googlevideo.com`, e o DNS é resolvido antecipadamente.
   - O `MediaCodec` é instanciado na abertura do app e reaproveitado.
   - O buffer mínimo para começar a tocar é de **~250 ms** de PCM. Depois disso o buffer cresce até ~30 s.

O formato preferido é **Opus/WebM (itag 251)**, com **AAC/M4A (itag 140)** como alternativa.
Faixas que já tocaram inteiras vão para um `StreamCache` LRU (~500 MB, configurável), e repetir é instantâneo.

Para medir: logar `tempo_ate_primeiro_som` em cada play (só local) e exibir numa tela de debug.

### 10.2 R2: Capas que nunca somem

Hoje as capas costumam sumir por três motivos: URL expirada, cache apagado pelo sistema e
metadado sobrescrito por vazio. Cada um tem sua solução:

**Armazenamento em duas camadas**
| Camada | Local | Remoção |
|---|---|---|
| **Fixada** (pinned) | `filesDir/covers/` (armazenamento interno, que o Android **não limpa sozinho**) | **Nunca** enquanto a faixa estiver na biblioteca, numa playlist, no histórico recente ou em downloads |
| **Navegação** | `cacheDir/covers/` | LRU de ~300 MB; o sistema pode limpar, mas basta baixar de novo |

- A capa é **promovida** para a camada fixada assim que a faixa é tocada, curtida, entra numa playlist ou é baixada.
- O arquivo é nomeado pelo `trackId` (`yt_dQw4w9WgXcQ.webp`), e não pela URL. Assim, se a URL mudar, o arquivo continua valendo.
- Cada capa fica salva em 2 tamanhos: 544px (player) e 120px (listas). O app também guarda a
  **cor dominante** no banco.

**Lista de URLs alternativas**: se uma URL falhar, tenta a próxima:
```
1. URL original salva
2. https://i.ytimg.com/vi/{id}/maxresdefault.jpg   ← gerada a partir do ID, nunca expira
3. https://i.ytimg.com/vi/{id}/hqdefault.jpg
4. capa do álbum ou do artista
5. placeholder gerado (gradiente na cor dominante + iniciais)
```

**Metadados**
- Ficam no Room **sem expiração**. Atualização em segundo plano no esquema *stale-while-revalidate*:
  o app mostra o que já tem e atualiza depois.
- **Regra de merge: nunca trocar um valor bom por vazio ou nulo.** Se a atualização vier sem capa ou sem título, o antigo é mantido.

Carregador de imagens próprio: cache em memória (LRU de ~1/8 da RAM do app) → disco fixado →
disco de navegação → rede → alternativas.

### 10.3 R3: Playlists locais

```kotlin
@Entity data class PlaylistEntity(
    @PrimaryKey val id: String,          // UUID: já pronto para sincronizar no futuro (R5)
    val name: String, val description: String?,
    val coverMode: CoverMode,            // AUTO_MOSAIC (4 capas) | CUSTOM | FIRST_TRACK
    val customCoverPath: String?,
    val ownerId: String?,                // null = local; preenchido quando existirem perfis
    val remoteId: String?, val version: Long,
    val createdAt: Long, val updatedAt: Long, val deletedAt: Long? // soft delete, para sincronizar
)
@Entity(primaryKeys = ["playlistId", "entryId"])
data class PlaylistEntry(
    val playlistId: String, val entryId: String,   // UUID: permite a mesma música 2x
    val trackId: String,
    val position: Double,                // posição fracionária: reordenar = atualizar 1 linha
    val addedAt: Long, val addedBy: String?
)
```
Funções: criar, renomear, descrição, capa (mosaico automático ou imagem da galeria), adicionar
e remover, reordenar com arrastar, duplicar, "baixar playlist inteira", buscar dentro da playlist e ordenar.

**Compartilhar antes de existirem perfis:** um link ou código com a lista de IDs compactada
(`sounos://p/<base64-zstd>`). Quem recebe importa uma cópia. Isso funciona sem servidor.

### 10.4 R4: Downloads privados

- O áudio fica em **`context.filesDir/downloads/`** (armazenamento interno do app):
  - não aparece na galeria, no app de música nem no gerenciador de arquivos;
  - outros apps não acessam;
  - é apagado se o app for desinstalado (o app avisa o usuário disso).
- **Opcional, recomendado:** criptografar com AES-GCM e uma chave guardada no Android Keystore. Assim, mesmo extraído do
  aparelho, o arquivo não toca fora do app. O player descriptografa durante o streaming.
- Também baixa junto **capa (camada fixada), metadados e letra**, para funcionar 100% offline.
- **Gerenciador de downloads próprio**, rodando num foreground service:
  - fila com prioridade, 2 downloads paralelos;
  - **retoma** de onde parou (HTTP `Range`);
  - se a URL expirar no meio, resolve de novo e continua;
  - verifica o tamanho final (`Content-Length`);
  - opção "baixar só no Wi-Fi";
  - mostra o espaço usado.
- O player sempre procura nesta ordem: **download → StreamCache → HeadCache → rede**.

```kotlin
@Entity data class DownloadEntity(
    @PrimaryKey val trackId: String,
    val state: DownloadState,            // QUEUED, RUNNING, PAUSED, DONE, FAILED
    val filePath: String?, val bytesDone: Long, val bytesTotal: Long,
    val format: String, val bitrate: Int, val encrypted: Boolean,
    val requestedAt: Long, val completedAt: Long?
)
```

### 10.5 R5: Perfis e playlists compartilhadas (futuro)
Já previsto no modelo de dados: UUIDs, `ownerId`, `version`, `updatedAt` e soft delete. O app funciona
**local-first** e sincroniza depois. O backend fica para decidir mais tarde (servidor próprio ou Supabase).

### 10.6 Ordem de construção revisada
1. `:core:extractor` + `StreamResolveCache` (prova de conceito: buscar, resolver, baixar o início)
2. **Player do zero**: rede/cache de bytes → demuxer WebM/MP4 → `MediaCodec` → `AudioTrack`, com HeadCache (R1)
3. Room + carregador de imagens próprio com as duas camadas (R2)
4. Interface base (busca, mini-player, tela do player)
5. Playlists locais (R3)
6. Gerenciador de downloads privados (R4)
7. Personalização (seção 5)
8. Perfis e compartilhamento (R5)

---

## 11. Decisões de identidade e estado atual (2026-10-20)

- **Nome:** Sonus (provisório — "Sonos" é marca registrada de caixas de som, evitar).
- **Ícone do celular:** a letra **G** (a nota Sol) em branco sobre azul (`prototype/logo.svg`).
- **Dentro do app:** no topo aparece só a letra G, sem o quadrado azul.
- **Temas:** Gradiente (padrão — o fundo acompanha as cores da capa tocando; sem música, azul), Escuro e Claro.
  Azul da marca (`#2f6bff`) nos destaques.
- **Protótipo de interface:** `prototype/` (home, player, busca, "+", configurações, transições).
- **Extração (NewPipe Extractor v0.26.5):** `android/core/extractor` (Kotlin/JVM) — busca, sugestões, resolução de áudio
  (Opus 160 kbps), `StreamResolveCache`, `HeadCache` (começo do áudio), `CoverStore` (capas 1200×1200, fixadas/navegação).
  Teste real: `cd android && ./gradlew :tools:smoke:run --args="sua busca"`.
- **Configurações só com escolhas reais:** tema, qualidade do áudio, volume equilibrado, transição entre músicas,
  armazenamento (músicas baixadas, cache com "Limpar", baixar só no Wi-Fi), país e idioma.
  Ficam **sempre ligados, sem opção:** início instantâneo, letra sincronizada, capas na qualidade máxima, capas guardadas.
- **Fila sempre pronta** (`QueuePrefetcher`): a próxima música fica inteira em disco antes de a atual acabar;
  as 2 seguintes ficam com endereço resolvido + começo do áudio + capa.
