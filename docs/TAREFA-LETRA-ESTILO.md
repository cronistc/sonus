# Tarefa: letra linha por linha (estilo Spotify) + desfoque nas bordas

Leia docs/TECNICO.md. Não publicar, não push, não mexer em tools/publicar.sh, na chave nem no nome da versão.
Comentários em português. Você (mesmo ramo da tarefa de travadas) já conhece a letra no player.html.
Regras do celular: as mesmas de docs/TAREFA-TRAVADAS.md (só dentro do app; proibido monkey/wifi/sistema).

Pedido do dono:
1. Tirar o efeito de preenchimento progressivo da linha (a cor "vindo" palavra por palavra, estilo Apple Music).
   Voltar para LINHA POR LINHA: a linha atual acende inteira (cor cheia), as outras ficam esmaecidas, como no
   Spotify. Rolagem suave para a linha atual (só transform/opacity; nada de engasgo).
2. Desfoque/esmaecimento nas bordas da área da letra: um "blurzinho" em BAIXO sempre, e em CIMA só depois que a
   letra começou a subir (no início, com a primeira linha no topo, a parte de cima fica SEM desfoque; quando a
   rolagem sai do início, o desfoque de cima aparece suavemente). Fazer com mask-image (gradiente) na área da
   letra, barato para a GPU (não usar backdrop-filter/blur animado em tela cheia).
3. Mantém tipografia atual (leve e grande) e a persistência do modo letra entre músicas.
Instalar e conferir no celular (screenshots no início da música e no meio). Relatório no fim deste arquivo.
Commit; `git merge main`, compile, commit.
