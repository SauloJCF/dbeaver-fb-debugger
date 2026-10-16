# 15 — Variables: ver e editar parâmetros e variáveis

Status: ready-for-agent
Blocked by: 14

## O que construir

- View Variables mostra parâmetros de entrada, de saída e variáveis locais do frame selecionado, com tipo declarado.
- Editar um valor durante a pausa: o texto digitado é convertido pelo servidor (`CAST(? AS <tipo>)`); erro de conversão é mostrado e o valor anterior é mantido.
- `NULL` editável explicitamente.

## Critérios de aceite

- [ ] Alterar uma variável usada num `IF` muda o caminho tomado no próximo step.
- [ ] Valor inválido para o tipo (ex.: texto em `INTEGER`) mostra erro e não altera a variável.
- [ ] Valores `NUMERIC`, `DATE`, `TIMESTAMP`, `BLOB SUB_TYPE TEXT` e `NULL` exibidos legivelmente.
