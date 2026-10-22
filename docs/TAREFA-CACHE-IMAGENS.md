# Tarefa: capas e páginas abrem NA HORA (cache de imagens e metadados) + limites de cache

Leia docs/TECNICO.md. Não publicar, não push, não mexer em tools/publicar.sh, na chave nem no nome da versão.
Comentários em português. NÃO mudar o visual. NÃO instale nem mexa no celular (outros estão testando nele);
teste com testes unitários/instrumentados e logs. Compilar: `cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q
:app:assembleDebug -PversionCode=7`; `node --check`.

Relato do dono: coisas que JÁ foram abertas demoram para mostrar as capas de novo (tela inicial, páginas de artista,
álbum, busca). Não pode: o que já foi visto abre instantâneo, sem rede.

1. Investigar e medir o caminho de uma capa já vista: JS monta a URL /cover/<id> → MainActivity.shouldInterceptRequest
   → CoverStore (local / get com runBlocking / upgrade de capa pequena / rede). Suspeitos: `pin` + isSmall forçando
   download de novo; runBlocking na thread da WebView segurando outras requisições; ids diferentes para a mesma capa
   (chave muda entre telas → nunca acha no disco); imagens enormes (2 MB+) decodificadas na tela (servir tamanho de
   tela: miniaturas ~400px em listas, grande só no player); JS que só define a capa depois de pedir dados ao nativo.
   Corrigir para: se existe no disco, responde do disco imediatamente; upgrade de qualidade só em segundo plano
   (próxima vez já vem a melhor).
2. Metadados em cache em disco: páginas de artista, álbum, resultados de busca recentes, sugestões/rádio — abrir de
   novo mostra na hora o que foi salvo e atualiza em segundo plano (stale-while-revalidate), sem piscar.
3. Limites (pedido do dono):
   - Música: 3 GB (era 5 GB), mantém prazo de 3 dias desde a última vez ouvida, LRU.
   - Imagens + metadados: 2 GB juntos, SEM prazo; só sai o usado há mais tempo (LRU) quando passar do limite.
   - Baixadas e capas de baixadas/playlists ("pin") nunca saem.
4. Medir antes/depois (logs com tempos por etapa) e escrever no fim deste arquivo. Commit; `git merge main`,
   compile, commit.
