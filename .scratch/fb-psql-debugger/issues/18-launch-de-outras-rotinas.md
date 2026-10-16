# 18 — Launch de selecionável, function, package e `EXECUTE BLOCK`

Status: ready-for-agent
Blocked by: 06, 09, 13

## O que construir

- Ação "Debug" para function, rotina de package e procedure selecionável.
- `EXECUTE BLOCK`: ação "Debug" sobre o bloco selecionado no editor SQL (o texto é a fonte; a regra de editor sujo não se aplica aqui — o texto do editor é o código).
- Procedure selecionável: linhas emitidas por `SUSPEND` aparecem num resultado ao fim da sessão (e acumuladas durante a pausa).

## Critérios de aceite

- [ ] Cada tipo pode ser lançado, pausado em breakpoint e concluído com o resultado correto.
- [ ] Linhas de `SUSPEND` exibidas na ordem de emissão.

## Comments

- 2026-10-13 — Confirmado pelo humano: `EXECUTE BLOCK` é depurado a partir do texto do editor SQL, única exceção à regra "sempre a Fonte do banco".
