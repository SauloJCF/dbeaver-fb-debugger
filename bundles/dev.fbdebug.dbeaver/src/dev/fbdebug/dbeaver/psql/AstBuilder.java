package dev.fbdebug.dbeaver.psql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.TerminalNode;

import dev.fbdebug.dbeaver.psql.ast.Assignment;
import dev.fbdebug.dbeaver.psql.ast.Block;
import dev.fbdebug.dbeaver.psql.ast.CloseCursor;
import dev.fbdebug.dbeaver.psql.ast.Continue;
import dev.fbdebug.dbeaver.psql.ast.CursorDeclaration;
import dev.fbdebug.dbeaver.psql.ast.DmlStatement;
import dev.fbdebug.dbeaver.psql.ast.ErrorCondition;
import dev.fbdebug.dbeaver.psql.ast.ExecuteStatement;
import dev.fbdebug.dbeaver.psql.ast.Exit;
import dev.fbdebug.dbeaver.psql.ast.Expression;
import dev.fbdebug.dbeaver.psql.ast.Fetch;
import dev.fbdebug.dbeaver.psql.ast.ForExecuteStatement;
import dev.fbdebug.dbeaver.psql.ast.ForSelect;
import dev.fbdebug.dbeaver.psql.ast.Handler;
import dev.fbdebug.dbeaver.psql.ast.If;
import dev.fbdebug.dbeaver.psql.ast.InAutonomousTransaction;
import dev.fbdebug.dbeaver.psql.ast.Leave;
import dev.fbdebug.dbeaver.psql.ast.OpenCursor;
import dev.fbdebug.dbeaver.psql.ast.ParameterDeclaration;
import dev.fbdebug.dbeaver.psql.ast.Raise;
import dev.fbdebug.dbeaver.psql.ast.Return;
import dev.fbdebug.dbeaver.psql.ast.RoutineDefinition;
import dev.fbdebug.dbeaver.psql.ast.SourcePosition;
import dev.fbdebug.dbeaver.psql.ast.Statement;
import dev.fbdebug.dbeaver.psql.ast.Suspend;
import dev.fbdebug.dbeaver.psql.ast.UnsupportedConstruct;
import dev.fbdebug.dbeaver.psql.ast.UnsupportedUse;
import dev.fbdebug.dbeaver.psql.ast.VariableDeclaration;
import dev.fbdebug.dbeaver.psql.ast.While;
import dev.fbdebug.dbeaver.psql.parser.PsqlLexer;
import dev.fbdebug.dbeaver.psql.parser.PsqlParser;

/** Árvore de parse do ANTLR → AST. */
final class AstBuilder {

    /** Quais identificadores de um texto opaco podem ser referência de variável. */
    private enum Candidates {
        /** Expressão PSQL: qualquer identificador solto, com ou sem {@code :}. */
        PSQL,
        /**
         * Fatia DML: só {@code :var} (o servidor recusa variável sem {@code :} em DML) e as variáveis de contexto
         * de erro, que só existem em PSQL.
         */
        DML
    }

    /** Variáveis de contexto do handler, que valem também dentro de uma Fatia DML. */
    private static final Set<String> ERROR_CONTEXT = Set.of("GDSCODE", "SQLCODE", "SQLSTATE");

    private final TokenStream tokens;
    /** Construções recusadas achadas dentro de expressões, em qualquer ponto da Rotina. */
    private final List<UnsupportedUse> unsupported = new ArrayList<>();
    /** A Rotina tem {@code SUSPEND}. Um builder monta uma Rotina só. */
    private boolean suspends;

    AstBuilder(TokenStream tokens) {
        this.tokens = tokens;
    }

    /** Procedure, function ou {@code EXECUTE BLOCK} avulsos. */
    RoutineDefinition routine(PsqlParser.RoutineDefinitionContext ctx) {
        if (ctx.procedureDefinition() != null) {
            PsqlParser.ProcedureDefinitionContext procedure = ctx.procedureDefinition();
            return procedure(null, name(procedure.identifier()), procedure.procedureSignature(),
                    procedure.routineBody());
        }
        if (ctx.functionDefinition() != null) {
            PsqlParser.FunctionDefinitionContext function = ctx.functionDefinition();
            return function(null, name(function.identifier()), function.functionSignature(),
                    function.routineBody());
        }
        PsqlParser.ExecuteBlockContext block = ctx.executeBlock();
        List<ParameterDeclaration> inputs = block.inputParameters() == null ? List.of()
                : block.inputParameters().parameterDeclaration().stream().map(this::blockInput).toList();
        return routine(RoutineDefinition.Kind.EXECUTE_BLOCK, null, null, inputs, outputs(block.outputParameters()),
                null, block.routineBody());
    }

    /**
     * Rotina de um {@code CREATE PACKAGE BODY}.
     *
     * @return {@code null} se o corpo não tiver a rotina
     */
    RoutineDefinition packageRoutine(PsqlParser.PackageDefinitionContext ctx, RoutineDefinition.Kind kind,
            String name) {
        if (ctx.BODY() == null) {
            throw new PsqlSyntaxException("esperado CREATE PACKAGE BODY", position(ctx));
        }
        PsqlParser.PackageRoutineContext routine = findPackageRoutine(ctx, kind, name);
        if (routine == null) {
            return null;
        }
        if (routine.routineBody() == null) {
            throw new PsqlSyntaxException("rotina sem corpo no CREATE PACKAGE BODY: " + name, position(routine));
        }
        String packageName = name(ctx.identifier());
        return kind == RoutineDefinition.Kind.FUNCTION
                ? function(packageName, name, routine.functionSignature(), routine.routineBody())
                : procedure(packageName, name, routine.procedureSignature(), routine.routineBody());
    }

    /**
     * Parâmetros de entrada de uma rotina declarada num {@code CREATE PACKAGE} (cabeçalho), onde ficam os valores
     * padrão.
     *
     * @return {@code null} se o cabeçalho não declarar a rotina (rotina privada)
     */
    List<ParameterDeclaration> packageRoutineInputs(PsqlParser.PackageDefinitionContext ctx,
            RoutineDefinition.Kind kind, String name) {
        PsqlParser.PackageRoutineContext routine = findPackageRoutine(ctx, kind, name);
        if (routine == null) {
            return null;
        }
        PsqlParser.InputParametersContext inputs = kind == RoutineDefinition.Kind.FUNCTION
                ? routine.functionSignature().inputParameters()
                : routine.procedureSignature().inputParameters();
        return inputs(inputs);
    }

    private PsqlParser.PackageRoutineContext findPackageRoutine(PsqlParser.PackageDefinitionContext ctx,
            RoutineDefinition.Kind kind, String name) {
        for (PsqlParser.PackageRoutineContext routine : ctx.packageRoutine()) {
            boolean function = routine.FUNCTION() != null;
            if (function == (kind == RoutineDefinition.Kind.FUNCTION) && name.equals(name(routine.identifier()))) {
                return routine;
            }
        }
        return null;
    }

    private RoutineDefinition procedure(String packageName, String name, PsqlParser.ProcedureSignatureContext sig,
            PsqlParser.RoutineBodyContext body) {
        return routine(RoutineDefinition.Kind.PROCEDURE, packageName, name, inputs(sig.inputParameters()),
                outputs(sig.outputParameters()), null, body);
    }

    private RoutineDefinition function(String packageName, String name, PsqlParser.FunctionSignatureContext sig,
            PsqlParser.RoutineBodyContext body) {
        if (sig.sqlSecurity() != null) {
            sqlSecurity(sig.sqlSecurity().getStart().getTokenIndex(), sig.sqlSecurity().getStop().getTokenIndex());
        }
        // Sem NOT NULL, o SQL SECURITY fica no fim do returnType.
        int typeStart = sig.returnType().getStart().getTokenIndex();
        int typeEnd = sig.returnType().getStop().getTokenIndex();
        List<Integer> visible = visible(typeStart, typeEnd);
        int n = visible.size();
        if (n > 3 && isWord(tokens.get(visible.get(n - 3)), "SQL")
                && isWord(tokens.get(visible.get(n - 2)), "SECURITY")) {
            sqlSecurity(visible.get(n - 3), typeEnd);
            typeEnd = visible.get(n - 4);
        }
        ParameterDeclaration returnValue = new ParameterDeclaration(name,
                tokens.getText(Interval.of(typeStart, typeEnd)), sig.notNull() != null, null,
                position(sig.returnType()));
        return routine(RoutineDefinition.Kind.FUNCTION, packageName, name, inputs(sig.inputParameters()), List.of(),
                returnValue, body);
    }

    /**
     * {@code SQL SECURITY DEFINER | INVOKER} nos tokens {@code [first, last]}. Não muda a execução: a Sessão de
     * debug roda com o usuário dela.
     */
    private void sqlSecurity(int first, int last) {
        List<Integer> words = visible(first, last);
        if (words.size() != 3 || !isWord(tokens.get(words.get(0)), "SQL")
                || !isWord(tokens.get(words.get(1)), "SECURITY") || !(isWord(tokens.get(words.get(2)), "DEFINER")
                        || isWord(tokens.get(words.get(2)), "INVOKER"))) {
            throw new PsqlSyntaxException("esperado SQL SECURITY DEFINER ou INVOKER", position(tokens.get(first)));
        }
    }

    private RoutineDefinition routine(RoutineDefinition.Kind kind, String packageName, String name,
            List<ParameterDeclaration> inputs, List<ParameterDeclaration> outputs, ParameterDeclaration returnValue,
            PsqlParser.RoutineBodyContext body) {
        List<VariableDeclaration> variables = new ArrayList<>();
        List<CursorDeclaration> cursors = new ArrayList<>();
        for (PsqlParser.DeclarationContext declaration : body.declaration()) {
            if (declaration.cursorDeclaration() != null) {
                cursors.add(cursor(declaration.cursorDeclaration()));
            } else {
                variables.add(variable(declaration.variableDeclaration()));
            }
        }
        Block block = block(body.block());
        return new RoutineDefinition(kind, packageName, name, inputs, outputs, returnValue, variables, cursors,
                block, suspends, unsupported);
    }

    private List<ParameterDeclaration> inputs(PsqlParser.InputParametersContext ctx) {
        return ctx == null ? List.of() : ctx.parameterDeclaration().stream().map(this::parameter).toList();
    }

    private List<ParameterDeclaration> outputs(PsqlParser.OutputParametersContext ctx) {
        return ctx == null ? List.of() : ctx.parameterDeclaration().stream().map(this::parameter).toList();
    }

    /** Parâmetro de entrada de {@code EXECUTE BLOCK}: {@code nome tipo = ?}, sem valor padrão. */
    private ParameterDeclaration blockInput(PsqlParser.ParameterDeclarationContext ctx) {
        PsqlParser.DefaultValueContext binding = ctx.defaultValue();
        if (binding == null || binding.EQ() == null || !text(binding.expression()).equals("?")) {
            throw new PsqlSyntaxException("parâmetro de EXECUTE BLOCK sem '= ?'", position(ctx));
        }
        return new ParameterDeclaration(name(ctx.identifier()), text(ctx.typeSpec()), ctx.notNull() != null, null,
                position(ctx));
    }

    private ParameterDeclaration parameter(PsqlParser.ParameterDeclarationContext ctx) {
        return new ParameterDeclaration(name(ctx.identifier()), text(ctx.typeSpec()), ctx.notNull() != null,
                defaultValue(ctx.defaultValue()), position(ctx));
    }

    private VariableDeclaration variable(PsqlParser.VariableDeclarationContext ctx) {
        return new VariableDeclaration(name(ctx.identifier()), text(ctx.typeSpec()), ctx.notNull() != null,
                defaultValue(ctx.defaultValue()), position(ctx));
    }

    private CursorDeclaration cursor(PsqlParser.CursorDeclarationContext ctx) {
        String option = ctx.IDENTIFIER().stream()
                .map(word -> word.getText().toUpperCase(Locale.ROOT))
                .collect(Collectors.joining(" "));
        boolean scroll = switch (option) {
            case "" , "NO SCROLL" -> false;
            case "SCROLL" -> true;
            default -> throw new PsqlSyntaxException("esperado SCROLL ou NO SCROLL antes de CURSOR",
                    position(ctx.IDENTIFIER(0).getSymbol()));
        };
        Token open = ctx.LPAREN().getSymbol();
        Token close = ctx.RPAREN().getSymbol();
        List<Integer> visible = visible(open.getTokenIndex() + 1, close.getTokenIndex() - 1);
        Token first = visible.isEmpty() ? close : tokens.get(visible.get(0));
        if (first.getType() != PsqlLexer.SELECT && first.getType() != PsqlLexer.WITH) {
            throw new PsqlSyntaxException("esperado o SELECT do cursor", position(first));
        }
        DmlStatement select = dml(first.getTokenIndex(), close.getTokenIndex() - 1, position(first));
        if (!select.into().isEmpty()) {
            throw new PsqlSyntaxException("o SELECT de um cursor declarado não tem INTO", position(first));
        }
        return new CursorDeclaration(name(ctx.identifier()), scroll, select, position(ctx));
    }

    private Expression defaultValue(PsqlParser.DefaultValueContext ctx) {
        return ctx == null ? null : expression(ctx.expression());
    }

    private Block block(PsqlParser.BlockContext ctx) {
        return new Block(ctx.statement().stream().map(this::statement).toList(),
                ctx.handler().stream().map(this::handler).toList(), position(ctx));
    }

    private Handler handler(PsqlParser.HandlerContext ctx) {
        return new Handler(ctx.errorCondition().stream().map(this::errorCondition).toList(),
                statement(ctx.statement()), position(ctx));
    }

    private ErrorCondition errorCondition(PsqlParser.ErrorConditionContext ctx) {
        if (ctx.EXCEPTION() != null) {
            return new ErrorCondition.UserException(name(ctx.identifier()));
        }
        Token word = ctx.IDENTIFIER(0).getSymbol();
        switch (word.getText().toUpperCase(Locale.ROOT)) {
            case "ANY" -> {
                if (ctx.getChildCount() == 1) {
                    return new ErrorCondition.AnyError();
                }
            }
            case "SQLCODE" -> {
                if (ctx.NUMBER() != null) {
                    int code = Integer.parseInt(ctx.NUMBER().getText());
                    TerminalNode sign = ctx.OPERATOR();
                    if (sign == null || sign.getText().equals("+")) {
                        return new ErrorCondition.SqlCode(code);
                    }
                    if (sign.getText().equals("-")) {
                        return new ErrorCondition.SqlCode(-code);
                    }
                }
            }
            case "GDSCODE" -> {
                if (ctx.IDENTIFIER().size() == 2) {
                    return new ErrorCondition.GdsCode(ctx.IDENTIFIER(1).getText());
                }
            }
            case "SQLSTATE" -> {
                if (ctx.STRING() != null) {
                    String literal = ctx.STRING().getText();
                    return new ErrorCondition.SqlState(literal.substring(1, literal.length() - 1));
                }
            }
            default -> {
            }
        }
        throw new PsqlSyntaxException("condição de WHEN não reconhecida: '" + text(ctx) + "'", position(word));
    }

    private Statement statement(PsqlParser.StatementContext ctx) {
        if (ctx.block() != null) {
            return block(ctx.block());
        }
        if (ctx.assignment() != null) {
            PsqlParser.AssignmentContext assignment = ctx.assignment();
            return new Assignment(name(assignment.identifier()), expression(assignment.expression()),
                    position(assignment));
        }
        if (ctx.ifStatement() != null) {
            PsqlParser.IfStatementContext ifStatement = ctx.ifStatement();
            List<PsqlParser.StatementContext> branches = ifStatement.statement();
            return new If(expression(ifStatement.condition()), statement(branches.get(0)),
                    branches.size() > 1 ? statement(branches.get(1)) : null, position(ifStatement));
        }
        if (ctx.whileStatement() != null) {
            PsqlParser.WhileStatementContext loop = ctx.whileStatement();
            return new While(label(loop.label()), expression(loop.condition()), statement(loop.statement()),
                    position(loop));
        }
        if (ctx.forStatement() != null) {
            return forStatement(ctx.forStatement());
        }
        if (ctx.leaveStatement() != null) {
            PsqlParser.LeaveStatementContext leave = ctx.leaveStatement();
            return new Leave(optionalName(leave.identifier()), position(leave));
        }
        if (ctx.continueStatement() != null) {
            PsqlParser.ContinueStatementContext next = ctx.continueStatement();
            return new Continue(optionalName(next.identifier()), position(next));
        }
        if (ctx.dmlStatement() != null) {
            PsqlParser.DmlStatementContext dml = ctx.dmlStatement();
            return dml(dml.getStart().getTokenIndex(), dml.getStop().getTokenIndex() - 1, position(dml));
        }
        if (ctx.executeStatement() != null) {
            PsqlParser.ExecuteStatementContext execute = ctx.executeStatement();
            Token semi = execute.getStop();
            return executeStatement(execute.getStart().getTokenIndex(), semi.getTokenIndex() - 1, semi,
                    position(execute));
        }
        if (ctx.openStatement() != null) {
            return new OpenCursor(name(ctx.openStatement().identifier()), position(ctx.openStatement()));
        }
        if (ctx.fetchStatement() != null) {
            return fetch(ctx.fetchStatement());
        }
        if (ctx.closeStatement() != null) {
            return new CloseCursor(name(ctx.closeStatement().identifier()), position(ctx.closeStatement()));
        }
        if (ctx.raiseStatement() != null) {
            return raise(ctx.raiseStatement());
        }
        if (ctx.suspendStatement() != null) {
            suspends = true;
            return new Suspend(position(ctx.suspendStatement()));
        }
        if (ctx.returnStatement() != null) {
            return new Return(expression(ctx.returnStatement().expression()), position(ctx.returnStatement()));
        }
        if (ctx.autonomousTransaction() != null) {
            return autonomousTransaction(ctx.autonomousTransaction());
        }
        return new Exit(position(ctx.exitStatement()));
    }

    /**
     * {@code FOR SELECT ... [INTO ...] [AS CURSOR c] DO} ou {@code FOR EXECUTE STATEMENT ... DO}: o texto vai do
     * token depois do {@code FOR} até antes do {@code DO}.
     */
    private Statement forStatement(PsqlParser.ForStatementContext ctx) {
        Token doToken = ctx.DO().getSymbol();
        Token start = tokens.get(visible(ctx.FOR().getSymbol().getTokenIndex() + 1, doToken.getTokenIndex()).get(0));
        int last = doToken.getTokenIndex() - 1;
        String label = label(ctx.label());
        Statement body = statement(ctx.statement());
        if (start.getType() == PsqlLexer.EXECUTE) {
            List<Integer> visible = visible(start.getTokenIndex(), last);
            if (visible.size() < 2 || tokens.get(visible.get(1)).getType() != PsqlLexer.STATEMENT) {
                throw new PsqlSyntaxException("esperado FOR EXECUTE STATEMENT", position(start));
            }
            ExecuteStatement execute = executeStatement(start.getTokenIndex(), last, doToken, position(start));
            if (execute.into().isEmpty()) {
                throw new PsqlSyntaxException("FOR EXECUTE STATEMENT sem INTO", position(doToken));
            }
            return new ForExecuteStatement(label, execute, body, position(ctx));
        }
        // AS CURSOR c fica no fim, depois do INTO.
        List<Integer> visible = visible(start.getTokenIndex(), last);
        String cursor = null;
        int n = visible.size();
        if (n > 3 && tokens.get(visible.get(n - 3)).getType() == PsqlLexer.AS
                && tokens.get(visible.get(n - 2)).getType() == PsqlLexer.CURSOR
                && isIdentifier(tokens.get(visible.get(n - 1)).getType())) {
            cursor = Identifiers.normalize(tokens.get(visible.get(n - 1)).getText());
            last = visible.get(n - 3) - 1;
        }
        DmlStatement select = dml(start.getTokenIndex(), last, position(start));
        if (select.into().isEmpty() && cursor == null) {
            throw new PsqlSyntaxException("FOR SELECT sem INTO nem AS CURSOR", position(doToken));
        }
        return new ForSelect(label, select, cursor, body, position(ctx));
    }

    /** {@code FETCH [<orientação> FROM] cursor [INTO ...]}. */
    private Fetch fetch(PsqlParser.FetchStatementContext ctx) {
        Token semi = ctx.getStop();
        List<Integer> visible = visible(ctx.getStart().getTokenIndex() + 1, semi.getTokenIndex() - 1);
        int from = -1;
        int depth = 0;
        for (int k = 0; k < visible.size() && from < 0; k++) {
            Token token = tokens.get(visible.get(k));
            depth += depthChange(token);
            if (depth == 0 && isWord(token, "FROM")) {
                from = k;
            }
        }
        Fetch.Orientation orientation = Fetch.Orientation.NEXT;
        Expression offset = null;
        int k = 0;
        if (from >= 0) {
            Token word = tokens.get(visible.get(0));
            orientation = orientation(word);
            boolean positioned =
                    orientation == Fetch.Orientation.ABSOLUTE || orientation == Fetch.Orientation.RELATIVE;
            if (positioned) {
                offset = expression(visible.subList(1, from), word);
            } else if (from != 1) {
                throw new PsqlSyntaxException("trecho não reconhecido no FETCH: '"
                        + tokens.get(visible.get(1)).getText() + "'", position(tokens.get(visible.get(1))));
            }
            k = from + 1;
        }
        if (k >= visible.size() || !isIdentifier(tokens.get(visible.get(k)).getType())) {
            Token wrong = k < visible.size() ? tokens.get(visible.get(k)) : semi;
            throw new PsqlSyntaxException("esperado o nome do cursor no FETCH", position(wrong));
        }
        String cursor = Identifiers.normalize(tokens.get(visible.get(k)).getText());
        k++;
        List<String> targets = List.of();
        if (k < visible.size()) {
            Token into = tokens.get(visible.get(k));
            if (into.getType() != PsqlLexer.INTO) {
                throw new PsqlSyntaxException("trecho não reconhecido no FETCH: '" + into.getText() + "'",
                        position(into));
            }
            targets = targets(visible.subList(k, visible.size()));
        }
        return new Fetch(cursor, orientation, offset, targets, position(ctx));
    }

    private static Fetch.Orientation orientation(Token word) {
        if (word.getType() == PsqlLexer.IDENTIFIER) {
            try {
                return Fetch.Orientation.valueOf(word.getText().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                // cai no erro abaixo
            }
        }
        throw new PsqlSyntaxException("orientação de FETCH não reconhecida: '" + word.getText() + "'",
                position(word));
    }

    private Raise raise(PsqlParser.RaiseStatementContext ctx) {
        if (ctx.identifier() == null) {
            return new Raise(null, null, List.of(), position(ctx));
        }
        String exception = name(ctx.identifier());
        List<Expression> expressions = ctx.expression().stream().map(this::expression).toList();
        if (ctx.USING() != null) {
            return new Raise(exception, null, expressions, position(ctx));
        }
        return new Raise(exception, expressions.isEmpty() ? null : expressions.get(0), List.of(), position(ctx));
    }

    /** Fatia DML dos tokens {@code [first, last]}, sem o {@code ;}. */
    private DmlStatement dml(int first, int last, SourcePosition position) {
        List<Integer> visible = visible(first, last);
        DmlStatement.Kind kind = dmlKind(visible);
        // INSERT INTO, MERGE INTO e UPDATE OR INSERT INTO nomeiam a tabela; qualquer outro INTO é o das variáveis.
        int tableInto = switch (kind) {
            case INSERT, MERGE -> visible.get(1);
            case UPDATE_OR_INSERT -> visible.get(3);
            default -> -1;
        };
        List<UnsupportedUse> unsupported = new ArrayList<>();
        int into = -1;
        int depth = 0;
        for (int k = 0; k < visible.size() && into < 0; k++) {
            Token token = tokens.get(visible.get(k));
            depth += depthChange(token);
            if (depth == 0 && token.getType() == PsqlLexer.INTO && token.getTokenIndex() != tableInto) {
                into = k;
            } else if (isWord(token, "ROW_COUNT")) {
                unsupported.add(new UnsupportedUse(UnsupportedConstruct.ROW_COUNT_IN_DML, position(token)));
            } else if (isWord(token, "WHERE") && k + 2 < visible.size()
                    && isWord(tokens.get(visible.get(k + 1)), "CURRENT")
                    && isWord(tokens.get(visible.get(k + 2)), "OF") && depth == 0) {
                unsupported.add(new UnsupportedUse(UnsupportedConstruct.WHERE_CURRENT_OF, position(token)));
            }
        }
        // O servidor só aceita o INTO no fim do statement: o que vem depois dele são só os alvos.
        List<String> targets = into < 0 ? List.of() : targets(visible.subList(into, visible.size()));
        int lastSent = into < 0 ? visible.get(visible.size() - 1) : visible.get(into) - 1;
        return new DmlStatement(kind, expression(first, lastSent, Candidates.DML), targets, unsupported,
                position);
    }

    private DmlStatement.Kind dmlKind(List<Integer> visible) {
        return switch (tokens.get(visible.get(0)).getType()) {
            case PsqlLexer.SELECT, PsqlLexer.WITH -> DmlStatement.Kind.SELECT;
            case PsqlLexer.INSERT -> DmlStatement.Kind.INSERT;
            case PsqlLexer.DELETE -> DmlStatement.Kind.DELETE;
            case PsqlLexer.MERGE -> DmlStatement.Kind.MERGE;
            default -> visible.size() > 3 && tokens.get(visible.get(1)).getType() == PsqlLexer.OR
                    && tokens.get(visible.get(2)).getType() == PsqlLexer.INSERT
                            ? DmlStatement.Kind.UPDATE_OR_INSERT
                            : DmlStatement.Kind.UPDATE;
        };
    }

    /**
     * {@code EXECUTE STATEMENT <sql> [(<parâmetros>)] [<opções>] [INTO <alvos>]} nos tokens {@code [first, last]},
     * onde {@code <sql>} entre parênteses é o que permite passar parâmetros.
     *
     * @param end token logo depois do trecho ({@code ;} ou {@code DO}), para a posição de erro
     */
    private ExecuteStatement executeStatement(int first, int last, Token end, SourcePosition position) {
        List<Integer> visible = visible(first, last);
        int k = 2; // depois de EXECUTE STATEMENT
        if (k >= visible.size()) {
            throw new PsqlSyntaxException("falta o texto do EXECUTE STATEMENT", position(end));
        }
        Expression sql;
        List<ExecuteStatement.Parameter> parameters = List.of();
        if (tokens.get(visible.get(k)).getType() == PsqlLexer.LPAREN) {
            int close = matchingParenthesis(visible, k);
            sql = expression(visible.subList(k + 1, close), tokens.get(visible.get(k)));
            k = close + 1;
            if (k < visible.size() && tokens.get(visible.get(k)).getType() == PsqlLexer.LPAREN) {
                close = matchingParenthesis(visible, k);
                parameters = executeStatementParameters(visible.subList(k + 1, close),
                        tokens.get(visible.get(k)));
                k = close + 1;
            }
        } else {
            int optionStart = nextOption(visible, k);
            sql = expression(visible.subList(k, optionStart), tokens.get(visible.get(k)));
            k = optionStart;
        }

        List<UnsupportedUse> unsupported = new ArrayList<>();
        List<String> targets = List.of();
        while (k < visible.size()) {
            Token token = tokens.get(visible.get(k));
            if (token.getType() == PsqlLexer.INTO) {
                targets = targets(visible.subList(k, visible.size()));
                break;
            }
            if (token.getType() == PsqlLexer.WITH && k + 2 < visible.size()) {
                Token what = tokens.get(visible.get(k + 1));
                Token which = tokens.get(visible.get(k + 2));
                if (isWord(what, "AUTONOMOUS") && isWord(which, "TRANSACTION")) {
                    unsupported.add(new UnsupportedUse(UnsupportedConstruct.EXECUTE_STATEMENT_AUTONOMOUS,
                            position(token)));
                    k += 3;
                    continue;
                }
                if ((isWord(what, "COMMON") && isWord(which, "TRANSACTION"))
                        || (isWord(what, "CALLER") && isWord(which, "PRIVILEGES"))) {
                    k += 3; // o padrão já é a transação da sessão; privilégios não mudam a execução
                    continue;
                }
            }
            UnsupportedConstruct construct = token.getType() == PsqlLexer.AS || isWord(token, "PASSWORD")
                    || isWord(token, "ROLE") ? UnsupportedConstruct.EXECUTE_STATEMENT_AS_USER
                            : isWord(token, "ON") ? UnsupportedConstruct.EXECUTE_STATEMENT_ON_EXTERNAL : null;
            if (construct == null) {
                throw new PsqlSyntaxException(
                        "opção de EXECUTE STATEMENT não reconhecida: '" + token.getText() + "'", position(token));
            }
            if (unsupported.stream().noneMatch(use -> use.construct() == construct)) {
                unsupported.add(new UnsupportedUse(construct, position(token)));
            }
            k = nextOption(visible, k + 1); // os argumentos da opção recusada não importam
        }
        return new ExecuteStatement(sql, parameters, targets, unsupported, position);
    }

    /** Início da próxima opção do {@code EXECUTE STATEMENT} fora de parênteses, ou o fim do statement. */
    private int nextOption(List<Integer> visible, int from) {
        int depth = 0;
        for (int k = from; k < visible.size(); k++) {
            Token token = tokens.get(visible.get(k));
            depth += depthChange(token);
            if (depth == 0 && (token.getType() == PsqlLexer.WITH || token.getType() == PsqlLexer.AS
                    || token.getType() == PsqlLexer.INTO || isWord(token, "ON") || isWord(token, "PASSWORD")
                    || isWord(token, "ROLE"))) {
                return k;
            }
        }
        return visible.size();
    }

    /** {@code valor, ...} ou {@code [EXCESS] nome := valor, ...}. */
    private List<ExecuteStatement.Parameter> executeStatementParameters(List<Integer> visible, Token open) {
        List<ExecuteStatement.Parameter> parameters = new ArrayList<>();
        int start = 0;
        int depth = 0;
        for (int k = 0; k <= visible.size(); k++) {
            Token token = k < visible.size() ? tokens.get(visible.get(k)) : null;
            if (token != null) {
                depth += depthChange(token);
                if (depth != 0 || token.getType() != PsqlLexer.COMMA) {
                    continue;
                }
            }
            Token before = k == 0 ? open : tokens.get(visible.get(k - 1));
            parameters.add(executeStatementParameter(visible.subList(start, k), before));
            start = k + 1;
        }
        return parameters;
    }

    private ExecuteStatement.Parameter executeStatementParameter(List<Integer> visible, Token before) {
        boolean excess = visible.size() > 4 && isWord(tokens.get(visible.get(0)), "EXCESS");
        int name = excess ? 1 : 0;
        if (visible.size() > name + 3 && isIdentifier(tokens.get(visible.get(name)).getType())
                && tokens.get(visible.get(name + 1)).getType() == PsqlLexer.COLON
                && tokens.get(visible.get(name + 2)).getType() == PsqlLexer.EQ) {
            Expression value = expression(visible.subList(name + 3, visible.size()), tokens.get(visible.get(name + 2)));
            return new ExecuteStatement.Parameter(Identifiers.normalize(tokens.get(visible.get(name)).getText()),
                    excess, value);
        }
        return new ExecuteStatement.Parameter(null, false, expression(visible, before));
    }

    private InAutonomousTransaction autonomousTransaction(PsqlParser.AutonomousTransactionContext ctx) {
        PsqlParser.IdentifierContext autonomous = ctx.identifier(0);
        if (!"AUTONOMOUS".equals(name(autonomous)) || !"TRANSACTION".equals(name(ctx.identifier(1)))) {
            throw new PsqlSyntaxException("esperado IN AUTONOMOUS TRANSACTION DO", position(autonomous));
        }
        return new InAutonomousTransaction(statement(ctx.statement()), position(ctx));
    }

    /** {@code INTO [:]alvo, ...} até o fim do statement; {@code visible} começa no {@code INTO}. */
    private List<String> targets(List<Integer> visible) {
        List<String> targets = new ArrayList<>();
        for (int k = 1;; k++) {
            if (k < visible.size() && tokens.get(visible.get(k)).getType() == PsqlLexer.COLON) {
                k++;
            }
            if (k >= visible.size() || !isIdentifier(tokens.get(visible.get(k)).getType())) {
                Token wrong = tokens.get(visible.get(Math.min(k, visible.size() - 1)));
                throw new PsqlSyntaxException("alvo do INTO não reconhecido: '" + wrong.getText() + "'",
                        position(wrong));
            }
            targets.add(Identifiers.normalize(tokens.get(visible.get(k)).getText()));
            k++;
            if (k == visible.size()) {
                return targets;
            }
            if (tokens.get(visible.get(k)).getType() != PsqlLexer.COMMA) {
                Token wrong = tokens.get(visible.get(k));
                throw new PsqlSyntaxException("alvo do INTO não reconhecido: '" + wrong.getText() + "'",
                        position(wrong));
            }
        }
    }

    /** Posição, em {@code visible}, do {@code )} que fecha o {@code (} da posição {@code open}. */
    private int matchingParenthesis(List<Integer> visible, int open) {
        int depth = 0;
        for (int k = open; k < visible.size(); k++) {
            depth += depthChange(tokens.get(visible.get(k)));
            if (depth == 0) {
                return k;
            }
        }
        throw new PsqlSyntaxException("parêntese sem fechamento", position(tokens.get(visible.get(open))));
    }

    private static int depthChange(Token token) {
        return switch (token.getType()) {
            case PsqlLexer.LPAREN -> 1;
            case PsqlLexer.RPAREN -> -1;
            default -> 0;
        };
    }

    /** Palavra de SQL que o léxico não separa (sai como {@code IDENTIFIER}), comparada sem caixa. */
    private static boolean isWord(Token token, String word) {
        return token.getType() == PsqlLexer.IDENTIFIER && token.getText().equalsIgnoreCase(word);
    }

    /** Índices dos tokens fora do canal oculto (espaços e comentários) em {@code [first, last]}. */
    private List<Integer> visible(int first, int last) {
        List<Integer> visible = new ArrayList<>();
        for (int i = first; i <= last; i++) {
            if (tokens.get(i).getChannel() == Token.DEFAULT_CHANNEL) {
                visible.add(i);
            }
        }
        return visible;
    }

    private Expression expression(ParserRuleContext ctx) {
        return expression(ctx.getStart().getTokenIndex(), ctx.getStop().getTokenIndex(), Candidates.PSQL);
    }

    /** Expressão PSQL do primeiro ao último token visível da lista; vazia é erro, na posição de {@code before}. */
    private Expression expression(List<Integer> visible, Token before) {
        if (visible.isEmpty()) {
            throw new PsqlSyntaxException("expressão vazia depois de '" + before.getText() + "'",
                    position(before));
        }
        return expression(visible.get(0), visible.get(visible.size() - 1), Candidates.PSQL);
    }

    private Expression expression(int first, int last, Candidates candidates) {
        List<Expression.Part> parts = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        for (int i = first; i <= last; i++) {
            Token token = tokens.get(i);
            if (isWord(token, "RDB$ERROR")) {
                unsupported.add(new UnsupportedUse(UnsupportedConstruct.RDB_ERROR, position(token)));
            }
            int fieldEnd = fieldEnd(i, first, last, candidates);
            if (fieldEnd >= 0) {
                flush(sql, parts);
                Token qualifier = token.getType() == PsqlLexer.COLON ? tokens.get(i + 1) : token;
                parts.add(new Expression.Field(Identifiers.normalize(qualifier.getText()),
                        Identifiers.normalize(tokens.get(fieldEnd).getText()),
                        tokens.getText(Interval.of(i, fieldEnd))));
                i = fieldEnd;
                continue;
            }
            if (token.getType() == PsqlLexer.COLON && isVariableCandidate(i + 1, first, last, candidates)) {
                continue; // o ':' vai junto do nome, logo abaixo
            }
            if (!isVariableCandidate(i, first, last, candidates)) {
                sql.append(token.getText());
                continue;
            }
            flush(sql, parts);
            boolean prefixed = i > first && tokens.get(i - 1).getType() == PsqlLexer.COLON;
            parts.add(new Expression.Name(Identifiers.normalize(token.getText()),
                    (prefixed ? ":" : "") + token.getText()));
        }
        flush(sql, parts);
        return new Expression(tokens.getText(Interval.of(first, last)), parts, position(tokens.get(first)));
    }

    private static void flush(StringBuilder sql, List<Expression.Part> parts) {
        if (!sql.isEmpty()) {
            parts.add(new Expression.Sql(sql.toString()));
            sql.setLength(0);
        }
    }

    /**
     * Fim de um nome qualificado solto {@code [:]a.b} que começa em {@code index} (no {@code :} ou no {@code a}):
     * {@code a} não vem depois de ponto e {@code b} não é seguido de ponto nem de {@code (}. Numa Fatia DML, o
     * {@code :} é obrigatório.
     *
     * @return índice do token {@code b}, ou -1 se não houver nome qualificado ali
     */
    private int fieldEnd(int index, int first, int last, Candidates candidates) {
        int qualifier = index;
        if (tokens.get(index).getType() == PsqlLexer.COLON) {
            qualifier = index + 1;
        } else if (candidates == Candidates.DML) {
            return -1;
        } else {
            Token previous = visible(index, -1, first, last);
            if (previous != null && (previous.getType() == PsqlLexer.DOT || previous.getType() == PsqlLexer.COLON)) {
                return -1;
            }
        }
        if (qualifier > last || !isIdentifier(tokens.get(qualifier).getType())) {
            return -1;
        }
        Token dot = visible(qualifier, +1, first, last);
        if (dot == null || dot.getType() != PsqlLexer.DOT) {
            return -1;
        }
        Token name = visible(dot.getTokenIndex(), +1, first, last);
        if (name == null || !isIdentifier(name.getType())) {
            return -1;
        }
        Token next = visible(name.getTokenIndex(), +1, first, last);
        if (next != null && (next.getType() == PsqlLexer.DOT || next.getType() == PsqlLexer.LPAREN)) {
            return -1;
        }
        return name.getTokenIndex();
    }

    /**
     * Identificador solto: não é chamada de função ({@code F(}) nem parte de nome qualificado ({@code A.B}). Numa
     * Fatia DML, precisa ainda vir logo depois de {@code :}, a menos que seja variável de contexto de erro.
     */
    private boolean isVariableCandidate(int index, int first, int last, Candidates candidates) {
        if (index > last) {
            return false;
        }
        Token token = tokens.get(index);
        if (!isIdentifier(token.getType())) {
            return false;
        }
        Token previous = visible(index, -1, first, last);
        Token next = visible(index, +1, first, last);
        boolean loose = (previous == null || previous.getType() != PsqlLexer.DOT)
                && (next == null || (next.getType() != PsqlLexer.DOT && next.getType() != PsqlLexer.LPAREN));
        if (candidates == Candidates.PSQL || !loose) {
            return loose;
        }
        return (index > first && tokens.get(index - 1).getType() == PsqlLexer.COLON)
                || (token.getType() == PsqlLexer.IDENTIFIER
                        && ERROR_CONTEXT.contains(token.getText().toUpperCase(Locale.ROOT)));
    }

    /** Vizinho mais próximo fora do canal oculto (espaços e comentários), dentro de {@code [first, last]}. */
    private Token visible(int index, int step, int first, int last) {
        for (int i = index + step; i >= first && i <= last; i += step) {
            Token token = tokens.get(i);
            if (token.getChannel() == Token.DEFAULT_CHANNEL) {
                return token;
            }
        }
        return null;
    }

    /** Tokens da regra {@code identifier} da gramática: podem ser nome de variável. */
    private static boolean isIdentifier(int type) {
        return switch (type) {
            case PsqlLexer.IDENTIFIER, PsqlLexer.QUOTED_IDENTIFIER, PsqlLexer.RECREATE, PsqlLexer.BREAK,
                    PsqlLexer.CONTINUE, PsqlLexer.DO, PsqlLexer.EXCEPTION, PsqlLexer.EXIT, PsqlLexer.IF,
                    PsqlLexer.LEAVE, PsqlLexer.STATEMENT, PsqlLexer.SUSPEND, PsqlLexer.BLOCK, PsqlLexer.BODY,
                    PsqlLexer.PACKAGE -> true;
            default -> false;
        };
    }

    private static String name(PsqlParser.IdentifierContext ctx) {
        return Identifiers.normalize(ctx.getText());
    }

    private static String optionalName(PsqlParser.IdentifierContext ctx) {
        return ctx == null ? null : name(ctx);
    }

    private static String label(PsqlParser.LabelContext ctx) {
        return ctx == null ? null : name(ctx.identifier());
    }

    private String text(ParserRuleContext ctx) {
        return tokens.getText(Interval.of(ctx.getStart().getTokenIndex(), ctx.getStop().getTokenIndex()));
    }

    private static SourcePosition position(ParserRuleContext ctx) {
        return position(ctx.getStart());
    }

    private static SourcePosition position(Token token) {
        return new SourcePosition(token.getLine(), token.getCharPositionInLine() + 1);
    }
}
