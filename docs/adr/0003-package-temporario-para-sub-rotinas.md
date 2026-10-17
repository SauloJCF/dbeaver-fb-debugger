# 0003 — Package temporário para sub-rotinas

Status: aceito — 2026-10-13 · revisado 2026-10-17 (sub-rotinas do Firebird 5 enxergam as variáveis da Rotina)

## Contexto

No Modo Careful, uma expressão como `x = a + SUB_F(b)` é enviada ao servidor, mas `SUB_F` (sub-rotina declarada dentro da Rotina) não existe fora dela.

## Decisão

Como o IBExpert, o plugin cria um Package temporário de debug (`DBG$...`) com as sub-rotinas da Rotina e reescreve as chamadas nas expressões para apontar para ele. O package é removido ao fim da Sessão de debug; ao abrir uma sessão, restos com o prefixo `DBG$` são varridos.

~~Sub-rotinas do Firebird não acessam variáveis da Rotina-mãe, então copiá-las para o package preserva a semântica.~~ Premissa falsa no Firebird 5 (ver revisão abaixo).

## Revisão — 2026-10-17

Conferido no Firebird 5 local durante a issue 11: a sub-rotina **lê e altera** os parâmetros de entrada e de saída da Rotina que a declara e as variáveis locais declaradas antes dela. Não enxerga os cursores da Rotina nem, numa trigger, `NEW`/`OLD` e os eventos. Um parâmetro ou variável da própria sub-rotina com o mesmo nome esconde o da Rotina. Dentro da sub-rotina, uma rotina de package só é alcançada com o nome da package (a privada, nem assim).

A cópia no package só é fiel para a sub-rotina que não usa essas variáveis, nem direto nem chamando outra que usa. Por isso:

- **Sub-rotina que não usa variáveis da Rotina**: vai para o package. Chamada dentro de expressão ou Fatia DML usa a cópia. Chamada Step into elegível é interpretada no step into e usa a cópia no step over (exceto no `FOR SELECT`, sempre interpretado, ver issue 10).
- **Sub-rotina que usa variáveis da Rotina**: fica fora do package. Chamada Step into elegível é sempre interpretada (no step over, sem a camada de debug parar nela), com as variáveis da Rotina compartilhadas. Chamada dentro de expressão ou Fatia DML é recusada pelo Pré-check (`UnsupportedConstruct.SUBROUTINE_USING_PARENT_VARIABLES`), seguindo o ADR 0001: nada é executado "mais ou menos".

Mecânica: um package `DBG$<CURRENT_CONNECTION>_<n>` por Rotina com sub-rotinas (a Rotina raiz e cada Rotina alcançada por step into), criado na primeira chamada no servidor que precisa dele. O DDL roda num `EXECUTE STATEMENT ... WITH AUTONOMOUS TRANSACTION` na própria conexão da sessão. Assim o package fica visível para a transação da sessão, também em `SNAPSHOT`, e nada da Rotina é commitado. A varredura remove só os packages de conexões que não estão mais em `MON$ATTACHMENTS`. Os de outra sessão em andamento ficam.

### Alternativas para as sub-rotinas que usam variáveis da Rotina (não implementadas)

- Passar as variáveis como parâmetros extras da cópia: resolve leitura numa function, mas não a escrita, e exige reescrever cada chamada dentro das expressões.
- Passar as variáveis por `RDB$SET_CONTEXT`: vale só para valores que cabem em `VARCHAR(255)` e não devolve as alterações feitas pela sub-rotina.

## Alternativas descartadas

- **Avaliar a sub-rotina no cliente e passar o resultado como parâmetro**: dispensa DDL, mas executa a sub-rotina mesmo quando a chamada é condicional (`CASE`, `COALESCE`), o que quebra a semântica.

## Consequências

- O debugger executa DDL no banco. Aceitável porque o alvo é sempre banco local de desenvolvimento.
- A limpeza precisa resistir a crash do DBeaver (varredura por prefixo).
- Sub-rotina que usa variáveis da Rotina não pode ser chamada dentro de expressão ou DML numa Rotina depurada: o launch é recusado com a posição da chamada.
- Não conferido: com um usuário sem privilégios diretos nas tabelas, a cópia pode não ter os privilégios que a Rotina tem. Num banco local acessado como SYSDBA isso não aparece.
