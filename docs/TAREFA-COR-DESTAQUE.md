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
