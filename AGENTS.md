# Sonus — guia para agentes de IA (mesmo conteúdo do CLAUDE.md)

Player de música para Android. Interface em HTML/CSS/JS (`prototype/`, roda num WebView) + parte nativa em Kotlin
(`android/`). Comece pelo **[MODULOS.md](MODULOS.md)**: diz onde fica cada parte; cada pasta de módulo tem um
`MODULO.md`. Documentação técnica: [docs/TECNICO.md](docs/TECNICO.md). Versões: [docs/VERSOES.md](docs/VERSOES.md).

## Regras do dono (Guilherme)
- **O visual é o produto.** O desenho de referência é o do Galaxy A54 (interface sempre com 384 px de largura,
  ampliada por inteiro em outros celulares). Nada muda no visual sem o dono pedir.
- **Nada é publicado sem o dono decidir** (`tools/publicar.sh` solta versão para os testadores).
- **Versão pública** = só "Sunshine Beta X.Y". Versões de módulo (Player 1.2…) são controle interno.
- **Nenhuma tela rola na vertical**, exceto o Ver tudo de playlists e listas longas (páginas).
- **Nada com cara de Spotify** nas propostas de tela.
- **Motor de entendimento**: definir com o dono antes de acrescentar regras de recomendação.
- Não mexer em configurações do celular sem autorização (testes no A54 por ADB).
- Chave de assinatura fica fora do repositório (`~/Sonus-chave`); ID do app `app.sounos` não muda.

## Como trabalhar
- Organização: cada parte no seu módulo. Mudou um módulo → atualize o `MODULO.md` dele (versão e histórico) e o
  `MODULOS.md` se a versão mudou.
- Compilar: `cd android && JAVA_HOME=~/Android/jdk17 ./gradlew :app:assembleDebug -PversionCode=<maior que o instalado>`.
- Ver no navegador: `python3 .claude/serve_nocache.py 5173 prototype` e abrir `home.html` (dados de exemplo).
- Testes: `./gradlew :core:extractor:test`; no celular, `tools/estresse/bateria.py <serial>`.
