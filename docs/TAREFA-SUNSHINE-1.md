# Sunshine Beta 1.0 (0.1.1.0) — polimento final (pedido do dono)

LEIA ANTES: docs/registro-0.1.1.0.md e docs/VERSOES.md (o dono fez muitas mudanças no celular; preserve TODAS).
Regras do celular (só dentro do app, nunca monkey/svc/settings; não apagar dados): ver docs/TAREFA-BOAS-VINDAS.md.
Compile SEMPRE limpo: `JAVA_HOME=~/Android/jdk17 ./gradlew clean assembleDebug -PversionCode=10` e instale com
`adb install -r`. Não mude o visual além do pedido. Teste cada item no celular (prints em docs/sunshine-1/).

1. CAPAS: ainda falham às vezes (capa de álbum que não carrega, foto de artista, capa de música) e às vezes aparecem
   em BAIXA qualidade, principalmente numa lista ou ao trocar de música. Ache as causas (rotas /cover, tamanhos
   w120/s400 nas URLs do YouTube, fallback de thumbnail pequena, cache com versão pequena guardada) e garanta:
   sempre a versão de boa resolução (ex.: ≥ 544 px para capas grandes/player, ≥ 226 px em listas), nunca trocar uma
   capa boa por uma pior, e capa de álbum/artista sempre carregando (tente fontes alternativas quando a 1ª falhar).
2. LENTIDÃO ao tocar numa música (na página do álbum e no carrossel de baixo do player): hoje demora para "se ajeitar".
   Meça o tempo do toque até o carrossel/capa/título ficarem na música nova e reduza ao mínimo (resposta visual
   imediata, < 150 ms; o áudio pode vir depois). Sem pulos/rearranjos visíveis do carrossel.
3. ALEATÓRIO: toda vez que for LIGADO, re-embaralha a lista de próximas (refaz a fila com nova ordem/novas músicas, a
   atual continua tocando). Ao DESLIGAR, não muda nada: segue a lista que já está.
4. REPETIR "tudo/álbum" sem álbum (música avulsa/rádio): repete a lista do carrossel — depois da última volta para a
   primeira. Com álbum: repete o álbum.
5. REPETIR FAIXA: só fixa a faixa atual. NÃO recarrega, NÃO esvazia, NÃO esconde e NÃO reorganiza o carrossel (hoje ele
   some e recarrega). Ao desligar o repetir (qualquer modo): a lista não muda. Só o ALEATÓRIO mexe na lista.
   (Isso substitui a regra antiga "repetir uma com álbum → indicadas do álbum": não troque a lista ao mudar o repetir.)
Commit; `git merge main`; compile limpo; instale; commit. No fim, liste o que mudou e os tempos medidos.
