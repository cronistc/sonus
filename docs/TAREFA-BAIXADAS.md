# Playlist "Músicas baixadas" + desbaixar (pedido do dono, entra na 0.1.3)

Regras do celular: as de docs/TAREFA-BOAS-VINDAS.md (só dentro do app; JAVA_HOME=~/Android/jdk17; `./gradlew clean`;
-PversionCode=7). NÃO baixe músicas novas no teste além de 1–2 de teste, e apague-as depois (desbaixar).
Use os componentes/estilos que já existem — nada de visual novo.

1. Assim que existir pelo menos 1 música baixada, aparece em "Suas playlists" (tela inicial) uma playlist especial
   **"Músicas baixadas"** (antes de "Nova playlist", que continua sempre por último). Ela não é salva em
   sounos.playlists: é derivada dos downloads (Sounos.call("getDownloads") / getMineTracks / Library.downloads).
   Capa: o mosaico das capas das baixadas (mesmo mosaico das outras playlists). Some quando não houver baixadas.
   Toda música baixada (pelo player, pela página, de qualquer lugar) entra nela automaticamente; atualiza ao vivo
   com o evento "download".
2. Abrir: página de playlist igual às outras (tocar, aleatório, lista). Não pode renomear/trocar capa/excluir a
   playlist. Remover uma música dela (o mesmo gesto/menu de remover das playlists normais) = **apaga o download do
   celular** (Sounos.call("removeDownload", {id})) — sem diálogo extra além do que as playlists já usam.
3. Player: o ícone de baixar, quando a música JÁ está baixada (ícone preenchido), ao tocar **desbaixa** (removeDownload)
   e o ícone volta ao contorno. Durante o download, tocar não faz nada.
4. "Minhas músicas" (tela inicial offline) continua funcionando.
5. Confira: home online mostra "Músicas baixadas" quando há baixadas; remover pela playlist e pelo player apaga o arquivo
   (getDownloads diminui); sem erros JS. Commit; `git merge main`; compile limpo; instale; commit. Prints em docs/baixadas/.

## Implementação e conferência (22/10/2026)

- A home deriva o cartão do resultado `getMineTracks.downloaded`, depois das playlists salvas e antes de “Nova playlist”.
  A página especial usa a mesma página de playlist, com reprodução e aleatório; o lápis só revela o controle existente
  de remoção. Nome, capa e exclusão não são editáveis, e ela não entra em `sounos.playlists`.
- Remover uma faixa da página chama `removeDownload`; o botão preenchido do player também remove, enquanto o download
  está em andamento o toque é ignorado. A tela offline continua usando a lista combinada de downloads e cache.
- `git merge main` integrou `777d7a4` e, depois, `5ff54ff` (animação do download), sem conflito. O resultado manteve
  a animação e a remoção pelo player. Build limpo com
  `JAVA_HOME=~/Android/jdk17 ./gradlew -q clean :app:assembleDebug -PversionCode=7` passou (código 0) após os dois
  merges; instalado e aberto normalmente no Galaxy A54. A home mostrou 6 downloads anteriores do aparelho; preservei
  todos. Baixei duas faixas de teste
  (Tempo Perdido e Sina) e removi cada uma pelo fluxo pedido: playlist e player. Em ambos os casos a contagem voltou
  de 7 para 6; ao remover pelo player, o armazenamento baixado caiu de 30.868.110 para 25.042.576 bytes e o ícone
  voltou ao contorno. Os IDs de teste não aparecem mais em `getDownloads` nem em `getMineTracks`.
- DevTools não registrou exceções JS (`Runtime.exceptionThrown`) nem erros de console JavaScript no fluxo conferido.
  Capturas: `docs/baixadas/01-home.png`, `02-playlist.png`, `03-player-baixada.png`, `04-playlist-remocao.png`,
  `05-player-test2.png`, `06-player-desbaixada.png`, `07-home-final.png` e `08-home-build-final.png`.
- As duas consultas e reproduções de teste ficaram no histórico normal do app; não apaguei nem alterei os registros
  anteriores.
