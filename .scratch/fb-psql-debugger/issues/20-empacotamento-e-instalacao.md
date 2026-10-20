# 20 — Empacotamento e instalação interna

Status: ready-for-agent
Blocked by: 17, 18, 19, 21

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
- [x] Suíte de Testes diferenciais inteira verde (critério 9 da spec).

## Comments

### 2026-10-19 — implementação (agente)

- **Build:** `.\mvnw clean verify` (JDK 21.0.12) termina em `BUILD SUCCESS` e gera `firebird-psql-debugger-0.1.0.<qualifier>.zip` (≈4 MB) com `features/dev.fbdebug.dbeaver.feature`, `plugins/dev.fbdebug.dbeaver` (com `lib/antlr4-runtime-4.13.2.jar` dentro) e `plugins/org.eclipse.debug.ui_3.20.0.v20260209-1220`. Nenhuma mudança de build foi necessária.
- **Testes:** 228 testes, 0 falhas, 0 ignorados, contra o Firebird 5 local (`fbdebug-test.properties`).
- **Protótipo:** não sobrou código da issue 01 (sem pacote `prototype`, sem `launchConfigurationType` de protótipo no `plugin.xml`). Nada a remover.
- **README:** seção "Instalação" com requisitos, instalar (Archive), desinstalar, aviso do `-clean` e onde ver o log (`%APPDATA%\DBeaverData\workspace6\.metadata\.log` e `dbeaver-debug.log`).
- **Falta (humano):** o primeiro critério. Instalação limpa pela UI num DBeaver 26.0.2 sem o plugin, seguindo só o README, e conferência dos critérios 1–8 da spec. Os nomes dos botões do assistente ("Install Anyway", "Restart Now") vêm do Eclipse 4.39 e não foram vistos na tela do DBeaver; corrigir no README se divergirem.

### 2026-10-19 — README com prints (agente)

- Prints do humano em `docs/images/`, referenciados no README (instalação passo a passo e nova seção "Utilização").
- Os prints corrigiram o README: o aviso de conteúdo não assinado é o diálogo *Trust Artifacts* (botão **Trust Selected**, não "Install Anyway"); antes dele aparece *Trust Authorities* para `https://dbeaver.io`; o menu de desinstalação é **Help > Installation Information** (não "Installation Details").
- O título da janela nos prints é "DBeaver 26.2.2", não 26.0.2.
