# 03 — Controle de fluxo

Status: ready-for-agent
Blocked by: 02

## O que construir

Gramática e Interpretador para `IF … THEN … ELSE`, `WHILE … DO`, blocos `BEGIN/END` aninhados, labels, `LEAVE [label]`, `CONTINUE [label]`, `EXIT`. Condições avaliadas no servidor (Modo Careful), incluindo lógica de três valores do `NULL`.

## Critérios de aceite

- [x] Testes diferenciais cobrindo: `IF` com condição `NULL`, `WHILE` com `LEAVE` e `CONTINUE` com e sem label, `EXIT` no meio de laço aninhado.
- [x] Todos passam.

## Comments

### 2026-10-14 — implementação (agente)

**Onde ficou**
- Gramática: tokens `IF`, `THEN`, `ELSE`, `WHILE`, `DO`, `LEAVE`, `BREAK`, `CONTINUE`, `EXIT` no `PsqlLexer.g4`; regras `ifStatement`, `whileStatement` (com `label?`), `leaveStatement`, `continueStatement`, `exitStatement` e `condition` (texto opaco entre os parênteses) no `PsqlParser.g4`.
- AST: `If`, `While`, `Leave`, `Continue`, `Exit` em `psql.ast`. A posição de um `WHILE` com label é a do label.
- Interpretador: cada statement devolve `null` (terminou normal) ou um `Jump` (`LEAVE`/`CONTINUE` com label opcional, ou `EXIT`), que sobe até o laço alvo ou até a Rotina. Sem exceções para controle de fluxo.
- Condição: `SELECT 1 FROM RDB$DATABASE WHERE (<cond>)` (`ExpressionEvaluator.isTrue`). Só `TRUE` devolve linha, então `FALSE` e `NULL` valem como falso pela lógica de três valores do próprio servidor, e o mapeamento de `BOOLEAN` do `DataTypeCompatibility` não entra no caminho.
- Testes: `ControlFlowDifferentialTest` (7 casos: `IF` com condição `NULL`/`NOT NULL`/`ELSE IF`/`IS NOT DISTINCT FROM`/variável `BOOLEAN` nula/`ELSE` pendente, `WHILE` com `LEAVE`/`CONTINUE`/`BREAK` sem label, aninhados sem label, com label, `EXIT` no meio de laço aninhado, palavras-chave como nome) e 3 testes novos no `RoutineParserTest`.

**Prova de que os testes pegam divergência:** troquei a condição por `WHERE ((<cond>) IS NOT FALSE)` (`NULL` vira verdadeiro) e fiz todo `LEAVE`/`CONTINUE` valer para o laço mais interno, ignorando o label. 5 dos 7 testes diferenciais falharam. Depois restaurei o código.

**Comportamento do FB5 conferido no servidor**
- Label só existe em laço: `L1: BEGIN ... END` é recusado. `LEAVE`/`CONTINUE` fora de laço não compilam. Por isso todo `Jump` tem quem o trate.
- `IF`, `DO`, `EXIT`, `LEAVE`, `CONTINUE` são palavras **não reservadas**: o Firebird aceita variável (e label) com esses nomes. `THEN`, `ELSE`, `WHILE` são reservadas. A regra `identifier` e o `AstBuilder.isIdentifier` aceitam as não reservadas (as duas listas precisam andar juntas).
- `BREAK` (sinônimo antigo de `LEAVE`, sem label) ainda compila no FB5 e foi suportado.
- `END;` dentro do corpo (`END; ELSE`, `END; R = ...`) é recusado pelo servidor. A gramática continua tolerante com o `;` depois de `END`.

**Para as próximas issues**
- Issue 05: `FOR SELECT` também aceita label e é alvo de `LEAVE`/`CONTINUE`. Basta tratar o `Jump` do mesmo jeito que o `executeLoop` faz.
- Issue 14: cada condição custa um round-trip. Falta decidir (vendo o IBExpert) se a pausa num `IF`/`WHILE` acontece antes de avaliar a condição e se o `WHILE` pausa de novo a cada iteração.
