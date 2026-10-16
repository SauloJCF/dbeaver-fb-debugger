# 04 — Fatias DML

Status: ready-for-agent
Blocked by: 02

## O que construir

- Reconhecer `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `UPDATE OR INSERT` e `SELECT … INTO` singleton como Fatias DML opacas: localizar `:var` (→ `?` com o tipo declarado) e os alvos de `INTO` / `RETURNING … INTO`.
- `EXECUTE STATEMENT` local (sem `ON EXTERNAL`), com `INTO` e parâmetros.
- Manter `ROW_COUNT` a partir do update count; variáveis de contexto (`CURRENT_TRANSACTION`, `CURRENT_USER`, `RDB$GET_CONTEXT`…) resolvidas pelo servidor na mesma conexão.

## Critérios de aceite

- [ ] Testes diferenciais comparam estado das tabelas antes do rollback para cada tipo de DML.
- [ ] `SELECT … INTO` sem linha deixa as variáveis como no motor; com mais de uma linha, levanta o mesmo erro.
- [ ] `ROW_COUNT` igual ao nativo após cada DML.
- [ ] Texto com `:` dentro de literal ou comentário não vira parâmetro.
