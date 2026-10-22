# Complemento das dicas (continue no mesmo ramo)
Pedidos do dono depois da tarefa original:
- Balões BEM animados: surgem com animação (ex.: escala + desfoque + leve salto, origem na seta) e somem com animação.
- Cada balão tem um "×" para fechar; também some sozinho depois de alguns segundos (~6–8 s, pausando se a pessoa
  estiver tocando nele).
- Não é "aos poucos ao longo dos dias": é na PRIMEIRA vez que a pessoa abre o app e vai explorando as funções —
  cada dica aparece quando a pessoa chega naquela função pela primeira vez. Depois, só recursos novos de cada versão.
Instale e confira no celular (regras: só dentro do app). Commit; `git merge main`, compile limpo, commit.

## Validação
- `./gradlew clean assembleDebug` passou com JDK 17.
- Instalei no Galaxy A54 e conferi a tela inicial, perfil de artista e resultados de busca dentro do app.
- Removi do histórico a busca `Adele` usada na verificação; a instalação preservou os demais dados.
- `git merge main` ficou atualizado; o ramo já continha o merge `f6719b7` com as alterações de cache.
