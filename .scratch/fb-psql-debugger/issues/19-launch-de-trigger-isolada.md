# 19 — Launch de Trigger isolada

Status: ready-for-agent
Blocked by: 12, 13

## O que construir

Ação "Debug" numa trigger de tabela. Diálogo de launch com `NEW.*` e `OLD.*` (uma linha por coluna da tabela, tipada) e checkboxes `INSERTING`/`UPDATING`/`DELETING` (desmarcados). Ao fim, mostra os `NEW.*` finais.

## Critérios de aceite

- [x] Trigger `BEFORE INSERT` que preenche campos mostra os `NEW.*` alterados ao fim.
- [x] Trigger universal segue o ramo do evento marcado.
- [x] Breakpoints, Variables e Expressions funcionam como em procedure.

## Comments

### 2026-10-18 — implementação (agente)

**Estado: o código compila e a suíte passa, mas a UI não foi testada no DBeaver.** Os critérios de aceite ficam desmarcados até o teste à mão. Fora do OSGi, contra o Firebird local:
- `TriggerSessionTest` (3 testes) roda a trigger como o launch: a Fonte do banco e as colunas de `loadTrigger`, as entradas na ordem de `parseTrigger` e o `ExecutionControl` como gancho.
  - `BEFORE INSERT`: para no breakpoint. Na pausa, `NEW.NOME` já está em maiúsculas, `OLD.NOME` é `NULL` e o watch `NEW.NOME || '/' || NEW.ID` dá `ANA/7`. `NEW.ID` editado na pausa para `42` muda o resultado, e o fim traz os `NEW.*` alterados (`NEW.CODIGO = C0042`, `NEW.ORIGEM = trigger`).
  - Universal (`BEFORE INSERT OR UPDATE OR DELETE`), com breakpoint nos três ramos: com `UPDATING` marcado, para só no ramo do `UPDATE`; com `INSERTING`, só no do `INSERT`; sem evento marcado, cai no `ELSE`.
  - As linhas com statement do texto da trigger, que recebem os breakpoints, incluem as linhas dos ramos.
- A suíte inteira passa: 228 testes (225 de antes e 3 novos).
- O núcleo não mudou. `NEW.campo` já valia em Variables, em watches (`BoundSql`) e como entrada editável desde as issues 12, 15 e 16. Os testes novos conferem isso pelo caminho do launch.
- Banco de exemplo: tabela `LOG_CLIENTE` e trigger universal `TRG_CLIENTE_BIUD` no `samples/sample-db/create-sample-db.sql`. Conferi o script inteiro com o isql num banco novo:
  - as cinco inserções de cliente dão cinco linhas `INSERT` no `LOG_CLIENTE`;
  - um `UPDATE` que baixa o `LIMITE` deixa `ATIVO = 0`;
  - um `DELETE` registra o nome.
- Também rodei a `TRG_CLIENTE_BI` e a `TRG_CLIENTE_BIUD` desse banco pelo Interpretador, com um teste temporário que apaguei depois. O Pré-check não achou nada, e os `NEW.*` saíram os esperados.

**Onde ficou**
- `RoutineEditors`:
  - `isTableTrigger`: uma `GenericTrigger` cujo dono é uma `GenericTableBase`. A `FireBirdTableTrigger` é uma delas; a trigger de banco não.
  - `isFirebird(DBSObject)`.
  - `sourceOf(objeto)` devolve um `SourceId` `TRIGGER` para a trigger de tabela. Com isso, o editor da trigger recebe breakpoints (ruler, Ctrl+Shift+B) e mostra "Depurar Rotina" no menu de contexto do texto, pelo `RoutineEditorTester`, como o de procedure. A pasta Source dele é a mesma `generic.source.view` (`GenericScriptObject`).
- `DebuggableRoutines.find` devolve a procedure, a function ou a trigger de tabela. O `DebugRoutineHandler` repassa ao `RoutineLauncher.launch(shell, objeto)`.
- `RoutineLauncher`: o `Subject` `TableTrigger`. Carrega com `RoutineSourceLoader.loadTrigger` e monta a Rotina com `RoutineParser.parseTrigger`. A regra do editor sujo, o Pré-check, o aviso de editor diferente da Fonte do banco e a chave dos valores lembrados (`TRIGGER nome`) seguem a procedure.
- `RoutineLaunchDialog`, numa trigger:
  - O título traz a fase, os eventos e a tabela.
  - O grupo "Colunas de TABELA" tem uma linha por coluna: nome, tipo, `NEW` (modo `Valor`/`NULL` e texto) e `OLD`. Com muitas colunas, a tabela rola (360 px).
  - O grupo "Evento" tem os checkboxes `INSERTING`, `UPDATING` e `DELETING`.
  - O diálogo devolve todas as entradas, na ordem da Rotina, com os eventos como `Boolean`.
  - Fora da trigger, o diálogo não mudou. Por dentro, os modos e os valores passaram a ser indexados pela entrada.
- `SessionConsole.finished`: numa trigger, `NEW final:` em vez de `Saídas:`. Numa `AFTER`, `(trigger AFTER: o NEW não é alterado)`.
- `plugin.xml`: o `enabledWhen` do "Depurar Rotina" e o menu de contexto do navegador aceitam também `FireBirdTableTrigger`. O teste usa `adapt` para `DBSObject`, porque o `NavigatorAdapterFactory` do DBeaver registra `DBSProcedure`, mas não `DBSTrigger`.
- `docs/como-funciona.md`: a seção "Triggers".

**Decisões**
- **`NEW` e `OLD` ficam lado a lado, numa linha por coluna**, em vez de uma lista de `NEW.*` seguida de outra de `OLD.*`. Assim a coluna e o tipo aparecem uma vez só.
  - O modo inicial é `NULL`, e não `Valor` vazio, que o servidor recusaria como número.
  - Não há modo `Padrão`: seria o mesmo `NULL`.
- **O registro que a trigger não tem fica desabilitado, com `NULL`**: `NEW` numa trigger só de `DELETE` e `OLD` numa só de `INSERT`. Na trigger, a referência a eles nem compila (issue 12). Pelo mesmo motivo, só os eventos da trigger podem ser marcados. Os outros ficam desabilitados e valem `FALSE`, como no motor.
- **Os eventos começam desmarcados, mesmo numa trigger de um evento só**, como pede a spec. O diálogo lembra, por trigger, os valores e os eventos marcados, como faz com os parâmetros de uma procedure. Por isso os checkboxes só vêm desmarcados na primeira vez. ⚠ Se o "desmarcados" da issue deve valer a cada launch, é só não ler o histórico dos eventos.
- **Os `NEW.*` finais vão para o console**, como as saídas das outras Rotinas (decisão da issue 13), e não para uma grade.
- Trigger de banco e de DDL não oferecem "Depurar Rotina". Se chegassem ao launch, o Pré-check as recusaria (issue 12).

**Para conferir à mão no DBeaver** (com o zip `releng/dev.fbdebug.dbeaver.repository/target/firebird-psql-debugger-0.1.0.*.zip` instalado)

Antes, rodar no `SAMPLE.FDB` o que está no fim deste comentário (os mesmos `CREATE` do `create-sample-db.sql`).

1. `BEFORE INSERT` que preenche campos: no navegador, tabela `CLIENTE` > Triggers > `TRG_CLIENTE_BI` > botão direito > "Depurar Rotina".
   - Título do diálogo: `Depurar TRG_CLIENTE_BI (BEFORE INSERT em CLIENTE)`.
   - A tabela tem uma linha por coluna (`ID INTEGER`, `NOME VARCHAR(80) CHARACTER SET UTF8`, …), com tudo em `NULL`. A coluna `OLD` está desabilitada. No "Evento", só `INSERTING` está habilitado, e desmarcado.
   - `NEW.NOME`: `Valor`, `  maria  `. OK.
   - Sem breakpoint, vai até o fim. O console mostra `NEW final:` com `NEW.NOME = MARIA` e `NEW.ID` igual ao próximo valor do `GEN_CLIENTE` (o gerador não volta com o rollback).
2. Breakpoints, Variables e Expressions: abrir o editor da `TRG_CLIENTE_BI`, pasta Source.
   - Pôr um breakpoint em `NEW.NOME = UPPER(TRIM(NEW.NOME));` com duplo-clique no ruler. Tirar e pôr de novo com Ctrl+Shift+B. A view Breakpoints mostra `TRG_CLIENTE_BI [linha 5]`.
   - Botão direito no texto > "Depurar Rotina", com os mesmos valores. Para na linha, com a perspectiva Debug.
   - Em Variables: `NEW.ID` já preenchido, `NEW.NOME = "  maria  "`, `OLD.*` `NULL` e `INSERTING = FALSE`.
   - Em Expressions, `UPPER(NEW.NOME) || NEW.ID` mostra o valor.
   - Editar `NEW.ID` para `100` em Variables. F6 e F8: o console mostra `NEW.ID = 100` e `NEW.NOME = MARIA`.
3. Trigger universal: `TRG_CLIENTE_BIUD` (`BEFORE INSERT OR UPDATE OR DELETE`). No editor, breakpoints em `V_EVENTO = 'INSERT';`, `V_EVENTO = 'UPDATE';` e `V_EVENTO = 'DELETE';`.
   - Os três eventos estão habilitados, e `NEW` e `OLD` também.
   - `UPDATING` marcado; `NEW.ID = 1`, `NEW.NOME = X`, `NEW.LIMITE = 100`, `OLD.ID = 1`, `OLD.LIMITE = 5000`. Para só no `V_EVENTO = 'UPDATE';`. F8: `NEW.ATIVO = 0` no fim.
   - Debug de novo: o diálogo lembra os valores e o `UPDATING` marcado. Trocar para `INSERTING`: para só no `V_EVENTO = 'INSERT';`, e no fim `NEW.CIDADE = NAO INFORMADA`.
   - Sem evento marcado: para no `V_EVENTO = 'DELETE';`.
   - Com "Commit ao terminar" desmarcado, `SELECT * FROM LOG_CLIENTE` não ganha linha.
4. `AFTER`: `TRG_PRODUTO_AU` (`AFTER UPDATE`).
   - O diálogo só habilita `UPDATING`.
   - Com `NEW.ID = 1`, `NEW.ESTOQUE = 10`, `OLD.ESTOQUE = 40` e `UPDATING` marcado, o console mostra `NEW final:` e `(trigger AFTER: o NEW não é alterado)`.
5. Editor sujo: mudar uma letra no editor da `TRG_CLIENTE_BI` sem salvar e usar "Depurar Rotina". Aparece a mensagem "Salve (compile) ou descarte…".
6. Regressão: uma procedure (`SP_FATURA_PEDIDO`, passo 1 da issue 17) e o `EXECUTE BLOCK` do passo 3 da issue 18. O diálogo de procedure continua igual: modo `Padrão` só onde há valor padrão, e modo inicial `Valor`.
7. `.metadata/.log` sem erro novo, em especial da expressão do `enabledWhen` com a trigger selecionada no navegador.

**Não tratado**
- Os valores `DEFAULT` das colunas não entram sozinhos em `NEW.*`, e uma `AFTER` que lê a própria tabela vê outro estado (issue 12).
- Uma coluna `COMPUTED BY` aparece no diálogo como as outras.
- Uma trigger de view (`FOR view`) passa pelo mesmo caminho, mas não foi testada.

CREATEs para o `SAMPLE.FDB`, no isql:

```sql
CREATE TABLE LOG_CLIENTE (
  EVENTO      VARCHAR(10) NOT NULL,
  ID_CLIENTE  INTEGER,
  DETALHE     VARCHAR(200)
);
COMMIT;

SET TERM ^ ;
CREATE TRIGGER TRG_CLIENTE_BIUD FOR CLIENTE ACTIVE BEFORE INSERT OR UPDATE OR DELETE POSITION 10 AS
  DECLARE VARIABLE V_EVENTO VARCHAR(10);
  DECLARE VARIABLE V_DETALHE VARCHAR(200);
BEGIN
  IF (INSERTING) THEN
  BEGIN
    V_EVENTO = 'INSERT';
    NEW.CIDADE = COALESCE(NEW.CIDADE, 'NAO INFORMADA');
    V_DETALHE = NEW.NOME;
  END
  ELSE IF (UPDATING) THEN
  BEGIN
    V_EVENTO = 'UPDATE';
    IF (NEW.LIMITE < OLD.LIMITE) THEN NEW.ATIVO = 0;
    V_DETALHE = OLD.LIMITE || ' -> ' || NEW.LIMITE;
  END
  ELSE
  BEGIN
    V_EVENTO = 'DELETE';
    V_DETALHE = OLD.NOME;
  END
  INSERT INTO LOG_CLIENTE (EVENTO, ID_CLIENTE, DETALHE) VALUES (:V_EVENTO, COALESCE(NEW.ID, OLD.ID), :V_DETALHE);
END^
SET TERM ; ^
COMMIT;
```

### 2026-10-19 — teste à mão (humano)

O humano conferiu o roteiro no DBeaver, sem problema. Critérios de aceite marcados. A suíte passa com 228 testes.
