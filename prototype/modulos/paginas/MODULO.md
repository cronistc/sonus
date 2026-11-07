# Módulo Páginas — versão 1.1
Controle interno.

## O que é
As páginas que deslizam por cima da tela inicial: artista (catálogo, seguir), álbum, playlist, Músicas baixadas,
Minhas músicas. Voltar desliza a página para fora (a tela inicial só se redesenha depois da animação).

## Arquivos
| Arquivo | Papel |
|---|---|
| `pages.js` / `pages.css` | as páginas, pilha, voltar, arrastar para fechar |
| nativo | `Bridge.kt` (`artist`, `album`), `YtMusicArtist.kt` |

## Histórico
- **1.0** — páginas existentes.
- **1.1** — Sunshine Beta 1.1 (em preparação, ainda não lançada) — volta animada de verdade; mini player por cima da página aberta a partir da busca.
  Página do artista em teste: foto limpa (nada por cima); nome embaixo da foto com "Seguir" logo abaixo dele (sem "Artista ·
  N músicas"); play à direita;
  sem botão de aleatório (27/10/2026, decisão do dono, vale para TODAS as páginas): o aleatório fica dentro do play —
  arrastar o play para cima/baixo troca o modo DA PÁGINA (`gestoAleatorio`; não é o aleatório geral do player) e o
  botão FICA no modo (`pintaPlay`: aleatório = o play toca a lista inteira embaralhada; play = em ordem; pausa = esta
  página tocando — aí trocar o modo também muda o player). Toda página abre no play; álbum e playlist lembram o
  aleatório só para si por 30 min desde o último uso (`sonus.modoPagina`); artista sempre abre no play. Tocar numa
  música: em ordem, ou (aleatório) ela primeiro e o resto embaralhado.
  Cabeçalho (artista, álbum, playlist): nome numa linha só (letreiro se não couber; o cabeçalho não cresce); embaixo
  Seguir + compartilhar (artista) ou "artista · N músicas" + compartilhar (álbum); play à direita. Compartilhar branco.
  Artista: sem "Você ouviu"; "Músicas" começa com as 5 mais atuais (catálogo do lançamento mais novo ao mais antigo)
  e "Ver mais" abre de 10 em 10; álbuns fixos no fim, maiores.
  Álbum: mesmo desenho (capa limpa; nome, "artista · N músicas"; play à direita); lista sem capinhas.
  Álbum mostra o artista creditado (YouTube Music), não o canal que subiu o vídeo ("DjavanOficial").
  Seta de voltar sempre branca sobre vidro escuro (visível em capa clara ou escura).
  Playlist no mesmo desenho do artista/álbum (capa limpa; nome, descrição e "N músicas · tempo" embaixo; play à direita; baixar/capa/editar numa linha abaixo; músicas com capa). Seu Mix abre como playlist (`openMix`).
  Auditoria visual 28/10: carrossel de álbuns do artista alinhado ao título (x20).
  Auditoria 100% (28/10): artista creditado do álbum sem "&amp;"; erro de rede não apaga a lista já mostrada (cache); capa de playlist com aspas.
  Nome do artista toca e abre a página dele (29/10): nome em todas as linhas de música (playlist, álbum, baixadas, artista) e no cabeçalho do álbum; na página do próprio artista o nome não vira link.
- **Próximo lançamento (em preparação; versão não subiu)** — encostar numa linha de música já começa a buscar o áudio.
