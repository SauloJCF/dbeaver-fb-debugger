# 16 — Expressions (watches)

Status: ready-for-agent
Blocked by: 15

## O que construir

Suporte à view Expressions: cada watch é uma expressão PSQL avaliada no servidor (Modo Careful) com as variáveis do frame selecionado, reavaliada a cada pausa. Expressão inválida mostra o erro do servidor no lugar do valor.

## Critérios de aceite

- [ ] Watch `a * b + 1` acompanha as variáveis a cada step.
- [ ] Watch com erro de sintaxe mostra a mensagem e não interrompe a sessão.
- [ ] Watch não altera estado do banco (avaliação só com `SELECT … FROM RDB$DATABASE`).
