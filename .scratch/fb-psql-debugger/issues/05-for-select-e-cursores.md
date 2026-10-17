# 05 — `FOR SELECT` e cursores

Status: ready-for-agent
Blocked by: 04

## O que construir

- `FOR SELECT … INTO … DO` e `FOR EXECUTE STATEMENT … INTO … DO`: cursor no servidor na mesma transação, iterado pelo Interpretador.
- `FOR SELECT … AS CURSOR c DO`, `DECLARE … CURSOR FOR`, `OPEN`, `FETCH` (incluindo scroll), `CLOSE`, `ROW_COUNT`/`FOUND` após `FETCH`.
- Fora: `WHERE CURRENT OF` (fica para o Pré-check recusar).

## Critérios de aceite

- [x] Testes diferenciais com laço que faz DML em outra tabela a cada linha.
- [x] Laço com `LEAVE` fecha o cursor; nenhum cursor fica aberto ao fim da sessão.
- [x] Scroll cursor (`FETCH PRIOR/ABSOLUTE/RELATIVE`) igual ao nativo.

## Comments

### 2026-10-16 — implementação (agente)

**Onde ficou**
- Gramática: tokens `FOR`, `OPEN`, `FETCH`, `CLOSE`, `CURSOR` (todos reservados no FB5). Regras `forStatement` (texto opaco até o `DO`), `cursorDeclaration`, `openStatement`, `fetchStatement`, `closeStatement`. O `AstBuilder` separa o `SELECT`, os alvos do `INTO` e o `AS CURSOR c` do fim; o `SELECT` vira uma Fatia DML como as da issue 04.
- AST: `ForSelect`, `ForExecuteStatement`, `CursorDeclaration` (em `ProcedureDefinition.cursors`), `OpenCursor`, `Fetch` (com orientação e deslocamento), `CloseCursor`. Campos de cursor (`C.ID`, `:C.ID`) viram `Expression.Field`. Numa Fatia DML só contam com `:`, porque sem ele `C.ID` é coluna de tabela. Quem decide se o qualificador é cursor é o escopo, na hora de avaliar (`BoundSql`).
- Interpretador: `Cursor` (statement e result set do Jaybird). Sem `SCROLL`, as linhas vêm uma a uma; com `SCROLL`, todas são lidas no `OPEN` e a posição é do Interpretador. Os campos são reenviados como `CAST(? AS <tipo da coluna>)`, com o tipo montado da descrição da coluna (`ColumnTypes`), para valer também com `NULL`. Cursores declarados ficam no `Frame` e são fechados ao fim da Rotina, termine ela bem ou com erro.
- Testes: `CursorDifferentialTest` (9 casos) e `CursorParserTest` (7). O teste de cursor esquecido conta os statements da conexão em `MON$STATEMENTS` antes e depois da Rotina (fim normal e com erro). O `PreCheckTest` ganhou o `WHERE CURRENT OF` dentro de um `FOR SELECT ... AS CURSOR` de verdade, como a issue 08 pedia.

**Prova de que os testes pegam divergência:** de propósito, fiz o `FOR` zerar o `ROW_COUNT` a cada linha, contei a linha do `FETCH RELATIVE 0`, tirei o fechamento dos cursores no fim da Rotina e deixei mudar variável de cursor aberto. Cada mudança derrubou o teste correspondente. Depois restaurei o código.

**Comportamento do FB5 conferido no servidor**
- **O motor relê as variáveis do `SELECT` de um cursor aberto.** Mudar dentro do laço a variável do `WHERE` de um `FOR SELECT` muda as linhas seguintes. Num cursor declarado, mudar a variável depois do `OPEN` (e antes do primeiro `FETCH`) muda até a primeira linha. O cursor do Interpretador recebe os valores uma vez, e o Jaybird ainda busca as linhas em lote. Por isso isso é recusado: o Pré-check acha a atribuição no corpo ou no `INTO` do próprio `FOR SELECT` (`CURSOR_VARIABLE_CHANGED`, na posição da atribuição), e o Interpretador recusa durante a execução a mudança de variável usada por qualquer cursor aberto (o caso dos cursores declarados).
- `ROW_COUNT` segue o contador de linhas afetadas do motor (`writeFlag`/lidas/alteradas). O `FOR SELECT` zera o contador ao abrir e soma 1 a cada linha, sem zerar. Depois de uma DML no corpo, o `ROW_COUNT` continua mostrando as linhas da DML até o fim do laço. Depois de um `FOR` interno ou de um `SELECT ... INTO`, a contagem do laço de fora continua a partir da deles. `FETCH` zera e dá 1 ou 0. `OPEN`, `CLOSE` e `FOR EXECUTE STATEMENT` não mexem no contador.
- `FOUND` não existe no Firebird (é "Column unknown"); o que a issue chamava de `FOUND` é o `ROW_COUNT` depois do `FETCH`.
- `FETCH` sem linha deixa os alvos como estavam. `FETCH` sem `INTO` vale, e os campos são lidos como `C.campo`. Campo de cursor sem linha atual dá `HY109`/`isc_cursor_not_positioned`; cursor fechado dá `24000`/`isc_cursor_not_open` (no `FETCH` e no campo).
- `OPEN` de cursor aberto fecha e reabre sem erro. `CLOSE` de cursor fechado não faz nada.
- Scroll: a posição vai de "antes da primeira" a "depois da última" e para nos extremos. `ABSOLUTE 0` vai para antes da primeira; `ABSOLUTE` negativo conta do fim; `ABSOLUTE NULL` não traz linha e não move. `FETCH RELATIVE 0` relê a linha atual e atribui, mas o `ROW_COUNT` fica 0.
- Desde o FB3, um cursor aberto não vê o que a própria transação altera depois (estabilidade de cursor). Inserir na tabela lida, dentro do laço, não gera linhas novas. É o que permite ler um cursor `SCROLL` inteiro no `OPEN`.
- `CLOSE`, `CURSOR`, `FETCH`, `FOR`, `OPEN`, `SCROLL` e `NO` são reservadas; `NEXT`, `PRIOR`, `FIRST`, `LAST`, `ABSOLUTE` e `RELATIVE` não.

**Achado de build:** os tokens do ANTLR são constantes `static final int`, copiadas para dentro das classes que as usam. Quando a gramática ganha tokens, o build incremental do Tycho não recompila quem não mudou (o `DynamicSql` passou a comparar com o número antigo do `COLON`). Depois de mexer na gramática, use `./mvnw clean verify`.

**Não tratado (casos raros)**
- `SELECT ... WHERE ... :X ... INTO :X` singleton: o motor atribui a primeira linha e relê `X` ao procurar a segunda. Não foi testado.
- Campo de um cursor declarado usado no `SELECT` de outro cursor aberto, com `FETCH` no primeiro durante o laço: mesma releitura do motor, não recusada.
- Coluna `CHAR`/`VARCHAR` de cursor com mais de 8191 caracteres é reenviada sem `CHARACTER SET` no `CAST` (vale o padrão do banco).
