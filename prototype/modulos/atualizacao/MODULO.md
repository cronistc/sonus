# Módulo Atualização — versão 1.0
Controle interno.

## O que é
A tela de atualização (versão nova, novidades da versão) e a clave do topo que brilha quando há versão nova.
O app procura `update.json` no ramo "updates" do GitHub e se atualiza sozinho.

## Arquivos
| Arquivo | Papel |
|---|---|
| `update.js` | tela de atualização e novidades (lê `../../changelog.json`) |
| `clave-atualizacao.js` | brilho da clave quando há atualização |
| nativo | `android/.../update/Updater.kt`; publicação: `tools/publicar.sh` (só com decisão do dono) |

## Histórico
- **1.0** (26/10/2026) — organizado em módulo, sem mudança.
