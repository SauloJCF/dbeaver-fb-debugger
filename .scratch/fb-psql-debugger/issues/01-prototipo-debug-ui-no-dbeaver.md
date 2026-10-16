# 01 — Protótipo: `org.eclipse.debug.ui` carrega no DBeaver 26.0.2

Status: ready-for-agent
Blocked by: —

Spec: `../spec.md` · ADR: `docs/adr/0004-eclipse-debug-ui-empacotado.md`

## Por quê

O ADR 0004 assume que o `org.eclipse.debug.ui` funciona no DBeaver CE 26.0.2 só sendo colocado no `dropins`. Ninguém provou. Se falhar, a UI inteira do MVP muda. Esta issue derruba esse risco antes de qualquer outra coisa.

## Fatos do ambiente (levantados em 2026-10-13)

- DBeaver em `C:\Users\Saulo\AppData\Local\DBeaver`, versão 26.0.2, JRE embutida Java 21.0.8.
- Plataforma Eclipse 2026-03 (4.39): `org.eclipse.ui.workbench_3.138.0.v20260204`, `org.eclipse.debug.core_3.23.200.v20251107`, `org.eclipse.osgi_3.24.100`.
- Presentes: `ui.console`, `ui.ide`, `ui.editors`, `jface.text`, `core.variables`, `core.expressions`, `ui.views`, `ui.workbench.texteditor`.
- Ausentes: `org.eclipse.debug.ui` e qualquer `org.jkiss.dbeaver.debug.*`.
- Instalação tem `p2/`; não há pasta `dropins/` ainda.

## O que construir

1. **Esqueleto de build Maven + Tycho** (fica para o projeto):
   - target platform apontando para o diretório da instalação do DBeaver (location do tipo diretório) **mais** o repositório p2 do Eclipse 2026-03 só para obter o `org.eclipse.debug.ui`;
   - um bundle `dev.fbdebug.dbeaver`, Java 21;
   - `mvn verify` gera o jar.
2. **Debug target de mentira** (descartável, num pacote `...prototype`):
   - um `launchConfigurationType` "Firebird PSQL (protótipo)";
   - ao lançar, cria um `IDebugTarget` com uma thread suspensa, um stack frame "PROTOTIPO linha 1" e duas variáveis fixas (uma editável);
   - troca para a perspectiva Debug.
3. Copiar para `dropins/`: o jar do plugin e o `org.eclipse.debug.ui` da versão do Eclipse 2026-03 (aquela cujo `debug.core` é 3.23.200 — conferir).

## Critérios de aceite

- [x] `mvn verify` gera o jar do plugin sem erro.
- [x] Com os dois jars em `dropins/`, o DBeaver inicia sem erro novo em `workspace6/.metadata/.log`. *(Só depois que o reconciler instala os jars; ver comentários.)*
- [x] Rodando o DBeaver com `-console`, `ss` mostra `org.eclipse.debug.ui` e `dev.fbdebug.dbeaver` como `ACTIVE` ou `RESOLVED` (nunca `INSTALLED`).
- [x] O launch do protótipo abre a perspectiva Debug, a view Debug mostra a thread suspensa, Variables mostra as duas variáveis, e editar a editável muda o valor exibido.
- [x] Ctrl+Shift+B no editor SQL de uma procedure Firebird alterna um marcador de breakpoint no ruler (mesmo sem efeito real). Se não alternar, registrar o motivo nos comentários — vira entrada da issue 13.
- [ ] ~~Remover os jars de `dropins/` volta o DBeaver ao estado original.~~ *Substituído: o `dropins` foi abandonado (ADR 0004 revisado em 2026-10-14). A desinstalação via p2 foi testada; ver comentários.*

## Se falhar

Registrar nos comentários o erro de resolução (bundle e dependência faltante). Não contornar sozinho: a decisão entre empacotar mais bundles ou views próprias é do ADR 0004 e precisa ser reaberta com o humano.

## Notas

- Confirmar se o DBeaver com `p2/` processa `dropins/` (reconciler). Se não processar, testar `-Dorg.eclipse.equinox.p2.reconciler.dropins.directory=...` e registrar o que funcionou — a instalação final (issue 20) depende disso.

## Comments

### 2026-10-13 — implementação e resultados (agente)

**Veredito:** o `org.eclipse.debug.ui` 3.20.0 funciona no DBeaver 26.0.2. Resolve, ativa, a perspectiva Debug abre e as views Debug e Variables funcionam, inclusive a edição de variável. A premissa técnica do ADR 0004 se sustenta. **O que não se sustenta é a parte "só colocar no `dropins`"** (ver abaixo). Essa decisão fica com o humano, junto com o ADR 0004 e a issue 20.

**Build**
- `pom.xml` na raiz (Tycho 5.0.4) + bundle `bundles/dev.fbdebug.dbeaver` (Java 21). Maven Wrapper incluído: `./mvnw verify`.
- Tycho 5 exige **JDK 21** rodando o Maven (`JAVA_HOME`). A máquina só tem o JDK 17 no PATH; o build foi validado com um Temurin 21 temporário.
- ~~Target platform inline no `pom.xml`~~ *(substituído em 2026-10-14)*: a target platform agora fica em `releng/dev.fbdebug.dbeaver.target/dev.fbdebug.dbeaver.target.target`, compartilhada pelo Tycho e pela IDE, com `${env_var:LOCALAPPDATA}/DBeaver/plugins`. Com isso deixou de existir a sobrescrita por `-Ddbeaver.home`.
- Repositório do `debug.ui`: `https://download.eclipse.org/eclipse/updates/4.39/R-4.39-202602260420`. Conferido: o `debug.core` dessa release é `3.23.200.v20251107-0507`, idêntico ao do DBeaver. O `debug.ui` correspondente é `3.20.0.v20260209-1220`, e todas as dependências dele já existem no DBeaver.

**`dropins/` não é processado pelo DBeaver 26.0.2**
- O bundle `org.eclipse.equinox.p2.reconciler.dropins` existe, mas está com `4,false` no `bundles.info`. Fica `RESOLVED` e nunca inicia, então os jars do `dropins/` são ignorados (`ss` não lista nenhum dos dois).
- `-Dorg.eclipse.equinox.p2.reconciler.dropins.directory=...` (via `--launcher.appendVmargs -vmargs`): **sem efeito**. A propriedade é lida (`getprop`), mas o bundle continua parado.
- **O que funcionou:** iniciar o reconciler uma vez pelo console (`start <id>`). Ele instala os dois jars no perfil p2 e grava ambos no `bundles.info`. A partir daí carregam em toda inicialização, sem nenhuma flag.
- **Remoção:** tirar os jars do `dropins/` faz os bundles pararem de carregar, mas o `bundles.info` e o perfil p2 continuam apontando para eles. No primeiro start, o workbench emite 4 avisos únicos ("Removing PartDescriptorImpl ... DebugView/BreakpointView/VariableView/ExpressionView"). Só com o reconciler rodando de novo o p2 desinstala: o `bundles.info` volta ao mesmo conteúdo (muda só o fim de linha), mas o perfil p2 fica com histórico novo e com o repositório "dropins" registrado. Para o critério de remoção, restaurei `configuration/` e `p2/` de um backup e conferi por hash contra o snapshot original.
- Para a issue 20, ainda **não testados**: (a) instalar por p2 ("Install New Software" a partir de um repositório p2 local/zip com uma feature contendo os dois bundles), (b) forçar o start do reconciler pelo `dbeaver.ini`, (c) editar o `bundles.info` à mão (frágil).
- Achado relacionado: o perfil p2 do DBeaver já traz cadastrado o update site oficial "DBeaver Debug extension" (`https://dbeaver.io/update/debug/latest/`), com `org.jkiss.dbeaver.debug.core/ui` e o debugger do PostgreSQL. A feature dele **também requer `org.eclipse.debug.ui` vindo de fora** (o perfil tem o repositório do Eclipse 2025-12 cadastrado). Ou seja, o caminho oficial do próprio DBeaver para debug é a instalação via p2, não o `dropins`.

**Bundles e log** (com os jars instalados)
- `ss`: `org.eclipse.debug.ui` `ACTIVE`; `dev.fbdebug.dbeaver` `STARTING` (lazy, resolvido) na inicialização e `ACTIVE` depois do uso. O único `INSTALLED` é `org.eclipse.ui.win32.nls`, que já estava assim antes.
- Inicialização sem nenhuma entrada nova no `.log`. De quebra, sumiu um aviso que já existia: "Invalid preference category path: org.eclipse.debug.ui.ConsolePreferencePage" (`ui.console` procurava o `debug.ui`).
- **Ao abrir a perspectiva Debug** apareceram duas entradas novas (entradas para a issue 13/14/17):
  - ERRO: `Plug-in 'org.eclipse.ui.ide' contributed an invalid Menu Extension (Path: 'navigate/goTo' is invalid): goToResource`. Vem de um action set do `ui.ide` que a perspectiva Debug liga; o menu "Navigate" do DBeaver não tem o grupo `goTo`.
  - AVISO: conflito de atalho no **F7**: `org.eclipse.debug.ui.commands.StepReturn` (contexto `org.eclipse.debug.ui.debugging`) × `org.jkiss.dbeaver.core.resultset.grid.togglePreview` (contexto `org.jkiss.dbeaver.ui.context.resultset`). Afeta o step return padrão do Eclipse (issue 17).

**UI** (testado pelo humano: tudo funcionou)
- Launch pelo menu "PSQL Debug > Depurar protótipo Firebird PSQL" (o DBeaver não exibe o menu Run do `debug.ui` fora da perspectiva Debug). A config de launch não é salva no workspace. O delegate implementa só `ILaunchConfigurationDelegate`, para o `debug.ui` não disparar build do workspace.
- A troca de perspectiva é feita pelo delegate **antes** do evento de suspensão. Sem isso, o `debug.ui` pergunta se deve trocar (o padrão da preferência é "prompt").

**Ctrl+Shift+B: funciona, mas exigiu três contornos (entradas para a issue 13)**
1. O `debug.ui` só liga `org.eclipse.debug.ui.commands.ToggleBreakpoint` a um handler pelo `breakpointActionSet`, que o DBeaver não exibe. O plugin registra um handler próprio para o comando, que delega ao `ToggleBreakpointsTargetManager`, mais um `toggleBreakpointsTargetFactories`.
2. O editor de objeto do DBeaver é um `MultiPageEditorPart` (`MultiPageAbstractEditor`). Para achar o editor de texto, o protótipo tenta nesta ordem: a própria part como `ITextEditor`, a página selecionada (`getSelectedPage()`) e `getAdapter(ITextEditor.class)`. Não foi verificado qual desses caminhos resolveu no teste.
3. O modelo de anotações do editor do DBeaver não é baseado em marker de arquivo. O breakpoint fica com o marker na raiz do workspace (`PERSISTED=false`), e a `MarkerAnnotation` é adicionada/removida à mão no modelo de anotações do editor. Removido pela view Breakpoints, o marcador some do ruler (listener).
- O protótipo aceita qualquer `ITextEditor`, não só procedure Firebird. Filtrar fica para a issue 13.

**Notas para quem repetir o teste**
- O console OSGi (`dbeaver.exe -console 5555`) é Gogo via telnet e só responde depois da negociação do tipo de terminal (TTYPE). Um `nc` cru não recebe nada.
- **Não use `close` no console**: o framework para fora da thread de UI, o processo trava e o `.log` ganha stack traces de `Invalid thread access`. Encerre o processo.

### 2026-10-14 — instalação via p2 (agente)

Decisão do humano: trocar o `dropins` pela instalação via p2 (ADR 0004 revisado; spec e issue 20 atualizadas).

**Build:** novos módulos `features/dev.fbdebug.dbeaver.feature` (inclui o plugin e o `org.eclipse.debug.ui` 3.20.0.v20260209-1220) e `releng/dev.fbdebug.dbeaver.repository` (`eclipse-repository` + `category.xml`). O `./mvnw verify` gera `firebird-psql-debugger-<versão>.zip`. Também ficou `targetDefinitionIncludeSource=ignore`, para o Tycho não tentar baixar o `debug.ui.source`.

**Testado com o p2 director headless** (`dbeaverc.exe -application org.eclipse.equinox.p2.director`, o mesmo motor do "Install New Software"):
- Instalação a partir do zip (`jar:file:...zip!/`): o pedido é satisfazível, e no start seguinte o `ss` mostra o `debug.ui` e o plugin resolvidos.
- Desinstalação (`-uninstallIU`): o `bundles.info` volta sem os dois bundles. Os jars ficam no repositório interno do p2 até o coletor de lixo rodar (comportamento normal do p2).
- **Achado:** o DBeaver não tem `artifacts.xml` na raiz da instalação. Por isso o p2 trata a pasta como "extension location" e guarda os bundles instalados em `configuration/org.eclipse.osgi/<id do p2.extensionlocation>/data/<hash>/`, e não em `plugins/`. Acontece igual com repositório por HTTP, então não depende do zip. Consequência: **um start com `-clean` apaga essa pasta e o plugin some em silêncio** (testado). O `platform.xml` também registra o site pelo id do bundle, que muda depois do `-clean`. Isso vale para qualquer extensão p2 no DBeaver. Ficou registrado no ADR 0004; o remédio é reinstalar.
- Repositório por HTTP: o p2 do DBeaver converte `http://` em `https://` (só funciona com `-Dp2.httpRule=allow`), então um servidor interno precisaria de HTTPS.
- **Falta** um teste pela UI ("Install New Software > Add > Archive"), para ver o diálogo de conteúdo não assinado e o reinício. Fica para a issue 20.

A instalação do DBeaver foi restaurada ao estado original depois dos testes (`configuration/` sem os caches OSGi, mais `p2/`, conferidos por hash).
