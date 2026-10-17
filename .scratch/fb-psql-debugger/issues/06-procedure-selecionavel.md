# 06 — Procedure selecionável (`SUSPEND`)

Status: ready-for-agent
Blocked by: 05

## O que construir

`SUSPEND` emite uma linha com os valores atuais dos parâmetros de saída. O Interpretador acumula as linhas emitidas como resultado da Sessão de debug.

## Critérios de aceite

- [x] Teste diferencial compara `SELECT * FROM sp(...)` nativo com as linhas emitidas pelo Interpretador (ordem e valores).
- [x] `SUSPEND` dentro de `FOR SELECT` e `EXIT` após `SUSPEND` cobertos.

## Comments

### 2026-10-16 — implementação (agente)

**Onde ficou**
- Gramática: token `SUSPEND` (não reservado no FB5: pode ser nome de variável e de saída), regra `suspendStatement`. AST: `Suspend`.
- Interpretador: `Interpreter.select(procedure, inputs, rows)` roda como um `SELECT * FROM sp(...)` que lê todas as linhas e entrega cada linha do `SUSPEND` ao `Consumer` na hora (para a issue 18 mostrar as linhas acumuladas durante a pausa). `Interpreter.execute` continua sendo o `EXECUTE PROCEDURE`. `DebugSession.select` faz o mesmo com Pré-check e fim da transação.
- O savepoint da chamada passou para a `SavepointStack` (`beginCall`/`endCall`/`undoCall`), porque o `SUSPEND` precisa renová-lo.
- Harness: `DifferentialHarness.assertSameRows(proc, args...)` compara colunas, linhas (ordem e valores), exceção e tabelas. `OutputComparison.assertSameRows` mostra `linha n, COLUNA: nativo=... × interpretado=...` e as linhas que só um lado emitiu.
- Testes: `SelectableDifferentialTest` (10 casos) e 1 teste novo no `RoutineParserTest`.

**Prova de que os testes pegam divergência:** de propósito, tirei o esvaziamento dos savepoints no `SUSPEND`, deixei o `SUSPEND` manter as alterações dos níveis, não renovei o savepoint da chamada, fiz o `SUSPEND` não terminar o `EXECUTE PROCEDURE` e tirei a validação de `NOT NULL` das saídas. Cada mudança derrubou pelo menos um teste. Depois restaurei o código.

**Comportamento do FB5 conferido no servidor**
- **`EXECUTE PROCEDURE` de uma Rotina com `SUSPEND`** devolve as saídas do primeiro `SUSPEND` e a Rotina termina ali (o resto não roda, como um `EXIT`). Sem passar por `SUSPEND`, devolve as saídas do fim.
- O fim de uma selecionável não emite linha. `EXIT` termina sem emitir.
- **Savepoints:** a cada `SUSPEND` (linha buscada pelo cliente), o motor passa para a busca tudo o que a Rotina alterou até ali, em todos os níveis. Os níveis continuam abertos, mas vazios. Por isso, depois de um `SUSPEND`:
  - um erro não tratado desfaz só o que veio depois do último `SUSPEND`; as linhas já emitidas e o que foi feito antes ficam;
  - um erro tratado por um `WHEN` também não desfaz o que veio antes do `SUSPEND`, mesmo dentro do statement em andamento do bloco;
  - um `FOR SELECT` aberto logo depois não conta as alterações de antes do `SUSPEND` (não ganha savepoint próprio).
- `ROW_COUNT` não muda com `SUSPEND`.
- `NULL` numa saída `NOT NULL` é erro de validação (`isc_not_valid_for_var`) no `SUSPEND`, e um `WHEN` pode tratá-lo, nos dois modos. No fim da executável (inclusive por `EXIT`) também é erro, mas fora do alcance de qualquer `WHEN`. No fim da selecionável não é validado. Com várias saídas nulas, a mensagem cita a primeira do `RETURNS`. Antes desta issue o Interpretador não validava as saídas no fim da executável; agora valida.
- `RDB$PROCEDURE_TYPE` é 1 para selecionável (tem `SUSPEND`) e 2 para executável; `SELECT` de uma executável dá "Procedure X is not selectable". A issue 18 pode usar isso para escolher o modo.

**Achado de harness (Jaybird):** o servidor busca linhas à frente do cliente e manda o erro junto com as últimas linhas boas. O Jaybird recebe essas linhas mas lança o erro antes de entregá-las (com o tamanho de lote padrão, nenhuma linha chega; com `setFetchSize(1)`, a última se perde). Insistir com `next()` depois do erro fechou a conexão, e cursor rolável no servidor materializa tudo na abertura. O harness descobre as linhas emitidas antes do erro com `SELECT FIRST n * FROM sp(...)` crescente, cada um com rollback: o `FIRST` para a Rotina depois da linha `n`. O debugger não tem esse problema: recebe as linhas direto do Interpretador.

**Para as próximas issues**
- Issue 10: `FOR SELECT ... FROM sp(...)` interpretado precisa entregar cada linha ao corpo do laço de quem chama com a Rotina chamada parada no `SUSPEND` (corrotina ou thread). O `Consumer` de hoje não pausa a Rotina, só recebe a linha. Os savepoints da chamada interna também precisam do esvaziamento a cada linha.
- Issue 14: o `SUSPEND` é um ponto natural de pausa (statement como outro qualquer).
