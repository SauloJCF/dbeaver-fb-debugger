# 04 — Fatias DML

Status: ready-for-agent
Blocked by: 02

## O que construir

- Reconhecer `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `UPDATE OR INSERT` e `SELECT … INTO` singleton como Fatias DML opacas: localizar `:var` (→ `?` com o tipo declarado) e os alvos de `INTO` / `RETURNING … INTO`.
- `EXECUTE STATEMENT` local (sem `ON EXTERNAL`), com `INTO` e parâmetros.
- Manter `ROW_COUNT` a partir do update count; variáveis de contexto (`CURRENT_TRANSACTION`, `CURRENT_USER`, `RDB$GET_CONTEXT`…) resolvidas pelo servidor na mesma conexão.

## Critérios de aceite

- [x] Testes diferenciais comparam estado das tabelas antes do rollback para cada tipo de DML.
- [x] `SELECT … INTO` sem linha deixa as variáveis como no motor; com mais de uma linha, levanta o mesmo erro.
- [x] `ROW_COUNT` igual ao nativo após cada DML.
- [x] Texto com `:` dentro de literal ou comentário não vira parâmetro.

## Comments

### 2026-10-16 — implementação (agente)

**Onde ficou**
- Gramática: tokens `SELECT`, `WITH`, `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `EXECUTE`, `STATEMENT`, `INTO`, `IN`. As regras `dmlStatement` e `executeStatement` são texto opaco até o `;`. O `AstBuilder` separa os `:var`, os alvos do `INTO` e as cláusulas recusadas pelo Pré-check (issue 08).
- AST: `DmlStatement` (tipo, texto sem o `INTO`, alvos) e `ExecuteStatement` (texto, parâmetros posicionais ou nomeados com `EXCESS`, alvos).
- Interpretador: `DmlExecutor` executa as Fatias. `BoundSql` troca cada `:var` por `CAST(? AS <tipo>)` e o `ROW_COUNT` das expressões pelo valor que fica no `Frame`. `DynamicSql` troca `:nome` por `?` no texto do `EXECUTE STATEMENT`.
- Os valores lidos para os alvos do `INTO` voltam ao servidor como `CAST(CAST(? AS <tipo do valor>) AS <tipo do alvo>)` (`ExpressionEvaluator.assignable`, `ValueTypes`). Assim a conversão é a do servidor (arredondamento etc.), e não a do Jaybird.
- `Interpreter.execute` roda sob savepoint: se a Rotina falha, o que ela fez é desfeito, como no `EXECUTE PROCEDURE` nativo. `RETURNING … INTO` e `EXECUTE STATEMENT … INTO` também rodam sob savepoint, para que o erro de singleton desfaça o statement inteiro.
- Harness: `DifferentialHarness.tables(...)` compara as linhas das tabelas antes do rollback. Agora também compara a exceção (código ISC, SQLSTATE e mensagem do motor sem as linhas `At procedure ...`). Para conseguir ler as tabelas antes do rollback, o harness usa o `Interpreter` direto, sem a `DebugSession`.
- Testes: `DmlDifferentialTest` (11 casos: um por tipo de DML, `SELECT … INTO` com e sem linha, singleton em `SELECT`/`RETURNING`/`EXECUTE STATEMENT`, chave duplicada, `EXECUTE STATEMENT` posicional/nomeado/sem linha/por variável, `ROW_COUNT`, contexto) e `DmlParserTest` (11 casos, incluindo `:` dentro de literal e comentário).

**Prova de que os testes pegam divergência:** de propósito, tirei o erro de singleton, fiz o `SELECT` sem linha atribuir `NULL`, fixei o `ROW_COUNT` em 1, troquei a contagem do `MERGE` pelo update count e tirei o savepoint da chamada. 6 dos 11 testes diferenciais falharam. Depois restaurei o código.

**Comportamento do FB5 conferido no servidor**
- Em DML, variável sem `:` é recusada ("Column unknown"). Por isso só `:var` é candidato numa Fatia.
- `INTO` só no fim do `SELECT` (no meio dá "Token unknown").
- Sem linha, `SELECT … INTO`, `UPDATE/DELETE … RETURNING … INTO` e `EXECUTE STATEMENT … INTO` deixam os alvos como estavam. Com mais de uma linha, os três levantam `multiple rows in singleton select` (335544652 / 21000).
- `ROW_COUNT` não muda com atribuição nem com `IF`. Também **não muda com `EXECUTE STATEMENT`**, que continua mostrando o valor da DML anterior. `SELECT … INTO` com uma linha dá 1. `MERGE` soma inseridas e atualizadas.
- **Dentro** de uma DML, o `ROW_COUNT` é o contador do próprio statement (0, 1, 2… a cada linha; num `SELECT … INTO` singleton vale 1). Uma Fatia opaca não reproduz isso, por isso o Pré-check recusa (`ROW_COUNT_IN_DML`). Em expressões, até dentro de subquery, ele vale o da DML anterior, e isso é suportado.
- `FIRST :n` não compila (precisa de `FIRST (:n)`), então todo `:var` cai numa posição de valor onde o `CAST(? AS …)` é válido.
- Em PSQL não existe `RETURNING` sem `INTO`.
- O update count do Jaybird para `MERGE` não soma inseridas e atualizadas (deu 1 onde o motor dá 2). O `ROW_COUNT` do `MERGE` sem `INTO` vem de `… RETURNING 1`, contando as linhas.
- `STATEMENT`, `RETURNING`, `AUTONOMOUS`, `PASSWORD`, `ROLE`, `EXCESS`, `COMMON` e `CALLER` não são reservadas. `MERGE`, `WITH`, `IN`, `INTO`, `CURRENT`, `OF`, `ON`, `EXTERNAL`, `USER` e `ROW_COUNT` são.

**Não tratado (casos raros)**
- Parâmetro nomeado do `EXECUTE STATEMENT` sem `EXCESS` e que não aparece no texto: o motor dá erro e o Interpretador aceita.
- Os valores dos parâmetros do `EXECUTE STATEMENT` são convertidos para o tipo do `?` pelo Jaybird, e não pelo servidor.
- Os valores de `TIME` passam por `java.sql.Time` (milissegundos), então frações de 100 µs podem se perder ao reenviar a variável. Isso já valia para as atribuições da issue 02 e não foi testado.
- `:NEW`/`:OLD` (trigger) ainda não são resolvidos (issue 12).
