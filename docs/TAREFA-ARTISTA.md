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
Prints em docs/artista/. Commit; `git merge main`; compile limpo; instale; commit.
