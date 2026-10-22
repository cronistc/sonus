# Tarefa: busca de álbuns + página de álbum (Sonus)

Leia docs/TECNICO.md antes. Regras: comentários em português; não mudar o visual existente; não publicar,
não fazer git push, não mexer em tools/publicar.sh nem na chave (~/Sonus-chave). Nome de versão: não mudar.

## O que fazer
1. Nativo (android/core/extractor/.../NewPipeSource.kt): `searchAlbums(query)` usando o filtro de álbuns do
   YouTube Music (SearchFilter.ALBUMS, item `PlaylistInfoItem`): nome, artista (uploader), URL, capas.
   E `album(url)`: `PlaylistInfo.getInfo` → lista de músicas (Track) do álbum, capas do álbum (quadradas).
2. Bridge.kt: comandos assíncronos `searchAlbums {q}` → `{items:[{name, artist, url, thumb}]}` e
   `album {url}` → `{name, artist, thumb, items:[tracks]}` (siga o padrão de "search"/"artist" com `async(a)`).
3. prototype/search.js (modo NATIVE, função nativeResults): pedir `searchAlbums` junto com músicas/artistas e
   mostrar uma seção "Álbuns" (carrossel horizontal, capa quadrada + nome + @artista), no mesmo estilo de
   "Artistas" (reaproveite classes de search.css; se precisar, adicione poucas regras novas).
   Capa via `Sounos.coverUrl({id: "album:"+chave, thumb})` (id válido: letras, números, ":", "_", "-", ".").
4. prototype/pages.js: `openAlbum(url, nome, artista, thumb)` — página igual à do artista/playlist (capa no topo
   tomando tudo, nome, @artista, botões tocar/aleatório, lista de músicas). Tocar → `Sounos.call("playQueue",
   {tracks, index, context:{type:"album", name}})` e `window.openPlayer?.()`.
   Busca: tocar num álbum abre essa página (como `openArtist`, inclusive quando a busca está dentro do player).
4b. Página do ARTISTA (pages.js, openArtist): além das músicas, mostrar uma seção "Álbuns" do artista
   (carrossel de capas quadradas). Fonte: `searchAlbums` com o nome do artista, filtrando só os álbuns
   cujo artista é ele (nome normalizado igual/contido). Tocar num álbum abre openAlbum.
5. Compilar: `cd android && JAVA_HOME=~/Android/jdk17 ./gradlew -q :app:assembleDebug -PversionCode=7`
   e `node --check` nos JS alterados. NÃO instale no celular (o Claude instala).
6. Escreva no fim deste arquivo um resumo do que mudou e do que testou.
