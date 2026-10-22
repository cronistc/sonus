# Refazer o VISUAL da tela de configuração inicial (o dono rejeitou: "cara de IA, nada a ver com o app")

Continue no mesmo ramo (a lógica de permissões está boa; refaça a aparência e a ordem). Mesmas regras do celular de
docs/TAREFA-BOAS-VINDAS.md (só dentro do app, compilar limpo, instalar, screenshots).

ORDEM dos itens (pedido do dono): 1) Notificações 2) Tocar sem interrupções 3) Atualizar sozinho (por último).

O que NÃO pode (foi exatamente o que incomodou): ícones em quadradinhos coloridos, botão com degradê, ícone de nota
musical (♪), slogan/frase de efeito ("Seu som, sem pausas"), selo "SONUS · CONFIGURAÇÃO RÁPIDA", fundo novo
(marrom/degradê próprio), cartões com borda brilhante. Nada de estilo genérico de app de IA.

Como deve ser: parecer uma tela que SEMPRE existiu no Sonus — use os MESMOS componentes e estilos que já existem:
- Fundo, cores e fonte do tema atual (theme.css: --bg, --text, --t-mid, superfícies; respeita escuro/claro/gradiente).
- O ícone do app é a CLAVE DE SOL (prototype/logo.svg / #brandLetter), no mesmo traço/cor do cabeçalho, se precisar.
- Linhas iguais às de Configurações (prototype/settings.css / settings.js: mesma altura, tipografia, separadores,
  e a MESMA chave liga/desliga das configurações). Ícones, se houver, os mesmos ícones de linha fina usados em
  Configurações, na cor do texto.
- Título curto e simples (ex.: "Antes de começar"), texto auxiliar pequeno como os das Configurações.
- Botão "Começar" igual ao botão da tela de atualização (prototype/update.js / .upd-btn) ou o botão primário já
  usado no app — sem degradê.
- Animações: as mesmas transições suaves já usadas nas telas (pages.css / settings.css), nada novo e chamativo.
Instale e salve screenshots em docs/boas-vindas/. Commit; `git merge main`, compile limpo, commit.
