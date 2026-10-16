# 0003 — Package temporário para sub-rotinas

Status: aceito — 2026-10-13

## Contexto

No Modo Careful, uma expressão como `x = a + SUB_F(b)` é enviada ao servidor, mas `SUB_F` (sub-rotina declarada dentro da Rotina) não existe fora dela.

## Decisão

Como o IBExpert, o plugin cria um Package temporário de debug (`DBG$...`) com as sub-rotinas da Rotina e reescreve as chamadas nas expressões para apontar para ele. O package é removido ao fim da Sessão de debug; ao abrir uma sessão, restos com o prefixo `DBG$` são varridos.

Sub-rotinas do Firebird não acessam variáveis da Rotina-mãe, então copiá-las para o package preserva a semântica.

## Alternativas descartadas

- **Avaliar a sub-rotina no cliente e passar o resultado como parâmetro**: dispensa DDL, mas executa a sub-rotina mesmo quando a chamada é condicional (`CASE`, `COALESCE`), o que quebra a semântica.

## Consequências

- O debugger executa DDL no banco. Aceitável porque o alvo é sempre banco local de desenvolvimento.
- A limpeza precisa resistir a crash do DBeaver (varredura por prefixo).
