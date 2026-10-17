# 11 — Sub-rotinas internas e Package temporário de debug

Status: ready-for-agent
Blocked by: 10

ADR: 0003

## O que construir

- Gramática para `DECLARE PROCEDURE` / `DECLARE FUNCTION` internas.
- Ao iniciar a sessão, criar o Package temporário `DBG$<id>` com cópias das sub-rotinas; reescrever chamadas a sub-rotinas dentro de expressões e Fatias DML para `DBG$<id>.nome`.
- Chamadas elegíveis a sub-rotinas podem ser interpretadas (step into) como na issue 10.
- Ao encerrar (normal, erro ou abort), remover o package. Ao abrir sessão, remover packages `DBG$%` que sobraram.

## Critérios de aceite

- [x] Teste diferencial com sub-function usada em expressão condicional (`CASE`, `COALESCE`) — a sub-function só roda quando o motor a rodaria.
- [x] Após qualquer forma de encerramento, `RDB$PACKAGES` não tem `DBG$%`.
- [x] Simulação de crash (processo morto com package criado) seguida de nova sessão: resto removido.

## Notas

- O DDL do package roda fora da transação da sessão (DDL precisa de commit para ficar visível); garantir que isso não comita nada da Rotina.

## Comments

### 2026-10-17 — implementação (agente)

**Achado que muda o ADR 0003 (revisado; decisão aberta ao humano).** O ADR partia de "sub-rotinas do Firebird não acessam variáveis da Rotina-mãe". No Firebird 5 isso é falso, conferido no servidor: a sub-rotina **lê e altera** os parâmetros de entrada e de saída da Rotina e as variáveis locais declaradas **antes** dela. Não enxerga os cursores da Rotina nem, numa trigger, `NEW`/`OLD`/eventos ("Column unknown"/"Token unknown" na compilação). Parâmetro ou variável da própria sub-rotina com o mesmo nome esconde o da Rotina. A cópia no package não tem essas variáveis. Por isso:
- sub-rotina que não usa variáveis da Rotina (nem chamando irmã que usa) vai para o package e funciona em qualquer chamada;
- sub-rotina que usa fica **fora** do package. Chamada elegível (`EXECUTE PROCEDURE`, `x = F(...)`, `FOR SELECT`) é sempre interpretada (no step over, com `Frame.steppedInto()` falso, como a rotina privada da issue 10). Chamada dentro de expressão ou Fatia DML é **recusada pelo Pré-check** (`UnsupportedConstruct.SUBROUTINE_USING_PARENT_VARIABLES`, na posição da chamada).
Isso reduz o critério 5 da spec ("sub-rotina chamada em expressão funciona") às sub-rotinas que não usam variáveis da Rotina. As alternativas (variáveis como parâmetros extras, `RDB$SET_CONTEXT`) estão no ADR, com o motivo de não terem entrado.

**Onde ficou**
- Gramática: `subroutineDeclaration` (`DECLARE PROCEDURE|FUNCTION nome <assinatura> AS <corpo>` ou `... ;`, a declaração antecipada) entre as declarações. Sub-rotina dentro de sub-rotina é recusada (o Firebird também recusa: "nested sub function").
- AST: `ast.Subroutine(routine, header, implementation, position)` e `RoutineDefinition.subroutines()`/`subroutine(kind, nome)`. A sub-rotina é uma `RoutineDefinition` sem package, montada com estado próprio no `AstBuilder` (construções recusadas, `SUSPEND`, contexto de trigger não vazam para a Rotina). Com declaração antecipada, os valores padrão das entradas vêm dela (o Firebird não aceita padrão na implementação de sub-rotina já declarada). `header` tem a assinatura com os padrões (cabeçalho do package); `implementation`, a assinatura sem os padrões e o corpo (corpo do package, que também não aceita padrão de rotina já declarada no cabeçalho).
- `psql.SubroutineScopes`: `parentVariables(rotina, sub)` (o que a sub-rotina enxerga) e `usingParentVariables(rotina)` (quem usa, transitivo pelas chamadas entre irmãs; comparação por nome, conservadora).
- `CallSites.of` inclui as chamadas dos corpos das sub-rotinas (mesmo texto). `PreCheck.scan` inclui as construções recusadas dos corpos delas e a recusa acima.
- Interpretador: `Frame.parent()` (frame da Rotina que declara a sub-rotina) e `Frame.find` cai nas variáveis visíveis dele; uma atribuição a variável da Rotina confere também os cursores abertos dela (`CURSOR_VARIABLE_CHANGED`). Dentro de sub-rotina, nenhuma package é o contexto (como no motor). `Interpreter.callee` procura primeiro a sub-rotina (ela esconde a Rotina avulsa com o mesmo nome, conferido no servidor), depois o catálogo.
- `interp.TemporaryPackages`: um `DBG$<CURRENT_CONNECTION>_<n>` por Rotina com sub-rotinas (a raiz e cada Rotina alcançada por step into), criado na primeira chamada no servidor que precisa dele (`Frame.serverText`/`serverName` reescrevem `SUB(` para `"DBG$..".SUB(`). DDL por `EXECUTE STATEMENT ... WITH AUTONOMOUS TRANSACTION` na própria conexão da sessão. `dropAll()` e `sweep()`, que remove só os packages de conexões que não estão em `MON$ATTACHMENTS`.
- `DebugSession`: varre ao abrir (antes do `setAutoCommit(false)`), remove os packages ao terminar (depois do commit/rollback), no erro e no `close()`. O `Interpreter` ganhou o construtor com `TemporaryPackages` (quem cria remove); o `DifferentialHarness` remove depois do rollback.
- Testes: `SubroutineParserTest` (9), `SubroutineDifferentialTest` (8: `CASE`/`COALESCE`/`IF`/DML, sub-procedures em `FOR SELECT`, `EXECUTE PROCEDURE`, DML e irmã chamando irmã, variáveis da Rotina lidas e alteradas inclusive com a selecionável parada no `SUSPEND`, erro tratado por quem chama, sub-rotinas de Rotina alcançada por step into com o mesmo nome das da raiz, rotina de package, `EXECUTE BLOCK`, trigger, cursor aberto da Rotina), `TemporaryPackagesTest` (6: fim normal, commit, erro, abort, crash, sessão viva).

**Comportamento do FB5 conferido no servidor**
- O DDL autônomo fica visível na transação da sessão, inclusive em `SNAPSHOT`, e o package pode ser removido com a transação ainda aberta e depois de usado nela. O DDL não commita nada da sessão (`TemporaryPackagesTest` confere a tabela depois do rollback).
- `END;` depois do corpo de sub-rotina e de rotina de package não compila.
- Num package, function e procedure podem ter o mesmo nome (sub-rotinas também).
- Selecionável parada no `SUSPEND` vê a variável da Rotina que o corpo do laço alterou (compartilhada, não copiada na chamada).

**Prova de que os testes pegam divergência:** de propósito, tirei a reescrita para o package (13 falhas), o acesso às variáveis da Rotina (falha em `subroutinesReadAndChangeTheVariablesOfTheirRoutine`) e a precedência da sub-rotina sobre a Rotina avulsa (2 falhas e 1 erro). Depois restaurei.

**Para as próximas issues**
- Issue 13: o abort por Terminate precisa ser cooperativo (o Interpretador para entre statements e o erro sobe pelo caminho normal). Fechar a conexão no meio da execução impede o `DROP` e deixa o package para a varredura da próxima sessão.
- Issue 15: `Frame.variables()` mostra só as da sub-rotina; as da Rotina que ela enxerga estão em `Frame.parent()` (filtrar por `SubroutineScopes.parentVariables`).
- Issue 17: step into em sub-rotina já passa pela `CallPolicy` com o `CallSite` dela. O frame tem `routine().name()` sem package e posições no texto da Rotina que a declara (mesmo editor).

**Não tratado**
- Chamada a sub-rotina sem parênteses depois de `FROM` com o nome de uma tabela é tratada como a sub-procedure. Uma chamada a nome de sub-rotina conta para `usingParentVariables` qualquer que seja o tipo, e um nome solto em subconsulta de expressão conta como variável. As duas coisas podem recusar a mais, nunca a menos.
- Privilégios da cópia: não conferido com usuário sem privilégios diretos nas tabelas.
