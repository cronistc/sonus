# Módulo Configurações — versão 1.0
Controle interno.

## O que é
A tela de configurações (engrenagem): tema, reprodução, armazenamento, conteúdo, atualizações, e a tela "Apoie o Sonus".

## Arquivos
| Arquivo | Papel |
|---|---|
| `settings.js` / `settings.css` | configurações |
| `support.js` | "Apoie o Sonus" (lê support.json do ramo updates) |
| tema | as cores dos temas ficam em `../base/theme.css` |

## Histórico
- **1.0** — Sunshine Beta 1.1 (em preparação, ainda não lançada) — organizado em módulo, sem mudança.
  Auditoria visual 28/10: tela de atualização com a mesma margem lateral (16px) dos cartões; botões "pill" com área de toque >= 48px.
  Auditoria 100% (28/10): "Copiar" do Apoie usa a área de transferência do Android.
