# 10 — Chamadas entre Rotinas no Interpretador

Status: ready-for-agent
Blocked by: 06, 09

## O que construir

- Classificar cada chamada como **Step into elegível** (`EXECUTE PROCEDURE`, `FOR SELECT … FROM sp(...)` com a procedure como única fonte, `x = F(...)` com a chamada como expressão inteira) ou **Executado no servidor** (qualquer chamada dentro de Fatia DML ou de expressão maior).
- Chamadas elegíveis podem ser interpretadas (pilha de frames, Fonte do banco da Rotina chamada) ou executadas no servidor — a escolha vem de quem controla a sessão (step into × step over). Selecionável chamada por `FOR SELECT` interpretada como gerador.
- Expor a pilha de frames (Rotina, linha, variáveis) para a camada de debug.

## Critérios de aceite

- [ ] Teste diferencial com cadeia A → B → C, todas interpretadas, igual ao nativo.
- [ ] Mesma cadeia com B executada no servidor dá o mesmo resultado.
- [ ] A classificação de cada chamada é testada por unidade, incluindo os casos-limite da spec (`INSERT … SELECT F(x)`, `WHERE id IN (SELECT … FROM sp)`).
