# Ícone de música baixada (último ajuste da 0.1.3 — rápido, sem mudar mais nada)

Regras do celular: as de docs/TAREFA-BOAS-VINDAS.md (só dentro do app; JAVA_HOME=~/Android/jdk17; `./gradlew clean`;
-PversionCode=7). Não baixe mais que 1 música de teste e desbaixe depois.

1. Player (prototype/player.html #dlBtn + player-native.js renderDl): quando a música termina de baixar, o ícone vira
   um CÍRCULO BRANCO cheio (cor var(--text), como .dl-fill já faz) com um ✓ escuro dentro NO LUGAR da setinha.
   A seta deve se TRANSFORMAR no ✓ de forma animada (morph do traço: interpole os pontos do path da seta para os do ✓,
   ~400 ms, suave, igual em espírito à abertura em que o nome vira a clave — use os mesmos 3 pontos/segmentos para os
   dois paths e anime o atributo "d" com requestAnimationFrame ou SMIL; só durante a transição, nada rodando depois).
   Ao desbaixar, volta animado do ✓ para a seta e o círculo esvazia. Ao abrir uma música já baixada: já mostra o ✓
   cheio, sem animar.
2. Playlists (prototype/pages.js rowHtml/.dl e pages.css): em cada música baixada, o mesmo ícone pequeno (círculo branco
   cheio com ✓ escuro, ~20 px), no lugar do ícone atual de círculo com ✓ em contorno.
3. Topo das playlists (botão data-a="dl", "Baixar playlist"): use o MESMO desenho do botão de baixar do player
   (círculo + seta; anel de progresso enquanto baixa; quando todas baixadas, círculo branco cheio com ✓) no lugar do
   ícone atual (seta com traço embaixo / ✓ solto).
Confira com prints em docs/icone-baixada/ (player antes/depois, playlist). Commit; `git merge main`; compile limpo;
instale; commit.
