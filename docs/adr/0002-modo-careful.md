# 0002 — Expressões avaliadas no servidor (Modo Careful)

Status: aceito — 2026-10-13

## Contexto

Atribuições, condições, watches e variáveis editadas exigem avaliar expressões PSQL: centenas de funções nativas, aritmética de `NUMERIC` escalado, semântica de `NULL`, charset e collation.

## Decisão

Toda expressão é avaliada no servidor como `SELECT <expr> FROM RDB$DATABASE`, com cada variável enviada como `CAST(? AS <tipo declarado>)`. É o Modo Careful, padrão do IBExpert e único modo do MVP.

## Alternativas descartadas

- **Avaliação local em Java**: rápida, mas reimplementar a semântica do Firebird é grande e cada diferença gera resultado errado silencioso.
- **Híbrido (Modo Fast)**: adiado. Só entra depois que os Testes diferenciais provarem a fidelidade do Careful.

## Consequências

- Um round-trip por expressão; aceitável contra banco local.
- Expressões que chamam sub-rotinas internas não resolvem no servidor — ver ADR 0003.
