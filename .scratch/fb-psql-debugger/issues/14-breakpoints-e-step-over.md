# 14 — Breakpoints de linha, step over e resume

Status: ready-for-agent
Blocked by: 03, 13

## O que construir

- Breakpoint de linha no editor da Rotina no DBeaver (ruler e Ctrl+Shift+B), via `IToggleBreakpointsTarget` adaptado ao editor do objeto.
- Breakpoints **não persistentes**: removidos quando o editor fecha; não sobrevivem a reinício.
- Breakpoint em linha sem statement é movido para o próximo statement ou recusado.
- Suspensão no breakpoint, linha atual destacada no editor, step over (F6), resume (F8), terminate.

## Critérios de aceite

- [ ] Procedure com `IF`/`WHILE` para em cada breakpoint na ordem certa; step over avança statement a statement.
- [ ] Fechar e reabrir o editor: nenhum breakpoint.
- [ ] Reiniciar o DBeaver: nenhum breakpoint na view Breakpoints.
- [ ] Linha destacada bate com o texto do editor (cabeçalho gerado pelo DBeaver considerado — ver issue 09).

## Comments

### 2026-10-17 — implementação (agente)

**Estado: código pronto e compilando; a UI não foi testada no DBeaver.** Os critérios de aceite são de UI e ficam desmarcados até o teste à mão. Fora do OSGi, o `ExecutionControlTest` cobre a pausa contra o Firebird local: breakpoints em `IF`/`WHILE` param na ordem da execução; o step over vai statement a statement e passa pela linha do `WHILE` a cada volta; o breakpoint vale uma vez por entrada na linha; nenhuma parada dentro da procedure de um `FOR SELECT`, interpretada no step over; o step over segue um erro até o `WHEN`; o pedido de pausa (Suspend) para um laço longo, e o Terminate pausado faz rollback mesmo com "commit ao terminar". O `StatementLinesTest` cobre as linhas que recebem breakpoint. De propósito, tirei o gancho de nova volta de laço e a regra de "entrar na linha": 4 testes falharam. Depois restaurei.

**Onde ficou**
- Núcleo (`interp`, sem Eclipse): `ExecutionControl` é o `StatementHook` da sessão. Ele pausa a thread da execução num breakpoint, no fim de um step over ou num pedido de pausa, e a solta com o resume, o step over ou o Terminate (`release`). Só para em frame com `steppedInto()`, e nunca num `BEGIN`. O breakpoint vale ao entrar na linha: `IF (...) THEN X = 1;` para uma vez, e um laço que volta à linha para de novo. O step over para no próximo statement com a pilha da mesma altura ou menor.
- Interpretador: chama o gancho de novo a cada volta de `WHILE`, `FOR SELECT` e `FOR EXECUTE STATEMENT`, antes da condição ou da próxima linha (`StatementHook` documenta). Assim um breakpoint na linha do laço para a cada volta, e o Terminate também é visto ali.
- `Frame.sourceRoutine()` é a Rotina de cujo texto são as posições (numa sub-rotina, a que a declara). `psql/StatementLines` dá as linhas com statement (sem `BEGIN`), também nas sub-rotinas.
- Modelo do Eclipse (`debug`):
  - `PsqlLineBreakpoint`: marker na raiz do workspace, do tipo `dev.fbdebug.dbeaver.lineBreakpointMarker`, não persistente (`PERSISTED=false` e `TRANSIENT`). Guarda a conexão, o tipo e o nome da Rotina (`SourceId`).
  - `PsqlDebugTarget`: acompanha os breakpoints da conexão da sessão (o índice é refeito a cada mudança, e "Skip All Breakpoints" vale).
  - `PsqlThread`: resume (F8), step over (F6) e suspend, com os eventos `BREAKPOINT`, `STEP_END` e `CLIENT_REQUEST`.
  - `PsqlSourceLocator`: dá o objeto do DBeaver de cada frame.
- Editor do DBeaver (pacote novo `editor`):
  - `EditorBreakpoints`: alterna o breakpoint no texto da pasta Source. Põe a marca no ruler à mão, porque o modelo de anotações do editor não acompanha markers, e põe de novo quando o editor recarrega o texto. Remove os breakpoints quando o editor do objeto fecha.
  - `PsqlModelPresentation`: acha o editor do objeto (ou o abre na pasta Source) e passa para a pasta Source. O destaque da linha e a remoção dele no resume são os padrões do debug.ui.
  - `ToggleBreakpointHandler` (Ctrl+Shift+B, linha do cursor), `RulerToggleBreakpointHandler` (duplo-clique e menu do ruler, linha clicada) e `RoutineEditorTester` (liga os comandos só em procedure e function Firebird).
- Launch: antes de iniciar, cada breakpoint da Rotina vai para a linha da marca no ruler, que acompanha o texto editado e salvo. Numa linha sem statement da Fonte do banco, ele vai para o próximo statement. Se, numa linha de statement, o texto do editor aberto não é o da Fonte do banco, o console avisa que as linhas podem não bater.
- O pacote `prototype` (issue 01) saiu.

**Decisões**
- **Duplo-clique no ruler.** O editor de texto da pasta Source é aninhado, e o site dele tem o id da pasta (`generic.source.view`). Ele não recebe as ações do `editorActions` do jeito normal. Mas o `AbstractTextEditor`, na hora do duplo-clique, procura uma ação `RulerDoubleClick` nas contribuições `editorActions` com o id do site dele (`findContributedAction`). O `plugin.xml` contribui uma com `targetID="generic.source.view"` e o `definitionId` do comando `dev.fbdebug.dbeaver.rulerToggleBreakpoint`. Conferido no bytecode do `org.eclipse.ui.workbench.texteditor` 3.20.200 e do `ContributedAction` do 26.2.2, não na UI. O menu do ruler (`#SQLRulerContext`) segue o caminho do debugger oficial do DBeaver.
- Ctrl+Shift+B: o handler tem `activeWhen` (editor de objeto de procedure ou function Firebird), para não tomar o comando de outro debugger instalado.
- Sem `IToggleBreakpointsTargetFactory`: os handlers chamam o `EditorBreakpoints` direto.
- Linha sem statement: vale a próxima linha com statement, e o toggle age nela. Como no JDT, um clique no `BEGIN` acima de um breakpoint o tira. Se o parser não reconhece o texto do editor (alteração não salva, por exemplo), o breakpoint fica na linha clicada e o launch o acerta.
- Um editor do objeto já aberto não passa pelo `NavigatorHandlerObjectOpen.openEntityEditor`. Ele recarrega o editor (`refreshPart(..., true)`) quando muda a pasta padrão, e as marcas do ruler sumiriam.
- Só para em frame `steppedInto()`. Com `CallPolicy.SERVER` (até a issue 17), só a Rotina raiz para; um breakpoint numa Rotina chamada ainda não vale.

**Para conferir à mão no DBeaver** (com o zip instalado)
1. Procedure executável Firebird, pasta Source. Duplo-clique no ruler de uma linha de statement: aparecem a marca e o breakpoint na view Breakpoints (`P [linha N]`). Duplo-clique de novo: os dois somem. O mesmo pelo menu do ruler ("Alternar Breakpoint") e por Ctrl+Shift+B na linha do cursor.
2. Na linha do `BEGIN` ou de um comentário: o breakpoint vai para o próximo statement, e a linha de status diz qual. No `END` final: recusado, com bipe e mensagem.
3. Launch de uma procedure com `IF`/`WHILE`: a execução para em cada breakpoint, na ordem, com a linha destacada no editor na pasta Source (mesmo se o editor estava na pasta Properties). A linha bate com o texto. F6 avança statement a statement e passa pela linha do `WHILE` a cada volta. F8 vai ao próximo breakpoint. Terminate com a execução pausada encerra com rollback.
4. O console não mostra o aviso "a linha N do editor não é a da Fonte do banco". Se mostrar, anotar a linha e o texto do editor: é o cabeçalho gerado pelo DBeaver (issue 09).
5. Fechar o editor: os breakpoints somem da view Breakpoints. Reabrir: nenhum. Reiniciar o DBeaver: nenhum.
6. Remover um breakpoint pela view Breakpoints: a marca sai do ruler. Desabilitado, ele não para. Com "Skip All Breakpoints", nenhum para.
7. `.metadata/.log` sem erro novo (em especial do `editorActions` e do property tester).

**Não tratado**
- A marca de um breakpoint desabilitado no ruler pode não mudar até o editor redesenhar.
- Duplo-clique num breakpoint da view Breakpoints não abre o editor.
- Se o editor da Rotina não está aberto na pausa, ele é aberto na pasta Source. Se o texto ainda estiver carregando, a linha pode ficar sem destaque até a próxima pausa.

**Para as próximas issues**
- Issue 16: os watches podem ser avaliados por `ExecutionControl.whilePaused` (issue 15), na thread da execução.
- Issue 17: o step into passa pela `CallPolicy` da sessão (hoje `SERVER`) e pelo `ExecutionControl`: step into para no próximo statement de qualquer frame `steppedInto`, e step return numa pilha menor. O `PsqlSourceLocator` precisa achar o objeto do DBeaver de uma Rotina chamada (hoje só conhece a raiz).
- Issues 18 e 19: o `SourceId` já tem `FUNCTION`, `PACKAGE`, `TRIGGER` e `EXECUTE_BLOCK`, mas o `RoutineEditors.sourceOf` só reconhece procedure e function. Para trigger, conferir o texto do editor: o loader não põe `ACTIVE` nem `POSITION`.

### 2026-10-17 — correção do teste de UI: a pausa sem a view Debug (agente)

No teste à mão, a perspectiva Debug do usuário não tinha a view Debug. A execução parava, mas a linha não era destacada e a view Variables ficava vazia, até abrir a view à mão.

**Causa (conferida no fonte do `org.eclipse.debug.ui` 3.20).** Na pausa, quem seleciona o frame do topo é a árvore da view Debug: o `ThreadEventHandler` manda um delta com `SELECT` para o viewer dela. A `LaunchView` é o único `IDebugContextProvider` do debug.ui, e o destaque da linha (`SourceLookupService`) e a view Variables seguem esse contexto. Sem a view, não há contexto. O próprio debug.ui reabre a view Debug na pausa (`PerspectiveManager.handleBreakpointHit`, preferência "Activate the debug view when a breakpoint is hit"), mas só se o tipo de launch tem perspectiva, e o nosso não tem tab group nem perspectiva no delegate.

**Correção.** `RoutineLauncher.showDebugPerspective`, depois de trocar de perspectiva e antes de a sessão começar, mostra a view Debug com `VIEW_VISIBLE`: a view é criada ou trazida para o topo da pilha dela, sem ativar e sem tirar o foco do editor. É o modo que o debug.ui usa (`ViewContextService.showViewQuiet`). O `VIEW_ACTIVATE` tiraria o foco sem necessidade.

**Para conferir à mão:** fechar a view Debug na perspectiva Debug, voltar para outra perspectiva e fazer o launch com um breakpoint. A view Debug aparece, a primeira pausa já destaca a linha e enche a view Variables, e o cursor continua no editor.
