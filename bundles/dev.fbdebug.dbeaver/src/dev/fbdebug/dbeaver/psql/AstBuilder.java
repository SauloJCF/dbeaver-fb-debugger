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
import dev.fbdebug.dbeaver.psql.ast.Exit;
import dev.fbdebug.dbeaver.psql.ast.Expression;
import dev.fbdebug.dbeaver.psql.ast.If;
import dev.fbdebug.dbeaver.psql.ast.Leave;
import dev.fbdebug.dbeaver.psql.ast.ParameterDeclaration;
import dev.fbdebug.dbeaver.psql.ast.ProcedureDefinition;
import dev.fbdebug.dbeaver.psql.ast.SourcePosition;
import dev.fbdebug.dbeaver.psql.ast.Statement;
import dev.fbdebug.dbeaver.psql.ast.VariableDeclaration;
import dev.fbdebug.dbeaver.psql.ast.While;
import dev.fbdebug.dbeaver.psql.parser.PsqlLexer;
import dev.fbdebug.dbeaver.psql.parser.PsqlParser;

/** Árvore de parse do ANTLR → AST. */
final class AstBuilder {

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
        return new Exit(position(ctx.exitStatement()));
    }

    private Expression expression(ParserRuleContext ctx) {
        int first = ctx.getStart().getTokenIndex();
        int last = ctx.getStop().getTokenIndex();
        List<Expression.Part> parts = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        for (int i = first; i <= last; i++) {
            Token token = tokens.get(i);
            if (token.getType() == PsqlLexer.COLON && isVariableCandidate(i + 1, first, last)) {
                continue; // o ':' vai junto do nome, logo abaixo
            }
            if (!isVariableCandidate(i, first, last)) {
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
        return new Expression(text(ctx), parts, position(ctx));
    }

    /** Identificador solto: não é chamada de função ({@code F(}) nem parte de nome qualificado ({@code A.B}). */
    private boolean isVariableCandidate(int index, int first, int last) {
        if (index > last) {
            return false;
        }
        if (!isIdentifier(tokens.get(index).getType())) {
            return false;
        }
        Token previous = visible(index, -1, first, last);
        Token next = visible(index, +1, first, last);
        return (previous == null || previous.getType() != PsqlLexer.DOT)
                && (next == null || (next.getType() != PsqlLexer.DOT && next.getType() != PsqlLexer.LPAREN));
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
                    PsqlLexer.CONTINUE, PsqlLexer.DO, PsqlLexer.EXIT, PsqlLexer.IF, PsqlLexer.LEAVE -> true;
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
        Token start = ctx.getStart();
        return new SourcePosition(start.getLine(), start.getCharPositionInLine() + 1);
    }
}
