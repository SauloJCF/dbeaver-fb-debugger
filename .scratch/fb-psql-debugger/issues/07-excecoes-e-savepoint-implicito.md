# 07 — Exceções, `WHEN` e savepoint implícito

Status: ready-for-agent
Blocked by: 04

ADR: 0001

## O que construir

- `EXCEPTION nome`, `EXCEPTION nome 'mensagem'`, `EXCEPTION nome USING (...)`, re-raise com `EXCEPTION` sem argumento dentro de handler.
- `WHEN EXCEPTION nome | GDSCODE x | SQLCODE n | SQLSTATE 'x' | ANY DO`.
- Erro do servidor (em Fatia DML ou Avaliação de expressão) mapeado para `GDSCODE` / `SQLCODE` / `SQLSTATE`, disponíveis também como variáveis de contexto no handler.
- Bloco com `WHEN` emula o savepoint implícito: `SAVEPOINT` ao entrar, `ROLLBACK TO SAVEPOINT` antes do handler, `RELEASE SAVEPOINT` ao sair normalmente.

## Critérios de aceite

- [ ] Teste diferencial: bloco faz `INSERT`, depois falha, handler roda — o `INSERT` do bloco foi desfeito, igual ao nativo.
- [ ] Exceção não tratada termina a Rotina com o mesmo tipo/códigos/mensagem do nativo.
- [ ] Handlers aninhados e re-raise cobertos.
