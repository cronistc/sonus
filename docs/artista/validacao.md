# Validação da página do artista

Dispositivo: Samsung Galaxy A54, Android 15. APK `Sunshine Beta 1.0`, `versionCode=10`.

## Catálogo e cache

O cache local foi testado após fechar e reabrir o app. Na reabertura de ANAVITÓRIA, o primeiro catálogo local mostrou `105 músicas`, 20 linhas principais e zero placeholders; conteúdo estabilizado apareceu em **143 ms** (cache completo do WebView local/nativo) e, na visita seguinte com cache atualizado, em **72 ms** (55 ms até os dados, 5 músicas ouvidas e zero placeholders). **Ver todas** mostrou 100 faixas do catálogo e 5 faixas ouvidas, total de 105; o cache guardou 104 itens do catálogo.

Djavan abriu em **73 ms** com cache, 20 linhas e 5 músicas ouvidas. Após atualizar o catálogo nativo, **Ver todas** mostrou 99 músicas do catálogo e 5 ouvidas, total de 104; o catálogo oficial retornou 103 faixas únicas, com uma faixa histórica adicional.

Comparação com o catálogo oficial do YouTube Music. Os números de origem vêm das respostas `Top songs` paginadas e das abas de álbuns/singles do próprio YouTube Music; a contagem final remove títulos normalizados duplicados quando as durações também são próximas. As páginas não exibem um total agregado de faixas, então o comparativo abaixo usa as faixas únicas obtidas dessas seções.

| Artista / página oficial | Top songs retornadas | Faixas de álbuns e singles | Total único no catálogo | Total exibido no Sonus |
| --- | ---: | ---: | ---: | ---: |
| [Djavan](https://music.youtube.com/channel/UCpHj6Odxfl5ufOKZ8CmLX0A) | 100 | 15 referências / 15 faixas | 103 | 104 |
| [Tribalistas](https://music.youtube.com/channel/UCOTV5IMHu8f2buB1mFGWeBg) | 55 | 13 referências | 49 | 49 |
| [ANAVITÓRIA](https://music.youtube.com/channel/UCDARE-4OotSQ2uHFla-zNvA) | 100 | 39 faixas | 104 | 105 |

ANAVITÓRIA mostra uma faixa adicional no Sonus porque uma música ouvida no aparelho não aparece no catálogo atual do canal; a deduplicação por título e duração mantém esse histórico sem duplicar as faixas do YouTube Music.

## Seguir e rádio

Na página de ANAVITÓRIA, o botão alternou imediatamente entre `Seguir` e `Seguindo`. Ao deixar de seguir, Djavan passou para a primeira posição em “Seus artistas”; ao seguir de novo, ANAVITÓRIA voltou para a primeira. O JSON nativo de `sounos.following` coincidiu com o localStorage. Após forçar o fechamento e reabrir o app, os dois artistas seguidos continuaram na lista e ANAVITÓRIA permaneceu primeiro.

Com o rádio de Tribalistas aberto e ANAVITÓRIA e Djavan seguidos, as 12 sugestões tiveram IDs únicos. “Lisboa” (ANAVITÓRIA) apareceu na posição 5 e “Um Amor Puro” (Djavan) na posição 10, uma faixa seguida a cada cinco sugestões quando disponível.

## Toque e retorno

No toque pressionado de `⋮` em uma faixa de Djavan, o WebView reportou o botão ativo e a linha ativa; a cor calculada da linha ficou igual ao fundo claro (`rgb(244, 244, 246)`), enquanto o botão recebeu o realce arredondado (`rgba(0, 0, 0, 0.04)`). A captura [track-menu-pressed.png](track-menu-pressed.png) mostra o quadrado no botão sem acender a linha.

Na busca por Djavan havia 20 linhas de música, 7 artistas e 12 álbuns. Depois de abrir o artista e voltar pelo botão Android, a busca permaneceu aberta com a mesma consulta, 20 músicas, 7 artistas, 12 álbuns e `scrollTop=419,91`; foram observadas transições de saída e entrada em `transform`. Abrindo “Djavan (Ao Vivo)” e voltando pela seta, a busca e a rolagem `419,91` também foram preservadas. Abrindo Tribalistas pelo artista no player e voltando pela seta, a página do player continuou aberta (`player-open`) e a saída da página também transitou em `transform`.

## Capturas

- [Página de Djavan](artista-djavan.png)
- [Página seguida de ANAVITÓRIA](artista-seguindo-anavitoria.png)
- [Tema claro](tema-claro.png)
- [Tema escuro](tema-escuro.png)
- [Tema gradiente](tema-gradiente.png)

Pixels centrais das barras nas capturas: claro `#f4f4f6` (status e navegação), escuro `#0e0e10` (ambas), gradiente `#201a11` (status, acompanha o topo) e `#261e1b` (navegação, acompanha a base). Os ícones foram claros nos dois temas escuros e escuros no claro.

## Build

Build limpo com `JAVA_HOME=~/Android/jdk17 ./gradlew clean assembleDebug --no-build-cache -PversionCode=10`. A opção `--no-build-cache` evita reutilizar classes Kotlin antigas de outra árvore de trabalho. A configuração do WebView também ignora o cache de assets locais, que sobrevive à atualização do APK. O APK exibiu `versionName=0.1.1.0` e `versionCode=10`; abriu em estado frio em 1,56 s. A compilação limpa e os testes do extrator passaram.
