# Registro interno da 0.1.1.0 — "Sunshine Beta 1.0" (não é público)

Estado em 2026-10-22, ao fim da sessão. **Nada commitado nem publicado** — tudo está no working tree de `/root/sonus`
(veja `git status`). Commit e publicação só quando o dono pedir.
**Cópia acessível fora do Termux** (repo completo com `.git` e as mudanças): `/storage/emulated/0/Documents/Sonus-codigo/sonus/`
(no git de lá use `-c safe.directory='*'`; `core.fileMode=false` já configurado). APK de teste em `sonus/apk/`.

## Versão
- Contagem reiniciada: interno **0.1.1.0** = **"Sunshine Beta 1.0"** na tela; **versionCode 10**.
  Quem tem 0.1.2 (code 5) ou 0.1.3 (code 8) recebe normalmente (o Android compara só o versionCode).
- Formato e regras: `docs/VERSOES.md`. Nunca ordenar versões pelo nome; sempre pelo versionCode.
- Build: `cd android && ANDROID_HOME=/root/android-sdk ./gradlew -q :app:assembleDebug -PversionCode=10 -PversionName=0.1.1.0`
  (release: `assembleRelease`, ~10 min). Testes: `./gradlew -q :core:extractor:test` → **30/30**.

## Pendências (em ordem)
1. **Instalar e conferir o último build** (compilado, testes ok, ainda NÃO instalado porque o dono estava ouvindo música):
   - barras de status/navegação mudam junto com o tema (antes ficavam brancas no escuro/gradiente depois de trocar o tema);
   - capa grande do player que ficava no degradê de reserva (ex.: "Vilarejo" — Marisa Monte, `yt:SZA0Hdua9Y0`). Causa: o aviso
     "capa pronta" era um só por música; a pequena gastava o aviso e a grande do player (pedida antes de baixar) nunca atualizava.
     Agora pequena e grande avisam separado (`MainActivity.notifyCoverReady(id, large)` + `native.js` `lastCoverRefresh`).
     Conferir: tocar música nova (sem capa em disco) e ver a capa aparecer no player.
2. **Destaque da linha ao tocar no ⋮**: código pronto (`.tm-on`), não visto no aparelho.
3. **Texto de novidades** (`prototype/changelog.json`, entrada "0.1.1.0") — rascunho, **precisa da aprovação do dono**.
   Ainda falta incluir: menu ⋮ nas listas, volume equilibrado de verdade, foto de artista de outra fonte (Deezer),
   abertura na cor do tema, aviso da clave em balão azul.
4. Commit + publicação (`tools/publicar.sh`, precisa do alias SSH `github-sonus`) — só com ordem do dono.
   Atualizar depois o nome do release no GitHub.
5. Opcional: atualizar `docs/TECNICO.md` com o motor novo (MediaCodec assíncrono + multi-frames) e a correção de metadados.

## Decisões do dono nesta sessão
- Cor da barra do Eminem (rosa `#C274AC`) **aprovada**. Regra: cor da própria capa que mais contrasta; pode ajustar o brilho;
  pode parecer a borda, contanto que dê para ver que é uma barra.
- Balão da clave: nada de caixa escura — o balão é o próprio botão azul "Atualizar agora" com setinha em cima.
- Configurações: tema com ícones (Claro ☀, Escuro ☾, Gradiente gota colorida) aprovado; armazenamento aprovado.
- Linhas "Tocar sem interrupções" e "Atualizar sozinho" só existem enquanto a permissão não foi dada.
- "Sunshine" brilha só na tela de atualização, não nas Configurações.
- Volume equilibrado tem que igualar de verdade (não pode deixar música baixa baixa).
- Buscar metadados de outra fonte quando o YouTube não tem: começou pelas fotos de artista (Deezer). Para capas de música,
  sugestão aceita como ideia: só usar outra fonte quando não houver capa nenhuma (risco de trocar capa certa por errada).

## O que mudou (por área)

### Motor de áudio / player
- `engine/AudioEngine.kt`: MediaCodec **assíncrono** (HandlerThread "sounos-codec") + modo **multiple-frames** (API 35+,
  lote de ~250 ms, por reflexão; compileSdk 34). Seek no padrão ExoPlayer (flush + start postado). `writeAll` espera com
  `lock.wait` (meio buffer). Seek rápido em WebM (`prepareFastSeek`/`SplicedWebmSource`, índice `WebmIndex`).
  Vigia de fim (EOS) só dá `stop()` quando o head nunca andou; `eosReached` impede a posição de voltar.
- `GrowingFileSource.kt`: falha em 12 s se nenhum byte chegou; prazo geral 25 s.
- `PlayerController.kt`: depois de erro, `next(afterError=true)` ignora o repetir-uma (antes travava para sempre).
- `PlaybackService.kt` + novo `player/MediaSessionHolder.kt`: MediaSession do processo; play do fone/carro/relógio funciona
  ao abrir o app. `startForeground` protegido.
- `engine/Loudness.kt`: ganho de **−9 a +18 dB** (antes +6), limitador de pico ligado entre canais (ataque 0,5 ms,
  soltura 150 ms, teto ≈ −0,6 dBFS) + corte suave tanh.
- Exception handlers em `SounosApp`, `Bridge`, `PlayerController`, `StreamResolveCache`, `QueuePrefetcher`.

### Medições (aparelho, Galaxy A54)
| | Antes | Agora |
|---|---|---|
| CPU do app tocando (release, 1 núcleo = 100%) | 24–30% | **6–8%** |
| swcodec | 12–15% | 3% |
| Silêncio real entre músicas (head do AudioTrack) | 215–504 ms | 209–506 ms (igual) |
| Pulo (seek) | 1,2–1,9 s | ~0,5 s |
| Pular para o fim (estresse) | 1/20 | 25/25 |
O `gap.py` (baseado na posição informada) mostra ~1 s no motor novo, mas é só a posição atualizando depois; o silêncio
real foi medido com `tools/medicoes/aud.sh` + `instr.py` (log temporário do head) e é igual ao antigo.
Volume equilibrado ligado (8 faixas, RMS 20–40 s): −12,6 a −17,7 dB (uma −20 por trecho calmo). Antes ficava preso em +6 dB.
Pular silêncio: corta 10–720 ms no começo; corta silêncio ≥ 1 s no fim.

### Metadados (nomes, capas, fotos)
- `TitleNormalizer.kt`: participações separadas ("Justin Bieber e MØ", "Sia, Diplo & Labrinth"); "&" sem vírgula fica
  junto (duplas); bandas com vírgula protegidas (Earth, Wind & Fire…); "com" solto não é participação; música oficial
  (Topic/YouTube Music) com " - " não vira artista; ruído depois de "|" ("Lyric Vídeo"); produtor ("Prod.X") vai para o fim;
  `repairSaved` para dados antigos.
- `NewPipeSource.kt` / `YtMusicRadio.kt`: artistas oficiais separados; `YtMusicRadio.track(id)` = dados atuais de uma música
  (clipe ganha a capa quadrada da versão música).
- `CoverStore.kt`: `artistKey` com ø/æ/ß… e nomes não latinos (hex "u…", antes todos viravam "x"); boneco cinza do
  YouTube Music não conta como foto (`isPlaceholderPhoto`, apagado do índice ao carregar); `recheckAllArtists`; `replace`
  (troca capa só depois de baixar a nova); hosts `*.dzcdn.net` permitidos.
- Novo `DeezerArtist.kt`: foto de artista pelo Deezer quando o YouTube Music não tem (nome exato pela chave; entre homônimos,
  o de mais fãs; rejeita o placeholder do Deezer). Belchior conferido no aparelho.
- `Bridge.kt` página do artista: só músicas do artista por nome/palavras inteiras ("Ana" não pega "Mariana").
- **Correção do que já está salvo** — novo `data/MetadataRepair.kt` (+ `Library.applyFix/rewriteAll/withFixes`,
  `native.js applyFixes`, ponte `trackFixes`): 20 s depois de abrir, regras novas sem internet em histórico/cache/baixadas/
  sessão/playlists/buscas; limpa cache de buscas; marca fotos de artista para conferir; depois, uma música a cada 3 s pela
  internet (YouTube Music), reescreve todas as cópias e troca capa 16:9 por quadrada. Retoma se o app fechar.
  **Para rodar de novo numa versão futura: aumentar `MetadataRepair.VERSION`.** No celular do dono rodou nas 314 músicas.
- Cor de destaque (`AccentColorSelector.kt`): contraste ≥ 3 nos dois fundos; 14 de 20 capas corrigidas.

### Interface
- Letras (font-weight) um passo mais finas em todo o app; `b/strong` 600. Lupa e engrenagem finas (stroke 1.4).
- Nome do artista no player respeita a largura (vira letreiro, não passa por baixo dos botões).
- **Configurações** (`settings.js/.css`): tema com ícones; tema troca por trás do painel (sem piscar ao fechar);
  "Tocar sem interrupções" e "Atualizar sozinho" só se não permitidos (`.set-row[hidden]`); armazenamento do celular inteiro
  (sistema e outros apps / baixadas / cache / livre — ponte `getStorage` com `deviceBytes/freeBytes`); "Remover todas" as
  baixadas (dois toques; ponte `removeAllDownloads`); versão sem brilho.
- "Baixar só no Wi-Fi" nos dados móveis: botão de baixar some do player (ponte `getNetwork` + evento `network`).
- **Tela de atualização** (`update.js`): ícone à esquerda, versão (Sunshine brilhando) + situação + explicação menor à
  direita; novidades em blocos; sem rodapé quando não há botão; segue o tema escolhido mesmo com as Configurações abertas.
- **Clave do topo** (novo `clave-atualizacao.js` + CSS em `home.html`): com versão nova brilha/pulsa (branco; preto no
  tema claro); a cada ≥ 8 h um balão azul "Versão nova: …"; toque → clave vai à esquerda, nome da versão sai dela e
  aparece o balão azul "Atualizar agora"; durante o download clave ↔ nome se alternam. A tela de atualização não abre mais
  sozinha a cada abertura (só "Nesta versão" depois de atualizar).
- **Menu ⋮** (novo `track-menu.js`, CSS em `add.css`): nas linhas de artista, álbum, playlist, baixadas e busca —
  Adicionar à playlist / Baixar ou Remover download / Ir para o artista. Tocar no ⋮ não toca a música; a linha inteira acende.
  Sem "Tocar a seguir" (o player ainda não tem fila editável pela ponte).
- **Abertura na cor do tema**: `res/values` = claro, `res/values-night` = escuro; `SounosApp.applyStartTheme` chama
  `UiModeManager.setApplicationNightMode` ao abrir e ao trocar o tema (Bridge `setSettings`). `splash_sonus.xml` usa
  `@color/sounos_fg` (gerador `tools/abertura/gera.py` atualizado). Primeira abertura após instalar pode vir na cor errada.
- `MainActivity.applyWindowTheme(theme)`: barras do sistema na cor do tema (ao abrir e quando o tema muda).
- Versão: `native.js SounosVersao` (parse/label/html), dicas ordenam por versionCode (`dicas.js`, tabela LEGACY).

## Testes
- Unitários: 30/30. Bateria (`tools/estresse/bateria.py`): **38/38** — rodar com repetir = "todas"
  (`tools/medicoes/tela.py <serial> "Sounos.call('setRepeat',{mode:'all'}),1"`) e devolver "one" no fim. Com repetir-uma,
  "anterior volta à música anterior" falha de propósito (o anterior recomeça a música).
- Scripts de medição salvos em `tools/medicoes/` (tela.py = avalia JS na tela inicial via DevTools; gap/gap2, aud.sh+instr.py,
  volteste.py, fim_musica.py, seek_trace.py, meio.py, estado.py, toca.py, bench.py). Alguns têm caminhos do scratchpad antigo.

## Estado do aparelho
- adb sem fio: `192.168.1.102:43609` (reconectar com `adb-celular`).
- Instalado: debug 0.1.1.0 code 10 **sem** as duas últimas correções (barras e capa grande). APK com elas:
  `android/app/build/outputs/apk/debug/app-debug.apk`.
- Ajustes do dono preservados: tema Claro, volume equilibrado desligado, repetir "uma", qualidade Alta.
- Cuidado ao testar: conferir se o Sonus está na frente antes de qualquer toque (`dumpsys activity activities | grep topResumed`)
  — um toque de teste caiu numa chamada do WhatsApp. Preferir interação por JS (tela.py) a toques.
- Código temporário: nenhum (BENCH-TEMP, HEAD-TEMP, FIM-TEMP, TESTE-TEMP removidos).
