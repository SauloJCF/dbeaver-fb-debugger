# 08 — Pré-check de construções não suportadas

Status: ready-for-agent
Blocked by: 02

## O que construir

Varredura do AST que devolve a lista de construções não suportadas com linha, coluna e descrição. Inclui no mínimo `WHERE CURRENT OF`, `EXECUTE STATEMENT … ON EXTERNAL`, `IN AUTONOMOUS TRANSACTION`, e qualquer coisa que a gramática reconheça mas o Interpretador ainda não execute. Fonte que não parseia também é recusada, com a posição do erro.

## Critérios de aceite

- [x] Uma Rotina de exemplo por construção recusada, com a posição correta.
- [x] Rotina só com construções suportadas devolve lista vazia.
- [x] A lista de construções recusadas fica documentada num único lugar do código (fonte da seção "Fora do MVP" da spec).

## Comments

### 2026-10-16 — implementação (agente)

**Onde ficou**
- `psql.PreCheck`: `check(texto)` parseia e varre; `scan(ProcedureDefinition)` varre um AST já pronto. Devolve `Finding(posição, descrição)` em ordem de posição. Fonte que não parseia dá um único item, na posição do erro de sintaxe. A mensagem cita o token não reconhecido e onde começa o statement (ex.: `FOR SELECT` dá erro no `SELECT`, "no statement que começa em 'FOR' (linha 5, coluna 3)").
- Lista única das construções recusadas: o enum `psql.ast.UnsupportedConstruct`. Hoje são `WHERE CURRENT OF`, `EXECUTE STATEMENT … ON EXTERNAL`, `… WITH AUTONOMOUS TRANSACTION`, `… AS USER/PASSWORD/ROLE`, `IN AUTONOMOUS TRANSACTION` e `ROW_COUNT` dentro de Fatia DML (achado da issue 04). O javadoc do enum lembra que o que a gramática nem reconhece (`FOR SELECT`, cursores, `EXCEPTION`/`WHEN`, `SUSPEND`, `EXECUTE PROCEDURE`, sub-rotinas) também é recusado, como erro de sintaxe.
- As construções recusadas ficam no AST (`UnsupportedUse` em `DmlStatement`/`ExecuteStatement`, nó `InAutonomousTransaction`), na posição da cláusula que as introduz. O Interpretador se recusa a executá-las mesmo sem o Pré-check, e `DebugSession.run` roda o Pré-check antes de executar qualquer coisa.
- A seção "Fora do MVP" da spec passou a apontar para o enum.
- Testes: `PreCheckTest` (9 casos: um por construção recusada com a posição, rotina só com construções suportadas → lista vazia, várias recusas em ordem, fonte que não parseia).

**Para as próximas issues**
- O exemplo de `WHERE CURRENT OF` usa um cursor `C` que não existe, porque cursores só entram na issue 05. Quando a 05 entrar, vale trocar por uma Rotina que compila de verdade (`FOR SELECT … AS CURSOR C DO UPDATE … WHERE CURRENT OF C`).
- A issue 12 deve acrescentar ao enum a trigger de banco/DDL.
- A issue 13 (launch) deve chamar `PreCheck.check` sobre o texto da Rotina e mostrar os `Finding`s.
