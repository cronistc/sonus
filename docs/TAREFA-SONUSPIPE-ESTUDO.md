# Tarefa: estudar como a equipe do NewPipe conserta quebras do YouTube → guia do "Sonus Pipe"

Não mexa no código do app nem no celular. Só pesquisa e documentação. Não publicar, não push.
Use `curl` (GitHub API sem login: https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/... e páginas raw) e,
se quiser, `git clone --depth 300 https://github.com/TeamNewPipe/NewPipeExtractor /tmp/npe` para ler histórico.

Objetivo: o dono vai manter um fork próprio ("Sonus Pipe") para remendar quebras antes da correção oficial, às vezes
pelo Claude CLI no celular. Precisamos saber COMO eles diagnosticam e corrigem, para não tentar coisas aleatórias.

Levantar (com exemplos reais de commits/PRs/issues dos últimos ~2 anos):
1. Tipos de quebra mais comuns no YouTube e onde ficam no código (arquivos/classes): assinatura/"n" (throttling)
   e o extrator de função JS do player (YoutubeJavaScriptExtractor / deobfuscação, regex de nome da função);
   versões de cliente (WEB, WEB_REMIX, ANDROID, IOS, TVHTML5…) e cabeçalhos; PoToken/BotGuard/"Sign in to confirm
   you're not a bot"; mudanças de JSON de busca/playlist/canal; 403 nos links de áudio; age-restricted.
2. Para cada tipo: sintoma (mensagem de erro/exceção típica), como confirmar, onde mexer, exemplo de PR que corrigiu,
   tempo que levou.
3. Como eles testam (testes com mocks gravados, "mocks" de respostas, como regravar), como rodar só os testes do
   YouTube, e como gerar um build do extractor para usar no app.
4. Onde acompanhar quebras em tempo real (issues abertas, labels, Matrix/IRC), e também o yt-dlp (que costuma
   corrigir primeiro: onde ver os commits do extrator do YouTube e como portar a ideia para Java).
5. Um roteiro de emergência passo a passo: "música não toca / busca quebrou" → diagnóstico em 10 min → correção
   → teste → publicar (no Sonus: tools/publicar.sh).
Escreva tudo em docs/SONUSPIPE-GUIA.md (português, objetivo, com links). Commit no ramo.
