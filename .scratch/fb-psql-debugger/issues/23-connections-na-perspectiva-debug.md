# 23 — View Connections some na perspectiva Debug

Status: ready-for-agent
Blocked by: —

Spec: `../spec.md`

## O que acontece

Quando a perspectiva Debug abre pela primeira vez, no início da primeira Sessão de debug, a aba **Connections** (view Database Navigator do DBeaver, `org.jkiss.dbeaver.core.databaseNavigator`) não aparece. Para ver as conexões durante o debug, o usuário precisa abri-la de novo por *Window > Show View*. Daí em diante ela fica, porque o E4 guarda o layout da perspectiva no `workbench.xmi`. Num workspace limpo ou depois de *Reset Perspective*, ela some outra vez.

## Por quê

- `RoutineLauncher.showDebugPerspective()` troca para `IDebugUIConstants.ID_DEBUG_PERSPECTIVE`. Cada perspectiva tem as próprias views; só a área de editores é compartilhada.
- O layout inicial vem de `DebugPerspectiveFactory.createInitialLayout`, do `org.eclipse.debug.ui` 3.22 empacotado (ADR 0004). Ele monta:
  - à esquerda: Debug, mais um placeholder do Project Explorer;
  - embaixo: Console, Problems e placeholders;
  - à direita: Variables, Breakpoints, Expressions e placeholders de Outline e Properties.

  Nada do DBeaver entra nesse layout.
- Todas as `org.eclipse.ui.perspectiveExtensions` do DBeaver (26.2.2) miram só `org.jkiss.dbeaver.core.perspective`.

## O que construir

- No `plugin.xml`, uma `perspectiveExtension` com `targetID="org.eclipse.debug.ui.DebugPerspective"`:
  - a view `org.jkiss.dbeaver.core.databaseNavigator` abaixo da view Debug, na coluna da esquerda: `relative="org.eclipse.debug.ui.DebugView"`, `relationship="bottom"`, `ratio="0.5"`, `visible="true"`;
  - um `viewShortcut` para ela, para aparecer em *Window > Show View* na perspectiva Debug.

  `bottom`, e não `stack`: empilhada, uma das duas views fica escondida, e o `showView(..., VIEW_VISIBLE)` do launcher sempre traz a Debug para a frente.
- No `plugin.xml`, um comentário explicando por que a extensão existe.
- No README, uma nota dizendo que a extensão só vale quando a perspectiva é criada do zero. Num workspace que já abriu a perspectiva Debug antes do plugin novo, é preciso *Window > Reset Perspective* uma vez, com a perspectiva Debug ativa.

## Critérios de aceite

- [ ] Num workspace onde a perspectiva Debug nunca foi aberta, a primeira Sessão de debug abre a perspectiva com a Connections visível abaixo da view Debug.
- [ ] A view Debug continua visível e seleciona o frame do topo na pausa (linha destacada, Variables cheia).
- [ ] Num workspace com a perspectiva Debug já salva, *Window > Reset Perspective* traz a Connections.
- [ ] A Connections aparece em *Window > Show View* na perspectiva Debug.
- [ ] O README tem a nota do *Reset Perspective*.
