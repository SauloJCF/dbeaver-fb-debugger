# 05 — `FOR SELECT` e cursores

Status: ready-for-agent
Blocked by: 04

## O que construir

- `FOR SELECT … INTO … DO` e `FOR EXECUTE STATEMENT … INTO … DO`: cursor no servidor na mesma transação, iterado pelo Interpretador.
- `FOR SELECT … AS CURSOR c DO`, `DECLARE … CURSOR FOR`, `OPEN`, `FETCH` (incluindo scroll), `CLOSE`, `ROW_COUNT`/`FOUND` após `FETCH`.
- Fora: `WHERE CURRENT OF` (fica para o Pré-check recusar).

## Critérios de aceite

- [ ] Testes diferenciais com laço que faz DML em outra tabela a cada linha.
- [ ] Laço com `LEAVE` fecha o cursor; nenhum cursor fica aberto ao fim da sessão.
- [ ] Scroll cursor (`FETCH PRIOR/ABSOLUTE/RELATIVE`) igual ao nativo.
