# Como o plugin funciona

Visão geral com exemplos. Os termos em **negrito** estão definidos em `GLOSSARY.md`. As decisões estão nos ADRs em `docs/adr/`.

## Visão geral

O Firebird 5 não tem API de debug no servidor. Então o plugin **simula** a execução da Rotina no cliente. Ele lê o PSQL, monta uma árvore (AST) e percorre essa árvore comando a comando. A lógica procedural roda no plugin, e o servidor só calcula o que depende dele (ADR 0001). Esse é o mesmo modelo do debugger do IBExpert, e nada é instalado no servidor.

### O que roda onde

| No cliente (Interpretador) | No servidor (Firebird) |
|---|---|
| `IF`, `WHILE`, `FOR SELECT` (o laço), `BEGIN/END`, `WHEN`, `EXCEPTION`, `SUSPEND`, `LEAVE`, cursores, `DECLARE` | **Fatias DML**: `SELECT/INSERT/UPDATE/DELETE/MERGE` |
| Controle de breakpoints, step over/into, pilha de chamadas | **Avaliação de expressão**: cada atribuição, condição ou watch vira `SELECT <expr> FROM RDB$DATABASE` (Modo Careful, ADR 0002) |

Tudo usa **uma conexão dedicada e uma única transação** por Sessão de debug.

## Exemplo completo

```sql
CREATE PROCEDURE P_REAJUSTE (PERCENTUAL NUMERIC(5,2))
RETURNS (TOTAL INTEGER)
AS
  DECLARE VARIABLE ID INTEGER;
  DECLARE VARIABLE PRECO NUMERIC(10,2);
BEGIN
  TOTAL = 0;                                              -- linha 1
  FOR SELECT ID, PRECO FROM PRODUTOS INTO :ID, :PRECO     -- linha 2
  DO BEGIN
    IF (PRECO > 100) THEN                                 -- linha 3
      UPDATE PRODUTOS SET PRECO = :PRECO * (1 + :PERCENTUAL/100)
       WHERE ID = :ID;                                    -- linha 4
    TOTAL = TOTAL + 1;                                    -- linha 5
  END
  SUSPEND;                                                -- linha 6
END
```

### 1. Launch

No DBeaver você escolhe a Rotina e abre o diálogo de launch, por "Depurar Rotina" no menu de contexto do navegador ou do editor da Rotina, ou no menu "PSQL Debug". Assim saem procedures (selecionáveis ou não) e functions; `EXECUTE BLOCK` e rotinas de package saem do editor SQL (ver "Selecionável, function, package e `EXECUTE BLOCK`"). Com uma view do debug ou o console em foco, o menu "PSQL Debug" usa a Rotina do editor ativo. No diálogo você preenche `PERCENTUAL = 10`, o isolamento da transação e se quer "commit ao terminar". O launch é a única forma de iniciar uma sessão, e não existe attach.

Antes de começar, o plugin lê o texto da Rotina nos metadados do banco (a **Fonte do banco**). Se o editor tiver alterações não salvas, o launch é bloqueado, para garantir que o que você vê é o que roda. Em seguida o **Pré-check** varre o AST. Se houver uma construção não suportada, ele recusa o launch e mostra linha e coluna. Isso segue o ADR 0001: nada é executado "mais ou menos".

### 2. Execução passo a passo

Você coloca um breakpoint na linha 4 e inicia.

| Passo | Quem executa | O que acontece |
|---|---|---|
| Linha 1 `TOTAL = 0` | Servidor (expressão) | `SELECT CAST(? AS INTEGER) FROM RDB$DATABASE`, com o 0 como valor. O resultado vai para `TOTAL`. |
| Linha 2 `FOR SELECT` | Servidor (DML) + cliente | O `SELECT ID, PRECO FROM PRODUTOS` vai ao servidor como Fatia DML. O cliente controla o laço, linha a linha. |
| Linha 3 `IF (PRECO > 100)` | Servidor (expressão) | `SELECT CASE WHEN CAST(? AS NUMERIC(10,2)) > 100 ...`, e o cliente decide o ramo. |
| Linha 4 `UPDATE` | Servidor (DML) | `:PRECO`, `:PERCENTUAL` e `:ID` viram `?` e são enviados como parâmetros. O breakpoint para aqui. |
| Linha 5 `TOTAL = TOTAL + 1` | Servidor (expressão) | Igual à linha 1. |
| Linha 6 `SUSPEND` | Cliente | Registra a linha de saída. |

Com a execução parada, o painel de variáveis mostra `ID`, `PRECO`, `TOTAL` e `PERCENTUAL`. Você pode adicionar um **watch** na view Expressions, por exemplo `PRECO * 1.1`, e ele é avaliado no servidor do mesmo jeito. Também pode editar uma variável.

### Breakpoints, step over, variáveis editadas e watches

- O breakpoint é posto no texto da pasta Source do editor da Rotina (ou no editor SQL, num `EXECUTE BLOCK` ou no console de uma package): duplo-clique no ruler, "Alternar Breakpoint" no menu do ruler ou Ctrl+Shift+B na linha do cursor. Numa linha sem statement (`BEGIN`, `END`, comentário), ele vai para o próximo statement. Sem nenhum depois, é recusado.
- O breakpoint não é persistente. Ele some quando o editor fecha e não volta depois de reiniciar o DBeaver.
- A execução para antes do statement. Num laço, para também a cada volta, na linha do `WHILE` ou do `FOR SELECT`, antes de avaliar a condição ou de buscar a próxima linha. O step over (F6) vai ao próximo statement da mesma Rotina, ou de quem a chamou quando ela termina. O resume é o F8. Step into (F5) e step return (F7) estão em "Step into em outra Rotina", abaixo.
- A camada de debug só para nas Rotinas que acompanha: a raiz, as alcançadas por step into e as que têm um breakpoint ao alcance da chamada (abaixo). Uma procedure de `FOR SELECT` interpretada no step over, sem breakpoint, não para.
- O valor digitado na view Variables vai ao servidor como texto (`CAST(CAST(? AS VARCHAR(...)) AS <tipo>)`). Por isso vale o que um `CAST` aceita, como `'17.10.2026'` numa `DATE`. `NULL`, sem aspas, é o valor nulo. Num erro de conversão, a variável fica como estava.
- Um watch vai ao servidor como `SELECT (<expr>) FROM RDB$DATABASE`, com as variáveis do frame selecionado na view Debug. Ele é avaliado de novo a cada pausa e quando outro frame é selecionado. Um erro do servidor fica no lugar do valor (abra o watch para ver a mensagem), e a sessão continua pausada.
- O texto do watch precisa ser uma expressão só: com `;`, com `,` fora de parênteses ou com parênteses desbalanceados, ele é recusado antes de ir ao servidor.
- O watch não altera o banco. Ele roda num savepoint desfeito logo depois, e assim o DML de uma function ou procedure chamada nele não fica. `GEN_ID` (fora `GEN_ID(g, 0)`, que só lê), `NEXT VALUE FOR` e `RDB$SET_CONTEXT` são recusados, porque o savepoint não desfaz gerador nem variável de contexto.
- Enquanto a execução está parada, a thread dela é a única que usa a conexão. A edição de uma variável e os watches rodam nessa thread, pedidos pela UI (`ExecutionControl.whilePaused`).

### 3. Fim

Terminando ou abortando, a transação faz commit ou rollback, conforme o que você escolheu no launch.

## Step into em outra Rotina

```sql
-- dentro de P_PEDIDO
EXECUTE PROCEDURE P_ESTOQUE(:ID);      -- Step into elegível: entra em P_ESTOQUE
X = F_DESCONTO(:VALOR);                -- Step into elegível: a chamada é a expressão inteira
INSERT INTO LOG SELECT F_DESCONTO(1) FROM RDB$DATABASE;  -- Executado no servidor
```

- Nas duas primeiras linhas, o step into (F5) abre o editor da outra Rotina (a Fonte do banco, na pasta Source) e para no primeiro statement dela. Ela aparece como um novo frame na pilha da view Debug.
- Na terceira, a chamada está dentro de uma Fatia DML e roda inteira no servidor, sem step into. O F5 ali vai ao próximo statement, como o step over. Durante a sessão, o nome chamado ganha uma moldura tracejada no editor e o ícone de informação no ruler. Na view Debug, o frame parado nessa linha diz `(executado no servidor: F_DESCONTO)`. O hover do editor SQL do DBeaver não mostra o texto da anotação, porque só mostra as anotações dele.
- O mesmo aviso vale para uma chamada elegível a uma Rotina que o Interpretador não executa, porque a Fonte não parseia ou o Pré-check a recusa. Ela vai inteira ao servidor. Uma função interna (`UPPER(...)`), uma tabela depois de `FROM` ou uma Rotina externa não têm aviso.

### Step into, step return e a pilha

- O step into entra na chamada do statement em que a execução parou. Num statement sem chamada que dê para interpretar, ele vai ao próximo statement, como o step over.
- O step return (F7) roda o resto do frame e para no próximo statement de quem o chamou, que é a linha seguinte à chamada. Na Rotina raiz ele fica desabilitado, como no Eclipse.
- No editor SQL do DBeaver, o F7 também é "toggle preview" da grade de resultado, num contexto de atalhos que o editor SQL deixa sempre ativo. O plugin liga o F7 ao step return num contexto filho do de debug do Eclipse, ativo durante a Sessão de debug, que ganha o conflito por ser mais fundo. Fora da sessão, o F7 volta a ser do DBeaver.
- Uma Rotina alcançada por step into, ou por um breakpoint, cujo editor não estava aberto abre com um texto provisório na pasta Source: o DBeaver lê a Fonte num job e só depois troca o texto, o que apagaria o destaque. Quando o texto chega, o plugin mostra a linha da pausa de novo.
- Os steps valem para o frame selecionado na view Debug, como no Eclipse. Com o frame de quem chamou selecionado, o F6 roda até o próximo statement desse frame e o F7 até o de quem o chamou. O F5 só vale no frame de cima.
- Selecionar outro frame na view Debug mostra a linha dele no editor da Rotina dele, e as variáveis e os watches passam a ser os desse frame.
- Uma sub-rotina abre no mesmo editor da Rotina que a declara. Na view Debug, o frame aparece como `ARREDONDA (em SP_FATURA_PEDIDO) linha N`.
- Num `FOR SELECT … FROM sp(...)`, o step into na linha do laço entra na procedure. Na primeira volta ela começa, e nas seguintes continua depois do `SUSPEND`. O step over no `SUSPEND` vai ao corpo do laço.
- Uma rotina de package aparece no console do corpo dela, se ele estiver aberto ("Abrir Package", abaixo). Sem o console, o frame dela aparece na pilha, com variáveis, mas o debug.ui mostra o editor de fonte não encontrada.

### Breakpoints em outra Rotina

- Um breakpoint vale em qualquer Rotina da conexão cujo editor esteja aberto, e não só na do launch. Fechar o editor tira os breakpoints dela, porque eles não são persistentes.
- No step over e no resume, um `EXECUTE PROCEDURE` ou um `x = F(...)` vai ao servidor, exceto quando há um breakpoint numa Rotina que a chamada alcança, direto ou por outras chamadas elegíveis. Aí a Rotina é interpretada, e a execução para no breakpoint. Fora dele, o step over não para dentro da chamada. A procedure de um `FOR SELECT` é sempre interpretada (issue 10), mas a camada de debug só a acompanha com breakpoint ao alcance, inclusive um posto nela durante o laço, que vale a partir da volta seguinte.
- Para saber o que uma chamada alcança, com algum breakpoint ativo, a primeira chamada a cada Rotina lê a Fonte do banco das Rotinas que ela chama, uma vez por sessão. Uma chamada dentro de expressão ou de Fatia DML roda no servidor e não para em breakpoint.

## Selecionável, function, package e `EXECUTE BLOCK`

- **Procedure selecionável** (com `SUSPEND`): roda como um `SELECT * FROM sp(...)` que lê todas as linhas. Cada linha emitida vai para o console na hora (`SUSPEND, linha 2: ID = 4, ...`), e numa pausa as anteriores já estão lá. No fim, o console mostra o resultado numa tabela, na ordem de emissão.
- **Function**: roda como um `SELECT f(...) FROM RDB$DATABASE`, e o console mostra o retorno.
- **`EXECUTE BLOCK`**: não existe no banco. No editor SQL de uma conexão Firebird, selecione o bloco (ou ponha o cursor nele, como para executá-lo) e use "Depurar Rotina", no menu de contexto do texto ou no "PSQL Debug". O texto do editor é o código depurado, e a regra de editor sujo não vale para ele. As linhas e colunas são as do editor, e os breakpoints vão no ruler do editor SQL, dentro do bloco. Os parâmetros `nome tipo = ?` aparecem no diálogo. O bloco roda sempre como selecionável: sem `SUSPEND` não devolve linha, como no servidor, e as saídas só aparecem na view Variables, numa pausa.
- **Rotina de package**: o DBeaver não mostra as packages do Firebird. "PSQL Debug > Abrir Package..." escolhe uma package da conexão (a do navegador ou a do editor ativo) e abre o corpo dela, que é a Fonte do banco, num console só de leitura. Os breakpoints das rotinas da package ficam nesse console, e "Depurar Rotina" com o cursor numa rotina depura essa rotina. Se o corpo mudou no banco depois de aberto, o launch é recusado. "Abrir Package" de novo fecha o console antigo, com os breakpoints dele, e abre o corpo novo.

## Triggers

Uma trigger **não** é alcançada por step into a partir de um `INSERT`. Ela só é depurada como **Trigger isolada**. No diálogo de launch você informa os valores de `NEW.*` e `OLD.*` e escolhe qual evento simular (`INSERTING`, `UPDATING` ou `DELETING`).

- "Depurar Rotina" vale para uma trigger de tabela, no navegador ou no editor dela. Os breakpoints vão no ruler da pasta Source, como numa procedure. Trigger de banco e de DDL não aparecem.
- O diálogo tem uma linha por coluna da tabela, com o tipo da coluna, o valor de `NEW` e o de `OLD`. Sem valor, a coluna vale `NULL`. O registro que a trigger não tem fica desabilitado: `NEW` numa trigger só de `DELETE` e `OLD` numa só de `INSERT` (o motor nem compila a referência). Os eventos são checkboxes, desmarcados na primeira vez. Só os eventos da trigger podem ser marcados. Numa trigger universal, o evento marcado decide o ramo.
- Na pausa, `NEW.NOME`, `OLD.NOME` e os eventos aparecem em Variables como entradas, editáveis. Em Expressions, `NEW.NOME` vale como em qualquer expressão da trigger.
- No fim, o console mostra os `NEW.*` finais (`NEW final:`). Numa trigger `AFTER`, o `NEW` não muda.
- Nada é gravado na tabela da trigger: a linha não existe fora do diálogo. Uma trigger `AFTER` que lê a própria tabela vê o estado de antes do DML, e os valores padrão (`DEFAULT`) das colunas não entram sozinhos em `NEW.*`.

## Sub-rotinas e package temporário

Hoje não usamos sub-rotinas (`DECLARE PROCEDURE/FUNCTION` dentro de uma Rotina), então o plugin não cria nenhum package `DBG$...` no banco. O resumo do ADR 0003, para quando isso mudar:

- Uma sub-rotina só existe dentro da Rotina que a declara. Uma Fatia DML ou expressão enviada ao servidor não a enxerga.
- Para o servidor poder chamá-la, o plugin copia a sub-rotina para um package temporário `DBG$<CURRENT_CONNECTION>_<n>`, criado na primeira chamada que precisa dele.
- A cópia só é fiel se a sub-rotina **não** usa variáveis da Rotina que a declara. Se usa, ela fica fora do package: chamada como comando é interpretada; chamada dentro de expressão ou Fatia DML é recusada pelo Pré-check (`SUBROUTINE_USING_PARENT_VARIABLES`).
- O DDL roda num `EXECUTE STATEMENT ... WITH AUTONOMOUS TRANSACTION` na conexão da sessão, para o package ficar visível mesmo em `SNAPSHOT` sem commitar nada da Rotina.
- A varredura remove só os packages de conexões que não estão mais em `MON$ATTACHMENTS`.

## Garantia de fidelidade

Como é uma simulação, o resultado pode divergir do servidor. O ADR 0005 trata isso com **Testes diferenciais**. Cada teste roda a mesma Rotina nativamente e no Interpretador, cada uma com rollback, e compara saídas, linhas de `SUSPEND`, exceção e estado das tabelas.

Um caso de divergência já tratado é o dos savepoints implícitos de blocos com `WHEN`. O motor desfaz só o statement em andamento do bloco, e o Interpretador reproduz isso com `SavepointStack`.

## Onde cada coisa vive no código

Mapa feito a partir dos nomes dos arquivos, dos ADRs e do glossário. Conferir no código antes de depender dos detalhes.

| Papel | Arquivos |
|---|---|
| Parser, AST e Pré-check | `psql/RoutineParser`, `AstBuilder`, `PreCheck`, `psql/ast/*` |
| Interpretador | `interp/Interpreter`, `Frame`, `ExpressionEvaluator`, `DmlExecutor` |
| Sessão de debug e pausa (breakpoint, steps, tarefas na pausa) | `interp/DebugSession`, `StatementHook`, `ExecutionControl`; linhas com statement em `psql/StatementLines` |
| Step into × servidor em cada chamada elegível | `interp/CallPolicy` (o `ExecutionControl` é a da sessão), `CallTargets` (Rotina chamada e linhas que a chamada alcança), `psql/CallSites` |
| Aviso "executado no servidor" | `interp/ServerCalls`, `debug/ServerCallWarnings`, `editor/ServerCallAnnotations`; o tipo de anotação no `plugin.xml` |
| Modelo de debug do Eclipse (pilha, variáveis, breakpoints, watches) | `debug/PsqlDebugTarget`, `PsqlThread`, `PsqlStackFrame`, `PsqlVariable`, `PsqlLineBreakpoint`, `PsqlSourceLocator`, `PsqlWatchExpressionDelegate` |
| Console da sessão (entradas, saídas, linhas do `SUSPEND`, `NEW.*` finais) | `debug/SessionConsole`, `ResultTable`, `ValueText` |
| Editor do DBeaver (ruler, linha da pausa, objeto de uma Rotina chamada) | `editor/EditorBreakpoints`, `RoutineEditors`, `PsqlModelPresentation`, handlers do `plugin.xml` |
| Editor SQL como texto de Rotina (`EXECUTE BLOCK`, console de package) | `editor/SqlEditors`, `RulerToggleBreakpointAction`; posições do documento em `psql/RoutineParser.placed` |
| Launch (diálogo de `NEW`/`OLD`/eventos de trigger em `RoutineLaunchDialog`) | `launch/DebugRoutineHandler`, `OpenPackageHandler`, `DebuggableRoutines`, `RoutineLaunchDialog`, `RoutineLauncher` |
| Fonte da Rotina | `source/RoutineSourceLoader` |
| Packages temporários (sub-rotinas) | `interp/TemporaryPackages` |
