# Capas e metadados — auditoria e correção definitiva (Sunshine Beta 1.0)

LEIA: docs/registro-0.1.1.0.md, docs/VERSOES.md e o que a tarefa docs/TAREFA-SUNSHINE-1.md já mudou (git log).
Regras do celular: docs/TAREFA-BOAS-VINDAS.md. Compile limpo `JAVA_HOME=~/Android/jdk17 ./gradlew clean assembleDebug
-PversionCode=10`. Visual não muda.

Problemas relatados pelo dono: capas PISCANDO, capas que NÃO carregam, capas em baixa qualidade, e METADADOS que às
vezes "não baixam" (nome/artista/álbum/capa faltando ou errados, inclusive em baixadas e no histórico).

1. Meça antes: rode tools/auditoria/auditar.py (ou crie um script de auditoria via DevTools da WebView) que percorre
   início, busca (5 buscas variadas), páginas de 5 artistas e 5 álbuns, player e fila, playlists e baixadas, e registra
   toda imagem que falhou (naturalWidth 0 / erro), toda imagem trocada mais de 1x em 10 s (piscar), toda capa com
   resolução menor que a área exibida × devicePixelRatio, e todo item sem título/artista/capa. Salve o relatório em
   docs/metadados/antes.md.
2. Corrija as causas na origem (CoverStore, rotas /cover em MainActivity, MetadataRepair, NewPipeSource,
   Library/downloads, native.js coverReady/throttle, home-native/pages/player-native/search): uma única fonte de
   verdade por id; nunca substituir capa boa por pior; trocar a imagem só depois de pré-carregada (sem piscar); fontes
   alternativas quando a primeira falha (thumbnail maior do YouTube, capa do álbum, Deezer já existente no código);
   metadados de downloads/histórico completados e salvos junto (título, artistas, álbum, capa grande guardada).
3. Meça depois com o mesmo script: zero falhas, zero piscadas, zero capas abaixo da resolução, zero itens incompletos
   (ou explique cada caso restante). Relatório em docs/metadados/depois.md.
Commit; `git merge main`; compile limpo; instale; commit.
