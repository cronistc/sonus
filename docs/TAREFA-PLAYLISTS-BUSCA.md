# Tarefa: playlists, página do artista e busca (bugs e ajustes)

Leia docs/TECNICO.md antes. Regras: comentários em português; NÃO mudar o visual existente (o design é do dono —
elementos novos copiam exatamente o estilo dos existentes); não publicar, não fazer push, não mexer em
tools/publicar.sh, na chave (~/Sonus-chave) nem no nome da versão. NÃO instale no celular (o Claude instala).
Compilar: `cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=7` e
`node --check` nos JS alterados. Antes de começar: `git merge main` (pegue o trabalho mais novo).

1. Tela inicial, carrossel de playlists: o cartão "Nova playlist" fica SEMPRE por último (à direita); playlist nova
   entra antes dele.
2. Não existe playlist vazia: "Nova playlist" abre um fluxo (nome, capa opcional, escolher músicas — busca e/ou
   músicas recentes/curtidas); só cria ao CONFIRMAR e com ≥1 música. Voltar/cancelar = nada criado. Mesma coisa no
   "+" que cria playlist a partir de uma música (aí a música já conta). Se existirem playlists vazias salvas por
   versões antigas, não apagar dados — só não mostrar? Não: mostrar normalmente (nunca perder dados do usuário).
3. BUG: a capa escolhida pelo usuário para a playlist não aparece no cartão da tela inicial (aparece na página da
   playlist). Deve aparecer em todo lugar.
4. BUG página do artista: abre mostrando a capa de um álbum e depois troca para a foto do artista. Deve mostrar a
   foto do artista desde o início (use a mesma foto/URL já mostrada no resultado da busca, `/cover/artist:*`,
   passada para a página); enquanto não há foto, fundo neutro sem trocar de imagem. Às vezes o nome do artista não
   aparece: garantir que aparece sempre.
5. BUG busca/histórico: às vezes não salva. Ex.: pesquisa e toca direto numa música → ao voltar à busca, nem a
   música nem o termo/artista estão no histórico. Salvar sempre: termo pesquisado e item tocado/aberto
   (música, artista, álbum), sem duplicar, persistido (localStorage espelhado).
6. BUG navegação: na busca, tocar uma música abre o player; ao descer o player, a pessoa cai na tela inicial.
   Correto: o player só cobre; descer volta para a busca exatamente como estava (texto, resultados, rolagem).
   Só sai da busca quem apertar voltar/fechar. Vale também para página de artista/álbum/playlist.

Escreva no fim deste arquivo o que mudou e o que testou. Commit no ramo (sem push).

## Registro final

### O que mudou

- O cartão “Nova playlist” fica no fim do carrossel; playlists novas aparecem antes dele. Capas escolhidas aparecem também nos cartões e miniaturas de playlists.
- A criação agora é um rascunho com nome, capa opcional, músicas recentes e busca. A música atual já vem selecionada no fluxo do “+”. Só confirma com nome e ao menos uma música; cancelar não grava. Playlists vazias antigas continuam visíveis.
- A página do artista recebe a mesma URL de capa do resultado da busca. Sem foto, começa com fundo neutro; o nome é preenchido desde a abertura.
- A busca grava termo e item aberto/tocado, deduplica entradas e força a gravação no espelho local. O player cobre a busca sem fechá-la, preservando resultados e rolagem; páginas de artista e playlist também registram o item aberto.
- O histórico aceita registros do tipo álbum, mas este ramo ainda não tem busca nem página de álbum para conectar a esse registro.

### O que testei

- `git merge main`: já estava atualizado.
- `node --check` em `add.js`, `home-native.js`, `pages.js` e `search.js`; também verifiquei os três blocos JavaScript inline de `home.html`.
- `git diff --check`.
- `JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=7`: concluído com código 0.
- Não instalei no celular.
