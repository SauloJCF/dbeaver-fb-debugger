# 17 — Step into e step return na UI

Status: ready-for-agent
Blocked by: 10, 11, 14

## O que construir

- Step into (F5) em Step into elegível abre o editor da Rotina chamada (Fonte do banco) e pausa no primeiro statement; a view Debug mostra a pilha de frames.
- Step return (F7) executa até sair do frame atual.
- Step into em linha com chamada Executada no servidor faz step over e a linha mostra o aviso "executado no servidor" (anotação no editor).
- Breakpoints na Rotina chamada só valem se o editor dela estiver aberto (consequência de breakpoints não persistentes).

## Critérios de aceite

- [x] Cadeia A → B → C: step into até C, step return volta a B na linha seguinte à chamada.
- [x] Selecionar um frame antigo na view Debug mostra as variáveis dele em Variables.
- [x] `INSERT … SELECT F(x)` mostra o aviso e não pausa em `F`.
- [x] Step into em sub-rotina interna funciona igual.

## Comments

### 2026-10-18 — implementação (agente)

**Estado: código pronto e compilando; a UI não foi testada no DBeaver.** Os critérios de aceite ficam desmarcados até o teste à mão. Fora do OSGi, contra o Firebird local:
- `StepIntoTest` (14 testes) conduz a sessão de outra thread, como a UI, com o `ExecutionControl` como gancho e como política das chamadas, igual ao launch.
  - Cadeia A → B → C: o step into vai de A para B e de B para C. O step return, pedido no primeiro statement de C, volta a B na linha seguinte à chamada, com a saída de C já no alvo do `RETURNING_VALUES`. Outro step return volta a A.
  - Step into em `x = F(...)` entra na function, e o step return volta.
  - Step into numa linha com a chamada dentro de `INSERT … SELECT F(x)` ou de uma expressão maior vai ao próximo statement, sem parar em `F`, mesmo com breakpoint nela.
  - Sub-rotina (function e procedure): o frame fica no texto da Rotina que a declara.
  - Os frames de quem chamou guardam as variáveis deles e a linha da chamada, e um watch no frame de B usa as variáveis de B.
  - Step over e step return pedidos para o frame de quem chamou. Step return na Rotina raiz vai ao próximo breakpoint ou ao fim.
  - Breakpoint em Rotina chamada: o step over para num breakpoint dentro de C; um breakpoint só em C, chamada por B, para no resume; uma sub-rotina com breakpoint para no resume, e sem breakpoint vai ao servidor no step over.
  - `FOR SELECT` sobre procedure: o step into na linha do laço entra na procedure e, numa volta seguinte, volta a ela depois do `SUSPEND`. Um step into numa volta seguinte passa a acompanhar a procedure que tinha sido passada com step over. Um breakpoint posto nela durante o laço vale a partir da volta seguinte.
- `ServerCallsTest` (1): as chamadas sem step into de um texto são só os candidatos que o banco confirma como Rotina. Ficam fora `UPPER`, a tabela depois de `FROM` e as chamadas elegíveis que o step into alcança. Cada chamada tem o statement em que a execução para antes dela: num `SELECT` de duas linhas com a chamada na segunda, a primeira. Uma chamada elegível a Rotina recusada pelo Pré-check também tem aviso.
- A suíte inteira passa: 215 testes (200 de antes e 15 novos).
- De propósito, tirei sete peças, uma por vez: o step into da política (5 testes falharam), o alcance transitivo dos breakpoints (5), o acompanhar a procedure do `FOR SELECT` numa volta seguinte (2), a altura do step return (3), o step pedido para outro frame (2), o breakpoint ao alcance da chamada (7) e a confirmação no banco do aviso (1). Depois restaurei. A do step return pegou só 2 testes na primeira vez, porque os testes voltavam sempre do último statement do frame, onde step return e step over dão no mesmo. Reforcei o teste da cadeia.
- Ensaio do roteiro abaixo: rodei os passos 1 a 10 pelo núcleo, sem a UI, numa cópia do banco de exemplo com as Rotinas novas. As paradas, as pilhas, as variáveis, o watch e o aviso bateram com o roteiro.

**Onde ficou**
- Núcleo (`interp`, sem Eclipse):
  - `ExecutionControl` também é a `CallPolicy` da sessão. O step into (`stepInto()`) solta a execução e interpreta a chamada Step into elegível do statement em que ela parou: `stepInto(call, caller)` só é verdadeiro para o frame que estava em cima. Ele para no próximo statement de qualquer frame acompanhado, que é o primeiro da Rotina chamada. Sem chamada que dê para interpretar, é um step over. O step return (`stepReturn()`) para no próximo statement com a pilha menor que a do frame. Na raiz, vale como resume. `stepOver(Frame)` e `stepReturn(Frame)` valem para um frame de baixo da pilha da pausa.
  - `CallPolicy.stopsInside(Reach)`: fora do step into, a política pode pedir que a chamada seja interpretada e acompanhada, porque a execução pode parar dentro dela. O `ExecutionControl` pede isso quando há breakpoint ativo numa linha que a chamada alcança. `Breakpoints.none()` dispensa a conta quando não há nenhum breakpoint.
  - `CallTargets` (novo): resolve a Rotina chamada como o Interpretador (sub-rotina primeiro, depois o catálogo), guarda o resultado do Pré-check de cada uma e calcula o alcance de uma chamada. O alcance são as linhas com statement da Rotina chamada e, seguindo as chamadas elegíveis que dá para interpretar, as das Rotinas que ela alcança, por texto, uma vez por sessão.
  - `Interpreter.callee` pergunta à política com o `CallSite`. No step over, a Rotina chamada só é procurada no catálogo se a política quer saber o alcance; com `CallPolicy.SERVER`, nada muda. Numa volta seguinte do `FOR SELECT` sobre procedure interpretada, `follow` pergunta de novo, e a procedure parada no `SUSPEND` pode passar a ser acompanhada (`Frame.follow()`; `Frame.steppedInto()` deixou de ser fixo).
  - `ServerCalls` (novo) e `DebugSession.serverCalls(texto)`: as chamadas sem step into de um texto, confirmadas no catálogo da sessão, cada uma com o statement que a contém (`psql/StatementLines.starts`).
- Modelo do Eclipse (`debug`):
  - `PsqlThread`: step into (F5) e step return (F7) habilitados; o step return só com mais de um frame.
  - `PsqlStackFrame`: os steps pedidos pelo frame valem para ele; o step into só no de cima. O nome do frame mostra a Rotina que declara uma sub-rotina (`ARREDONDA (em SP_FATURA_PEDIDO) linha 14`) e, quando o statement tem chamada sem step into, `(executado no servidor: F)`. O frame dá as chamadas do texto dele por `getAdapter(ServerCallWarnings.class)`.
  - `PsqlSourceLocator` acha o objeto do DBeaver de cada texto na primeira vez que um frame dele aparece (`RoutineEditors.routine`: procedure ou function fora de package, pelo nome e pelo tipo).
  - `PsqlDebugTarget`: o `SessionOpener` recebe o `ExecutionControl`, que o launch passa como gancho e como política.
- Editor (`editor`):
  - `ServerCallAnnotations` (novo) põe a anotação no nome de cada chamada sem step into quando o debug.ui mostra um frame do texto, e a tira no fim da sessão. O tipo `dev.fbdebug.dbeaver.serverCall` está no `plugin.xml`, com o ícone de informação no ruler e na régua da direita e uma moldura tracejada no nome.
  - `PsqlModelPresentation` põe as anotações e avisa na linha de status quando o editor da Rotina da pausa tem alterações não salvas.
  - `EditorBreakpoints.prepareLaunch` também leva para a linha da marca no ruler os breakpoints das outras Rotinas.
- Banco de exemplo: `HISTORICO_PEDIDO`, `SP_VALOR_ITEM`, `SP_TOTAL_PEDIDO` e `SP_FATURA_PEDIDO` no `samples/sample-db/create-sample-db.sql`. Conferi com o isql numa cópia: `SP_FATURA_PEDIDO(3)` dá `TOTAL = 7500.00`, `ITENS = 2`, `DESCONTO = 750.00`, `A_PAGAR = 6750.00` e `HISTORICOS = 1`.
- `docs/como-funciona.md`: step into, step return, a pilha, breakpoints em outra Rotina, o aviso e o mapa do código.

**Decisões**
- **⚠ Um breakpoint numa Rotina chamada vale também no step over e no resume.** Uma chamada elegível continua indo ao servidor no step over, como na issue 10, a menos que haja um breakpoint numa Rotina que ela alcança, direto ou por outras chamadas elegíveis. Aí a Rotina é interpretada e acompanhada, e a execução para no breakpoint. A issue 14 dizia "um breakpoint numa Rotina chamada ainda não vale", e esta issue diz que eles valem com o editor aberto. Sem isso, um breakpoint em C só valeria depois de chegar a C por step into. Custo: com algum breakpoint ativo, a primeira chamada a cada Rotina lê a Fonte do banco das Rotinas que ela alcança, uma vez por sessão. Isso muda a decisão da issue 10 ("`EXECUTE PROCEDURE` e `x = F()` no step over vão de fato ao servidor") só nesse caso. Interpretar é o caminho que os Testes diferenciais já conferem com `CallPolicy.INTERPRET`. Não contraria nenhum ADR.
- **⚠ Os steps pedidos por um frame de baixo valem para ele, como no Eclipse (JDT).** O debug.ui manda o step para o elemento selecionado na view Debug. Com o frame de quem chamou selecionado, o F6 roda até o próximo statement desse frame, e o F7 até o de quem o chamou. O F5 só fica habilitado no frame de cima. Quem seleciona um frame antigo só para ver as variáveis e aperta F6 sai do frame de cima. A alternativa seria mandar todo step para o frame de cima.
- **O step return fica desabilitado na Rotina raiz**, como no JDT. Se chegar ao `ExecutionControl`, vale como resume.
- **O step into na linha de um `FOR SELECT` sobre procedure entra nela em qualquer volta.** Na primeira volta ela começa; nas seguintes, continua depois do `SUSPEND`, mesmo que a primeira volta tenha sido com step over. O step over no `SUSPEND` vai ao corpo do laço, e o step return de dentro da procedure também, porque ela sai da pilha enquanto o corpo roda.
- **O aviso "executado no servidor" é uma anotação no editor e o nome do frame na view Debug.** A anotação marca, durante a sessão, o nome de cada chamada sem step into do texto, e é posta na primeira vez que o debug.ui mostra um frame do texto. O hover do editor SQL do DBeaver só mostra as anotações dele (erro de sintaxe, de semântica, de ortografia), e o texto da nossa não aparece nele. Por isso o nome do frame parado no statement diz `(executado no servidor: F)`. O aviso vale para a chamada dentro de expressão ou de Fatia DML e também para a chamada elegível a uma Rotina que o Interpretador não executa (recusada pelo Pré-check, ou com Fonte que a gramática não reconhece), que vai inteira ao servidor. Só os nomes que o banco confirma como Rotina PSQL ou sub-rotina têm aviso.
- **⚠ Conflito do F7 (ADR 0004).** No DBeaver 26.2.2, o F7 não é mais "Toggle result panels". Ele é `resultset.grid.togglePreview`, no contexto `org.jkiss.dbeaver.ui.context.resultset`, ativo só com uma grade de resultado em foco. O F7 do step return fica no contexto `org.eclipse.debug.ui.debugging`. Os dois contextos são filhos do da janela, e por isso só há conflito com uma grade de resultado em foco durante a sessão. No editor da Rotina e nas views do debug, o F7 é o step return. Não mudei atalhos. A linha do ADR 0004 sobre o conflito está desatualizada para o 26.2.2, e cabe ao humano decidir se ela é reescrita.
- **Rotina de package não tem editor.** O DBeaver não mostra as packages do Firebird. O frame de uma rotina de package aparece na pilha, com as variáveis, e o debug.ui mostra o editor de fonte não encontrada.
- **O editor sujo de uma Rotina chamada não bloqueia nada.** O launch só confere o editor da Rotina do launch. Na pausa numa Rotina chamada cujo editor tem alterações não salvas, a linha de status avisa que a linha destacada é a da Fonte do banco.
- O nome `Frame.steppedInto()` ficou, agora com o sentido de "a camada de debug acompanha a Rotina": a raiz, um step into ou um breakpoint ao alcance da chamada.

**Para conferir à mão no DBeaver** (com o zip instalado)

Antes, rodar no `SAMPLE.FDB` o que está no fim deste comentário (no isql, ou no DBeaver com um `CREATE` por vez) e fechar o editor de qualquer outra Rotina com breakpoint.

1. Abrir a `SP_FATURA_PEDIDO` na pasta Source e pôr um breakpoint na linha do `EXECUTE PROCEDURE SP_TOTAL_PEDIDO`. Launch com `P_ID_PEDIDO = 3`. A execução para nessa linha. No `INSERT INTO HISTORICO_PEDIDO`, o ruler mostra o ícone de informação na linha seguinte (a do `SELECT`), e o `FN_DESCONTO` dela ganha uma moldura tracejada.
2. F5: abre o editor da `SP_TOTAL_PEDIDO`, na pasta Source, parado em `TOTAL = 0;`. A view Debug mostra dois frames, `SP_TOTAL_PEDIDO linha …` em cima de `SP_FATURA_PEDIDO linha …`.
3. F6 três vezes: para no `EXECUTE PROCEDURE SP_VALOR_ITEM`, com `V_QTD = 5` e `V_PRECO = 1200.00` em Variables. F5: abre a `SP_VALOR_ITEM`, parada em `VALOR = P_QTD * P_PRECO;`, com três frames.
4. Na view Debug, selecionar o frame da `SP_TOTAL_PEDIDO`. O editor dela mostra a linha da chamada, e Variables mostra as variáveis dela. Um watch `V_QTD * V_PRECO` mostra `6000.00`. Selecionar o frame da `SP_FATURA_PEDIDO`: Variables mostra `P_ID_PEDIDO = 3` e as saídas vazias, e o watch dá erro, porque `V_QTD` não existe ali. Selecionar de novo o frame de cima.
5. F7: volta à `SP_TOTAL_PEDIDO`, na linha seguinte à chamada (`TOTAL = TOTAL + V_VALOR;`), com `V_VALOR = 5700.00` e dois frames.
6. F7: volta à `SP_FATURA_PEDIDO`, em `DESCONTO = FN_DESCONTO(TOTAL);`, com `TOTAL = 7500.00`, `ITENS = 2` e um frame só. O botão de step return fica desabilitado.
7. F5: abre o editor da function `FN_DESCONTO`, parado no primeiro `IF`, com `P_TOTAL = 7500.00`. F7: volta à `SP_FATURA_PEDIDO`, em `A_PAGAR = ARREDONDA(TOTAL - DESCONTO);`, com `DESCONTO = 750.00`.
8. F5: entra na sub-rotina `ARREDONDA`, no mesmo editor da `SP_FATURA_PEDIDO`, em `R = ROUND(V, 0);`. A view Debug mostra `ARREDONDA (em SP_FATURA_PEDIDO) linha …`, e Variables mostra `V = 6750.00`. F6 duas vezes: volta à `SP_FATURA_PEDIDO`, no `INSERT`.
9. No `INSERT`, o nome do frame termina em `(executado no servidor: FN_DESCONTO)`. F5: vai ao `SELECT COUNT(*) …` sem abrir a `FN_DESCONTO`.
10. F8: o console mostra `TOTAL = 7500.00`, `ITENS = 2`, `DESCONTO = 750.00`, `A_PAGAR = 6750.00`, `HISTORICOS = 1` (com a `HISTORICO_PEDIDO` vazia antes) e o rollback. A anotação sai do editor.
11. Breakpoint numa Rotina chamada: tirar o breakpoint da `SP_FATURA_PEDIDO` e, no editor da `SP_VALOR_ITEM`, pôr um no `IF (P_QTD >= 5)`. Launch da `SP_FATURA_PEDIDO` com 3: para na `SP_VALOR_ITEM`, com três frames e `P_QTD = 5`. F8: para de novo, com `P_QTD = 4`. F8: termina.
12. Step over com o frame de quem chamou selecionado: com o mesmo breakpoint, launch de novo. Na pausa, selecionar o frame da `SP_TOTAL_PEDIDO` e apertar F6: para em `TOTAL = TOTAL + V_VALOR;` da `SP_TOTAL_PEDIDO`. F8 até o fim.
13. Fechar o editor da `SP_VALOR_ITEM`: o breakpoint some da view Breakpoints. Pôr o breakpoint no `EXECUTE PROCEDURE SP_TOTAL_PEDIDO` da `SP_FATURA_PEDIDO`, launch, F6: para em `DESCONTO = …`, sem entrar na cadeia.
14. F7 com o foco no editor e com o foco na view Debug faz o step return (repetir os passos 2 e 5). Se der, conferir também com o foco numa grade de resultado de um editor SQL durante a sessão: aí o F7 pode não fazer nada, pelo conflito de atalho.
15. `.metadata/.log` sem erro novo, em especial do `markerAnnotationSpecification` e do `PsqlSourceLocator`.

**Não tratado**
- O hover do editor SQL do DBeaver não mostra o texto da anotação "executado no servidor" (ver Decisões).
- Uma chamada elegível a uma Rotina externa (UDR) vai ao servidor sem aviso: o catálogo só conhece as Rotinas PSQL com Fonte do banco.
- A anotação de um texto só aparece depois que o debug.ui mostra um frame dele. Uma Rotina chamada em que a execução não para não ganha anotação.
- Não conferido: recursão com step into. Cada chamada ganha frame próprio e o alcance trata o ciclo, mas não há teste.
- O aviso de editor diferente da Fonte do banco (issue 14) continua só para a Rotina do launch. Numa Rotina chamada, só o editor sujo é avisado.

**Para as próximas issues**
- Issue 18: o `RoutineEditors.routine` acha procedure e function fora de package. Rotina de package não tem objeto no DBeaver do Firebird, e o launch dela terá o mesmo problema de editor. Numa procedure selecionável como raiz, o step return continua desabilitado na raiz.
- Issue 20: o `plugin.xml` ganhou o tipo de anotação (`org.eclipse.ui.editors.annotationTypes` e `markerAnnotationSpecification`), do `org.eclipse.ui.editors`, que o bundle já exige.

CREATEs para o `SAMPLE.FDB`, os mesmos do `create-sample-db.sql` (no isql: `isql -user SYSDBA -password masterkey localhost:<caminho do SAMPLE.FDB> -i <arquivo>`):

```sql
CREATE TABLE HISTORICO_PEDIDO (
  ID_PEDIDO   INTEGER NOT NULL,
  TOTAL       NUMERIC(12,2),
  DESCONTO    NUMERIC(12,2),
  QUANDO      TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
COMMIT;

SET TERM ^ ;

/* cadeia de chamadas para step into e step return: SP_FATURA_PEDIDO -> SP_TOTAL_PEDIDO -> SP_VALOR_ITEM */
CREATE PROCEDURE SP_VALOR_ITEM (P_QTD INTEGER, P_PRECO NUMERIC(12,2))
  RETURNS (VALOR NUMERIC(12,2)) AS
BEGIN
  VALOR = P_QTD * P_PRECO;
  IF (P_QTD >= 5) THEN
    VALOR = VALOR * 0.95;
END^

CREATE PROCEDURE SP_TOTAL_PEDIDO (P_ID_PEDIDO INTEGER)
  RETURNS (TOTAL NUMERIC(12,2), ITENS INTEGER) AS
  DECLARE VARIABLE V_QTD INTEGER;
  DECLARE VARIABLE V_PRECO NUMERIC(12,2);
  DECLARE VARIABLE V_VALOR NUMERIC(12,2);
BEGIN
  TOTAL = 0;
  ITENS = 0;
  FOR SELECT QUANTIDADE, PRECO_UNIT FROM ITEM_PEDIDO WHERE ID_PEDIDO = :P_ID_PEDIDO ORDER BY ID
      INTO :V_QTD, :V_PRECO
  DO
  BEGIN
    EXECUTE PROCEDURE SP_VALOR_ITEM(:V_QTD, :V_PRECO) RETURNING_VALUES :V_VALOR;
    TOTAL = TOTAL + V_VALOR;
    ITENS = ITENS + 1;
  END
END^

/* step into em procedure, function e sub-rotina; FN_DESCONTO dentro do INSERT ... SELECT e executada no servidor */
CREATE PROCEDURE SP_FATURA_PEDIDO (P_ID_PEDIDO INTEGER)
  RETURNS (TOTAL NUMERIC(12,2), ITENS INTEGER, DESCONTO NUMERIC(12,2), A_PAGAR NUMERIC(12,2),
           HISTORICOS INTEGER) AS
  DECLARE FUNCTION ARREDONDA (V NUMERIC(12,2)) RETURNS NUMERIC(12,2)
  AS
    DECLARE R NUMERIC(12,2);
  BEGIN
    R = ROUND(V, 0);
    RETURN R;
  END
BEGIN
  EXECUTE PROCEDURE SP_TOTAL_PEDIDO(:P_ID_PEDIDO) RETURNING_VALUES :TOTAL, :ITENS;
  DESCONTO = FN_DESCONTO(TOTAL);
  A_PAGAR = ARREDONDA(TOTAL - DESCONTO);
  INSERT INTO HISTORICO_PEDIDO (ID_PEDIDO, TOTAL, DESCONTO)
    SELECT :P_ID_PEDIDO, :TOTAL, FN_DESCONTO(:TOTAL) FROM RDB$DATABASE;
  SELECT COUNT(*) FROM HISTORICO_PEDIDO WHERE ID_PEDIDO = :P_ID_PEDIDO INTO :HISTORICOS;
END^

SET TERM ; ^
COMMIT;
```

### 2026-10-18 — teste à mão (agente)

O humano conferiu o roteiro no DBeaver, sem problema. Critérios de aceite marcados. A suíte passa com 215 testes.
