# Firebird PSQL Debugger

Plugin do DBeaver CE 26.0.2 para depurar Rotinas PSQL do Firebird 5. Vocabulário em `CONTEXT.md`, decisões em `docs/adr/`.

> Instalação e uso pela equipe: a escrever na issue 20 (`.scratch/fb-psql-debugger/issues/20-empacotamento-e-instalacao.md`).

## Desenvolvimento

### Requisitos

- DBeaver CE 26.0.2 instalado na pasta padrão (`%LOCALAPPDATA%\DBeaver`). Os bundles dele são a target platform.
- JDK 21 no `JAVA_HOME` (o Tycho 5 exige Java 21).
- Acesso a `download.eclipse.org`: o `org.eclipse.debug.ui` vem do repositório do Eclipse 4.39.

### Build pela linha de comando

```
.\mvnw verify
```

Gera o plugin em `bundles/dev.fbdebug.dbeaver/target/` e o repositório p2 instalável em `releng/dev.fbdebug.dbeaver.repository/target/firebird-psql-debugger-<versão>.zip`.

O parser PSQL é gerado pelo ANTLR a partir de `bundles/dev.fbdebug.dbeaver/antlr4/` em `target/generated-sources/antlr4/` (que o `build.properties` inclui como fonte). O runtime do ANTLR vai embutido no plugin (`lib/`).

Depois de mudar a gramática, use `.\mvnw clean verify`: a compilação incremental do Tycho não recompila as classes que só usam as constantes de token do léxico (o Java as embute), e um token novo desloca os números.

### Testes diferenciais

O `verify` roda os testes JUnit do plugin, e os Testes diferenciais (ADR 0005) precisam de um **Firebird 5 local**:

1. Copie `fbdebug-test.properties.example` para `fbdebug-test.properties` na raiz (o arquivo fica fora do git) e ajuste host, porta, credenciais e o caminho do banco.
2. O banco indicado em `database` é **apagado e recriado** a cada execução da suíte. Use um arquivo só para isso.

Para gerar o build sem um Firebird disponível: `.\mvnw verify -DskipTests`.

### IDE: Eclipse IDE for RCP and RAP Developers

1. Instale o pacote "Eclipse IDE for RCP and RAP Developers", 2026-03 ou mais novo.
2. **File > Import > Maven > Existing Maven Projects**, apontando para a raiz do repositório e importando todos os módulos. Se o Eclipse oferecer o conector do Tycho ("Tycho Project Configurators"), aceite.
3. Abra `releng/dev.fbdebug.dbeaver.target/dev.fbdebug.dbeaver.target.target` e clique em **Set as Active Target Platform** (canto superior direito do editor). Espere a resolução; na primeira vez ela baixa o `debug.ui`.
4. Para rodar o DBeaver com o plugin: clique com o botão direito em `DBeaver com Firebird PSQL Debugger.launch` (mesmo projeto) e escolha **Debug As > DBeaver com Firebird PSQL Debugger**. Breakpoints no código do plugin funcionam direto.
   - Esse DBeaver usa um workspace separado (`runtime-dbeaver-fbdebug`, ao lado do workspace do Eclipse), sem as conexões do DBeaver de uso diário. Crie lá a conexão com o Firebird local.

O `.target` é o mesmo usado pelo `mvnw`, então o que compila na IDE compila no build.

#### Testes na IDE

Os testes ficam em `bundles/dev.fbdebug.dbeaver/test/` e são JUnit comum, sem OSGi. O m2e não configura essa pasta sozinho, então faça uma vez por workspace:

1. No projeto do bundle, rode **Maven > Update Project** (Alt+F5). Isso roda o ANTLR e põe o JUnit, o Jaybird e o runtime do ANTLR em *Maven Dependencies*.
2. Em **Project > Properties > Java Build Path > Source**:
   - confira se aparecem `src` e `target/generated-sources/antlr4`, e adicione as que faltarem com *Add Folder…*;
   - adicione `test` e marque **Contains test sources: Yes**;
   - marque **Allow output folders for source folders** e defina o *Output folder* de `test` como `target/test-classes`. Uma pasta de testes precisa de uma pasta de saída própria.
3. Para rodar: botão direito em `test` > **Run As > JUnit Test**. Não use "JUnit Plug-in Test".

O `fbdebug-test.properties` é achado subindo a partir da pasta do projeto. Se o código de `src/` não achar `org.antlr...`, use **PDE Tools > Update Classpath…**.
