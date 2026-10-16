# 13 — Launch e modelo de debug do Eclipse

Status: ready-for-agent
Blocked by: 01, 02

ADR: 0004

## O que construir

- Substituir o debug target de mentira da issue 01 pelo real: `IDebugTarget` / thread / stack frames / variables sobre a sessão do Interpretador.
- Ação "Debug" no menu de contexto de uma procedure Firebird no navegador do DBeaver e no editor do objeto.
- Diálogo de launch: valores dos parâmetros de entrada, isolamento (`READ COMMITTED` padrão, `SNAPSHOT`), checkbox "commit ao terminar" (desmarcado).
- Conexão dedicada criada a partir da configuração da conexão do DBeaver (não reaproveitar o contexto do editor SQL).
- Sessão roda até o fim (ainda sem breakpoints), mostra os parâmetros de saída e faz rollback ou commit. Terminate aborta com rollback.
- Troca automática para a perspectiva Debug.
- Pré-check (issue 08) e bloqueio de editor sujo ("Salve (compile) ou descarte as alterações antes de depurar") recusam o launch com mensagem.

## Critérios de aceite

- [ ] Launch de uma procedure executável mostra as saídas corretas.
- [ ] Sem o checkbox, nada persiste; com o checkbox, persiste.
- [ ] Terminate no meio de um laço longo encerra a sessão e faz rollback.
- [ ] Editor sujo e construção não suportada recusam o launch com a mensagem e a posição.
- [ ] Nenhuma transação do editor SQL do DBeaver é afetada durante a sessão.

## Notas

- Verificar a API do DBeaver para abrir contexto de execução isolado a partir do `DBPDataSourceContainer` antes de abrir JDBC à mão.
