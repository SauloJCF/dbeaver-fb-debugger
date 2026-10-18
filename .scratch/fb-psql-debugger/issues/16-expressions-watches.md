# 16 — Expressions (watches)

Status: ready-for-agent
Blocked by: 15

## O que construir

Suporte à view Expressions: cada watch é uma expressão PSQL avaliada no servidor (Modo Careful) com as variáveis do frame selecionado, reavaliada a cada pausa. Expressão inválida mostra o erro do servidor no lugar do valor.

## Critérios de aceite

- [x] Watch `a * b + 1` acompanha as variáveis a cada step.
- [x] Watch com erro de sintaxe mostra a mensagem e não interrompe a sessão.
- [x] Watch não altera estado do banco (avaliação só com `SELECT … FROM RDB$DATABASE`). *(Mais um savepoint desfeito em volta do watch e a recusa de `GEN_ID`, `NEXT VALUE FOR` e `RDB$SET_CONTEXT`; ver comentários.)*

## Comments

### 2026-10-18 — implementação (agente)

**Estado: código pronto e compilando; a UI não foi testada no DBeaver.** Os critérios de aceite ficam desmarcados até o teste à mão. Fora do OSGi, contra o Firebird local:
- `WatchTest` avalia os watches de outra thread durante a pausa, como a view Expressions. `a * b + 1` acompanha as variáveis a cada step (`NULL`, `NULL`, 3, 23) e sai como `BIGINT`. `A +` e `NAO_EXISTE + 1` dão o erro do servidor, `A; B` o erro do parser, e a sessão continua: o step seguinte e o resume vão até o fim com as saídas certas. Um watch que chama uma function com `INSERT` vê a linha dela, mas depois dele a tabela só tem a linha da procedure. `GEN_ID(WT_SEQ, 1)` é recusado. Com `CallPolicy.INTERPRET`, o mesmo `X` vale 7 no frame da procedure chamada e 100 no de quem chamou.
- `WatchParserTest` cobre o texto do watch: uma expressão só, comentário no fim fora do texto, `;`, `,` e `)` dentro de parênteses e de literais, e as construções recusadas.
- De propósito, tirei o savepoint do watch: o teste de "não altera o banco" falhou (2 linhas em vez de 1). Depois restaurei.

**Onde ficou**
- Parser: regra `watchExpression : expression EOF` na gramática e `RoutineParser.parseWatch`. Com a gramática, o texto não sai do `SELECT (<expr>) FROM RDB$DATABASE`: um `)` solto, um `;` ou uma `,` fora de parênteses é recusado antes de ir ao servidor. O comentário de linha no fim fica fora do texto (senão engoliria o parêntese). `AstBuilder.watch` recusa `GEN_ID(g, n)` com `n` diferente de 0, `NEXT VALUE FOR` e `RDB$SET_CONTEXT(`.
- Núcleo (`interp`): `Interpreter.watch` avalia a expressão com o `BoundSql` do frame, o mesmo das atribuições (variáveis, campos de cursor, `NEW`/`OLD`, `ROW_COUNT`, `GDSCODE`, sub-rotinas pelo Package temporário). Ele roda dentro de `Savepoints.undone`: um savepoint com `ROLLBACK TO SAVEPOINT` e `RELEASE` no fim, com ou sem erro. `WatchValue` traz o valor e o tipo que o servidor dá à expressão (`ColumnTypes.name`: `VARCHAR(13)`, `NUMERIC(18,2)`, sem o conjunto de caracteres). `DebugSession.watch` junta o parser e a avaliação.
- Modelo do Eclipse (`debug`):
  - `PsqlWatchExpressionDelegate`, registrado no `plugin.xml` (`org.eclipse.debug.core.watchExpressionDelegates`, modelo `dev.fbdebug.dbeaver.psql`). Não bloqueia a UI: a avaliação vai para `ExecutionControl.whilePaused`, e o resultado chega ao listener quando a thread da execução termina.
  - O frame é o selecionado na view Debug ou, com a thread ou a sessão selecionada, o de cima. Sem pausa, ou com um frame de uma pausa anterior (`PsqlStackFrame.isCurrent`), o watch fica sem valor.
  - `PsqlDebugTarget.watch` espera até 30 s (`orTimeout`), o mesmo prazo da edição de variável (`TASK_TIMEOUT_SECONDS`).
- Reavaliação a cada pausa: conferido no fonte do `debug.ui` 3.20. O `DefaultWatchExpressionModelProxy` chama `setExpressionContext` a cada mudança do contexto de debug, e o `WatchExpression` reavalia. Cada pausa cria frames novos, e a view Debug seleciona o de cima. Por isso o watch é reavaliado a cada pausa e ao selecionar outro frame.

**Decisões**
- **Savepoint em volta do watch.** Um `SELECT` pode alterar o banco quando chama uma function ou uma procedure selecionável com DML. O savepoint desfaz isso e não toca no que a sessão já fez: ele é criado depois de todos os da `SavepointStack` e solto no fim. São três idas a mais ao servidor por watch, o que serve para um banco local.
- **Recusa de gerador e de contexto.** O savepoint não desfaz `GEN_ID`, `NEXT VALUE FOR` nem `RDB$SET_CONTEXT`, então o parser recusa os três quando estão no texto do watch. Cabe a exceção `GEN_ID(g, 0)`, que só lê o valor atual e é o jeito usual de ver um gerador.
- **Erro no lugar do valor, do jeito do debug.ui.** O resultado com erro aparece na coluna de valor como `<error(s)_during_the_evaluation>`, em vermelho, e a mensagem do servidor (ou do parser) aparece como filho ao abrir o watch. Pôr a mensagem direto na coluna exigiria trocar o label provider do debug.ui para `WatchExpression`, que não é nosso.
- A mensagem do servidor vem como o Jaybird a dá. A "linha 1, coluna N" dela é do `SELECT` montado, em que cada variável virou `CAST(? AS <tipo>)`, e não do texto do watch.

**Para conferir à mão no DBeaver** (com o zip instalado; `SP_SOMA_ESTOQUE` do banco de exemplo serve)
1. Breakpoint numa linha do laço e launch. Na view Expressions (perspectiva Debug, junto de Variables e Breakpoints; se não estiver lá, Window > Show View > Other > Debug > Expressions), "Add Watch Expression": `TOTAL + V_ESTOQUE`. Ele mostra o valor, e a coluna de tipo mostra o tipo do servidor.
2. F6 algumas vezes: o watch muda a cada pausa, acompanhando Variables.
3. Watch `TOTAL +` ou `NAO_EXISTE`: a coluna de valor mostra `<error(s)_during_the_evaluation>` em vermelho, e ao abrir o watch aparece a mensagem do servidor. F6 e F8 continuam funcionando.
4. Watch `TOTAL; 1`: a mensagem do parser ("trecho não reconhecido"). Watch `GEN_ID(GEN_PRODUTO, 1)`: recusado, "GEN_ID não é permitido num watch...". `GEN_ID(GEN_PRODUTO, 0)` mostra o valor do gerador, que não muda a cada pausa.
5. `SP_REPOE_ESTOQUE` (nova no `create-sample-db.sql`), com `P_ID_PRODUTO = 4` e `P_QTD = 10` e breakpoint no último `SELECT ... INTO :ESTOQUE_DEPOIS`. O watch `(SELECT ESTOQUE FROM PRODUTO WHERE ID = P_ID_PRODUTO)` mostra 15, o estoque que o `UPDATE` da sessão gravou e ainda não commitou, enquanto Variables mostra `ESTOQUE_ANTES = 5`. `(SELECT COUNT(*) FROM LOG_ESTOQUE)` conta a linha que o trigger gravou. Num editor SQL do DBeaver, fora da sessão, o estoque continua 5, e continua depois da sessão sem "commit ao terminar".
6. Depois do fim da sessão, os watches ficam sem valor, e o `.metadata/.log` não tem erro novo.

**Não tratado**
- O DML de uma Rotina chamada no watch é desfeito, mas não o que não volta com savepoint: gerador incrementado dentro dela, `RDB$SET_CONTEXT` dentro dela, `IN AUTONOMOUS TRANSACTION` e `EXECUTE STATEMENT ... ON EXTERNAL` dentro dela.
- Como na edição de variável, um watch que demora mais de 30 s no servidor é dado como falha, mas a tarefa ainda roda. Até ela terminar, a execução não atende step nem resume.
- "Watch" no menu de uma variável da view Variables usa o nome dela como texto. Para uma variável de nome entre aspas (`"minúsculo"`), o texto sai sem aspas e não acha a variável.
- No step, o debug.ui pode selecionar a thread por um momento. Aí os watches ficam sem valor até a próxima pausa.

**Para as próximas issues**
- Issue 17: até ela, a pausa só acontece no frame da Rotina raiz (`CallPolicy.SERVER`), e a view Debug mostra um frame só. Com o step into, conferir à mão que, ao selecionar o frame de quem chamou, o watch passa a usar as variáveis dele (o `WatchTest` já cobre isso fora da UI).

### 2026-10-18 — teste à mão: passo 5 trocado (agente)

O humano conferiu os passos 1 a 4 e 6 no DBeaver, sem problema. O passo 5 usava `SP_PEDIDO_RAPIDO`, que tem `SUSPEND`, e o launch recusa procedure selecionável até a issue 18 ("Procedure selecionável (com SUSPEND) ainda não pode ser depurada..."). O erro era do roteiro, não do watch. O passo 5 agora usa a `SP_REPOE_ESTOQUE`, nova no `samples/sample-db/create-sample-db.sql`: executável, grava com um `UPDATE` (que dispara o trigger de `LOG_ESTOQUE`) e tem um statement depois dele, onde a pausa vê o que a sessão alterou. Conferida com o isql numa cópia do banco de exemplo: `(4, 10)` dá 5 e 15, e grava uma linha em `LOG_ESTOQUE`.

O humano refez o passo 5 com a `SP_REPOE_ESTOQUE`, sem problema. Critérios de aceite marcados.
