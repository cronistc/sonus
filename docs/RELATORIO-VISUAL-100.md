# Relatório da auditoria visual 100% — 28 e 29/10/2026

Designer de produto: Claude (dupla com o programador). Aparelho: Galaxy A54. Build final: 0.1.1.1 / versionCode 12 (debug), instalado e conferido com a música pausada.
Capturas: `docs/capturas/auditoria-100/antes/` e `.../depois/` (nome = item do checklist; sufixo -gradient / -dark / -light). Checklist com o visto de cada item: `docs/AUDITORIA-100/CHECKLIST.md`. Pedidos e registros: `docs/AUDITORIA-100/PEDIDOS.md`. Ferramentas novas em `tools/auditoria/` (icones.js mede o alfa real de cada ícone via DevTools; tres.sh tira o mesmo estado nos 3 temas; modelos.sh percorre cartões; seguro.sh só toca se o Sonus estiver na frente).

## 1. O problema dos ícones translúcidos (correção global)
Causa: os ícones usavam `currentColor` herdando `--text-dim` / `--t-mid` (rgba .55–.72 no Gradiente e no Claro) ou tinham `opacity`. A tela do artista tinha sido corrigida só em `.compart`.
Correção: dois tokens SÓLIDOS em `theme.css` nos 3 temas: `--icon` (#f5f5f7 no Escuro/Gradiente, #111114 no Claro) e `--icon-dim` (#d6d6dc / #34343c), mais `--outline` (contorno sólido). Trocados: "+" do mini player e do player (A/baixar/+), ⋮ das linhas, ações das páginas (baixar, capa, editar, compartilhar, ≡, ✓), ícones da busca (lupa, ×, ↖, relógio), seta das novidades, sol/lua/gota dos temas, "+" de Nova playlist e do círculo "Seguir artistas", anel de marcação da folha Adicionar, trilha do anel de download, chave desligada, contorno de Seguir/Ver mais/Nova playlist. Widgets/XML (programador): "A", logo e nota musical sem alfa; play do widget #4A1E6E → azul da marca.
Prova: `tools/auditoria/icones.js` (alfa de stroke/fill × opacidade dos ancestrais). Antes: 25+ ícones visíveis com alfa < 1 só na home (ex.: "+" do mini .72, Nova playlist .72, ⋮ .72). Depois: **0 ícones translúcidos visíveis em home e player, nos 3 temas** (também com busca, páginas, menus, Configurações abertos). Exceções por design (estado, não ícone): clave que pulsa quando há versão nova, botão desabilitado (.35–.4), capas já tocadas na fila (.45), trilha do anel enquanto o arco gira.

## 2. Tela por tela (antes → depois)
| Tela | Achado → correção |
|---|---|
| Abertura a frio (G/E/C, vídeo + quadros) | splash e palavra→clave→topo conferidos nos 3 temas; barras do sistema casam com o tema. Widgets da tela do Android ficam cinza até o app abrir após force-stop (comportamento do Android, programador explicou). |
| Boas-vindas | título a 16 px → 20 px (alinha com o texto); "Ativo" parecia botão "Permitir" → `✓ Ativo` sem fundo (`.pill.ok`); "Adicionado" idem. |
| Início (3 temas, vazio, nome enorme, offline, janelas Novidades/Ouvidos/Mix, selos Seguir) | ícones sólidos; vazio ("Busque uma música…") e offline conferidos; selos "Seguir" legíveis nos 3 temas. |
| Mini player | "+" sólido; some com teclado (busca, nome da playlist, criar playlist — `:has(...:focus)` ampliado). |
| Player (capa, controles, fila, scrub, flash de aleatório) | A/baixar/+ sólidos; scrub conferido em G/E/C; flash do aleatório sólido (programador). |
| Letra / seleção | linhas apagadas .36 → .52 (proposta 2); círculos de seleção com traço sólido 2,4 px; barra "✕ · N · Compartilhar" sólida (.42 → .92) e visto escuro no Claro; "Letra indisponível" nos 3 temas. |
| Busca (recentes, digitando, resultados, nada encontrado, nome enorme) | ícones sólidos; capa 44 → 48 px e artista 12,5 → 13 px (proposta 3); mini sai da frente do teclado. |
| Páginas (artista, álbum, playlist, baixadas, Seu Mix, edição) | seta de voltar: fundo .42 → .62 e 44 px (era 3:1 sobre capa branca); linha 50 → 48 px (+ esqueleto igual); ícones de ação e ✓ sólidos; contorno do Seguir sólido; nome da playlist editável (cursor aparece). |
| Folhas (⋮, Adicionar, Criar playlist) | ícones sólidos, anel de marcação sólido, checks por tema, folha só escurece o fundo (bem consistente). |
| Configurações (todas as seções) | ícones de tema, setas, chave desligada sólidos; botão "Confirmar" vermelho 3,9:1 → 5,6:1; "Na tela" para widgets já adicionados (programador). |
| Atualização/novidades (available, downloading, ready, installing, error, uptodate) | conferidas; grupos abrem/fecham; margens ok. |
| Dicas (19 ids) | todas vistas no Gradiente (busca-basica também em E/C); "nova-playlist" cobria o mini → balão agora abre em cima (programador). |
| Toast | texto longo era cortado em 1 linha → até 2 linhas, largura ajustada. |
| Compartilhar (letra 6 modelos × 5 cores, artista, álbum, playlist) | Leque com capa amarela dava texto branco ~1,4:1 → fundo limitado a luminância .42; Ingresso Papel: rótulos escurecidos; botões da folha herdam a fonte do app (estavam em Arial). |
| Widgets Barra/Capa/Completo | vistos na tela inicial do aparelho (depois/10-widgets-tela-inicial); A e controles sólidos. |
| Notificação | sem abrir a gaveta (tem notificações pessoais): `dumpsys notification` do Sonus mostra cor de destaque 0xff2f6bff (azul da marca, aplicada pelo programador), título, ações; ícones brancos sólidos no código. |

Correções globais extras: `user-select:none` no app (segurar numa letra abria "Copiar/Selecionar tudo"; campos e `[contenteditable]` continuam selecionáveis); `button,input{font-family:inherit}`.

## 3. Propostas da auditoria anterior
| # | Estado |
|---|---|
| 1 modo letra com faixa título/play | **RECUSADA pelo dono**; modo letra fica como está. |
| 2 linhas apagadas da letra | aplicada (.52). |
| 3 uniformizar linhas | aplicada: 48 px / 13 px (busca e páginas). Peso 500 já igual. |
| 4 escala tipográfica enxuta, 5 escala de raios, 6 grade de 4 | NÃO aplicadas: tocam ~380 declarações e mudariam o desenho em todas as telas; sugiro tratar numa rodada própria, com o dono aprovando os valores (11/12/13/14/16/18/22/30; raios 8/12/16/24/pílula). |
| 7 offline clave + "Offline" | conferida (offline visto nos 3 temas), aceitável; sem mudança. |
| 8 nomes de artista cortados | mantido o letreiro (marquee) + reticências; 2 linhas fica para o dono decidir. |
| 9 "A" em serif | fechado: definição do dono, não mexi. |
| 10 widgets | programador: play azul da marca, alvos 42 dp. |
| 11 cartão Fita | programador: rótulos escurecidos. |
| 12 histórico com playlist em mosaico | programador (search.js); confirmado no código; o quadrado cinza que vi foi o build antigo. |
| 13 capa preta/branca no Gradiente | programador (SounosCores). |
| 14 notificação com cor | feito (azul da marca). |
| 15 fundos das folhas | decisão: folha de compartilhar é uma cena escura nos 3 temas (cartões sobre véu); as folhas de Adicionar/⋮ seguem o tema. Mantido. |
| 16 "Se..." | fechado: é o nome real da música. |

## 4. O que fiz no celular (e restaurei)
Tudo está registrado em PEDIDOS.md. Tema do app trocado no DOM (e uma vez de verdade em Configurações) — **volta a gradient, conferido**. Não Perturbe ligado para esconder notificações pessoais — **zen_mode voltou a 0, conferido**. Backup antes de mexer (`/root/backup-sonus/`): localStorage e dados do app. Histórico de busca, modelos de cartão e modo de página devolvidos exatamente aos valores do backup (conferido chave a chave; só variam sozinhos os caches mix/novidades/artistas). Transparência: (a) um clique de teste em "Limpar" do cache limpou o cache de verdade (regenerável; biblioteca, baixadas e playlists intactas; por isso algumas capas do Início vieram diferentes); (b) um "voltar" a mais me levou à tela inicial do Android e um toque abriu o Mensagens do Google: nada foi tocado lá e os prints com dados pessoais foram apagados; (c) um toque sem querer no play tocou ~1 s e pausei de novo (música do dono: pausada, posição ~0:30); (d) abri sem querer o seletor de fotos do sistema, saí sem escolher e apaguei o print. Depois disso toda interação passa por `tools/auditoria/seguro.sh`.

## 5. O que sobrou para o dono decidir
1. Escalas de tipografia/raios/espaçamento (propostas 4–6).
2. Nomes de artista cortados: manter letreiro ou permitir 2 linhas (proposta 8).
3. Dica "nova-playlist" e outras: agora abrem em cima quando perto do mini; conferir se gosta.
4. Itens sem print do estado (gestos, estados que exigiriam baixar/criar/alterar dados do dono, share sheet do Android, notificação na gaveta): marcados com "—" e justificativa no checklist.
