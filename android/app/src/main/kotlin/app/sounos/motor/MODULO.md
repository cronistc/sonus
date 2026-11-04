# Motor de entendimento — versão 0.1
Controle interno.

## O que é
Aprende o uso de quem ouve, **só no celular** (nada sai do aparelho), e usa isso nas **Novidades** e na **busca**.

- Cada música ouvida vira uma *escuta*: tempo ouvido e como terminou — `completa`, `pulou`, `voltou`, `trocou`,
  `saiu` (fechou o app no meio; descoberto na abertura seguinte).
- Somas por música, artista e gênero (tocou, completas, puladas, tempo ouvido) e duração das músicas ouvidas até o fim.
- Gênero: Deezer (gênero do álbum), uma consulta por música; "sem gênero" tenta de novo depois de 7 dias.
- **Afinidade com artistas** (0..1): o que o motor aprendeu + o histórico de reprodução que já existia.
- **Gosto por gênero** (0..1): somas por gênero.
- **Novidades**: lançamentos dos seguidos e preferidos + lançamentos do país; destes, ~70% das vagas vão para os que
  combinam com o gosto (artista conhecido, gênero ouvido — gênero do lançamento via Deezer) e ~30% são sorteados,
  para conhecer coisa nova. Quanto mais a pessoa segue/ouve, mais pessoal.
- **Busca de artistas**: quem a pessoa já ouve vem antes (ver `prototype/modulos/busca/MODULO.md`).

- **Diário** (`Diario.kt`, pedido do dono em 04/11/2026: "o app guarda todo tipo de dado do usuário, mas não manda para
  lugar nenhum"): cada acontecimento vira uma linha em `files/motor/diario/AAAA-MM.jsonl`, com hora e dia da semana —
  escutas (com aleatório, repetir, saída de som, rede, volume, se estava baixada, pausas, barra arrastada, letra aberta,
  de onde veio), tocar/pausar/continuar/avançar/voltar/posição, aleatório, repetir, fila, baixar, busca + o que foi
  escolhido (posição), artista/álbum/playlist/letra abertos, playlists (criou, apagou, renomeou, pôs/tirou música),
  seguir/deixar de seguir, compartilhar, abrir/fechar o app (duração da sessão). Até 200 MB (anos), depois sai o mês
  mais antigo. As escutas de antes (escutas.jsonl) foram recontadas uma vez (`importarAntigas`) → `0000-antes.jsonl`.
- **Comportamentos** (`comportamentos()`, ponte `getPerfil`): horas e dias preferidos, taxa de completas e de pulos,
  sessão média, duração preferida, músicas do momento (14 dias), esquecidas (5+ vezes, nada há 30 dias), rejeitadas
  (puladas antes de 30 s 2+ vezes, nunca até o fim), favoritas de verdade (5+ vezes até o fim, sem pulo), artistas em
  alta. Por enquanto só descrevem: nada no app usa ainda (regras novas, só combinando com o dono).
- **Seu Mix** (`mix`, pedido do dono em 27/10/2026): mais ouvidas + novidades bem próximas + inéditas de artistas
  conhecidos + 1–2 descobertas do mesmo estilo (detalhes em `docs/ARVORE.md`, fluxos).

## Arquivos
| Arquivo | Papel |
|---|---|
| `Motor.kt` | tudo do motor |
| `Diario.kt` | o diário (linhas por mês, leitura dos últimos dias) |
| ganchos | `PlayerController.kt` (`fimPendente`, `trocando`, `comecou`, `marcar`, `retomar`), `Bridge.kt` (`novidades`, `searchArtists`) |
| dados no celular | `files/motor/diario/*.jsonl`, `escutas.jsonl`, `perfil.json`, `atual.json` |

## Antes de mudar
A definição do motor é do dono: não acrescentar regra de recomendação sem combinar.

## Histórico
- **0.1** — Sunshine Beta 1.1 (em preparação, ainda não lançada): criado — escutas, somas por música/artista/gênero,
  afinidade e gosto pesando nas Novidades e na busca de artistas.
  Diário (tudo o que a pessoa faz, só no celular), horários/dias/sessões no perfil, comportamentos (`getPerfil`),
  escutas antigas recontadas uma vez (04/11).
