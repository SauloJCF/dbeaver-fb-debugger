# 18 — Launch de selecionável, function, package e `EXECUTE BLOCK`

Status: ready-for-agent
Blocked by: 06, 09, 13

## O que construir

- Ação "Debug" para function, rotina de package e procedure selecionável.
- `EXECUTE BLOCK`: ação "Debug" sobre o bloco selecionado no editor SQL (o texto é a fonte; a regra de editor sujo não se aplica aqui — o texto do editor é o código).
- Procedure selecionável: linhas emitidas por `SUSPEND` aparecem num resultado ao fim da sessão (e acumuladas durante a pausa).

## Critérios de aceite

- [x] Cada tipo pode ser lançado, pausado em breakpoint e concluído com o resultado correto.
- [x] Linhas de `SUSPEND` exibidas na ordem de emissão.

## Comments

- 2026-10-13 — Confirmado pelo humano: `EXECUTE BLOCK` é depurado a partir do texto do editor SQL, única exceção à regra "sempre a Fonte do banco".

### 2026-10-18 — implementação (agente)

**Estado: código pronto e compilando; a UI não foi testada no DBeaver.** Os critérios de aceite ficam desmarcados até o teste à mão. Fora do OSGi, contra o Firebird local:
- `RootRoutineSessionTest` (4 testes) roda cada tipo de Rotina raiz como o launch, com o `ExecutionControl` como gancho e como política das chamadas.
  - Selecionável: um breakpoint dentro do laço para três vezes. A cada pausa, o `Consumer` já recebeu 0, 1 e 2 linhas, e no fim as linhas estão na ordem de emissão, iguais às do `SELECT * FROM sp(...)` nativo.
  - Function: para no breakpoint e devolve o retorno, com o nome da function.
  - Rotina de package: os breakpoints são linhas do texto do corpo. A execução para na rotina do launch e na function privada que ela chama (breakpoint ao alcance da chamada, issue 17), com dois frames. A saída é a do `EXECUTE PROCEDURE PKG.TOTAL` nativo, e as linhas com statement do corpo cobrem as duas rotinas.
  - `EXECUTE BLOCK` que começa na linha 3, coluna 3 de um documento maior: para na linha do documento, emite a linha certa, e o Pré-check fala em linha e coluna do documento.
- `RootRoutineParserTest` (3 novos): as rotinas do corpo de uma package, com a linha em que cada uma começa e termina, montadas à parte (o `SUSPEND` de uma não deixa outra selecionável); um cabeçalho de package não é corpo; o texto posto na posição do documento (a coluna só conta na primeira linha).
- `RoutineSourceLoaderTest` (1 novo): `listPackages` traz só as packages com corpo, em ordem.
- `ResultTableTest` (2): a tabela do resultado no console.
- A suíte inteira passa: 225 testes (215 de antes e 10 novos).
- De propósito, quebrei duas peças, uma por vez no mesmo build: um builder só para todas as rotinas do corpo e o texto posto sem a coluna. Cada uma derrubou o teste dela. Depois restaurei.
- Banco de exemplo: `PKG_ESTOQUE` no `samples/sample-db/create-sample-db.sql`. Conferi o script inteiro com o isql num banco novo: `SELECT * FROM PKG_ESTOQUE.REPOSICAO(30)` dá `(3, Monitor 24 polegadas, 30)`, `(4, Webcam HD, 30)` e `(5, Headset, 10)`, e `PKG_ESTOQUE.VALOR_ESTOQUE(3)` dá `10800.00`.

**Onde ficou**
- Núcleo (sem Eclipse):
  - `RoutineParser.parsePackageBody(corpo)`: as rotinas do corpo (`PackageRoutine`: a rotina, sem os padrões do cabeçalho, e a primeira e a última linha dela). `AstBuilder.packageRoutines` usa um builder por rotina, porque o builder guarda o `SUSPEND` e as construções recusadas de uma Rotina só.
  - `RoutineParser.placed(texto, linha, coluna)`: o texto precedido de quebras de linha e espaços até onde ele começa no documento. Assim as posições do AST, do Pré-check e das mensagens de erro já são as do editor SQL, e nenhum outro lugar precisa somar a linha do bloco.
  - `PreCheck.unrecognized(erro)` (o achado de texto não reconhecido, usado também na rotina de package) e `RoutineSourceLoader.listPackages`.
- Modelo do Eclipse (`debug`):
  - `PsqlDebugTarget` recebe o `SourceId` do texto da Rotina raiz: num `EXECUTE BLOCK`, o do editor SQL; nos outros, o mesmo `SourceId.of` de antes. Procedure selecionável e `EXECUTE BLOCK` rodam por `DebugSession.select`, e cada linha vai ao console na hora.
  - `SessionConsole`: `SUSPEND, linha n: A = …, B = …` à medida que as linhas são emitidas; no fim, `Resultado: n linhas` e a tabela (`ResultTable`, células cortadas em 60 caracteres). Function: `Retorno:` em vez de `Saídas:`.
  - `PsqlLineBreakpoint`: na view Breakpoints, `EXECUTE BLOCK (Script-3.sql) [linha 12]`.
- Editor (`editor`):
  - `SqlEditors` (novo): o editor SQL de uma conexão Firebird como texto de Rotina. O console de uma package é registrado ao abrir, com o `SourceId` da package; nos outros editores, o `SourceId` é um `EXECUTE_BLOCK` com o nome do editor, dado na primeira vez. O bloco do launch vem do `extractActiveQuery` do DBeaver (a seleção ou, sem ela, o statement do cursor; o dialeto do Firebird já trata o `EXECUTE BLOCK` como bloco), com o texto tirado do documento, sem o terminador do script (o `^` do `SET TERM`), e posto na posição dele. `openPackage` abre um `StringEditorInput` só de leitura por `SQLEditorHandlerOpenEditor.openSQLConsole`.
  - Breakpoints no editor SQL: no console da package, valem as linhas com statement de todas as rotinas do corpo; nos outros editores, as do `EXECUTE BLOCK` que contém a linha. Fora de um bloco, o breakpoint é recusado com mensagem na linha de status.
  - `RoutineEditors`: `sourceText` e `sourceOf` aceitam o editor SQL; `routine(conexão, SourceId)` acha a procedure ou function pela conexão, sem precisar de uma procedure raiz; `statementLines` por tipo de editor.
  - `EditorBreakpoints.prepareLaunch(texto, linhas, primeira linha)`: num corpo de package, as linhas de todas as rotinas dele, para um breakpoint em outra rotina não ir para a do launch; num `EXECUTE BLOCK`, só os breakpoints a partir da linha do bloco são acertados, para os de outro bloco do mesmo script ficarem onde estão.
  - `PsqlModelPresentation`: o elemento de fonte `SourceId` vira a entrada do editor SQL aberto, com o id do editor SQL. O aviso de editor com alterações não salvas fica só no editor de objeto.
  - `RulerToggleBreakpointAction` (novo): duplo-clique no ruler do editor SQL; num editor que não é de conexão Firebird, não faz nada.
  - `RoutineEditorTester`: também testa o editor ativo (`activeEditor`), verdadeiro num editor SQL de conexão Firebird.
- Launch (`launch`):
  - `RoutineLauncher` foi reorganizado em torno de `Subject`, o ponto de partida do launch: `DatabaseRoutine` (procedure, selecionável ou não, ou function; recusa com o editor sujo), `EditorBlock` e `ConsoleRoutine` (rotina do console de uma package; confere que o corpo gravado no banco é o texto do console). O resto (conexão dedicada, Pré-check, diálogo, sessão, perspectiva) é o mesmo para os três. Os valores do diálogo continuam lembrados por conexão e Rotina; a chave da procedure não mudou.
  - `OpenPackageHandler` (novo): "Abrir Package..." no menu "PSQL Debug". Vale a conexão Firebird do navegador ou a do editor ativo. A lista e o corpo são lidos cada um numa conexão dedicada aberta só para isso.
  - `DebuggableRoutines.findSqlEditor` e `findDataSource`. O `DebugRoutineHandler` usa o editor SQL quando não há Rotina do DBeaver no contexto.
- `plugin.xml`: o comando `dev.fbdebug.dbeaver.openPackage`, com handler e item no menu "PSQL Debug"; o editor SQL nas expressões do "Depurar Rotina" (`enabledWhen` e menu de contexto do texto), do Ctrl+Shift+B e do "Alternar Breakpoint" do ruler; um `editorContribution` para o `RulerDoubleClick` do editor SQL.
- `MANIFEST.MF`: `Require-Bundle` ganhou `org.jkiss.dbeaver.ui.editors.sql`.
- `docs/como-funciona.md`: a seção "Selecionável, function, package e `EXECUTE BLOCK`" e o mapa do código.

**Decisões**
- **⚠ Rotina de package se depura de um console com o corpo dela ("PSQL Debug > Abrir Package...").** O DBeaver não mostra as packages do Firebird: o plugin dele não tem classe de package, e o Jaybird só lista as rotinas de package com `useCatalogAsPackage`, que o DBeaver não liga. O comando lista as packages com corpo da conexão e abre o corpo, que é a Fonte do banco, num console só de leitura ligado à conexão. O texto é o que o `RoutineSourceLoader` monta (`RECREATE PACKAGE BODY "X"`, `AS` e o corpo gravado), e por isso as linhas batem com as do Interpretador. Os breakpoints das rotinas da package ficam nesse console, e "Depurar Rotina" com o cursor numa rotina depura essa rotina. Uma rotina privada também pode ser depurada assim, embora de fora não dê para chamá-la. No launch, o corpo é relido do banco e comparado com o texto do console; se mudou, o launch é recusado, como com o editor sujo. "Abrir Package" de uma package com console aberto e o mesmo corpo só ativa o console; com o corpo mudado, fecha o console antigo, com os breakpoints dele (as linhas eram de outro texto), e abre um novo. O console também é onde aparece a linha da pausa quando um step into ou um breakpoint chega a uma rotina da package vinda de outra Rotina, o que resolve o "fonte não encontrada" da issue 17 quando o console está aberto. Alternativas descartadas: escolher a rotina num diálogo e lançar direto (não haveria onde pôr o breakpoint antes) e um editor de texto próprio (sem o realce de SQL do DBeaver e com mais código).
- **`EXECUTE BLOCK`: a seleção ou o statement do cursor.** A issue fala no bloco selecionado; sem seleção, vale o bloco em que está o cursor, separado pelo DBeaver como para executá-lo. Os breakpoints são por editor (o `SourceId` leva o nome do editor), e não por conexão: um breakpoint num script não vale num bloco de outro script. O bloco roda sempre como selecionável (issue 09). Sem `SUSPEND`, o resultado é vazio, como no servidor, e o console diz que o `EXECUTE BLOCK` não devolve as saídas sem `SUSPEND`.
- **A procedure selecionável roda como `SELECT * FROM sp(...)`**, e não como `EXECUTE PROCEDURE`, que terminaria no primeiro `SUSPEND`.
- **As linhas do `SUSPEND` vão para o console**, na hora (acumuladas durante a pausa) e numa tabela no fim, e não para uma grade de resultado do DBeaver. Segue a decisão da issue 13: o console não bloqueia e guarda o histórico das sessões.
- Uma procedure do navegador com package (não acontece no Firebird) recebe mensagem apontando para "Abrir Package".

**Para conferir à mão no DBeaver** (com o zip instalado)

Antes, rodar no `SAMPLE.FDB` o que está no fim deste comentário (os mesmos `CREATE` do `create-sample-db.sql`).

1. Selecionável: no navegador, `SP_PRODUTOS_BAIXO_ESTOQUE` > "Depurar Rotina" (antes recusava). Breakpoint em `FALTA = MINIMO - ESTOQUE;`, launch com `MINIMO = 20`. Para com `ID = 4`. F8: antes de parar de novo, o console mostra `SUSPEND, linha 1: ID = 4, DESCRICAO = Webcam HD, ESTOQUE = 5, FALTA = 15`. F8: o console mostra `Resultado: 2 linhas`, a tabela com `4 Webcam HD 5 15` e `3 Monitor 24 polegadas 9 11`, e o rollback.
2. Function: `FN_FATORIAL`, breakpoint em `R = R * I;`, launch com `N = 5`. Para quatro vezes (`I` de 2 a 5). No fim, `Retorno: FN_FATORIAL = 120`.
3. `EXECUTE BLOCK`: num script SQL novo da conexão do `SAMPLE.FDB`, escrever uma linha qualquer (`SELECT 1 FROM RDB$DATABASE;`), uma linha em branco e o bloco do fim deste comentário. Pôr um breakpoint no `DESCONTO = FN_DESCONTO(TOTAL);` com duplo-clique no ruler, e tirar e pôr de novo com Ctrl+Shift+B. Com o cursor dentro do bloco, botão direito no texto > "Depurar Rotina". O diálogo pede `P_MINIMO`: `0`. A execução para três vezes, com a linha destacada no editor SQL e `PEDIDO`/`TOTAL` em Variables. No fim, o console tem as linhas `(1, 940.00, 0.00)`, `(2, 1200.00, 60.00)` e `(3, 7800.00, 780.00)`.
   - Numa pausa, F5 na linha do `FN_DESCONTO` abre o editor da function, e F7 volta ao editor SQL.
   - Selecionar só a linha do `SELECT 1` e "Depurar Rotina": mensagem pedindo o `EXECUTE BLOCK`. Breakpoint na linha do `SELECT 1`: a linha de status diz que no editor SQL o breakpoint só vale dentro de um `EXECUTE BLOCK`.
   - Num editor SQL de outra conexão que não seja Firebird (se houver): "Depurar Rotina" não aparece no menu de contexto, e o duplo-clique no ruler não faz nada.
4. Package: com a conexão do `SAMPLE.FDB` selecionada no navegador, "PSQL Debug > Abrir Package...". A lista tem `PKG_ESTOQUE`, e o console "PKG_ESTOQUE (corpo da package)" abre só de leitura. Breakpoints em `PEDIR = LOTE(P_MINIMO - V_ESTOQUE);` (na `REPOSICAO`) e em `RETURN (P_QTD + 9) / 10 * 10;` (na `LOTE`). Cursor dentro da `REPOSICAO`, "Depurar Rotina", `P_MINIMO = 30`. Para no `PEDIR = …` com `ID = 3`. F8: para na `LOTE`, com dois frames e `P_QTD = 21`. F7: volta à `REPOSICAO`. F8 até o fim: `Resultado: 3 linhas`, com `3 Monitor 24 polegadas 30`, `4 Webcam HD 30` e `5 Headset 10`.
   - Cursor dentro da `VALOR_ESTOQUE`, `P_ID_PRODUTO = 3`: `Retorno: VALOR_ESTOQUE = 10800.00`.
   - Cursor na linha `BEGIN` do corpo, fora das rotinas: mensagem pedindo o cursor dentro de uma rotina.
   - "Abrir Package" de novo: ativa o mesmo console, com os breakpoints.
5. Regressão: a `SP_FATURA_PEDIDO` da issue 17, passos 1 e 2 do roteiro dela.
6. `.metadata/.log` sem erro novo, em especial do `editorContribution` do editor SQL e das expressões com `activeEditor`.

**Não tratado**
- Um breakpoint num script cujo editor muda de conexão depois deixa de valer: o texto do editor ganha outro `SourceId`.
- Se o DBeaver não separar o `EXECUTE BLOCK` pelo cursor (um script com outro terminador, por exemplo), é preciso selecionar o bloco.
- Uma selecionável que emite muitas linhas escreve todas no console, uma por uma e de novo na tabela do fim.
- Rotina de package chamada de outra Rotina, sem o console da package aberto: continua o editor de "fonte não encontrada".
- O console da package não acompanha mudanças no banco sozinho: "Abrir Package" de novo.

CREATEs para o `SAMPLE.FDB` (no isql, com `SET TERM ^ ;` antes e `SET TERM ; ^` e `COMMIT;` depois):

```sql
CREATE PACKAGE PKG_ESTOQUE AS
BEGIN
  PROCEDURE REPOSICAO (P_MINIMO INTEGER) RETURNS (ID INTEGER, DESCRICAO VARCHAR(80), PEDIR INTEGER);
  FUNCTION VALOR_ESTOQUE (P_ID_PRODUTO INTEGER) RETURNS NUMERIC(12,2);
END^

CREATE PACKAGE BODY PKG_ESTOQUE AS
BEGIN
  FUNCTION LOTE (P_QTD INTEGER) RETURNS INTEGER
  AS
  BEGIN
    RETURN (P_QTD + 9) / 10 * 10;
  END

  PROCEDURE REPOSICAO (P_MINIMO INTEGER) RETURNS (ID INTEGER, DESCRICAO VARCHAR(80), PEDIR INTEGER)
  AS
    DECLARE V_ESTOQUE INTEGER;
  BEGIN
    FOR SELECT ID, DESCRICAO, ESTOQUE FROM PRODUTO WHERE ESTOQUE < :P_MINIMO ORDER BY ID
        INTO :ID, :DESCRICAO, :V_ESTOQUE
    DO
    BEGIN
      PEDIR = LOTE(P_MINIMO - V_ESTOQUE);
      SUSPEND;
    END
  END

  FUNCTION VALOR_ESTOQUE (P_ID_PRODUTO INTEGER) RETURNS NUMERIC(12,2)
  AS
    DECLARE V_VALOR NUMERIC(12,2);
  BEGIN
    SELECT PRECO * ESTOQUE FROM PRODUTO WHERE ID = :P_ID_PRODUTO INTO :V_VALOR;
    RETURN V_VALOR;
  END
END^
```

`EXECUTE BLOCK` do passo 3 (no editor SQL, não no banco):

```sql
EXECUTE BLOCK (P_MINIMO NUMERIC(12,2) = ?)
RETURNS (PEDIDO INTEGER, TOTAL NUMERIC(12,2), DESCONTO NUMERIC(12,2))
AS
BEGIN
  FOR SELECT ID, TOTAL FROM PEDIDO WHERE TOTAL >= :P_MINIMO ORDER BY ID
      INTO :PEDIDO, :TOTAL
  DO
  BEGIN
    DESCONTO = FN_DESCONTO(TOTAL);
    SUSPEND;
  END
END
```

### 2026-10-18 — teste à mão (humano) e correções (agente)

O humano testou no DBeaver: tudo funcionou, menos três pontos, corrigidos abaixo. **As correções compilam e a suíte passa (225 testes), mas não foram testadas no DBeaver**: as três são de UI, sem teste automático possível.

1. **F7 no console da package não fazia nada** (passo 4: parado na `LOTE`, F7 não voltava à `REPOSICAO`).
   - Causa: o `SQLEditor` do DBeaver põe `org.jkiss.dbeaver.ui.context.resultset` entre os contextos de atalho dele (`getKeyBindingContexts`), ativo sempre que o editor SQL está em foco, e não só com a grade em foco, como a issue 17 supôs. Nesse contexto, F7 é `resultset.grid.togglePreview`. Ele e o `org.eclipse.debug.ui.debugging` (F7 = step return) são filhos do contexto da janela. O E4 (`BindingTableManager.getPerfectMatch`) escolhe o contexto mais fundo e, empatados, desempata pelo id. Com isso, `org.jkiss…` ganha de `org.eclipse…`. No passo 3 o F7 funcionou porque o foco estava no editor da function, que não é editor SQL.
   - Correção (`plugin.xml`): contexto `dev.fbdebug.dbeaver.debugging`, filho do `org.eclipse.debug.ui.debugging`, ligado ao modelo `dev.fbdebug.dbeaver.psql` por `debugModelContextBindings` (o debug.ui o ativa enquanto um elemento da sessão está selecionado na view Debug), com F7 → `StepReturn`. Ganha por ser mais fundo. Fora da sessão, o F7 do editor SQL volta a ser do DBeaver. A linha do ADR 0004 sobre o conflito foi atualizada.
2. **A primeira linha de uma Rotina cujo editor não estava aberto ficava sem destaque.**
   - Causa: o `SQLObjectDocumentProvider` do DBeaver cria o documento da pasta Source com um texto provisório (comentário "Loading…"), lê a Fonte num job e, no fim, chama `setInput` de novo no editor de texto. O debug.ui destacava a linha no texto provisório, que não tem essa linha, e o `setInput` apagava o que houvesse.
   - Correção (`PsqlModelPresentation.addAnnotations`): se o documento não tem a linha da pausa, espera o `PROP_INPUT` do editor de texto e chama `DebugUITools.displaySource` de novo, se o frame ainda é o contexto de debug selecionado.
3. **Editor de procedure restaurado ao reabrir o DBeaver: sem breakpoint e sem "Depurar Rotina".**
   - Causa: o editor restaurado nasce com um `DatabaseLazyEditorInput`, cujo `getDatabaseObject()` devolve a conexão. Quando a conexão abre, o `ProgressEditorPart` chama `init` com a entrada real e `recreateEditorControl`, sem `PROP_INPUT`. Por isso a variável `activeEditorInput` do workbench continua com a entrada provisória, e o tester (`breakpointSource`) e o `DebugRoutineHandler` a liam.
   - Correção: as expressões do `plugin.xml` testam `activeEditor`, e o `RoutineEditorTester` lê a entrada atual do editor. Com uma entrada provisória, ele espera uma mudança de propriedade do editor (o `recreateEditorControl` dispara uma) com a entrada real e então pede `IEvaluationService.requestEvaluation` da propriedade. O `DebugRoutineHandler`/`DebuggableRoutines.find` também passaram a usar a entrada atual do editor ativo.

**Para conferir à mão** (com o zip novo instalado):
- Passo 4 de novo: parado na `LOTE`, F7 com o foco no console volta à `REPOSICAO`, no `SUSPEND`, com um frame. Fora da sessão, F7 no editor SQL faz o que fazia antes no DBeaver.
- Com o editor da `FN_DESCONTO` fechado, passo 3 do roteiro: F5 na linha do `FN_DESCONTO` abre o editor da function com o primeiro `IF` destacado.
- Com o editor de uma procedure aberto, fechar e reabrir o DBeaver. Depois de conectar (pelo editor ou pelo navegador): duplo-clique no ruler e Ctrl+Shift+B põem breakpoint, e o botão direito no texto da pasta Source mostra "Depurar Rotina". Conferir também sem trocar de editor antes, com o editor restaurado já ativo.

### 2026-10-18 — reteste à mão (agente)

O humano conferiu no DBeaver as três correções (F7 no console da package, destaque da primeira linha numa Rotina recém-aberta, editor restaurado ao reabrir o DBeaver) e o resto do roteiro, sem problema. Critérios de aceite marcados. A suíte passa com 225 testes.
