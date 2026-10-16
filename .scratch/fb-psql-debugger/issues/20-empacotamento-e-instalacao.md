# 20 — Empacotamento e instalação interna

Status: ready-for-agent
Blocked by: 17, 18, 19

## O que construir

- `mvn verify` produz o zip do repositório p2 (`releng/dev.fbdebug.dbeaver.repository/target/firebird-psql-debugger-<versão>.zip`) com a feature, o plugin final (runtime ANTLR embutido) e o `org.eclipse.debug.ui` fixado. A estrutura (feature + `eclipse-repository` + `category.xml`) já existe desde a issue 01; aqui falta só o conteúdo final do plugin.
- Remover o código do protótipo da issue 01.
- `README.md` com:
  - requisitos (DBeaver 26.0.2, Firebird 5 local; para compilar, JDK 21 no `JAVA_HOME`);
  - instalação: "Help > Install New Software > Add > Archive", escolher o zip, aceitar o conteúdo não assinado e reiniciar;
  - desinstalação: "Help > Installation Details > Firebird PSQL Debugger > Uninstall";
  - aviso: iniciar o DBeaver com `-clean` remove o plugin em silêncio, e é preciso reinstalar (ADR 0004, consequências);
  - onde ver o log em caso de erro.

## Critérios de aceite

- [ ] Instalação limpa num DBeaver 26.0.2 sem o plugin, seguindo só o README, permite cumprir os critérios de aceite 1–8 da spec.
- [ ] Suíte de Testes diferenciais inteira verde (critério 9 da spec).
