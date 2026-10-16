package dev.fbdebug.dbeaver.psql;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.misc.Interval;

import dev.fbdebug.dbeaver.psql.ast.Assignment;
import dev.fbdebug.dbeaver.psql.ast.Block;
import dev.fbdebug.dbeaver.psql.ast.Continue;
import dev.fbdebug.dbeaver.psql.ast.DmlStatement;
import dev.fbdebug.dbeaver.psql.ast.ExecuteStatement;
import dev.fbdebug.dbeaver.psql.ast.Exit;
import dev.fbdebug.dbeaver.psql.ast.Expression;
import dev.fbdebug.dbeaver.psql.ast.If;
import dev.fbdebug.dbeaver.psql.ast.InAutonomousTransaction;
import dev.fbdebug.dbeaver.psql.ast.Leave;
import dev.fbdebug.dbeaver.psql.ast.ParameterDeclaration;
import dev.fbdebug.dbeaver.psql.ast.ProcedureDefinition;
import dev.fbdebug.dbeaver.psql.ast.SourcePosition;
import dev.fbdebug.dbeaver.psql.ast.Statement;
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
        /** Fatia DML: só {@code :var}, porque o servidor recusa variável sem {@code :} em DML. */
        DML
    }

    private final TokenStream tokens;

    AstBuilder(TokenStream tokens) {
        this.tokens = tokens;
    }

    ProcedureDefinition procedure(PsqlParser.ProcedureDefinitionContext ctx) {
        List<ParameterDeclaration> inputs = ctx.inputParameters() == null ? List.of()
                : ctx.inputParameters().parameterDeclaration().stream().map(this::parameter).toList();
        List<ParameterDeclaration> outputs = ctx.outputParameters() == null ? List.of()
                : ctx.outputParameters().parameterDeclaration().stream().map(this::parameter).toList();
        PsqlParser.RoutineBodyContext body = ctx.routineBody();
        List<VariableDeclaration> declarations = body.declaration().stream()
                .map(d -> variable(d.variableDeclaration()))
                .toList();
        return new ProcedureDefinition(name(ctx.identifier()), inputs, outputs, declarations, block(body.block()));
    }

    private ParameterDeclaration parameter(PsqlParser.ParameterDeclarationContext ctx) {
        return new ParameterDeclaration(name(ctx.identifier()), text(ctx.typeSpec()), ctx.notNull() != null,
                defaultValue(ctx.defaultValue()), position(ctx));
    }

    private VariableDeclaration variable(PsqlParser.VariableDeclarationContext ctx) {
        return new VariableDeclaration(name(ctx.identifier()), text(ctx.typeSpec()), ctx.notNull() != null,
                defaultValue(ctx.defaultValue()), position(ctx));
    }

    private Expression defaultValue(PsqlParser.DefaultValueContext ctx) {
        return ctx == null ? null : expression(ctx.expression());
    }

    private Block block(PsqlParser.BlockContext ctx) {
        return new Block(ctx.statement().stream().map(this::statement).toList(), position(ctx));
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
            return new While(loop.label() == null ? null : name(loop.label().identifier()),
                    expression(loop.condition()), statement(loop.statement()), position(loop));
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
            return dml(ctx.dmlStatement());
        }
        if (ctx.executeStatement() != null) {
            return executeStatement(ctx.executeStatement());
        }
        if (ctx.autonomousTransaction() != null) {
            return autonomousTransaction(ctx.autonomousTransaction());
        }
        return new Exit(position(ctx.exitStatement()));
    }

    private DmlStatement dml(PsqlParser.DmlStatementContext ctx) {
        int first = ctx.getStart().getTokenIndex();
        List<Integer> visible = visible(first, ctx.getStop().getTokenIndex() - 1);
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
                position(ctx));
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
     * {@code EXECUTE STATEMENT <sql> [(<parâmetros>)] [<opções>] [INTO <alvos>]}, onde {@code <sql>} entre
     * parênteses é o que permite passar parâmetros.
     */
    private ExecuteStatement executeStatement(PsqlParser.ExecuteStatementContext ctx) {
        Token semi = ctx.getStop();
        List<Integer> visible = visible(ctx.getStart().getTokenIndex(), semi.getTokenIndex() - 1);
        int k = 2; // depois de EXECUTE STATEMENT
        if (k >= visible.size()) {
            throw new PsqlSyntaxException("falta o texto do EXECUTE STATEMENT", position(semi));
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
            int end = nextOption(visible, k);
            sql = expression(visible.subList(k, end), tokens.get(visible.get(k)));
            k = end;
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
        return new ExecuteStatement(sql, parameters, targets, unsupported, position(ctx));
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
            if (token.getType() == PsqlLexer.COLON && isVariableCandidate(i + 1, first, last, candidates)) {
                continue; // o ':' vai junto do nome, logo abaixo
            }
            if (!isVariableCandidate(i, first, last, candidates)) {
                sql.append(token.getText());
                continue;
            }
            if (!sql.isEmpty()) {
                parts.add(new Expression.Sql(sql.toString()));
                sql.setLength(0);
            }
            boolean prefixed = i > first && tokens.get(i - 1).getType() == PsqlLexer.COLON;
            parts.add(new Expression.Name(Identifiers.normalize(token.getText()),
                    (prefixed ? ":" : "") + token.getText()));
        }
        if (!sql.isEmpty()) {
            parts.add(new Expression.Sql(sql.toString()));
        }
        return new Expression(tokens.getText(Interval.of(first, last)), parts, position(tokens.get(first)));
    }

    /**
     * Identificador solto: não é chamada de função ({@code F(}) nem parte de nome qualificado ({@code A.B}). Numa
     * Fatia DML, precisa ainda vir logo depois de {@code :}.
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
        return index > first && tokens.get(index - 1).getType() == PsqlLexer.COLON;
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
                    PsqlLexer.CONTINUE, PsqlLexer.DO, PsqlLexer.EXIT, PsqlLexer.IF, PsqlLexer.LEAVE,
                    PsqlLexer.STATEMENT -> true;
            default -> false;
        };
    }

    private static String name(PsqlParser.IdentifierContext ctx) {
        return Identifiers.normalize(ctx.getText());
    }

    private static String optionalName(PsqlParser.IdentifierContext ctx) {
        return ctx == null ? null : name(ctx);
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
