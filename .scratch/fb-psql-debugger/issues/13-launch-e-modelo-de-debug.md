# 13 — Launch e modelo de debug do Eclipse

Status: ready-for-agent
Blocked by: 01, 02

ADR: 0004

## O que construir

- Substituir o debug target de mentira da issue 01 pelo real: `IDebugTarget` / thread / stack frames / variables sobre a sessão do Interpretador.
- Ação "Debug" no menu de contexto de uma procedure Firebird no navegador do DBeaver e no editor do objeto.
- Diálogo de launch: valores dos parâmetros de entrada, isolamento (`READ COMMITTED` padrão, `SNAPSHOT`), checkbox "commit ao terminar" (desmarcado).
- Conexão dedicada criada a partir da configuração da conexão do DBeaver (não reaproveitar o contexto do editor SQL).
- Sessão roda até o fim (ainda sem breakpoints), mostra os parâmetros de saída e faz rollback ou commit. Terminate aborta com rollback.
- Troca automática para a perspectiva Debug.
- Pré-check (issue 08) e bloqueio de editor sujo ("Salve (compile) ou descarte as alterações antes de depurar") recusam o launch com mensagem.

## Critérios de aceite

- [x] Launch de uma procedure executável mostra as saídas corretas.
- [x] Sem o checkbox, nada persiste; com o checkbox, persiste.
- [x] Terminate no meio de um laço longo encerra a sessão e faz rollback.
- [x] Editor sujo e construção não suportada recusam o launch com a mensagem e a posição.
- [x] Nenhuma transação do editor SQL do DBeaver é afetada durante a sessão.

## Notas

- Verificar a API do DBeaver para abrir contexto de execução isolado a partir do `DBPDataSourceContainer` antes de abrir JDBC à mão.

## Comments

### 2026-10-17 — implementação (agente)

**Estado: código pronto e compilando; a UI não foi testada no DBeaver.** Os critérios de aceite são de UI e ficam desmarcados até o teste à mão. Fora do OSGi, o que dá para testar está coberto: `DebugSessionTest` (Terminate de outra thread num laço longo, dentro de bloco com `WHEN ANY`, com "commit ao terminar" marcado: a sessão para, faz rollback e remove o Package temporário; `SNAPSHOT` não vê o que outra conexão commita durante a sessão, `READ COMMITTED` vê). A suíte inteira passa (179 testes) e o `./mvnw verify` gera o zip do repositório p2.

**Ambiente: o DBeaver desta máquina é o 26.2.2, não o 26.0.2.** `.eclipseproduct` diz `version=26.2.2` (build `202610041844`), com `org.eclipse.debug.core_3.24.0.v20260714` e plataforma do Eclipse 2026-09 (`ui.workbench_3.140.0`). A spec e o ADR 0004 fixam o 26.0.2 e o target fixa o `org.eclipse.debug.ui` 3.20.0 do Eclipse 4.39, que é o par do `debug.core` 3.23.200. O build resolve, mas ninguém conferiu o `debug.ui` 3.20 rodando sobre o `debug.core` 3.24. **Decisão do humano:** voltar ao 26.0.2 ou revalidar o par (e talvez trocar o `debug.ui` pelo do Eclipse 2026-09). O código desta issue foi compilado contra o 26.2.2; não conferi a API do DBeaver no 26.0.2.

**Onde ficou**
- Interpretador: `StatementHook` (chamado antes de cada statement, na thread da execução; o que ele lança passa por cima dos `WHEN`) e o construtor do `Interpreter` que o recebe. `DebugSession` ganhou `Isolation` (`READ_COMMITTED`, `SNAPSHOT`, aplicado antes da primeira transação), o construtor completo `(conexão, isolamento, commit, CallPolicy, StatementHook)` e `abort()`: thread-safe, a execução para antes do próximo statement com `SessionAbortedException`, rollback mesmo com "commit ao terminar", e os Packages temporários são removidos. Um statement que já está no servidor termina antes.
- `dev.fbdebug.dbeaver.debug`: `PsqlDebugTarget` (roda a sessão numa thread própria; `terminate()` chama `abort()`), `PsqlThread`, `PsqlStackFrame` (frame do Interpretador; linha do `RoutineSourceLoader`, que segue o editor, issue 09), `PsqlVariable`/`PsqlValue` (só leitura; edição na issue 15). `SessionConsole`: console "Firebird PSQL Debug" com as entradas, as saídas e se houve commit ou rollback (também erro e Terminate). `PsqlLaunchDelegate`: o tipo de launch `dev.fbdebug.dbeaver.launchType` (`public="false"`) só dá nome à sessão na view Debug; um "Relaunch" é recusado com mensagem.
- `dev.fbdebug.dbeaver.launch`: comando "Depurar Rotina" (`dev.fbdebug.dbeaver.debugRoutine`) no menu de contexto do navegador e do editor (`popup:org.eclipse.ui.popup.any`, visível só quando habilitado) e no menu "PSQL Debug". `RoutineLauncher`: recusa com o editor do objeto sujo ("Salve (compile) ou descarte as alterações antes de depurar"), abre a conexão dedicada com `DBSInstance.openIsolatedContext` (contexto isolado do DBeaver, fora de qualquer editor SQL; `JDBCExecutionContext.getConnection` dá o `java.sql.Connection`), carrega a Fonte do banco, passa pelo Pré-check (lista com linha/coluna), mostra o `RoutineLaunchDialog` e inicia a sessão na perspectiva Debug. O diálogo tem, por parâmetro de entrada, Valor/NULL/Padrão (padrão só nos últimos, como no `EXECUTE PROCEDURE`) e lembra os valores por conexão e Rotina; isolamento (`READ COMMITTED` padrão) e "Commit ao terminar" (desmarcado, não é lembrado).
- O protótipo da issue 01 perdeu o launch e o debug target de mentira; ficaram só os breakpoints sem efeito (Ctrl+Shift+B), para a issue 14 substituir.

**Decisões**
- O plugin do Firebird do DBeaver **não exporta nenhum pacote**. O código usa `GenericProcedure` (de `ext.generic`) e reconhece a conexão Firebird pelo provedor do driver (`jaybird`). No `plugin.xml`, a expressão compara o nome `FireBirdProcedure` com `instanceof`, que não precisa da classe.
- Só procedure executável fora de package é aceita. Selecionável, function e package recebem mensagem (issue 18).
- As saídas vão para um console, não para um diálogo: não bloqueia, fica o histórico das sessões, e a issue 18 pode escrever ali as linhas do `SUSPEND`.
- O `Launch` é criado à mão, com uma configuração não salva, para levar a Rotina e a conexão já abertas ao alvo. O delegate não é usado.
- A `DebugSession` é aberta na thread da execução (a varredura dos packages já fala com o banco). Um Terminate antes disso também vale.

**Para conferir à mão no DBeaver** (com o zip instalado por "Install New Software > Add > Archive")
1. Botão direito numa procedure executável Firebird no navegador: "Depurar Rotina" aparece (e não aparece em tabela, nem em procedure de outro banco). O mesmo no editor do objeto e no menu "PSQL Debug".
2. Diálogo, perspectiva Debug, saídas no console "Firebird PSQL Debug". Sem o checkbox, nada persiste; com ele, persiste.
3. Terminate (view Debug) no meio de um `WHILE` longo: console mostra "Interrompida pelo Terminate: rollback feito.", nada persiste e `RDB$PACKAGES` sem `DBG$`.
4. Editor do objeto com alteração não salva: mensagem e nada é aberto. Procedure com `WHERE CURRENT OF`: lista do Pré-check com linha/coluna.
5. Com um editor SQL do DBeaver com transação aberta (modo manual) na mesma conexão: a sessão não a afeta, nem é afetada por ela.
6. `.metadata/.log` sem erro novo (em especial de avaliação do `enabledWhen` do comando).

**Não tratado**
- Terminate durante um statement longo no servidor (um `SELECT` pesado) só vale quando ele termina; não há cancelamento do statement.
- Valor inválido para o tipo (texto num `INTEGER`) só aparece como erro no console, depois do launch, e não no diálogo.
- Os stack frames e as variáveis só existem com a execução pausada, o que entra com os breakpoints (issue 14). `PsqlThread.suspended()` é o ponto de entrada.

### 2026-10-17 — teste à mão (humano)

O humano rodou o roteiro "Para conferir à mão" no DBeaver 26.2.2 desta máquina e informou que tudo passou ("aparentemente tudo ok"). Critérios de aceite marcados com base nisso. Fica valendo que o `debug.ui` 3.20 do target rodou sobre o `debug.core` 3.24 do 26.2.2 nesse roteiro; a escolha entre voltar ao 26.0.2 e refixar as versões continua aberta.

### 2026-10-17 — "Depurar Rotina" com outra view em foco (agente)

No teste de UI das issues 14 e 15, com a view Breakpoints em foco, "PSQL Debug > Depurar Rotina" ficou inativo. O comando olhava só a parte ativa: no editor de objeto, a Rotina dele; fora dele, a seleção, que numa view do debug não é uma Rotina. Na perspectiva Debug o foco costuma estar numa view do debug, então o menu "PSQL Debug" não servia para depurar de novo.

**Decisão:** mudar. Numa parte que não é editor nem navegador do DBeaver (`INavigatorModelView`), sem objeto do banco selecionado, vale a Rotina do editor ativo, que continua definida com uma view em foco. No editor e no navegador nada muda: um navegador com uma tabela ou uma pasta selecionada continua sem Rotina. É a mesma ideia do "Launch the selected resource or active editor" do Eclipse.

**Menu de contexto.** Com a regra nova, `checkEnabled` mostraria "Depurar Rotina" no menu de contexto das views do debug e do console. O `visibleWhen` passou a ser uma expressão com a regra antiga: editor de objeto ativo com Rotina Firebird (property tester `breakpointSource`), ou outra parte com uma `FireBirdProcedure` selecionada.

**Para conferir à mão**
1. Com o editor da `SP_SOMA_ESTOQUE` aberto, clicar na view Breakpoints (ou Variables, Debug, console) e usar "PSQL Debug > Depurar Rotina": abre o diálogo da Rotina do editor.
2. Botão direito nas views Breakpoints, Variables, Debug e no console: "Depurar Rotina" não aparece.
3. Navegador com uma procedure selecionada e o editor de outra procedure aberto: o menu de contexto e o menu "PSQL Debug" usam a selecionada. Com uma tabela selecionada: o item não aparece no menu de contexto e fica inativo no "PSQL Debug".
4. Menu de contexto do editor da procedure: ver o comentário seguinte (o item nunca apareceu ali).

### 2026-10-17 — "Depurar Rotina" no menu de contexto do editor (agente)

No teste à mão, "Depurar Rotina" não apareceu no menu de contexto do editor da procedure, nem na pasta Source nem na Properties. Não é regressão: nunca apareceu ali, e o comentário da implementação ("no menu de contexto do navegador e do editor") estava errado.

**Causa (conferida nos jars do 26.2.2).** O item entra em `popup:org.eclipse.ui.popup.any?after=generate`. O grupo `generate` é posto depois do grupo `tools`, que só o `NavigatorUtils` cria, nos menus do navegador. O menu do texto da pasta Source é o `#SQLEditorContext` do `SQLEditorBase`, com os grupos `sql.additions`, `sql.extras`, `group.copy` … `group.rest`, `additions` e `sql.preferences`. Sem o grupo, o Eclipse descarta a contribuição.

**Correção.** Uma contribuição a mais em `popup:#SQLEditorContext?after=additions`, visível só com a entrada do editor ativo sendo procedure ou function Firebird (property tester `breakpointSource`). É o mesmo caminho do "Alternar Breakpoint" no `#SQLRulerContext`, que o `SQLEditorBase` registra do mesmo jeito. A pasta Properties fica sem o item; ali vale o menu "PSQL Debug".

**Para conferir à mão**
1. Editor da `SP_SOMA_ESTOQUE`, pasta Source, botão direito no texto: "Depurar Rotina" aparece perto do fim, antes de "Preferences", e abre o diálogo.
2. Editor SQL comum (script) e editor de uma tabela: o item não aparece.
3. Navegador: continua como antes.

### 2026-10-17 — teste à mão do menu de contexto (humano)

O humano instalou o zip `0.1.0.202610172355` no DBeaver 26.2.2 e informou que está "tudo certo agora": "Depurar Rotina" aparece no menu de contexto do texto da pasta Source e abre o diálogo.
