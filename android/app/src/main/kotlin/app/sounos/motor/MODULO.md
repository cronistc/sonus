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

## Arquivos
| Arquivo | Papel |
|---|---|
| `Motor.kt` | tudo do motor |
| ganchos | `PlayerController.kt` (`fimPendente`, `trocando`, `comecou`, `marcar`, `retomar`), `Bridge.kt` (`novidades`, `searchArtists`) |
| dados no celular | `files/motor/escutas.jsonl`, `perfil.json`, `atual.json` |

## Antes de mudar
A definição do motor é do dono: não acrescentar regra de recomendação sem combinar.

## Histórico
- **0.1** — Sunshine Beta 1.1 (em preparação, ainda não lançada): criado — escutas, somas por música/artista/gênero,
  afinidade e gosto pesando nas Novidades e na busca de artistas.
