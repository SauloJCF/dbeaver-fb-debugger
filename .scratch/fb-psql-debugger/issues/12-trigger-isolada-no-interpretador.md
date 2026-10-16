# 12 — Trigger isolada no Interpretador

Status: ready-for-agent
Blocked by: 07, 09

## O que construir

- Carregar Fonte do banco de trigger de tabela (`RDB$TRIGGERS`), incluindo triggers universais (`BEFORE INSERT OR UPDATE OR DELETE`).
- `NEW.*` e `OLD.*` como entradas tipadas pelas colunas da tabela; `INSERTING`/`UPDATING`/`DELETING` como entradas boolean (padrão `FALSE`).
- Em `BEFORE`, atribuições a `NEW.campo` aparecem como saída da sessão.

## Critérios de aceite

- [ ] Teste diferencial: executar o DML nativo que dispara a trigger e comparar com o Interpretador alimentado com os mesmos `NEW`/`OLD` — mesmo estado das outras tabelas tocadas e mesmos `NEW.*` finais (lidos da linha gravada).
- [ ] Trigger de banco/DDL é recusada pelo Pré-check.
