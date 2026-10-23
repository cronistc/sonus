# Compartilhar letra — imagem bonita e funcional (Sunshine Beta 1.0)

LEIA docs/registro-0.1.1.0.md. Regras do celular: docs/TAREFA-BOAS-VINDAS.md. Compile limpo
`JAVA_HOME=~/Android/jdk17 ./gradlew clean assembleDebug -PversionCode=10`.

Hoje o botão de compartilhar (player, modo letra, #shareBtn) gera uma imagem genérica e salvar/compartilhar não
funciona. Faça:
1. Ao tocar: abre uma folha (sheet) no estilo do app com a PRÉVIA da imagem e as escolhas: linhas da letra (a atual
   selecionada por padrão; tocar para escolher 1–4 linhas seguidas), estilo **Gradiente** (cores da capa, o mesmo
   gradiente do player), **Escuro** e **Claro**. Botões **Salvar** e **Compartilhar**.
2. A imagem (1080×1350, canvas): capa da música no topo (cantos arredondados, sombra suave), título e artistas, as
   linhas da letra grandes na fonte do app, e no rodapé a clave + "Sonus". Cores do texto com bom contraste em cada
   estilo; cores tiradas da capa (state.colors). Nada genérico.
3. **Compartilhar**: abre o compartilhamento do Android com a imagem (FileProvider + Intent.ACTION_SEND image/png).
   **Salvar**: grava em Pictures/Sonus/ (MediaStore) e mostra um aviso curto "Imagem salva". Testar os dois no celular
   (no compartilhar, só abrir a folha do Android e fechar com voltar — não envie para ninguém).
Prints em docs/compartilhar/. Commit; `git merge main`; compile limpo; instale; commit.

EXTRA (rápido, nativo): barra de status e barra de navegação do Android devem casar com o TEMA escolhido (escuro,
gradiente, claro) — desde a abertura (splash/tela de abertura que já segue o tema) até o uso normal, e trocar na hora
ao mudar o tema. Hoje ficam brancas (modo claro) mesmo em tema escuro/gradiente. Cor de fundo das barras = fundo do
tema (no gradiente, a cor do topo/fundo da tela), ícones claros em tema escuro/gradiente e escuros no claro
(WindowInsetsControllerCompat / APPEARANCE_LIGHT_*). Ver res/values-night e themes.xml que o dono mexeu. Prints dos 3.
