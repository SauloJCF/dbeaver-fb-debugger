# 09 — `EXECUTE BLOCK`, functions e rotinas de package como Rotina raiz

Status: ready-for-agent
Blocked by: 03

## O que construir

Carregar Fonte do banco, parâmetros e tipos de:
- function (`RDB$FUNCTIONS`, `RDB$FUNCTION_ARGUMENTS`) — `RETURN` com valor;
- rotinas de package (corpo em `RDB$PACKAGES` / registros por rotina);
- `EXECUTE BLOCK` (texto fornecido, sem metadados; parâmetros declarados no próprio bloco).

## Critérios de aceite

- [ ] Teste diferencial para cada tipo: function chamada nativamente via `SELECT f(...) FROM RDB$DATABASE`, procedure de package via `EXECUTE PROCEDURE pkg.sp`, `EXECUTE BLOCK` executado direto.
- [ ] Linhas e colunas do AST batem com o texto que será exibido no editor do DBeaver para aquele objeto (conferir offset do cabeçalho gerado pelo DBeaver).
