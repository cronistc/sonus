# Tarefa: cor de destaque (barra/anel) com contraste — e nunca cor de pele

Leia docs/TECNICO.md. Não publicar, não push, não mexer em tools/publicar.sh, na chave nem no nome da versão.
Comentários em português. NÃO instale nem mexa no celular (outro programador testa nele). Teste com testes unitários
(JVM) usando imagens reais: baixe 15–20 capas variadas (YouTube Music, i.ytimg/lh3.googleusercontent) para
android/app/src/test/resources ou core, incluindo capas com rostos/pele em destaque e capas de borda azul.
Compilar: `cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q clean :app:assembleDebug -PversionCode=7`.

Hoje: data/CoverColors.kt → accent() (índice [3] das 4 cores) pinta a barra de progresso e o anel da capa.
Problemas relatados pelo dono:
1. Barra azul numa capa com BORDAS azuis → sem contraste, a barra some.
2. Muitas vezes escolhe a COR DE PELE da pessoa da capa.
Fazer:
- Amostrar vários pontos/regiões da capa (grade, ex. 24×24 ou k-means simples em Lab/OKLab) e as BORDAS (faixa
  externa onde a barra/anel ficam encostados) separadamente.
- Escolher a cor candidata mais "chamativa" (saturação/croma alto, luminância adequada para fundo escuro e claro)
  que tenha MAIOR contraste/distância (ΔE em Lab/OKLab) em relação à cor dominante das bordas e ao fundo do player.
- Excluir tons de pele (faixa de matiz ~10°–50° com saturação/luminância típicas de pele; use um classificador
  simples conhecido, ex. regras YCbCr) e cinzas/quase brancos/quase pretos.
- Se nada passar, gerar uma cor de destaque derivada (girar o matiz da dominante ~150–180°, croma alto) com
  contraste garantido.
- Determinístico e rápido (<15 ms numa capa 512px; roda uma vez por música).
- Teste unitário: para cada imagem, imprimir a cor escolhida e checar ΔE mínimo contra as bordas e "não é pele".
Gere uma folha de prova (PNG) com as capas + amostra da cor escolhida em docs/cores-prova.png para o dono ver.
Escreva no fim deste arquivo o que mudou. Commit; `git merge main`, compile, commit.

## O que mudou

- O seletor agora amostra uma grade 32×32 e separa a faixa externa de 14% do restante da capa. A cor da borda é calculada à parte para orientar a escolha do destaque.
- A cor é escolhida em OKLab por croma e distância ΔE em relação à borda e aos fundos escuro e claro do player. Tons de pele passam por regras YCbCr e matiz; cinzas e cores sem contraste suficiente são descartados. Se não houver candidata, o seletor gira a matiz da borda e ajusta luminância e croma até encontrar uma cor contrastante.
- O seletor puro ficou em `core/extractor`, permitindo teste JVM sem Android. Há 20 capas reais quadradas obtidas de resultados de álbuns do YouTube Music em `android/core/extractor/src/test/resources/covers`; a coleção contém retratos e a capa azul de *Blue*, de Joni Mitchell. O README da pasta registra os títulos e URLs de origem.
- O teste imprime a cor e o ΔE de cada imagem, exige ΔE mínimo 18 contra a borda dominante, contraste mínimo 3:1 nos dois temas e reprova tons de pele. Também repete cada seleção para conferir que o resultado é determinístico e gera `docs/cores-prova.png` com as capas e as amostras escolhidas.
- No JDK 17 deste computador, a seleção das 20 capas levou em média 2,0 ms por imagem. Isso mede o seletor JVM, não o aparelho. O app continua usando seu cache por arquivo e não foi instalado no celular.

### Integração final

- `git merge main` integrou as alterações paralelas sem conflitos; a atualização seguinte, `git pull origin main`, respondeu `Already up to date.`.
- Após a integração, `:core:extractor:test --rerun-tasks` passou nas 20 capas e a compilação limpa `JAVA_HOME=~/Android/jdk17 ./gradlew -q clean :app:assembleDebug -PversionCode=7` passou. Nenhum APK foi instalado.
