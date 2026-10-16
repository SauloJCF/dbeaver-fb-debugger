# 02 — Tracer bullet do Interpretador + harness de Teste diferencial

Status: ready-for-agent
Blocked by: 01

Spec: `../spec.md` · ADRs: 0001, 0002, 0005

## O que construir

O caminho mais fino de ponta a ponta, sem UI:

- Gramática ANTLR4 inicial da camada procedural: cabeçalho de procedure executável (parâmetros de entrada e `RETURNS`), `DECLARE VARIABLE` com tipo e valor inicial, atribuição, `BEGIN/END`. AST com linha e coluna por statement.
- Carregar a Fonte do banco de uma procedure (`RDB$PROCEDURES`, `RDB$PROCEDURE_PARAMETERS`) e os tipos declarados.
- Interpretador que executa atribuições via Avaliação de expressão (`SELECT <expr> FROM RDB$DATABASE`, variáveis como `CAST(? AS <tipo>)`), numa conexão JDBC dedicada (Jaybird) com uma transação.
- Ao fim: parâmetros de saída e rollback (ou commit, se pedido).
- Harness de Teste diferencial: executa a procedure nativamente (`EXECUTE PROCEDURE`) e no Interpretador, cada uma com rollback, e compara as saídas. Rotinas de exemplo criadas num banco de teste FB5 local pelo próprio setup do teste.

## Critérios de aceite

- [x] Procedure com parâmetros, `DECLARE` e atribuições com aritmética `NUMERIC(18,4)`, `NULL`, `SUBSTRING`, `CAST`, `DATEADD` produz as mesmas saídas no Interpretador e no servidor.
- [x] O harness falha de forma legível quando as saídas divergem (mostra valor nativo × interpretado).
- [x] Os testes rodam no `mvn verify` contra o FB5 local; host, banco e credenciais vêm de configuração, não do código.
- [x] O núcleo do Interpretador não depende de nenhuma classe de UI do Eclipse/DBeaver.

## Notas

- O banco de teste é dedicado aos testes (criado/recriado pelo setup), nunca um banco de produção.

## Comments

### 2026-10-14 — implementação (agente)

**Onde ficou** (tudo no bundle `dev.fbdebug.dbeaver`)
- Gramática: `antlr4/.../psql/parser/PsqlLexer.g4` e `PsqlParser.g4`. O `antlr4-maven-plugin` gera o parser em `target/generated-sources/antlr4/`, que o `build.properties` inclui como fonte. Tipos e expressões são sequências opacas de tokens (Modo Careful).
- Núcleo: `psql` (parser e `RoutineParser`), `psql.ast` (records com `SourcePosition` linha/coluna, base 1), `interp` (`Interpreter`, `ExpressionEvaluator`, `Frame`, `DebugSession`), `source` (`RoutineSourceLoader`). O `CoreIndependenceTest` falha se algum desses pacotes importar `org.eclipse`, `org.jkiss` ou `org.osgi`.
- Runtime ANTLR embutido em `lib/antlr4-runtime-4.13.2.jar` (`Bundle-ClassPath`). É o mesmo jar que o DBeaver traz (SHA-1 idêntico).
- Testes: JUnit puro em `test/` (e não em `src/test/java`, porque `src/` inteiro é pasta de fontes do plugin e os testes iam parar no jar), rodados pelo `maven-surefire-plugin` na fase `test`. Config do banco em `fbdebug-test.properties` na raiz (fora do git; modelo em `fbdebug-test.properties.example`). O banco é recriado por `FBManager` a cada execução da suíte. Jaybird 6.0.7 só nos testes.
- Harness: `DifferentialHarness.assertSameOutputs(proc, args...)`. A comparação (`OutputComparison`) mostra uma linha `SAIDA: nativo=... (Tipo) × interpretado=... (Tipo)` por divergência.

**Como funciona a avaliação**
- Atribuição: `SELECT CAST((<expr>) AS <tipo do alvo>) FROM RDB$DATABASE`, com cada variável como `CAST(? AS <tipo>)`. O `CAST` externo reproduz a conversão da atribuição (arredondamento de escala, padding de `CHAR`, string → número).
- Parâmetros de entrada: `SELECT CAST(? AS <tipo>)`, a mesma conversão do lado do cliente que o `EXECUTE PROCEDURE ?` nativo faz. Parâmetros que faltam no fim usam o padrão (`= valor`/`DEFAULT`).
- Quem decide se um nome da expressão é variável é o escopo, na hora de avaliar. Identificadores seguidos de `(` ou ligados por `.` nunca são candidatos.

**Prova de que o harness pega divergência:** tirei o `CAST` para o tipo do alvo de propósito e rodei a suíte. Falharam DT_NUMERIC (escala 8 × 4), DT_SUBSTRING (`CHAR(5)` sem padding), DT_CAST (`'12.345'` ficou String) e DT_DECLARE (`Long` × `Integer`), todos com a mensagem legível. Depois restaurei o código.

**Achados para as próximas issues**
- O cabeçalho não existe como texto no banco. O `RoutineSourceLoader` monta `CREATE PROCEDURE "X" (...) RETURNS (...) AS` a partir de `RDB$PROCEDURE_PARAMETERS`/`RDB$FIELDS` e emenda o `RDB$PROCEDURE_SOURCE` intacto. **As linhas do AST são deste texto**: o corpo começa depois de 2 ou 3 linhas de cabeçalho, que não batem com o DDL que o editor do DBeaver mostra. A issue 14 precisa mapear isso.
- O Firebird recusa `DECLARE ... = <expressão com parâmetro>` (só aceita literal ou variável de contexto). A gramática aceita qualquer expressão, o que não faz mal porque a Fonte já foi compilada.
- Domínio com `NOT NULL`/`CHECK`: `CAST(NULL AS dominio)` deve falhar no servidor, e isso afeta variáveis desse domínio enquanto valem `NULL` (ex.: saída ainda não atribuída). Não foi testado. Fica para a issue 07, junto com o mapeamento de erros (hoje o `NOT NULL` de variável dá uma `PsqlExecutionException` com a mensagem do motor).
- Palavras como `FROM`/`FOR`/`DAY` saem como candidatas a variável e só são trocadas se existir variável com esse nome. Uma variável chamada `DAY` usada em `DATEADD(DAY, ...)` seria trocada errado. Caso raro, por isso não foi tratado.
- Este servidor tem `DataTypeCompatibility = 2.5` no `firebird.conf`. Os testes passam assim, mas os tipos que o cliente vê (BOOLEAN, INT128, DECFLOAT, TIME ZONE) dependem dessa configuração.
- Nesta máquina o `JAVA_HOME` ainda aponta para o JDK 17. O build foi rodado com `JAVA_HOME=C:\Program Files\Java\jdk-21.0.12.1`.
