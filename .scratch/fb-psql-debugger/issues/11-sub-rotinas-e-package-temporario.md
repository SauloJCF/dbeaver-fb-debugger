# 11 — Sub-rotinas internas e Package temporário de debug

Status: ready-for-agent
Blocked by: 10

ADR: 0003

## O que construir

- Gramática para `DECLARE PROCEDURE` / `DECLARE FUNCTION` internas.
- Ao iniciar a sessão, criar o Package temporário `DBG$<id>` com cópias das sub-rotinas; reescrever chamadas a sub-rotinas dentro de expressões e Fatias DML para `DBG$<id>.nome`.
- Chamadas elegíveis a sub-rotinas podem ser interpretadas (step into) como na issue 10.
- Ao encerrar (normal, erro ou abort), remover o package. Ao abrir sessão, remover packages `DBG$%` que sobraram.

## Critérios de aceite

- [ ] Teste diferencial com sub-function usada em expressão condicional (`CASE`, `COALESCE`) — a sub-function só roda quando o motor a rodaria.
- [ ] Após qualquer forma de encerramento, `RDB$PACKAGES` não tem `DBG$%`.
- [ ] Simulação de crash (processo morto com package criado) seguida de nova sessão: resto removido.

## Notas

- O DDL do package roda fora da transação da sessão (DDL precisa de commit para ficar visível); garantir que isso não comita nada da Rotina.
