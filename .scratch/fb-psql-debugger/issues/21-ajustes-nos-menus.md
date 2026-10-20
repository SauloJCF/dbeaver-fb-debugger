# 21 — Ajustes nos menus: launch só pelo editor e ícones

Status: ready-for-agent

## Por que

"Depurar Rotina" no menu de contexto do navegador lança a sessão sem o editor da Rotina aberto, e por isso não há onde pôr breakpoints antes. O fluxo passa a ser sempre "abre a Rotina, põe os breakpoints, depura pelo editor". A seleção do navegador deixa de contar para o launch.

## O que construir

- **Sem "Depurar Rotina" no navegador.** Sai do `plugin.xml` a contribuição `popup:org.eclipse.ui.popup.any?after=generate` inteira, com os dois ramos. O grupo `generate` só existe nos menus que o DBeaver monta com o `NavigatorUtils`, então o ramo do editor só aparecia nas abas de navegador do editor de objeto, como Dependencies.
- **"PSQL Debug > Depurar Rotina" vale sempre a Rotina do editor ativo, com qualquer parte em foco, inclusive o navegador.** Com a `SP_X` selecionada no navegador e o editor da `SP_Y` aberto, depura a `SP_Y`. Sem editor de Rotina, o item fica inativo.
  - O editor ativo pode ser:
    - o de objeto de procedure, function ou trigger de tabela Firebird;
    - o editor SQL de conexão Firebird, com o `EXECUTE BLOCK` ou o console de package.
  - `DebuggableRoutines.find` deixa de ler a seleção e passa a olhar só o editor ativo, pela entrada atual dele e não pela `activeEditorInput` (ver a issue 18, editor restaurado).
  - `DebuggableRoutines.findSqlEditor` passa a usar o editor ativo também com o navegador em foco.
  - O `DebugRoutineHandler` se ajusta a isso.
  - Sai do `enabledWhen` do handler o ramo `selection`, com o `adapt` para `FireBirdProcedure` e `FireBirdTableTrigger`.
- **Fica** "Depurar Rotina" no `popup:#SQLEditorContext`, no texto da pasta Source e no editor SQL.
- **"Abrir Package..." não muda.** Ele continua pegando a conexão do navegador (`findDataSource`), porque ali o que interessa é a conexão, e não uma Rotina.
- **Ícones** pelo `org.eclipse.ui.commandImages`, com `platform:/plugin/...`, do jeito que o DBeaver faz nos comandos dele. Nenhum arquivo é copiado para o plugin.

  | Comando | Ícone |
  |---|---|
  | `dev.fbdebug.dbeaver.debugRoutine` | `platform:/plugin/org.eclipse.debug.ui/icons/full/etool16/debug_exc.svg` |
  | `dev.fbdebug.dbeaver.rulerToggleBreakpoint` | `platform:/plugin/org.eclipse.debug.ui/icons/full/obj16/brkp_obj.svg` |
  | `dev.fbdebug.dbeaver.openPackage` | `platform:/plugin/org.jkiss.dbeaver.model/icons/tree/package.svg` |

  O submenu "PSQL Debug" fica sem ícone, porque está na barra de menus.
- **Textos que mudam**:
  - a mensagem do `PsqlLaunchDelegate` manda abrir a Rotina e usar "Depurar Rotina" no editor ou no menu "PSQL Debug";
  - os comentários do `plugin.xml`, do `DebugRoutineHandler` e do `DebuggableRoutines`;
  - `docs/como-funciona.md`, onde falar do launch pelo navegador;
  - um comentário na issue 13, que tinha a ação no navegador como critério de aceite, apontando para esta.

## Critérios de aceite

- [x] O menu de contexto do navegador não tem "Depurar Rotina" em procedure, function ou trigger de tabela.
- [x] "PSQL Debug > Depurar Rotina" depura a Rotina do editor ativo com o editor, o navegador ou uma view do debug em foco, e fica inativo sem editor de Rotina.
- [x] "Depurar Rotina", "Alternar Breakpoint" e "Abrir Package..." aparecem com ícone nos menus onde estão.
- [x] `.metadata/.log` sem erro novo, em especial da avaliação do `enabledWhen` e dos ícones.

## Notas

- Decidido em sessão de grilling em 2026-10-19. Ficou de fora: invocar o launch pelo botão Debug (inseto) do `org.eclipse.debug.ui.launchActionSet`, que dependeria de um `launchShortcut`. A ideia pode voltar numa issue própria.
- O caminho do `package.svg` é interno ao DBeaver. Se mudar num upgrade, o item fica sem ícone e nada quebra. A conferência entra na revalidação a cada upgrade que o ADR 0004 já pede.

## Roteiro para conferir à mão no DBeaver

1. Navegador: botão direito em `SP_SOMA_ESTOQUE`, numa function e em `TRG_CLIENTE_BI`. "Depurar Rotina" não aparece.
2. Editor da `SP_SOMA_ESTOQUE` aberto e a `SP_PRODUTOS_BAIXO_ESTOQUE` selecionada no navegador, com o navegador em foco: "PSQL Debug > Depurar Rotina" abre o diálogo da `SP_SOMA_ESTOQUE`.
3. Com a view Breakpoints em foco e o mesmo editor ativo: o mesmo.
4. Editor SQL com um `EXECUTE BLOCK` ativo e o navegador em foco: "PSQL Debug > Depurar Rotina" lança o bloco.
5. Sem editor aberto, ou com o editor de uma tabela ativo: o item fica inativo.
6. Ícones:
   - o inseto em "Depurar Rotina", no "PSQL Debug" e no menu de contexto do texto da pasta Source;
   - a bolinha de breakpoint em "Alternar Breakpoint", no menu do ruler;
   - o ícone de package em "PSQL Debug > Abrir Package...".
7. Relaunch pela view Debug: a mensagem nova.
8. `.metadata/.log` sem erro novo.

## Comments

### 2026-10-19 — implementação (agente)

- `plugin.xml`:
  - saiu a contribuição `popup:org.eclipse.ui.popup.any?after=generate` inteira;
  - o `enabledWhen` do `DebugRoutineHandler` ficou só com `activeEditor` + `breakpointSource`. Saíram também o ramo `activeEditorInput` e o `instanceof SQLEditor`. O tester já cobre exatamente o editor de objeto de procedure, function ou trigger de tabela Firebird e o editor SQL de conexão Firebird, lê a entrada atual do editor e pede reavaliação quando a entrada de um editor restaurado chega (issue 18). Com o `activeEditorInput`, um editor restaurado podia ficar com o item no estado errado;
  - ícones por `org.eclipse.ui.commandImages`, com os três caminhos da tabela. Conferido que os arquivos existem no `org.eclipse.debug.ui_3.20.0` do repositório p2 e no `org.jkiss.dbeaver.model_2.0.48` instalado.
- `DebuggableRoutines.find(activeEditor)` e `findSqlEditor(activeEditor)` olham só o editor ativo. `findDataSource` não mudou.
- `DebugRoutineHandler` só passa o editor ativo.
- Mensagem do `PsqlLaunchDelegate`: "Para depurar de novo, abra a Rotina e use "Depurar Rotina" no editor dela ou no menu "PSQL Debug"."
- Comentários (`plugin.xml`, `DebugRoutineHandler`, `DebuggableRoutines`, `RoutineEditorTester`, `RoutineLauncher`), `docs/como-funciona.md` e comentário na issue 13.
- `mvnw verify` verde, 228 testes, zip `firebird-psql-debugger-0.1.0.202610200005.zip`. O roteiro à mão no DBeaver ainda não foi feito.

### 2026-10-19 — teste à mão (humano)

O humano rodou o roteiro no DBeaver e informou que está "tudo certo". Critérios de aceite marcados com base nisso.
