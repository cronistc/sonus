# Sunshine Beta 1.0 — verificação no A54

Build instalado com `JAVA_HOME=~/Android/jdk17 ./gradlew clean assembleDebug -PversionCode=10` e `adb install -r`. Os testes foram feitos dentro do Sonus; os dados do app não foram apagados.

Após o merge com main, o mesmo build limpo passou novamente e o APK foi reinstalado no A54; o app abriu com a sessão pausada, repetir e aleatório desligados.

## Tempos medidos

Os tempos usam `performance.now()` no WebView até o próximo quadro em que título, capa e item selecionado já apontavam para a faixa nova. O tempo de imagem vai até o evento `load` da capa grande.

- Toque numa faixa da página do álbum Tribalistas: **93,6 ms**; capa grande carregada em **120,7 ms**. Fila com 13 faixas, selecionada na posição 2.
- Toque numa faixa do carrossel: **25,6 ms**; capa carregada em **56 ms**. Fila de 13 faixas manteve a ordem.
- Upgrade de capas antigas em cache: música `yt:MaEMsbtmCPU`, **400 → 544 px em 151 ms**; música `yt:9bgyuqM4LcU`, **400 → 544 px em 232 ms**; álbum Tribalistas, **400 → 544 px em 167 ms**.
- Foto de artista testada: **1000 × 1000 px**, carregada em **21,7 ms**.

## Verificações funcionais

- Repetir faixa e repetir tudo mantiveram a fila de álbum intacta (13 itens); repetir tudo também voltou ao início no fim do álbum.
- Repetir faixa repetiu a faixa atual sem reconstruir a fila.
- Aleatório ligado preservou a faixa atual e embaralhou as próximas; desligá-lo manteve a ordem embaralhada.
- Repetir tudo em rádio voltou ao primeiro item da fila.
- Capas de álbum e fotos de artistas carregaram; as respostas de capa em cache foram atualizadas para resoluções maiores sem substituir arquivos melhores por versões menores.

## Capturas

| Arquivo | Verificação |
| --- | --- |
| `01-inicio.png` | Tela inicial e fotos de artistas |
| `02-player-aberto.png` | Player aberto |
| `03-carrossel-tocado.png` | Troca pelo carrossel |
| `04-album.png` | Álbum Tribalistas e capa |
| `05-repetir-faixa.png` | Repetir faixa com fila visível |
| `06-repetir-tudo.png` | Repetir tudo |
| `07-aleatorio.png` | Aleatório ligado |
| `08-album-repetir-faixa.png` | Repetir faixa dentro do álbum |
| `09-toque-album-imediato.png` | Faixa nova no player após toque no álbum |
