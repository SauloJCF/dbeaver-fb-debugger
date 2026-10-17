# 09 — `EXECUTE BLOCK`, functions e rotinas de package como Rotina raiz

Status: ready-for-agent
Blocked by: 03

## O que construir

Carregar Fonte do banco, parâmetros e tipos de:
- function (`RDB$FUNCTIONS`, `RDB$FUNCTION_ARGUMENTS`) — `RETURN` com valor;
- rotinas de package (corpo em `RDB$PACKAGES` / registros por rotina);
- `EXECUTE BLOCK` (texto fornecido, sem metadados; parâmetros declarados no próprio bloco).

## Critérios de aceite

- [x] Teste diferencial para cada tipo: function chamada nativamente via `SELECT f(...) FROM RDB$DATABASE`, procedure de package via `EXECUTE PROCEDURE pkg.sp`, `EXECUTE BLOCK` executado direto.
- [x] Linhas e colunas do AST batem com o texto que será exibido no editor do DBeaver para aquele objeto (conferir offset do cabeçalho gerado pelo DBeaver). *(Conferido contra o código do DBeaver 26.0.2, não no editor aberto; ver comentários.)*

## Comments

### 2026-10-16 — implementação (agente)

**Onde ficou**
- Gramática: regra de entrada `routineDefinition` (procedure, function ou `EXECUTE BLOCK`) e `packageDefinition` (cabeçalho ou corpo de package). Tokens `FUNCTION`, `RETURN`, `DETERMINISTIC` (reservados) e `BLOCK`, `PACKAGE`, `BODY` (não reservados, também na regra `identifier`). Statement `RETURN valor;`. O tipo de retorno de function tem regra própria (`returnType`), porque o `typeSpec` dos parâmetros comeria o `AS`.
- AST: `ProcedureDefinition` virou `RoutineDefinition`, com `kind` (`PROCEDURE`, `FUNCTION`, `EXECUTE_BLOCK`), `packageName`, `returnValue` (tipo de retorno da function), `selectable` (tem `SUSPEND`) e `displayName()`. `RoutineParser.parseProcedure` virou `RoutineParser.parse`; `RoutineParser.parsePackageRoutine(cabeçalho, corpo, tipo, nome)` monta a rotina de package.
- Fonte do banco (`RoutineSourceLoader`): `loadProcedure`, `loadFunction` (`RDB$FUNCTIONS`/`RDB$FUNCTION_ARGUMENTS`; o argumento de posição `RDB$RETURN_ARGUMENT` é o retorno) e `loadPackage`, que devolve cabeçalho e corpo (`RDB$PACKAGES`). `EXECUTE BLOCK` não tem metadados: o texto é a Fonte.
- Interpretador: `RETURN` converte para o tipo de retorno (`CAST`, como numa atribuição) e termina a function; `Interpreter.execute` de uma function devolve uma saída só, com o nome da function. Parâmetros de `EXECUTE BLOCK` são `nome tipo = ?`, sempre informados.
- Harness: `assertSameResult` (function), `assertSamePackageOutputs`/`assertSamePackageRows`/`assertSamePackageResult` (package) e `assertSameExecuteBlock`.
- Testes: `RootRoutineDifferentialTest` (7 casos), `RootRoutineParserTest` (7), e 2 testes novos no `RoutineSourceLoaderTest` (formato do cabeçalho de cada forma, inclusive `SQL SECURITY`, e posições).
- `SQL SECURITY DEFINER | INVOKER` de function é aceito e ignorado na execução: a Sessão de debug roda com o usuário dela.

**Linhas e colunas × editor do DBeaver.** O editor do objeto mostra o texto de `FireBirdUtils.getProcedureSourceWithHeader` (plugin `org.jkiss.dbeaver.ext.firebird`): `CREATE OR ALTER PROCEDURE NOME (entradas)`, `RETURNS (`, uma saída por linha com tabulação, `)`, `AS` e o corpo de `RDB$PROCEDURE_SOURCE` intacto. Para function, `RETURNS tipo` numa linha só. Sem entradas, o `RETURNS (` (ou o `AS`) fica na linha do `CREATE`. Li o fonte da tag `26.0.2` no GitHub e conferi pelo bytecode (`javap`) do jar instalado (`1.0.289.202610041844`), que é mais novo que a tag: ele acrescenta, **só em function** com `RDB$SQL_SECURITY` preenchido (FB4+), uma linha `SQL SECURITY DEFINER|INVOKER` antes do `AS`. Procedure nunca ganha essa linha. O loader e a gramática tratam esse caso. O `RoutineSourceLoader` agora monta o cabeçalho com essas mesmas quebras de linha (o texto dos tipos pode ser outro). Assim o corpo começa na mesma linha do editor e as posições do AST no corpo são as do editor. O `CursorDifferentialTest` que citava "linha 4" passou a "linha 6", que é a linha do editor. **Não conferido:** o texto do editor aberto de verdade. Um default com quebra de linha mudaria a conta só se o DBeaver o imprimisse diferente. Fica para conferir à mão na issue 14, com o editor aberto.

**Package e `EXECUTE BLOCK` no DBeaver**
- O DBeaver CE 26.0.2 **não tem editor de package para Firebird**: o plugin não tem classe de package, e as rotinas de package não aparecem. A Fonte do banco de uma rotina de package é o corpo inteiro (`RDB$PROCEDURE_SOURCE`/`RDB$FUNCTION_SOURCE` são `NULL` nelas). O texto montado é `RECREATE PACKAGE BODY "PKG"` / `AS` / corpo gravado, e as posições são desse texto. A issue 18 precisa decidir onde mostrar esse texto (o editor SQL, por exemplo) e como o usuário escolhe a rotina.
- `EXECUTE BLOCK`: as posições são do texto recebido (linha 1 é a primeira linha dele). A issue 18 precisa somar a linha (e, na primeira linha, a coluna) em que a seleção começa no editor SQL.

**Comportamento do FB5 conferido no servidor**
- Function: `RETURN` converte para o tipo de retorno (`1.26` → `1.3` em `NUMERIC(5,1)`, `12.5` → `'12.5'`, truncamento é erro). Sem `RETURN` (fim do corpo ou `EXIT`) devolve `NULL`. `RETURN` é reservada; `RETURN` em procedure não compila.
- Retorno `NOT NULL`: `RETURN NULL` dá `isc_not_valid_for` ("validation error for variable number 1"), que um `WHEN` pode tratar. Terminar sem `RETURN` dá o mesmo erro, fora do alcance do `WHEN`. **Peculiaridade do motor:** na mesma conexão, depois que um `WHEN` tratou esse erro uma vez, a mensagem do erro do fim passa a ser "output parameter number 1". A Sessão de debug e o harness começam sempre em conexão nova, então o Interpretador usa a primeira.
- Package: o cabeçalho e o corpo ficam em `RDB$PACKAGES` (`RDB$PACKAGE_HEADER_SOURCE`, `RDB$PACKAGE_BODY_SOURCE`). Os valores padrão dos parâmetros ficam só no cabeçalho; o Interpretador os junta à rotina do corpo. Não existe `CREATE OR ALTER PACKAGE BODY`, só `CREATE` e `RECREATE`. `PACKAGE`, `BODY` e `BLOCK` não são reservadas; `FUNCTION`, `PROCEDURE` e `DETERMINISTIC` são.
- **`EXECUTE BLOCK` sem `SUSPEND` não devolve linha**, nem com `RETURNS`: o resultado nativo é vazio (isql e Jaybird). Ou seja, um `EXECUTE BLOCK` é sempre comparado como selecionável, e as saídas dele só aparecem pelo `SUSPEND`. Para a issue 18: o resultado de um bloco sem `SUSPEND` é vazio, e o que o usuário vê das saídas é a view Variables. Parâmetro de entrada só aceita `= ?` (literal dá "Token unknown").
- `POSITION` é reservada (nem como alias de coluna).

**Prova de que os testes pegam divergência:** de propósito, converti o `RETURN` para `VARCHAR(100)` em vez do tipo de retorno, fiz o `RETURN` não terminar a function, tirei a validação de `NOT NULL` do `RETURN` e do fim, e ignorei os valores padrão do cabeçalho da package. Cada mudança derrubou pelo menos um teste. Depois restaurei o código.

**Para as próximas issues**
- Issue 10: dentro do corpo de uma package, chamada sem qualificar (`SOMA(...)`, `EXECUTE PROCEDURE SOMA`) só resolve dentro da package. Uma Avaliação de expressão (`SELECT SOMA(?) FROM RDB$DATABASE`) daria "Function unknown". Os testes desta issue só usam rotinas de package que não chamam outras.
- Issue 13: `DebugSession.run`/`select` já recebem qualquer `RoutineDefinition`; a escolha entre executável e selecionável pode vir de `RoutineDefinition.selectable()` (igual a `RDB$PROCEDURE_TYPE` = 1). `EXECUTE BLOCK` sempre como selecionável.

**Não tratado (casos raros)**
- Rotinas externas (`EXTERNAL NAME ... ENGINE`): sem Fonte do banco, o carregamento falha com mensagem.
- Function com `NOT NULL` num parâmetro de entrada recebendo `NULL`: a mensagem do motor para function não foi conferida (o Interpretador usa a de variável).
