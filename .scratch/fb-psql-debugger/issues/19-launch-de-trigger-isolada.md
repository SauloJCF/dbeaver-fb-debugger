# 19 — Launch de Trigger isolada

Status: ready-for-agent
Blocked by: 12, 13

## O que construir

Ação "Debug" numa trigger de tabela. Diálogo de launch com `NEW.*` e `OLD.*` (uma linha por coluna da tabela, tipada) e checkboxes `INSERTING`/`UPDATING`/`DELETING` (desmarcados). Ao fim, mostra os `NEW.*` finais.

## Critérios de aceite

- [ ] Trigger `BEFORE INSERT` que preenche campos mostra os `NEW.*` alterados ao fim.
- [ ] Trigger universal segue o ramo do evento marcado.
- [ ] Breakpoints, Variables e Expressions funcionam como em procedure.
