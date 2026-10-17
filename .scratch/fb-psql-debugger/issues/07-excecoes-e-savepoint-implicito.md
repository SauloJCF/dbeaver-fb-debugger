# 07 — Exceções, `WHEN` e savepoint implícito

Status: ready-for-agent
Blocked by: 04

ADR: 0001

## O que construir

- `EXCEPTION nome`, `EXCEPTION nome 'mensagem'`, `EXCEPTION nome USING (...)`, re-raise com `EXCEPTION` sem argumento dentro de handler.
- `WHEN EXCEPTION nome | GDSCODE x | SQLCODE n | SQLSTATE 'x' | ANY DO`.
- Erro do servidor (em Fatia DML ou Avaliação de expressão) mapeado para `GDSCODE` / `SQLCODE` / `SQLSTATE`, disponíveis também como variáveis de contexto no handler.
- Bloco com `WHEN` emula o savepoint implícito: `SAVEPOINT` ao entrar, `ROLLBACK TO SAVEPOINT` antes do handler, `RELEASE SAVEPOINT` ao sair normalmente.

## Critérios de aceite

- [x] Teste diferencial: bloco faz `INSERT`, depois falha, handler roda — o `INSERT` do bloco ~~foi desfeito~~ fica como no nativo. *(O motor não desfaz o `INSERT` já terminado; ver comentários.)*
- [x] Exceção não tratada termina a Rotina com o mesmo tipo/códigos/mensagem do nativo.
- [x] Handlers aninhados e re-raise cobertos.

## Comments

### 2026-10-16 — implementação (agente)

**A premissa do primeiro critério estava errada.** O motor **não** desfaz o bloco desde a entrada quando um handler trata o erro: o `INSERT` já terminado do bloco fica. O critério foi mantido no que importa ("igual ao nativo"), e o ADR 0001 e a spec ganharam a correção. A regra real, tirada do `BlockNode::execute`/`EXE_looper` do Firebird 5 e conferida no servidor, está abaixo.

**Onde ficou**
- Gramática: tokens `WHEN`, `EXCEPTION` (não reservada: pode ser nome de variável) e `USING`. Regras `handler`/`errorCondition` no fim do `block` e `raiseStatement`. `SQLCODE`, `GDSCODE`, `SQLSTATE` e `ANY` saem como `IDENTIFIER` e o `AstBuilder` confere qual veio.
- AST: `Block.handlers`, `Handler`, `ErrorCondition` (`UserException`, `SqlCode`, `GdsCode`, `SqlState`, `AnyError`), `Raise`.
- `EXCEPTION nome [mensagem | USING (...)]` é levantada pelo próprio servidor (`ExceptionRaiser`), num `EXECUTE BLOCK` com os valores já avaliados. Assim o número da exceção, o código, o SQLSTATE e a formatação dos `@1` (`1.50`, `*** null ***`, datas, booleanos) são os do motor.
- `GDSCODE`/`SQLCODE`/`SQLSTATE` (`ErrorStatus`): o Jaybird põe o status vector inteiro como causa da `SQLException`, um item por código. O `GDSCODE` é o primeiro código, e **não** o `getErrorCode()` do Jaybird, que escolhe um código mais específico (divisão por zero: o Jaybird dá 335544778, o motor dá 335544321). O `SQLCODE` vem do argumento do `isc_sqlerr`, se houver (erros de DSQL, ex. -204), senão da tabela do motor para o primeiro código. O `SQLSTATE` do Jaybird bate com o do motor. A tabela GDSCODE → nome/SQLCODE/SQLSTATE (`interp/gds-codes.txt`, 1470 códigos) é gerada por `tools/gds-codes.sh` a partir de `include/firebird/impl/msg/*.h` do FB5 instalado. Ela também resolve `WHEN GDSCODE nome`.
- Erros que o Interpretador detecta no cliente viraram erros do motor, que um `WHEN` trata (`ServerErrors`): singleton, `NULL` em variável `NOT NULL` (`isc_not_valid_for_var`, 42000) e os de cursor (issue 05).
- Savepoints (`SavepointStack`): todo `BEGIN ... END` abre um nível; o `FOR SELECT` abre um se o nível atual já tem alterações. Um erro tratado por um bloco com `WHEN` desfaz só os níveis abertos depois do bloco. Os savepoints do servidor são criados só antes da primeira alteração de um nível, e só se houver embaixo um bloco com `WHEN`. Sem isso, o erro desfaz a chamada inteira de qualquer jeito.
- `RDB$ERROR(...)` foi para o Pré-check (`RDB_ERROR`): no servidor ele só conhece erro de handler do próprio servidor, então daria sempre `NULL`.
- Testes: `ExceptionDifferentialTest` (9 casos, a maioria em laço sobre 15 tipos de erro: exceções de usuário com e sem mensagem/`USING`/nome entre aspas, divisão por zero, truncamento, conversão, chave duplicada, `CHECK`, FK, singleton, erro de DSQL no `EXECUTE STATEMENT`, `NOT NULL`, overflow) e `ExceptionParserTest` (5). O `EXC_SAVEPOINTS` cobre um caso por regra de savepoint abaixo.

**Prova de que os testes pegam divergência:** de propósito, desfiz o bloco desde a entrada (o modelo original desta issue), desliguei a saída antecipada dos handlers, dei savepoint sempre ao `FOR SELECT`, fiz o primeiro handler que casa ganhar e usei o código de erro do Jaybird como `GDSCODE`. Cada mudança derrubou pelo menos um teste diferencial. Depois restaurei o código.

**Comportamento do FB5 conferido no servidor**
- **O que um erro tratado desfaz**: só os savepoints acima do bloco que tem o `WHEN`, ou seja, o statement do bloco que estava em andamento, inteiro (um `BEGIN ... END` interno, um `IF` com bloco, a iteração em curso de um laço com bloco). Statements do bloco que já terminaram ficam. O handler roda no nível do bloco, e o que ele faz fica.
- `FOR SELECT` só tem savepoint próprio se o nível atual já tinha alterações quando ele abriu (é como o motor implementa a estabilidade de cursor). Sem alteração antes no bloco, as iterações já terminadas ficam; com alteração, o laço inteiro é desfeito. DML sem linhas, `SELECT ... INTO` e `EXECUTE STATEMENT` de consulta não contam como alteração; o que um bloco interno alterou conta.
- **Peculiaridade do motor**: se um bloco *dentro de um handler* trata um erro, o laço de execução daquele handler termina ali. A saída se propaga pelos handlers em execução até o laço principal, e cada bloco desses handlers libera o seu savepoint naquele ponto. O resto do handler continua, mas um erro que saia dele depois não desfaz mais o que os blocos e os handlers fizeram antes. Reproduzido em `SavepointStack.nestedErrorHandled`.
- Escolha do handler: o motor testa todos os `WHEN`, na ordem, e o que casa limpa o erro. Entre os específicos, ganha o primeiro. Um `WHEN ANY` depois de um que casou também casa, e é ele que roda, mas já com o erro limpo: `GDSCODE` 0, `SQLCODE` 0, `SQLSTATE` `'00000'`, e `EXCEPTION;` não faz nada. Com vários `ANY`, roda o último.
- `GDSCODE`/`SQLCODE`/`SQLSTATE` valem 0/0/`'00000'` fora de handler. Voltam a esses valores quando um handler termina, mesmo que ele esteja dentro de outro handler.
- `EXCEPTION;` sem erro pendente não faz nada (não é erro).
- `WHEN GDSCODE nome` usa o nome simbólico sem `isc_` e sem diferença de caixa (`CONVERT_ERROR` vale).
- Erro dentro do handler sobe para o bloco de fora, que desfaz o bloco e o handler (salvo a peculiaridade acima).

**Para as próximas issues**
- Issue 10: uma chamada de Rotina (`EXECUTE PROCEDURE`, `FOR SELECT ... FROM sp`) é também um nível de savepoint, e o que ela altera conta para o `FOR SELECT` seguinte. Uma chamada executada no servidor precisa marcar `SavepointStack.changed()` (não dá para saber se alterou: conservador). Um `WHEN` da Rotina chamadora trata erros da chamada interpretada como trata os locais.
- Issues 14/15: o Pré-check agora recusa `RDB$ERROR` e a mudança de variável de cursor aberto (`UnsupportedConstruct`); a mensagem de recusa em tempo de execução cita a posição do cursor, não a da atribuição.

**Não tratado (casos raros)**
- `EXECUTE STATEMENT` que não é consulta conta sempre como alteração quando não informa linhas (ex.: `EXECUTE PROCEDURE` dinâmico); um `FOR SELECT` logo depois pode ganhar savepoint onde o motor não daria.
- Função com efeito colateral chamada dentro de uma consulta (ex.: `SELECT F(...) ... INTO`) não conta como alteração para o `FOR SELECT`.
- Domínio com `NOT NULL`/`CHECK` (achado da issue 02) continua não testado.
