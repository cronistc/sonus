# Página do artista — ajustes (Sunshine Beta 1.0)

LEIA docs/registro-0.1.1.0.md e o git log recente (outras tarefas mexeram em pages.js/track-menu.js). Regras do celular:
docs/TAREFA-BOAS-VINDAS.md. Compile limpo `JAVA_HOME=~/Android/jdk17 ./gradlew clean assembleDebug -PversionCode=10`.

1. Linhas de música (página do artista e demais listas com o "⋮" do track-menu.js): ao tocar nos três pontinhos, hoje
   a linha inteira (retângulo) fica destacada. Inverter: o destaque do toque é SÓ um quadradinho de cantos arredondados
   em volta do "⋮" (como era antes), e a linha não acende. Tocar no resto da linha continua tocando a música.
2. SEGUIR ARTISTA (funcional, camada leve): botão "Seguir"/"Seguindo" na página do artista (estilo dos botões
   existentes da página, ao lado de tocar/aleatório). Salvo em localStorage "sounos.following" (lista {name, key,
   thumb, since}) e espelhado como os outros dados (native.js KEYS / saveData) e enviado ao nativo. Efeito: (a) nas
   indicadas/rádio (PlayerController fill/suggestions) entram de vez em quando músicas de artistas seguidos que
   combinem (ex.: ~1 a cada 5, sem repetir, só se houver), (b) artistas seguidos aparecem primeiro em "Seus artistas"
   na tela inicial. Nada quebrado: seguir/deixar de seguir na hora, persiste após reabrir o app.
3. VOLTAR da página do artista aberta pela BUSCA: deve voltar para a busca com os mesmos resultados e rolagem, com a
   animação de saída das páginas (deslizar), nunca direto para a tela inicial e nunca sem animação. Vale também para
   álbum aberto pela busca e artista aberto pelo player. Teste com o botão voltar do Android e com a seta da página.
6. barra de status e barra de navegação do Android devem casar com o TEMA escolhido (escuro,
gradiente, claro) — desde a abertura (splash/tela de abertura que já segue o tema) até o uso normal, e trocar na hora
ao mudar o tema. Hoje ficam brancas (modo claro) mesmo em tema escuro/gradiente. Cor de fundo das barras = fundo do
tema (no gradiente, a cor do topo/fundo da tela), ícones claros em tema escuro/gradiente e escuros no claro
(WindowInsetsControllerCompat / APPEARANCE_LIGHT_*). Ver res/values-night e themes.xml que o dono mexeu. Prints dos 3.
(NÃO mexa na tela de compartilhar letra: o dono vai redesenhá-la.)
Prints em docs/artista/. Commit; `git merge main`; compile limpo; instale; commit.

4. ABRIR A PÁGINA DO ARTISTA SEM ESPERA: hoje demora um pouco e depois aparecem as músicas todas de uma vez. A página
   deve aparecer na hora com o que já se sabe (nome, foto guardada, músicas que a pessoa já ouviu dele, dados em cache
   da última visita) e completar sem pulo (placeholders do mesmo tamanho das linhas, nada de a lista "saltar").
   Meça do toque até a página com conteúdo (< 150 ms com cache; mostre os tempos).
5. TODAS AS MÚSICAS DO ARTISTA: hoje vem só uma parte. Busque a lista completa: aba/playlist "Músicas" do artista no
   YouTube Music (com paginação/continuação), mais as faixas dos álbuns e singles dele, sem duplicatas (mesmo título
   normalizado + duração parecida). Mostre as principais primeiro e um "Ver todas" (ou rolagem que continua carregando,
   como na busca). Compare a contagem com a página do artista no YouTube Music para 3 artistas (ex.: Djavan,
   Tribalistas, Anavitória) e registre.

CONTINUAÇÃO: você já fez quase tudo neste ramo (commit "WIP artista"); parou nos testes finais. Confira com git show HEAD, termine o que faltar, teste TODOS os itens e entregue a tabela.
