# 0001 — Interpretador no cliente

Status: aceito — 2026-10-13

## Contexto

O Firebird 5 não oferece API nem hook de debug no servidor (nada equivalente ao `pldebugger` do PostgreSQL). A equipe usa hoje o debugger do IBExpert, que simula a execução interpretando a Rotina e mandando os comandos ao servidor um a um.

## Decisão

O plugin interpreta a Rotina no cliente. A lógica procedural é executada pelo Interpretador; Fatias DML e Avaliações de expressão são enviadas ao servidor numa conexão dedicada, numa única transação por Sessão de debug.

## Alternativas descartadas

- **Instrumentação + UDR que bloqueia**: reescrever a Rotina com chamadas a uma função UDR que pausa num socket. Semântica exata e step into em triggers, mas exige instalar binário no servidor, recompilar objetos e manter código nativo. Preterida em favor do modelo já conhecido do IBExpert.
- **Hook nativo no motor**: inviável, não existe API.

## Consequências

- Nada é instalado no servidor (exceto o Package temporário de debug, ver ADR 0003).
- A semântica é aproximada; o risco de divergência é tratado por Testes diferenciais (ADR 0005).
- Triggers não são alcançáveis por step into a partir de DML; só como Trigger isolada.
- Chamadas dentro de Fatias DML são Executadas no servidor, sem step into.
- Savepoints implícitos de blocos com `WHEN` precisam ser emulados com `SAVEPOINT`/`ROLLBACK TO SAVEPOINT`, e erros do servidor mapeados para `GDSCODE`/`SQLCODE`/`SQLSTATE`.
- Construções não suportadas são recusadas pelo Pré-check, nunca executadas "mais ou menos".
