# Tarefa: foto do artista rápida, guardada em cache e aparecendo na tela inicial

Leia docs/TECNICO.md. Regras: comentários em português; NÃO mudar o visual (design do dono); não publicar, não
push, não mexer em tools/publicar.sh, na chave nem no nome da versão. NÃO instale no celular.
Compilar: `cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=7` e
`node --check`. Antes: `git merge main`. Outros programadores mexem em search.js (busca) e em prototype/*.html
(desempenho): mudanças pequenas e localizadas nesses arquivos.

Problemas relatados pelo dono:
1. A foto do artista demora muito para carregar (busca, página do artista).
2. Não fica em cache: toda vez carrega de novo.
3. Na tela inicial (onde aparecem artistas) a foto não aparece.

O que fazer:
- Entender o caminho completo: `/cover/artist:*` (MainActivity rota de capas → CoverStore) e de onde vem a URL da
  foto (searchArtists / artist no Bridge, NewPipeSource). Medir o tempo de cada etapa (log).
- Cache em disco permanente para fotos de artista (como as capas fixadas "pin"), chave estável pelo nome
  normalizado do artista, e índice nome→URL da foto guardado (para a tela inicial e a página do artista acharem a
  foto sem buscar de novo). Depois da 1ª vez: abrir instantâneo, sem rede.
- Se não houver URL conhecida, buscar a foto UMA vez (searchArtists com o nome, pegar o melhor match) e guardar.
  Pedidos simultâneos do mesmo artista viram um só.
- Tamanho adequado (~400 px) para não baixar imagem enorme.
- Tela inicial: onde há artista (seção de artistas/recentes), usar a mesma rota e mostrar a foto.
- WebView: cabeçalhos de cache corretos na resposta da rota (Cache-Control longo) para o navegador também guardar.

Escreva no fim deste arquivo o que mudou, o que mediu (antes/depois) e o que testou. Commit no ramo (sem push).
