# 22 — Parâmetro vazio no launch dá erro do JDBC

Status: needs-triage
Blocked by: —

Spec: `../spec.md`

## O que acontece

No diálogo de launch, com o modo **Valor** e o campo em branco num parâmetro `INTEGER`, a sessão termina logo no início com um erro do Jaybird no console:

```
== SP_SOMA_ESTOQUE (READ COMMITTED)
   P_LIMITE =
Erro (SQLSTATE 07006, código 0): Unsupported set conversion requested for field null at index 1 (JDBC type INTEGER), source type: java.lang.String, reason:
Fim: rollback feito.
```

Visto no print `docs/images/in-debug.png` (DBeaver 26.2.2, procedure `SP_SOMA_ESTOQUE` do `samples/sample-db`).

## Por quê

O diálogo manda o texto do campo como está (`RoutineLaunchDialog`, `values[i].getText()`), inclusive `""`. O texto vazio vai como `String` para um `CAST(? AS INTEGER)`, e o Jaybird recusa a conversão antes de chegar ao servidor. A mensagem não diz qual parâmetro nem o que fazer.

## A decidir (humano)

O texto vazio é um valor legítimo para `CHAR`/`VARCHAR`/`BLOB SUB_TYPE TEXT`, então não dá para tratar "vazio" como `NULL` em todos os tipos. Opções:

1. **Validar no diálogo:** para tipo não texto, campo vazio com o modo **Valor** desabilita o OK e mostra no cabeçalho do diálogo "Informe um valor para P_LIMITE ou escolha NULL".
2. **Vazio vira `NULL`** só nos tipos não texto.
3. **Só melhorar a mensagem:** pegar o erro de conversão e mostrar "P_LIMITE: valor '' não é um INTEGER".

Sugestão: 1, mais 3 para os casos que o diálogo não pega (por exemplo, `abc` num `INTEGER`, se também cair no mesmo erro do Jaybird em vez de um erro de conversão do servidor). Vale o mesmo para `NEW.*`/`OLD.*` da Trigger isolada.

## Critérios de aceite

- [ ] Campo vazio num parâmetro não texto não chega ao JDBC; o usuário vê qual parâmetro corrigir.
- [ ] Campo vazio num parâmetro `VARCHAR` continua passando `''`.
- [ ] Um valor que não converte (`abc` em `INTEGER`) gera mensagem com o nome do parâmetro.
- [ ] O mesmo vale para `NEW.*`/`OLD.*` no launch de trigger.
