# 0005 — Fidelidade provada por Testes diferenciais

Status: aceito — 2026-10-13

## Contexto

Com o Interpretador no cliente (ADR 0001), o maior risco é o debugger divergir do motor real: mostrar fluxos ou dados que não aconteceriam na execução nativa.

## Decisão

A suíte de integração roda contra o Firebird 5 local. Cada caso executa a mesma Rotina no servidor e no Interpretador, cada uma na sua transação com rollback, e compara:

- parâmetros de saída;
- linhas emitidas por `SUSPEND`;
- exceção levantada (tipo, `GDSCODE`/`SQLCODE`/`SQLSTATE`, mensagem);
- estado das tabelas tocadas, antes do rollback.

Há uma Rotina de exemplo por construção suportada; rotinas reais de produção podem ser acrescentadas. Todo bug de fidelidade encontrado vira um novo caso.

## Alternativas descartadas

- **Testes unitários com resultado escrito à mão**: o gabarito pode estar tão errado quanto o Interpretador.
- **Só teste manual**: não protege contra regressão.

## Consequências

- Os testes exigem um Firebird 5 local acessível no build.
- O servidor é o gabarito; nenhuma expectativa é escrita à mão para semântica de PSQL.
