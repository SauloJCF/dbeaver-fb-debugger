# Spec — Firebird PSQL Debugger (MVP)

Status: needs-triage

Vocabulário: ver `GLOSSARY.md`. Decisões de arquitetura: `docs/adr/0001` a `0005`.

## Objetivo

Depurar Rotinas PSQL do Firebird 5 dentro do DBeaver CE, com breakpoints, step e inspeção/edição de variáveis, substituindo o debugger do IBExpert no dia a dia da equipe.

## Ambiente alvo

- DBeaver CE **26.2.2**, fixado (cada upgrade revalidado à mão).
- Firebird **5.0**, banco **local de desenvolvimento** (Windows, `ServerMode = Super`).
- Distribuição interna como repositório p2 em zip, instalado por "Help > Install New Software > Add > Archive" (ADR 0004). A feature "Firebird PSQL Debugger" inclui o plugin (`dev.fbdebug.dbeaver`, com runtime ANTLR embutido) e o `org.eclipse.debug.ui` da versão da plataforma do DBeaver.

## Escopo do MVP

### Rotinas suportadas

- Procedure executável
- Procedure selecionável (`SUSPEND`): linhas emitidas aparecem como resultado da sessão
- `EXECUTE BLOCK`
- Function
- Rotina de package
- Trigger de tabela, apenas como **Trigger isolada**: `NEW.*`, `OLD.*` e `INSERTING`/`UPDATING`/`DELETING` (boolean, padrão `FALSE`) são entradas do launch

### Recursos de debug

- Breakpoints de linha (ruler do editor, Ctrl+Shift+B), não persistentes: existem só enquanto o editor da Rotina está aberto
- Resume, step over, step into, step return (atalhos padrão do Eclipse)
- Variables: parâmetros de entrada/saída e variáveis locais, editáveis durante a pausa
- Expressions (watches), avaliadas no servidor (Modo Careful)
- Step into apenas em **Step into elegível**; demais chamadas são **Executadas no servidor**, com aviso na linha
- Sub-rotinas internas: step into interpretado; chamadas em expressões resolvidas via Package temporário de debug

### Launch

- Iniciado a partir de uma Rotina no DBeaver (não há attach).
- Diálogo com: valores dos parâmetros de entrada (ou `NEW`/`OLD`/eventos, para trigger), isolamento (`READ COMMITTED` padrão, `SNAPSHOT` opcional), checkbox "commit ao terminar" (desmarcado).
- Bloqueado se o editor da Rotina tiver alterações não salvas: "Salve (compile) ou descarte as alterações antes de depurar".
- O código depurado é sempre a Fonte do banco; Rotinas alcançadas por step into também vêm do banco. Única exceção: `EXECUTE BLOCK`, que não existe no banco e é depurado a partir do texto selecionado no editor SQL.
- Pré-check: se houver construção não suportada, lista com linha/coluna e recusa o launch.
- Troca automática para a perspectiva Debug.

### Execução

- Conexão dedicada por Sessão de debug, criada a partir da configuração da conexão do DBeaver; uma transação.
- Ao terminar ou abortar: rollback, a menos que "commit ao terminar" esteja marcado.
- Interpretador sobre AST de gramática ANTLR4 da camada procedural; Fatias DML opacas, com `:var` → `?` e alvos de `INTO` capturados.
- Toda expressão avaliada via `SELECT <expr> FROM RDB$DATABASE` com `CAST(? AS <tipo>)`.
- Blocos com `WHEN` emulam o savepoint implícito (`SAVEPOINT` / `ROLLBACK TO SAVEPOINT`) como o motor: um erro tratado desfaz o statement em andamento do bloco, não o que os statements já terminados fizeram. Erros do servidor mapeados para `GDSCODE`/`SQLCODE`/`SQLSTATE`.
- Package temporário `DBG$...` removido ao fim; restos varridos ao abrir sessão.

## Fora do MVP

- Modo Fast
- Collect Statistics / Performance Analysis
- Breakpoints por comentário `IBE_BREAKPOINT`
- Aba Last Statement
- Arrastar célula do grid para parâmetro/variável
- Attach a execuções iniciadas pela aplicação
- Step into em triggers a partir de DML; triggers de banco/DDL
- Breakpoints persistentes
- Construções recusadas pelo Pré-check (ex.: `WHERE CURRENT OF`, `EXECUTE STATEMENT … ON EXTERNAL`, `IN AUTONOMOUS TRANSACTION`, `ROW_COUNT` dentro de DML) — a lista fica no enum `UnsupportedConstruct` (`bundles/dev.fbdebug.dbeaver/src/dev/fbdebug/dbeaver/psql/ast/UnsupportedConstruct.java`)

## Build e qualidade

- Maven + Tycho, target platform apontando para o diretório da instalação do DBeaver 26.2.2.
- Testes diferenciais contra o Firebird 5 local (ADR 0005): uma Rotina de exemplo por construção suportada, comparando execução nativa × Interpretador.

## Critérios de aceite

1. Com a feature instalada pelo zip do repositório p2, o DBeaver 26.2.2 inicia sem erro e oferece "Debug" para Rotinas de uma conexão Firebird 5.
2. Uma procedure com `IF`, `WHILE`, `FOR SELECT`, `WHEN` e `SUSPEND` pode ser percorrida por breakpoint, step over/into/return, com Variables e Expressions corretos a cada pausa.
3. Editar uma variável durante a pausa altera o fluxo subsequente.
4. Step into em `EXECUTE PROCEDURE` abre a Rotina chamada na pausa; chamada dentro de DML é executada sem pausa e com aviso na linha.
5. Sub-rotina chamada em expressão funciona no Modo Careful; nenhum `DBG$...` sobra no banco após encerrar (inclusive após matar o DBeaver e abrir nova sessão).
6. Trigger isolada roda com `NEW`/`OLD`/eventos informados no launch.
7. Sem "commit ao terminar", nenhuma alteração da Rotina persiste após a sessão.
8. Launch é recusado com editor sujo e com construção não suportada (mensagem com linha/coluna).
9. Toda a suíte de Testes diferenciais passa contra o Firebird 5 local.
