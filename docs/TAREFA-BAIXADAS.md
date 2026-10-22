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
