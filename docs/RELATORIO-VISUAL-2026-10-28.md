# Relatório da auditoria visual — 28/10/2026

Designer de produto: Claude. Aparelho: Galaxy A54 (1080x2340, interface de 384 px, 1 px CSS ≈ 1,07 dp). Build auditado: 0.1.1.1 (versionCode 12), tema Gradiente.
Capturas: `docs/capturas/auditoria-2026-10-28/antes/` e `.../depois/`. Tudo foi visto com o app aberto, música pausada; o tema foi trocado para Claro/Escuro só para ver e devolvido a Gradiente; o modo letra do player e o estado "offline" simulado (só o evento de interface, sem mexer na rede) foram devolvidos.

## 1. O que estudei e como usei
- Material 3 / Android: alvo de toque >= 48 dp, escala tipográfica enxuta (11/12/14/16/22/24...), grade de 4/8 dp, raios em poucos degraus, estados (vazio/carregando/erro), movimento curto com easing de saída.
- WCAG 2.1: contraste 4,5:1 para texto pequeno e 3:1 para texto grande/ícones (medi pixels reais dos prints).
- Leis de UX: Fitts (alvo pequeno = erro; aumentei a área sem mexer no desenho), Hick (poucas escolhas por folha), Jakob (esconder mini-player com teclado, como Spotify/YT Music), Proximidade/Gestalt (alinhamento de borda esquerda entre título e carrossel).
- Referências de música: Spotify (letra linha a linha, mini-player some com teclado), Apple Music (capa grande, controles grandes), YT Music (listas densas, menu ⋮ em folha). Regra da casa mantida: "nada com cara de Spotify", logo usei só como lente.
- Definições do dono lidas: CLAUDE.md, AGENTS.md, MODULOS.md, DESIGN.md (parte visual: seção 11), ARVORE.md, TAREFA-AUDITORIA-VISUAL e as TAREFA de abertura, artista, álbuns, baixadas, boas-vindas 1/2, busca-2, compartilhar, cor-destaque, dicas 1/2, letra-estilo, mini-mais, player-topo, texto 1/2, ícone-baixada.
- Método: prints (adb screencap), medição de DOM via DevTools (tamanhos de toque, fontes, textos cortados, overflow), amostragem de contraste nos pixels, leitura do CSS/Kotlin dos widgets.

## 2. Tela por tela (o que vi)
| Tela | Print | Resultado |
|---|---|---|
| Início (Gradiente) | antes/01-inicio, 10-inicio-reaberto | Segue a definição (sem rolagem, Sora nos títulos, Urbanist no resto, hero com janelas que passam). Defeitos: "Ver tudo" com alvo de 15 px de altura; legenda "Músicas baixadas" fraca sobre capa clara. |
| Ver tudo artistas / playlists | 11, 11b, 12 | Conforme MODULO.md (Novidades + Escute novamente; grade 2 colunas). OK. |
| Início offline (evento simulado) | 45 | "Minhas músicas" ok; a clave e o "Offline" ficam separados por um vão grande (proposta 7). |
| Início Claro / Escuro | 39, 41 | Sem quebras; Claro com texto secundário abaixo de 4,5:1 (corrigido). |
| Busca vazia / digitando / resultados | 02, 03, 04, 05, 37 | Mini-player flutuando sobre a lista com teclado aberto e vazando texto; fim da lista escondido sob o mini-player. Corrigidos. |
| Artista (topo e rolado) | 06, 07 | Carrossel de álbuns começava em x32 enquanto o título está em x20. Corrigido. Voltar/Seguindo/compartilhar ok. |
| Álbum | 08 | Título longo anda em letreiro (ok pela TAREFA-TEXTO). Sem capinhas nas linhas (definido). |
| Playlist "Músicas baixadas" | 13 | Ícone ✓ branco cheio nas linhas e no topo, conforme TAREFA-ICONE-BAIXADA. |
| Player (capa, letra, seleção) | 18, 19, 20, 21, 30, 31 | Sem engrenagem/clave/lupa (TAREFA-PLAYER-TOPO ok). Botões A/baixar/+ de 40 px e anterior/próxima de 32 px de altura. Linhas apagadas da letra com 1,7:1 (proposta). |
| Folha "Adicionar à playlist" e menu ⋮ | 29, 35 | Destaque só no quadradinho do ⋮ (TAREFA-ARTISTA ok). Consistentes entre si. |
| Folha de compartilhar e cartões | 20-28 (+depois/05) | Cartões muito bem acabados. Defeito: modelo Clave com rodapé branco sobre fim de degradê claro (~1,5:1). Corrigido. |
| Configurações (todas as seções, incl. Widgets) | 14-17, 38 | Consistente (linhas 58 px, chaves iguais, seções em caixa alta). Botões pill de 34 px sem folga de toque (corrigido). |
| Atualização / novidades | 32, 33 | Cartão com margem 22 px; Configurações usa 16 px. Corrigido. |
| Toast/aviso | 36 (aviso simulado no DOM e removido) | Pílula escura acima do mini-player, ok em Claro e Escuro. |
| Widgets Barra, Capa, Completo (letra e player) | 09 | Ver seção 6. Ainda sem exemplos com letra "desligada" para os três tamanhos ao mesmo tempo. |
| Notificação | sem print | Só existe tocando; a tentativa de abrir a gaveta mostrou notificações pessoais do dono, então apaguei a imagem. Análise só pelo código (MediaStyle, ícone ic_stat_sounos, sem cor de destaque). |
| Abertura animada | docs/abertura (quadros antigos) | Não consegui refazer a captura a frio: `am force-stop` foi bloqueado. Observei a animação de entrada da home após a instalação (depois/01): sem piscar. |
| Boas-vindas / dicas | docs/boas-vindas (antigo), CSS | Só aparecem uma vez e forçar a exibição mexeria nos dados do dono (o espelho nativo restaura a chave). Revisei o CSS: usa as linhas e o botão `.upd-btn` da definição; o balão de dica tem "×" de 28 px (aceitável porque tocar em qualquer lugar fecha). Prints antigos usam a fonte Inter; precisam ser refeitos numa instalação limpa. |

## 3. Divergências encontradas e correções aplicadas (só CSS/JS visual; nenhuma lógica Kotlin)
| # | Problema | Arquivo | Antes -> depois |
|---|---|---|---|
| 1 | Mini-player translúcido no tema Gradiente: texto da lista aparece por baixo (antes/02, 04, 05, 07, 13) | `base/theme.css` `--mini-bg` | fundo com alpha ~0,90 -> ~0,985 |
| 2 | Mini-player ficava sobre a lista com o teclado aberto, cobrindo 1/4 da área útil | `mini-player/mini-player.css` | `.phone:has(.sbar input:focus) .mini { opacity:0; pointer-events:none }` (depois/02) |
| 3 | Fim da lista de resultados escondido sob o mini-player | `busca/search.css` `.sbody` | padding-bottom 40 px -> `--mini-h + 40 px` |
| 4 | Carrossel "Álbuns" da página do artista em x32, título em x20 | `paginas/pages.css` | padding lateral do `.s-hrow` 20 -> 8 px dentro de `.pg` (depois/03) |
| 5 | Texto secundário abaixo de 4,5:1: Gradiente `--text-dim` .62 -> .72 (4,2 -> 5,6:1); Claro `--t-mid` .50 -> .62 (3,1 -> 4,7:1); tempo do player e "Ver tudo" estavam em 3,8-3,9:1 | `base/theme.css` | idem |
| 6 | Legenda "Músicas baixadas / 6 músicas" fraca sobre capa clara | `inicio/inicio.css` `.blk .meta` | degradê 0->.7 sobre 22 px -> 0->.5->.82 sobre 30 px, sombra de texto, subtítulo .75 -> .88 (depois/01 vs antes/01) |
| 7 | Cartão Clave: rodapé branco sobre fim do degradê claro | `compartilhar/cartao.js` | fim do degradê limitado por `escurece(k, .42)` (depois/05) |
| 8 | Alvos de toque pequenos (Fitts): "Ver tudo" 54x15; mini "+" 34x34, anterior/próxima 36x44; player A/baixar/+ 40x40, anterior/próxima 64x32; ⋮ 36x40; "Limpar" e X do histórico; botões pill 34 px | `inicio.css`, `mini-player.css`, `player.css`, `add.css`, `search.css`, `settings.css` | área de toque estendida com `::after`/altura (>= 44-50 px) sem mudar o desenho; medido no DOM depois |
| 9 | Tela de atualização com margem lateral 22 px, Configurações 16 px | `configuracoes/settings.css` `.upd-body` | 22 -> 16 px (depois/07) |

Todos os módulos alterados tiveram uma linha no Histórico do MODULO.md, sem subir versão (Sunshine Beta 1.1 não lançada). Build debug `0.1.1.1/12` compilado e instalado com a música pausada; `docs/cores-prova.png` restaurado.

## 4. Verificado e conforme a definição do dono
Sora nos títulos/Urbanist no resto (fonte.css); letra linha a linha com desfoque embaixo e em cima só após rolar (TAREFA-LETRA-ESTILO); mini-player com "+" no lugar do A (TAREFA-MINI-MAIS); player sem ícones do topo (TAREFA-PLAYER-TOPO); álbuns no meio dos resultados da busca (TAREFA-BUSCA-2); destaque do ⋮ só no quadradinho; "Músicas baixadas" antes de "Nova playlist"; barras de status e navegação casando com o tema; nenhuma tela com rolagem indevida (rolam só Configurações, busca, páginas).

## 5. Propostas NÃO aplicadas (ordem de impacto)
1. **Modo letra esconde título, artista e controles** (antes/18). Sem saber o que toca nem como pausar sem sair do modo. Proposta: faixa fina com título/artista e play/pausa na base, ou manter o mini-controle. Impacto alto; muda a identidade da tela.
2. **Linhas apagadas da letra com 1,7:1** (antes/18). Subir de ~40% para ~55% de opacidade; a linha atual segue cheia. Muda o clima do modo letra.
3. **Uniformizar as linhas de música**: busca usa capa 44 px e artista 12,5 px; páginas usam 50 px e 13 px; a busca de sugestões põe o título da música em negrito e os resultados não. Proposta: 48 px / 13 px / peso 500 em todas (grade de 4 dp).
4. **Escala tipográfica enxuta**: hoje existem 10,5 / 11 / 11,5 / 12 / 12,5 / 13 / 13,5 / 14 / 14,5 / 15 / 16... Proposta: 11, 12, 13, 14, 16, 18, 22, 30 (menos ruído de hierarquia). Textos de 10,3-10,7 px (contagem de músicas, artista na fila) ficam ~11 dp, no limite.
5. **Escala de raios**: 8, 9, 10, 11, 12, 14, 15, 16, 17, 18, 20, 21, 22, 23, 24... Proposta: 8 / 12 / 16 / 24 / pílula. Botões secundários hoje misturam retângulo 14 px ("Seguindo"), pílula 22 px ("Ver mais") e pílula 17 px (Configurações).
6. **Espaçamentos fora da grade de 4**: ~85 de ~380 declarações usam 5, 7, 9, 10, 14, 18, 22, 26 ou 30 px.
7. **Modo offline**: "Offline" fica longe da clave no cabeçalho (antes/45); agrupar clave + "Offline" no centro.
8. **Nomes de artista cortados** em "Seus artistas" (ex. "Grupo Menos É M...", antes/01): permitir 2 linhas ou letreiro como no mini-player.
9. **"A" do botão de letra em Georgia** (player, widgets): é a única fonte fora da família do app e no Android cai em serif genérico. Trocar por Sora 700 ou manter como assinatura, a decidir.
10. **Widgets**: texto em fonte do sistema (RemoteViews não aceita Sora/Urbanist; só renderizando texto em imagem); botões anterior/próximo 38 dp e "A" 36 dp na Barra (abaixo de 48 dp, limitado pelo espaço do 4x1); o play tem tint roxo fixo `#4A1E6E` no XML (o código troca pela cor do fundo, mas o padrão do XML destoa da marca).
11. **Cartão Fita**: rótulos "LADO A" e "MINHA FITA" em bege claro sobre fundo claro (~1,4:1); é desenho do dono, só sugiro escurecer.
12. **Histórico de busca**: playlist aparece com quadrado cinza e letra "S" em vez do mosaico (antes/02).
13. **Capa viva e tema Gradiente com capas pretas/brancas** deixam o fundo marrom-acinzentado. Sugestão: limitar saturação mínima do degradê.
14. **Notificação**: sem cor de destaque (`setColor`) nem estilo colorido; hoje cinza padrão do sistema.
15. **Folhas** têm fundos diferentes (cartão de compartilhar mais escuro que as folhas de adicionar e menu): unificar `--sheet`.
16. Título com "..." literal vindo do dado ("Se...", antes/03): tratar na fonte de metadados (fora do visual).

## 6. Widgets (print antes/09)
Barra (4x1), Capa (2x2) e Completo (4x2) aparecem na tela inicial do aparelho (Completo mostrando letra, os outros mostrando player). Capa, nome, controles e "A" corretos; cantos arredondados iguais; "A" em círculo quando a letra está aberta. Divergências de fonte e alvos estão em 5.9-5.10. Nenhum widget foi alterado nesta rodada.

## 7. O que ficou sem ver
Abertura a frio, boas-vindas, balões de dica e notificação: a captura exige reiniciar o app ou apagar dados do dono, o que não fiz. Vale repetir numa instalação limpa.
