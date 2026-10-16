# 06 — Procedure selecionável (`SUSPEND`)

Status: ready-for-agent
Blocked by: 05

## O que construir

`SUSPEND` emite uma linha com os valores atuais dos parâmetros de saída. O Interpretador acumula as linhas emitidas como resultado da Sessão de debug.

## Critérios de aceite

- [ ] Teste diferencial compara `SELECT * FROM sp(...)` nativo com as linhas emitidas pelo Interpretador (ordem e valores).
- [ ] `SUSPEND` dentro de `FOR SELECT` e `EXIT` após `SUSPEND` cobertos.
