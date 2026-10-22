# Corrigir 5 problemas achados no teste de usuário (prints em /tmp/claude-1000/haiku/)

NÃO mude o visual além do necessário para corrigir cada item. Teste no celular (regras de docs/TAREFA-BOAS-VINDAS.md:
só dentro do app, compilar com `./gradlew clean`, JAVA_HOME=~/Android/jdk17, -PversionCode=7).

1. Página de artista/álbum (prototype/pages.js): o botão grande ▶ deve virar ⏸ quando a música tocando é dessa página
   (contexto artista/álbum igual) e voltar a ▶ quando pausar ou quando tocar outra coisa. Tocar nele com essa página
   tocando = pausar/continuar (não recomeçar). Atualize pelo evento "state" do Sounos (sem polling).
2. Linhas de música nas páginas (artista/álbum/minhas músicas) ficam SUBLINHADAS depois de tocadas
   (ex.: "Já Sei Namorar" em /tmp/claude-1000/haiku/16.png e 12.png). Ache a causa (foco/:visited/link/classe) e remova
   — nenhuma linha deve ficar sublinhada. Use o mesmo destaque que já existe para a música tocando, se houver.
3. Álbuns duplicados na página do artista: "Tribalistas" aparece 2x com a mesma capa (e capa de outro artista).
   Remova duplicatas (mesmo nome normalizado + mesma capa, ou mesma url) em NewPipeSource/Bridge ou pages.js, e
   descarte álbuns cujo artista não confere com o artista da página (comparação normalizada, sem acento/caixa).
4. Página de artista/álbum: o mini-player cobre as últimas linhas. Dê espaço no fim da rolagem (padding-bottom igual
   à altura do mini-player + margem) quando o mini-player estiver visível — como já é feito na busca (.search-ov .sbody).
5. Ao pular de música (próxima/anterior) enquanto a nova carrega: o botão central do player vira um CÍRCULO BRANCO
   VAZIO (/tmp/claude-1000/haiku/06.png) e o mesmo no mini-player. Enquanto carrega, mostre o ícone de pausa (a
   intenção é tocar) com um indicador de carregamento discreto já existente no app (se houver) — nunca o círculo vazio.
   A capa fica cinza lisa 1–2 s: mostre a capa da música (já vem no item da fila/thumb) imediatamente.
Commit; `git merge main`; compile limpo; instale; commit.
