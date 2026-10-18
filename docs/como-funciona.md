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

No DBeaver você escolhe a Rotina e abre o diálogo de launch, por "Depurar Rotina" no menu de contexto do navegador ou do editor da Rotina, ou no menu "PSQL Debug". Com uma view do debug ou o console em foco, o menu "PSQL Debug" usa a Rotina do editor ativo. No diálogo você preenche `PERCENTUAL = 10`, o isolamento da transação e se quer "commit ao terminar". O launch é a única forma de iniciar uma sessão, e não existe attach.

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

Com a execução parada, o painel de variáveis mostra `ID`, `PRECO`, `TOTAL` e `PERCENTUAL`. Você pode adicionar um **watch**, por exemplo `PRECO * 1.1`, e ele é avaliado no servidor do mesmo jeito. Também pode editar uma variável.

### Breakpoints, step over e variáveis editadas

- O breakpoint é posto no texto da pasta Source do editor da Rotina: duplo-clique no ruler, "Alternar Breakpoint" no menu do ruler ou Ctrl+Shift+B na linha do cursor. Numa linha sem statement (`BEGIN`, `END`, comentário), ele vai para o próximo statement. Sem nenhum depois, é recusado.
- O breakpoint não é persistente. Ele some quando o editor fecha e não volta depois de reiniciar o DBeaver.
- A execução para antes do statement. Num laço, para também a cada volta, na linha do `WHILE` ou do `FOR SELECT`, antes de avaliar a condição ou de buscar a próxima linha. O step over (F6) vai ao próximo statement da mesma Rotina, ou de quem a chamou quando ela termina. O resume é o F8.
- A camada de debug só para nas Rotinas que acompanha. Uma procedure de `FOR SELECT` interpretada no step over não para, nem com breakpoint.
- O valor digitado na view Variables vai ao servidor como texto (`CAST(CAST(? AS VARCHAR(...)) AS <tipo>)`). Por isso vale o que um `CAST` aceita, como `'17.10.2026'` numa `DATE`. `NULL`, sem aspas, é o valor nulo. Num erro de conversão, a variável fica como estava.
- Enquanto a execução está parada, a thread dela é a única que usa a conexão. A edição de uma variável (e, depois, os watches) roda nessa thread, pedida pela UI (`ExecutionControl.whilePaused`).

### 3. Fim

Terminando ou abortando, a transação faz commit ou rollback, conforme o que você escolheu no launch.

## Step into em outra Rotina

```sql
-- dentro de P_PEDIDO
EXECUTE PROCEDURE P_ESTOQUE(:ID);      -- Step into elegível: entra em P_ESTOQUE
X = F_DESCONTO(:VALOR);                -- Step into elegível: a chamada é a expressão inteira
INSERT INTO LOG SELECT F_DESCONTO(1) FROM RDB$DATABASE;  -- Executado no servidor
```

- Nas duas primeiras linhas, o step into abre a outra Rotina, que aparece como um novo frame na pilha.
- Na terceira, a chamada está dentro de uma Fatia DML e roda inteira no servidor, sem step into. A linha mostra o aviso "Executado no servidor".

## Triggers

Uma trigger **não** é alcançada por step into a partir de um `INSERT`. Ela só é depurada como **Trigger isolada**. No diálogo de launch você informa os valores de `NEW.*` e `OLD.*` e escolhe qual evento simular (`INSERTING`, `UPDATING` ou `DELETING`).

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
| Sessão de debug e pausa (breakpoint, step over, tarefas na pausa) | `interp/DebugSession`, `StatementHook`, `ExecutionControl`; linhas com statement em `psql/StatementLines` |
| Modelo de debug do Eclipse (pilha, variáveis, breakpoints) | `debug/PsqlDebugTarget`, `PsqlThread`, `PsqlStackFrame`, `PsqlVariable`, `PsqlLineBreakpoint`, `PsqlSourceLocator` |
| Editor do DBeaver (ruler, linha da pausa) | `editor/EditorBreakpoints`, `RoutineEditors`, `PsqlModelPresentation`, handlers do `plugin.xml` |
| Launch | `launch/DebugRoutineHandler`, `RoutineLaunchDialog`, `RoutineLauncher` |
| Fonte da Rotina | `source/RoutineSourceLoader` |
| Packages temporários (sub-rotinas) | `interp/TemporaryPackages` |
