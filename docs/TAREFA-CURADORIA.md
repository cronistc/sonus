# Curadoria completa do Sonus — achar TUDO antes de corrigir (Sunshine Beta 1.0)

NÃO corrija nada nesta tarefa: o produto é o MAPA e a LISTA DE PROBLEMAS. Regras do celular: docs/TAREFA-BOAS-VINDAS.md
(só dentro do app; nunca monkey/svc/settings; nunca apagar dados; nada fora do app; não publique, não baixe mais que 2
músicas e desbaixe depois; não crie playlists reais sem apagar depois). O APK instalado é o que está no celular.

1. INVENTÁRIO (a partir do código prototype/*.js|html e android/): liste TODAS as telas, camadas por cima, botões,
   gestos (toque, toque longo, arrastar, deslizar), menus, chaves das Configurações, estados (online/offline, tocando/
   pausado, temas escuro/gradiente/claro, aleatório/repetir, playlist vazia/cheia, baixadas, primeira abertura,
   atualização disponível) e caminhos de navegação (de onde se chega a cada tela e o voltar de cada uma).
   Salve em docs/curadoria/inventario.md (tabela: área → controle → o que deveria acontecer).
2. EXPLORAÇÃO: escreva tools/curadoria/explorar.py (DevTools da WebView + adb, como tools/estresse/bateria.py) que
   passa por CADA item do inventário, em cada tema relevante, e para cada ação registra: tempo do toque até a primeira
   mudança visual e até estabilizar (MutationObserver/rAF + screencap), se algo mudou, erros JS, imagens quebradas ou
   baixa resolução, texto cortado/estourando, elementos fora da tela ou sobrepostos, animação que trava (quadros
   perdidos), voltar que não volta para o lugar certo, estados que não persistem após reabrir. Faça também
   exploração livre: sequências aleatórias de 300+ ações só dentro do app, misturando áreas.
3. RELATÓRIO docs/curadoria/problemas.md: cada problema com passos para reproduzir, esperado × obtido, tempo medido,
   print (docs/curadoria/prints/), gravidade (bloqueia / feio / lento / detalhe). Considere LENTO qualquer resposta
   visual > 100 ms ou transição que engasga. Ordene por gravidade. No topo, um resumo com números.
Commit; `git merge main`; commit.
