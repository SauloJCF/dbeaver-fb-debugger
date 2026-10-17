# 12 — Trigger isolada no Interpretador

Status: ready-for-agent
Blocked by: 07, 09

## O que construir

- Carregar Fonte do banco de trigger de tabela (`RDB$TRIGGERS`), incluindo triggers universais (`BEFORE INSERT OR UPDATE OR DELETE`).
- `NEW.*` e `OLD.*` como entradas tipadas pelas colunas da tabela; `INSERTING`/`UPDATING`/`DELETING` como entradas boolean (padrão `FALSE`).
- Em `BEFORE`, atribuições a `NEW.campo` aparecem como saída da sessão.

## Critérios de aceite

- [x] Teste diferencial: executar o DML nativo que dispara a trigger e comparar com o Interpretador alimentado com os mesmos `NEW`/`OLD` — mesmo estado das outras tabelas tocadas e mesmos `NEW.*` finais (lidos da linha gravada).
- [x] Trigger de banco/DDL é recusada pelo Pré-check.

## Comments

### 2026-10-17 — implementação (agente)

**Onde ficou**
- Gramática: `CREATE [OR ALTER] | RECREATE TRIGGER nome <cabeçalho> AS corpo` (token `TRIGGER`). O cabeçalho é separado pelo `AstBuilder`: `FOR tabela [ACTIVE|INACTIVE] {BEFORE|AFTER} evento [OR evento ...] [POSITION n]`, a forma do SQL padrão (`... ON tabela`) e `SQL SECURITY` no fim. Atribuição a `NEW.campo` e alvo `INTO NEW.campo`.
- AST: `RoutineDefinition.Kind.TRIGGER` e `RoutineDefinition.trigger()` (`Trigger(tabela, before, eventos)`). Trigger de banco (`ON CONNECT`...) e de DDL viram construções recusadas (`UnsupportedConstruct.DATABASE_TRIGGER`, `DDL_TRIGGER`), na posição do `ON` ou da fase.
- Entradas: `RoutineParser.parseTrigger(texto, colunas)` dá à trigger as entradas `NEW.<coluna>`, `OLD.<coluna>` (na ordem da tabela) e `INSERTING`, `UPDATING`, `DELETING` (`BOOLEAN`). Sem valor, `NEW`/`OLD` valem `NULL` e os eventos `FALSE`. Nenhuma é `NOT NULL`, e o tipo é o tipo base da coluna (não o domínio): o motor só valida a linha ao gravá-la, depois da trigger. Assim a trigger roda pelo mesmo `Interpreter.execute`/`DebugSession.run` das outras Rotinas, e a view Variables vai mostrar `NEW.NOME` etc. como entradas.
- Dentro de uma trigger, numa Fatia DML, `NEW.campo`/`OLD.campo` e os eventos são referências mesmo sem `:` (como o motor aceita). Fora de trigger, `NEW.X` numa Fatia DML continua sendo coluna (alias `NEW`).
- Saída: numa `BEFORE`, `ProcedureResult` com os `NEW.*` finais (nomes `NEW.<coluna>`); numa `AFTER`, vazio.
- Fonte do banco: `RoutineSourceLoader.loadTrigger` devolve o texto e as colunas da tabela (tipo base, de `RDB$RELATION_FIELDS`/`RDB$FIELDS`). O cabeçalho é o de `FireBirdUtils.getTriggerSourceWithHeader` do DBeaver 26.0.2 (lido do bytecode do jar instalado): `CREATE TRIGGER nome FOR tabela BEFORE INSERT OR UPDATE` numa linha só, sem `ACTIVE` nem `POSITION`, e a partir da linha 2 o `RDB$TRIGGER_SOURCE` intacto, que começa no `AS`. Então as linhas do corpo são as do editor. Trigger de banco sai com `ON CONNECT` etc.; de DDL, com `ANY DDL STATEMENT` (o DBeaver nem as mostra; só servem para o Pré-check recusar).
- Harness: `DifferentialHarness.assertSameTrigger(trigger, dml, linhaGravada, entradas)`. Testes: `TriggerDifferentialTest` (7: `BEFORE INSERT OR UPDATE` com DML em outra tabela, `FOR SELECT` usando `NEW` e `INTO NEW.campo`; `UPDATE` com `OLD` e com exceção; `AFTER DELETE`; cabeçalho do SQL padrão com coluna entre aspas; universal com os três eventos; cabeçalho como o editor mostra; triggers de banco e de DDL recusadas), `TriggerParserTest` (7).

**Comportamento do FB5 conferido no servidor**
- `RDB$TRIGGER_SOURCE` começa no `AS` (diferente de `RDB$PROCEDURE_SOURCE`).
- `RDB$TRIGGER_TYPE` (BIGINT): numa trigger de tabela, até três ações (1 = INSERT, 2 = UPDATE, 3 = DELETE) em pares de bits a partir do bit 1, ímpar numa `BEFORE` (17 = `BEFORE INSERT OR UPDATE`, 113 = `BEFORE INSERT OR UPDATE OR DELETE`). De banco: 8192 a 8196. De DDL: bit 0x4000 ligado (16390 = `BEFORE CREATE TABLE OR ALTER TABLE`; `AFTER ANY DDL STATEMENT` dá um número enorme).
- Numa trigger só de `DELETE`, referência a `NEW` não compila ("Column unknown"); numa universal, `NEW` existe e vale `NULL` no `DELETE`.

**Prova de que os testes pegam divergência:** de propósito, deixei `NEW.campo` sem ligar ao valor, devolvi a saída de `NEW` só nas `AFTER` e deixei de ligar os eventos dentro de DML. Cada mudança derrubou pelo menos um teste. Depois restaurei o código. O padrão `FALSE` dos eventos é conferido pelo `TriggerParserTest` (o harness sempre informa todas as entradas).

**Achados de harness**
- O `noCursorIsLeftOpen` (issue 05) oscilava em ±1 statement depois desta issue e da 10, que fazem consultas de metadados durante a execução: um statement fechado às vezes ainda aparece no `MON$STATEMENTS` logo depois, às vezes não (a liberação parece assíncrona; não confirmei a causa). O teste agora espera a contagem parar de mudar antes de comparar; um cursor deixado aberto não some, e o teste continua pegando o vazamento (conferido fechando o cursor de propósito sem liberar o statement).
- A compilação incremental do Tycho não recompila classes que só usam constantes do léxico gerado (o Java as embute). Um token novo no léxico desloca os números e quebra essas classes (o `DynamicSql` quebrou). Depois de mexer no `PsqlLexer.g4`, compilar do zero (apagar `target/classes`).

**Para as próximas issues**
- Issue 19: o diálogo de launch sai das entradas da `RoutineDefinition` de `parseTrigger` (`NEW.*`, `OLD.*`, eventos), com os padrões delas. As colunas vêm de `RoutineSourceLoader.loadTrigger(...).columns()`.

**Não tratado**
- Isolamento numa `AFTER`: no DML nativo a linha já está gravada (ou apagada) quando a trigger roda; a Trigger isolada não grava nada, então uma `AFTER` que lê a própria tabela vê outro estado. É da natureza da Trigger isolada (a spec define `NEW`/`OLD` como entradas); os testes não leem a própria tabela numa `AFTER`.
- Valores padrão de coluna (`DEFAULT`) não entram sozinhos em `NEW.*`: no DML nativo, coluna omitida no `INSERT` chega à trigger com o padrão; na Trigger isolada, chega o que for informado.
- Coluna calculada (`COMPUTED BY`) aparece como entrada como as outras.
