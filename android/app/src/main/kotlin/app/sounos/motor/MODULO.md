# Motor de entendimento — versão 0.1
Controle interno.

## O que é
Aprende o uso de quem ouve, **só no celular** (nada sai do aparelho). Hoje observa e guarda; o que já usa:
o peso das **Novidades** (artistas preferidos).

- Cada música ouvida vira uma *escuta*: tempo ouvido e como terminou — `completa`, `pulou`, `voltou`, `trocou`,
  `saiu` (fechou o app no meio; descoberto na abertura seguinte).
- Somas por música, artista e gênero (tocou, completas, puladas, tempo ouvido) e duração das músicas ouvidas até o fim.
- Gênero: Deezer (gênero do álbum), uma consulta por música; "sem gênero" tenta de novo depois de 7 dias.
- Novidades: lançamentos do país (genérico) + dos seguidos e preferidos; quanto mais a pessoa segue/ouve, mais pessoal.

## Arquivos
| Arquivo | Papel |
|---|---|
| `Motor.kt` | tudo do motor |
| ganchos | `PlayerController.kt` (`fimPendente`, `trocando`, `comecou`, `marcar`, `retomar`), `Bridge.kt` (`novidades`) |
| dados no celular | `files/motor/escutas.jsonl`, `perfil.json`, `atual.json` |

## Antes de mudar
A definição do motor é do dono: não acrescentar regra de recomendação sem combinar.

## Histórico
- **0.1** (26/10/2026) — criado: escutas, somas, gênero, Novidades.
