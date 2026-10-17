# 10 — Chamadas entre Rotinas no Interpretador

Status: ready-for-agent
Blocked by: 06, 09

## O que construir

- Classificar cada chamada como **Step into elegível** (`EXECUTE PROCEDURE`, `FOR SELECT … FROM sp(...)` com a procedure como única fonte, `x = F(...)` com a chamada como expressão inteira) ou **Executado no servidor** (qualquer chamada dentro de Fatia DML ou de expressão maior).
- Chamadas elegíveis podem ser interpretadas (pilha de frames, Fonte do banco da Rotina chamada) ou executadas no servidor — a escolha vem de quem controla a sessão (step into × step over). Selecionável chamada por `FOR SELECT` interpretada como gerador.
- Expor a pilha de frames (Rotina, linha, variáveis) para a camada de debug.

## Critérios de aceite

- [x] Teste diferencial com cadeia A → B → C, todas interpretadas, igual ao nativo.
- [x] Mesma cadeia com B executada no servidor dá o mesmo resultado.
- [x] A classificação de cada chamada é testada por unidade, incluindo os casos-limite da spec (`INSERT … SELECT F(x)`, `WHERE id IN (SELECT … FROM sp)`).

## Comments

### 2026-10-17 — implementação (agente)

**Onde ficou**
- Gramática: statement `EXECUTE PROCEDURE ...` (texto até o `;`, separado pelo `AstBuilder`: nome com ou sem package, argumentos com ou sem parênteses, `RETURNING_VALUES` com ou sem parênteses).
- AST: `ExecuteProcedure`; `Assignment.call` (`FunctionCall`, quando a expressão é inteira `[pkg.]F(...)`); `ForSelect.procedure` (`ProcedureSource`: `SELECT colunas FROM [pkg.]sp[(args)] [alias]`, nada mais; colunas são saídas com alias opcional, ou `*`). Nova parte de expressão `Expression.Call`: candidato a chamada (`[pkg.]nome(`, ou numa Fatia DML `FROM|JOIN nome` sem parênteses). Palavras reservadas do FB5 (`AND (`, `CAST(`, `FROM (`...) e nomes de sistema (`RDB$`, `MON$`, `SEC$`) não são candidatos.
- Classificação: `CallSites.of(rotina)` lista `CallSite(form, package, nome, posição)`; `form` é `EXECUTE_PROCEDURE`, `FOR_SELECT` ou `ASSIGNMENT` (elegíveis) ou `EXPRESSION`/`DML` (executados no servidor). É sintática: `COALESCE(` ou uma tabela depois de `FROM` aparecem como candidatos, e só o banco diz o que é Rotina.
- Interpretador: `CallPolicy` (quem controla a sessão decide, a cada chamada elegível, step into ou step over; recebe o `CallSite` e o frame de quem chama) e `RoutineCatalog`/`DatabaseRoutineCatalog` (resolve o nome como o motor — dentro de package, nome sem package é primeiro rotina da package —, carrega e parseia a Fonte do banco uma vez por sessão). Rotina chamada que não é PSQL com Fonte do banco, não parseia ou não passa no Pré-check vai ao servidor.
- Pilha: `Interpreter.frames()` (do topo à raiz), `Frame.routine()`, `Frame.position()` (statement em execução), `Frame.variables()`, `Frame.steppedInto()`. `DebugSession` ganhou o construtor com `CallPolicy` e `frames()`.
- Gerador: `FOR SELECT` sobre procedure interpretada roda o corpo do laço dentro do `SUSPEND` da procedure (sem thread). Durante o corpo, a procedure parada sai de `frames()` e não tem savepoint aberto no servidor (`SavepointStack.suspended()`/`resumed()`); `LEAVE`/`EXIT`/erro do corpo atravessam a procedure parada como `LoopExit` (que nenhum `WHEN` dela trata) e a abandonam, fechando os cursores dela. `AS CURSOR` funciona (`Cursor.openRows`).
- Savepoints: a chamada interpretada tem pilha própria; o que ela altera passa para o nível de quem chama (`SavepointStack.takeChanges()`), o que decide se um `FOR SELECT` seguinte ganha nível próprio.
- Package: chamada sem package a rotina da package, numa expressão ou Fatia DML enviada ao servidor, ganha o nome da package (`Frame.serverText`); num `EXECUTE PROCEDURE` no servidor também (`Frame.serverName`). Sem isso, `SOMA(1)` fora da package seria "Function unknown" — ou, pior, uma function avulsa com o mesmo nome (o teste tem uma `TRIPLO` avulsa para pegar isso).
- Harness: `DifferentialHarness.calls(policy)`. Testes: `CallDifferentialTest` (10 casos, cada um com várias políticas: tudo interpretado, tudo no servidor, só B no servidor, só C no servidor), `CallParserTest` (8), `CallSitesTest` (2).

**Decisão: `FOR SELECT` sobre procedure é sempre interpretado quando dá.** No step over a procedure também é interpretada, sem a camada de debug parar nela (`Frame.steppedInto()` falso). Motivo, conferido nos testes: um cursor no cliente não é fiel ao motor. O servidor remoto busca linhas à frente (lote do Jaybird e read-ahead do próprio servidor), então uma selecionável com DML roda além do ponto em que o laço sai (`LEAVE`, `EXIT`, erro do corpo), e o DML dela fica. E num erro da procedure no meio do lote o Jaybird descarta as linhas boas que vieram junto, e o corpo do laço não as vê. `setFetchSize(1)` não resolve (o servidor continua lendo à frente e a última linha boa ainda se perde) e ainda muda o que o cursor vê do que o próprio laço insere (`CUR_DESVIOS` quebrou); foi desfeito. Pelo mesmo raciocínio, rotina **privada** da package de quem chama também é interpretada no step over: fora da package ela não existe. `EXECUTE PROCEDURE` e `x = F()` no step over vão de fato ao servidor (uma linha só: são fiéis).

**Comportamento do FB5 conferido no servidor**
- `RETURNING_VALUES` precisa ter exatamente o número de saídas, e `EXECUTE PROCEDURE` de procedure com saídas sem `RETURNING_VALUES` não compila ("Output parameter mismatch").
- `EXECUTE PROCEDURE` de selecionável, de dentro de PSQL, devolve as saídas do primeiro `SUSPEND` e o que ela alterou até ali fica (igual ao da raiz, issue 06).
- Argumento é convertido para o tipo do parâmetro (`:N + 0.4` num `INTEGER` vira o inteiro). Parâmetro `NOT NULL` recebendo `NULL` numa chamada aninhada dá o mesmo erro tratável que na raiz.
- Erro dentro da Rotina chamada desfaz o que ela fez (procedure e function), e quem chama trata com `WHEN` como qualquer erro; o que o bloco de quem chama já tinha feito fica.
- Erro no corpo do laço com a procedure parada: o `WHEN` de fora do laço desfaz o corpo em andamento; o que a procedure fez antes do `SUSPEND` e as iterações anteriores ficam (bloco sem alterações antes do `FOR`).
- O `FOR SELECT` ganha nível de savepoint só se o bloco já alterou algo — inclusive por uma Rotina chamada. Com o nível, um erro tratado pelo bloco desfaz **todas** as iterações; sem ele, só a em andamento (`CH_NIVEL_FOR`: com a mutação que ignora as alterações da Rotina chamada, o Interpretador guardava a iteração 1 e o nativo não).

**Prova de que os testes pegam divergência:** de propósito, tirei a qualificação das chamadas dentro da package, o desfazer da chamada que falha, o esconder da procedure parada na pilha, a passagem das alterações da Rotina chamada para quem chama, a conversão dos argumentos para o tipo do parâmetro, o `LEAVE` no gerador, o soltar do savepoint da chamada durante o `SUSPEND` e a interpretação da rotina privada no step over. Cada mudança derrubou pelo menos um teste. Depois restaurei o código. (A da conversão dos argumentos só aparecia no valor da variável que a view Variables mostraria — toda leitura de variável vai ao servidor com `CAST` para o tipo declarado —, então o teste da pilha confere esse valor.)

**Para as próximas issues**
- Issue 11: `Expression.Call` e `Frame.serverText` são o ponto para reescrever chamadas a sub-rotinas para `DBG$<id>.nome`. Uma rotina privada de package usada **dentro de expressão** (não elegível) ainda falha no servidor; o package temporário pode hospedar cópias delas também.
- Issues 13/14: a pausa precisa de um gancho por statement (`Frame.position()` já é atualizado no começo de cada um). Não parar em frames com `steppedInto()` falso. O `CallPolicy` da sessão decide pelo comando do usuário (step into na linha × resto).
- Issue 17: `CallSites` dá as posições para o aviso "Executado no servidor"; os nomes são candidatos e precisam ser confirmados no banco (`RoutineCatalog.find`).

**Não tratado (aproximações conhecidas)**
- `EXECUTE PROCEDURE` no step over conta sempre como alteração para o nível de quem chama (como o `EXECUTE STATEMENT`); se a procedure não alterou nada, um `FOR SELECT` logo depois, no mesmo bloco, ganharia um nível que o motor não dá. Function chamada numa expressão no servidor, ao contrário, nunca conta como alteração.
- `FOR SELECT` sobre procedure com outras cláusulas (`WHERE`, `ORDER BY`, join), sobre procedure externa ou que não passa no Pré-check usa o cursor do servidor, com o problema da busca à frente descrito acima.
- Não conferido: com cursor do servidor sobre tabela com mais linhas que um lote do Jaybird (400), DML no corpo sobre a mesma tabela pode ficar visível para os lotes seguintes.
