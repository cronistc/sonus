# Tarefa: telinha rápida de configuração inicial (permissões)

Leia docs/TECNICO.md. Não publicar, não push, não mexer em tools/publicar.sh, na chave nem no nome da versão.
Comentários em português. Regras do celular: só DENTRO do app (proibido monkey/wifi/sistema); pode compilar limpo
(`cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q clean :app:assembleDebug -PversionCode=7`), instalar
(`adb -s <serial> install -r -i app.sounos ...`), abrir/fechar o app, screenshots. Nunca apagar dados.

Pedido do dono: na PRIMEIRA abertura (depois da animação de abertura terminar — veja window.__sonusAberturaFim em
opening.js), uma tela rápida e bonita, no estilo do app (theme.css, fonte do app, animações suaves), com 3 itens,
cada um com um botão/chave que pede a permissão do Android na hora e mostra ✓ quando concedida:
1. Atualizar sozinho — permissão de instalar atualizações (REQUEST_INSTALL_PACKAGES / "Instalar apps
   desconhecidos" para o Sonus; já existe lógica em Configurações → Atualizações / Bridge "allowInstalls").
2. Notificações — POST_NOTIFICATIONS (controles da música na notificação). Hoje o MainActivity pede isso ao abrir:
   mova para esta tela (não pedir duas vezes).
3. Tocar sem interrupções — ignorar otimização de bateria (já existe pedido na 1ª música e linha em Configurações):
   se a pessoa já resolveu aqui, não pedir de novo na 1ª música.
Botão "Começar" (pode pular). Aparece uma vez só (guardar em localStorage espelhado, ex. "sounos.boasVindas").
Quem já usava o app (tem histórico/playlists) também vê uma vez nesta versão se faltar alguma permissão.
Ao voltar das telas do sistema, atualizar o ✓. As dicas (dicas.js) só começam depois que esta tela fecha.
Instale e confira no celular (screenshots). Escreva no fim deste arquivo o que mudou. Commit; `git merge main`,
compile limpo, commit; deixe instalado um build que abre normal.

## Refação visual (21/10/2026)

- A tela agora usa o fundo e as cores de `theme.css`, as linhas, tipografia, separadores e chaves de
  `settings.css`, e o botão primário `.upd-btn`. O título é “Antes de começar”; a ordem ficou
  Notificações, Tocar sem interrupções e Atualizar sozinho.
- As capturas do Galaxy A54 estão em `docs/boas-vindas/`: uma mostra permissões pendentes e outra as três
  permissões ativas. Como o aparelho já tinha concluído a configuração, a exibição foi forçada apenas
  durante a sessão do WebView por DevTools; a captura pendente usou estados simulados em memória, e a
  captura ativa consultou as permissões reais. Não toquei nas linhas nem alterei dados ou permissões.
  Depois, reabri o Sonus normalmente e confirmei a tela inicial.
- O build debug habilita DevTools e ignora o cache de assets para inspeção; o comportamento não se aplica
  ao build de release.
- `git merge main` informou `Already up to date` porque o ramo já continha o merge paralelo
  `dd6ab48`; as alterações de `dicas.js` e `home.html` continuaram presentes. Compilação limpa
  (`clean :app:assembleDebug -PversionCode=7`) concluída com sucesso antes da instalação.
