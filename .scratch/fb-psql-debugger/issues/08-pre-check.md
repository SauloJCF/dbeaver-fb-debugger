# 08 — Pré-check de construções não suportadas

Status: ready-for-agent
Blocked by: 02

## O que construir

Varredura do AST que devolve a lista de construções não suportadas com linha, coluna e descrição. Inclui no mínimo `WHERE CURRENT OF`, `EXECUTE STATEMENT … ON EXTERNAL`, `IN AUTONOMOUS TRANSACTION`, e qualquer coisa que a gramática reconheça mas o Interpretador ainda não execute. Fonte que não parseia também é recusada, com a posição do erro.

## Critérios de aceite

- [ ] Uma Rotina de exemplo por construção recusada, com a posição correta.
- [ ] Rotina só com construções suportadas devolve lista vazia.
- [ ] A lista de construções recusadas fica documentada num único lugar do código (fonte da seção "Fora do MVP" da spec).
