# 15 — Variables: ver e editar parâmetros e variáveis

Status: ready-for-agent
Blocked by: 14

## O que construir

- View Variables mostra parâmetros de entrada, de saída e variáveis locais do frame selecionado, com tipo declarado.
- Editar um valor durante a pausa: o texto digitado é convertido pelo servidor (`CAST(? AS <tipo>)`); erro de conversão é mostrado e o valor anterior é mantido.
- `NULL` editável explicitamente.

## Critérios de aceite

- [x] Alterar uma variável usada num `IF` muda o caminho tomado no próximo step.
- [x] Valor inválido para o tipo (ex.: texto em `INTEGER`) mostra erro e não altera a variável.
- [x] Valores `NUMERIC`, `DATE`, `TIMESTAMP`, `BLOB SUB_TYPE TEXT` e `NULL` exibidos legivelmente.

## Comments

### 2026-10-17 — implementação (agente)

**Estado: código pronto e compilando; a UI não foi testada no DBeaver.** Os critérios de aceite ficam desmarcados até o teste à mão. Fora do OSGi, contra o Firebird local:
- `VariableEditTest` edita de outra thread durante a pausa, como a view Variables. A variável alterada antes do `IF` muda o ramo no step seguinte. Texto inválido (`abc` num `INTEGER`) e `NULL` numa variável `NOT NULL` dão erro, e a variável fica como estava. `10.5` num `INTEGER` vira 11 e `17.10.2026` numa `DATE` funciona. `NULL` sem aspas é o nulo, e `'NULL'` com aspas é texto.
- `ValueTextTest` confere a exibição de `NUMERIC`, `DECFLOAT`, `DATE`, `TIMESTAMP` (com e sem fração), `TIME`, `BLOB SUB_TYPE TEXT`, `CHAR`, `BOOLEAN`, `NULL` e binário. Também confere que o texto exibido, convertido de volta pelo servidor, dá o mesmo valor.

**Onde ficou**
- `ExecutionControl.whilePaused(Callable)`: roda uma tarefa na thread da execução parada, antes do comando que a solta. A conexão e o estado do Interpretador continuam usados por uma thread só.
- `Interpreter.assignText` e `DebugSession.assign`: o texto vai como `CAST(CAST(? AS VARCHAR(n) CHARACTER SET UTF8) AS <tipo>)`, o mesmo caminho do `assignable` para os valores do servidor. `NULL` sem aspas é o nulo. `NOT NULL` e variável usada por cursor aberto valem como numa atribuição.
- `PsqlVariable`: editável na pausa. O `verifyValue` aceita qualquer texto, porque o debug.ui o chama a cada tecla no diálogo "Change Value". O `setValue` espera a tarefa (até 30 s) e, num erro, lança `DebugException` com a mensagem do servidor, que o debug.ui mostra. O tipo exibido é o declarado, com `NOT NULL`. O valor que mudou desde a pausa anterior fica destacado (`hasValueChanged`).
- `ValueText` (`debug`, sem Eclipse): texto dos valores para a view e o console.

**Decisões**
- A conversão é do servidor, não do Jaybird. Com `CAST(? AS INTEGER)` direto, o Jaybird converte o texto no cliente e recusa `10.5` (`NumberFormatException`) e datas como `17.10.2026`. Conferido de propósito: com a conversão do Jaybird, o teste falhou.
- `NULL` sem aspas é o nulo. Não há como gravar pela view o texto `NULL` sem aspas num `VARCHAR`.
- O binário aparece em hexadecimal (`0xCAFE`), mas o que se digita vai como texto: `0xCAFE` numa variável binária grava os caracteres, não os bytes.

**Achado para decidir (issue 13).** O diálogo de launch diz "Cada valor é convertido pelo servidor para o tipo do parâmetro, como num CAST". Mas as entradas vão como `CAST(? AS <tipo>)` com o texto de parâmetro, e quem converte é o Jaybird: `10.5` num `INTEGER` ou `17.10.2026` numa `DATE` dão erro no console. Para alinhar com a edição de variável, as entradas de texto do launch passariam pelo caminho do `assignable`. Não mudei, porque é comportamento da issue 13, já conferida à mão. Nos Testes diferenciais, os dois lados recebem as entradas pelo Jaybird; a troca valeria só para o texto que vem do diálogo.

**Para conferir à mão no DBeaver**
1. Pausado num breakpoint: Variables mostra as entradas, as saídas e as locais do frame selecionado, com o tipo declarado (coluna de tipo, ou "Show Type Names"). Selecionar outro frame na view Debug mostra as variáveis dele.
2. Alterar, antes de um `IF`, uma variável usada nele (clique no valor, ou "Change Value..."): F6 segue o outro ramo.
3. Texto inválido (`abc` num `INTEGER`): mensagem com o erro do servidor, e o valor continua o anterior. `NULL`: vira nulo.
4. `NUMERIC`, `DATE`, `TIMESTAMP`, `BLOB SUB_TYPE TEXT` e `NULL` legíveis. Os valores que mudaram no último step ficam destacados.

**Não tratado**
- No frame de uma sub-rotina, as variáveis da Rotina que a declara (que a sub-rotina lê e altera) não aparecem. Os campos de cursor nomeado (`C.CAMPO`) também não.
- Uma edição de valor que demore mais de 30 s no servidor é dada como falha, mas a tarefa ainda roda.
