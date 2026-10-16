# 14 — Breakpoints de linha, step over e resume

Status: ready-for-agent
Blocked by: 03, 13

## O que construir

- Breakpoint de linha no editor da Rotina no DBeaver (ruler e Ctrl+Shift+B), via `IToggleBreakpointsTarget` adaptado ao editor do objeto.
- Breakpoints **não persistentes**: removidos quando o editor fecha; não sobrevivem a reinício.
- Breakpoint em linha sem statement é movido para o próximo statement ou recusado.
- Suspensão no breakpoint, linha atual destacada no editor, step over (F6), resume (F8), terminate.

## Critérios de aceite

- [ ] Procedure com `IF`/`WHILE` para em cada breakpoint na ordem certa; step over avança statement a statement.
- [ ] Fechar e reabrir o editor: nenhum breakpoint.
- [ ] Reiniciar o DBeaver: nenhum breakpoint na view Breakpoints.
- [ ] Linha destacada bate com o texto do editor (cabeçalho gerado pelo DBeaver considerado — ver issue 09).
