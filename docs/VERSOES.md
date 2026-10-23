# Versões do Sonus

## Número interno (4 números)

Exemplo: `0.1.3.1`

| Posição | Exemplo | Significado |
|---|---|---|
| 1º | `0` | Estágio: `0` = Beta. |
| 2º | `1` | Versão do beta. Só muda com uma grande refatoração ("quase um app 2"). Tem um NOME: `1` = **Sunshine**. |
| 3º | `3` | Muda com um conjunto de novidades, ajustes em massa, correções mais pesadas. |
| 4º | `1` | Patches: mudanças pequenas, correções rápidas, ajustes de interface. |

## O que a pessoa vê

Os números não aparecem como `0.1.1.0`. A pessoa vê o **nome do 2º número** + **Beta** (por causa do 1º ser 0) + **3º.4º** (sempre os dois):

- `0.1.1.0` → **Sunshine Beta 1.0** — primeira versão com nome (22/10/2026). A contagem do 3º/4º foi **reiniciada** aqui:
  as versões antigas `0.1.2` e `0.1.3` vieram antes do esquema de nomes.
- `0.1.1.1` → **Sunshine Beta 1.1** (patch) · `0.1.2.0` → **Sunshine Beta 2.0** (conjunto de recursos/correções)
- 1º número diferente de 0 → sem "Beta" (formato fora da beta ainda não foi definido pelo dono)
- 2º número sem nome cadastrado → mostra o `versionName` cru

## Ordem das versões: sempre pelo versionCode

Como a contagem do nome recomeça a cada versão grande (e os números `0.1.2`/`0.1.3` antigos podem se repetir na
contagem nova), **nada no app pode ordenar versões pelo nome**. Use o `versionCode`, que só sobe:
- atualização: o `Updater` já compara `versionCode`;
- dicas (`prototype/dicas.js`): marcas novas usam o número do `versionCode`; as antigas (`"0.1.2"`, `"0.1.3"`) são
  convertidas pela tabela `LEGACY` (0.1.2 = 5, 0.1.3 = 8);
- `changelog.json`: a chave é o `versionName` exato com 4 números (ex.: `"0.1.1.0"`), então não colide com `"0.1.2"` antigo.

Histórico de versionCode: 0.1 = 1 · 0.1.2 = 5 · 0.1.3 = 8 (publicada) · Sunshine Beta 1.0 (`0.1.1.0`) = 10.

Em Configurações, a palavra "Sunshine" brilha (brilho dourado + reflexo lento) para representar a versão.

## Onde fica no código

- Conversão: `SounosVersao` em `prototype/native.js` (tabela `NOMES`; para nomear uma nova versão grande, basta acrescentar uma linha).
- Usos: `prototype/settings.js` (linha da versão, rodapé e mensagens de atualização) e `prototype/update.js` (tela de atualização).
- Brilho: classe `.sun-glow` em `prototype/settings.css`.

## Pendente

Os nomes de release/tag no GitHub ainda seguem o formato antigo; serão alterados depois para o novo esquema (não agora).
