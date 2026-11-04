# Módulo Dicas — versão 1.0
Controle interno.

## O que é
Os balões de dica que aparecem uma vez (por versão) apontando para partes da tela.

## Arquivos
`dicas.js` (lista das dicas, com seletor e versão) e `dicas.css`.

## Histórico
- **1.0** — Sunshine Beta 1.1 (em preparação, ainda não lançada) — organizado em módulo, sem mudança.

## Regra (27/10/2026): uma dica por vez, no momento certo
- `offer(tela)` mostra no máximo UMA dica: a primeira da lista `TIPS` (ordem = importância) que a pessoa ainda não viu e
  cujo alvo está visível. As outras daquela tela ficam para as próximas vezes que ela abrir a tela.
- Espera ~0,9 s depois de a tela abrir; depois de uma dica, nenhuma outra por 30 s (nada em sequência).
- Telas com gancho: home, offline, mini, search, search-results, artist, artist-albums, album, playlist, player,
  suggestions, lyrics.
- Toda versão nova traz as dicas das ferramentas novas, com `version` = versionCode da versão (quem atualiza vê só
  essas; quem instala agora vê todas, aos poucos).

## Regra (27/10/2026): uma dica por vez, no momento certo
- `offer(tela)` mostra no máximo UMA dica: a primeira da lista `TIPS` (ordem = importância) ainda não vista e com o
  alvo visível. As outras daquela tela ficam para as próximas vezes que a pessoa abrir a tela.
- Espera ~0,9 s depois de a tela abrir; depois de uma dica, nenhuma outra por 30 s (nada em sequência).
- Telas com gancho: home, offline, mini, search, search-results, artist, artist-albums, album, playlist, player,
  suggestions, lyrics.
- Toda versão nova traz as dicas das ferramentas novas, com `version` = versionCode da versão.
  Auditoria 100% (28/10): balão nunca cobre o mini-player; `SounosTips.previa(id)` mostra o balão sem marcar como vista (só auditoria).
