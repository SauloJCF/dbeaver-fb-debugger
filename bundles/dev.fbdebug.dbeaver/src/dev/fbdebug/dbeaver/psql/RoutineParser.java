package dev.fbdebug.dbeaver.psql;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;

import dev.fbdebug.dbeaver.psql.ast.ParameterDeclaration;
import dev.fbdebug.dbeaver.psql.ast.RoutineDefinition;
import dev.fbdebug.dbeaver.psql.ast.SourcePosition;
import dev.fbdebug.dbeaver.psql.parser.PsqlLexer;
import dev.fbdebug.dbeaver.psql.parser.PsqlParser;

/** Converte o texto de uma Rotina no AST que o Interpretador executa. */
public final class RoutineParser {

    private RoutineParser() {
    }

    /**
     * @param text {@code CREATE [OR ALTER] PROCEDURE ...}, {@code CREATE [OR ALTER] FUNCTION ...} ou
     *            {@code EXECUTE BLOCK ...} completo. As posições do AST são deste texto (linha 1 é a primeira dele).
     * @throws PsqlSyntaxException no primeiro trecho não reconhecido
     */
    public static RoutineDefinition parse(String text) {
        Parsing parsing = new Parsing(text);
        return parsing.builder.routine(parsing.parser.routineDefinition());
    }

    /**
     * Rotina de package. A Fonte do banco dela é o corpo da package inteiro: as posições do AST são do texto
     * {@code body}. Os valores padrão dos parâmetros de entrada ficam no cabeçalho, e vêm de {@code header}.
     *
     * @param header {@code CREATE [OR ALTER] PACKAGE ...} completo
     * @param body {@code CREATE | RECREATE PACKAGE BODY ...} completo
     * @param kind {@link RoutineDefinition.Kind#PROCEDURE} ou {@link RoutineDefinition.Kind#FUNCTION}
     * @param name nome normalizado da rotina (ver {@link Identifiers#normalize})
     * @throws PsqlSyntaxException no primeiro trecho não reconhecido, ou se o corpo não tiver a rotina
     */
    public static RoutineDefinition parsePackageRoutine(String header, String body, RoutineDefinition.Kind kind,
            String name) {
        Parsing bodyParsing = new Parsing(body);
        PsqlParser.PackageDefinitionContext bodyTree = bodyParsing.parser.packageDefinition();
        RoutineDefinition routine = bodyParsing.builder.packageRoutine(bodyTree, kind, name);
        if (routine == null) {
            throw new PsqlSyntaxException("o corpo da package não tem a rotina " + name,
                    new SourcePosition(1, 1));
        }
        Parsing headerParsing = new Parsing(header);
        List<ParameterDeclaration> declared = headerParsing.builder.packageRoutineInputs(
                headerParsing.parser.packageDefinition(), kind, name);
        if (declared == null || declared.size() != routine.inputs().size()) {
            return routine; // rotina privada: só existe no corpo
        }
        List<ParameterDeclaration> inputs = new ArrayList<>();
        for (int i = 0; i < declared.size(); i++) {
            ParameterDeclaration input = routine.inputs().get(i);
            inputs.add(input.defaultValue() != null ? input
                    : new ParameterDeclaration(input.name(), input.type(), input.notNull(),
                            declared.get(i).defaultValue(), input.position()));
        }
        return new RoutineDefinition(routine.kind(), routine.packageName(), routine.name(), inputs,
                routine.outputs(), routine.returnValue(), routine.declarations(), routine.cursors(), routine.body(),
                routine.selectable(), routine.unsupported());
    }

    /** Léxico e parser de um texto, parando no primeiro erro. */
    private static final class Parsing {

        final PsqlParser parser;
        final AstBuilder builder;

        Parsing(String text) {
            PsqlLexer lexer = new PsqlLexer(CharStreams.fromString(text));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            parser = new PsqlParser(tokens);
            lexer.removeErrorListeners();
            lexer.addErrorListener(FailFast.INSTANCE);
            parser.removeErrorListeners();
            parser.addErrorListener(FailFast.INSTANCE);
            builder = new AstBuilder(tokens);
        }
    }

    private static final class FailFast extends BaseErrorListener {

        static final FailFast INSTANCE = new FailFast();

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                int charPositionInLine, String msg, RecognitionException e) {
            String detail = msg;
            if (offendingSymbol instanceof Token token) {
                detail = token.getType() == Token.EOF ? "fim do texto inesperado"
                        : "trecho não reconhecido: '" + token.getText() + "'";
                Token statement = statementStart(recognizer);
                if (statement != null && statement != token) {
                    detail += " no statement que começa em '" + statement.getText() + "' ("
                            + position(statement) + ")";
                }
            }
            throw new PsqlSyntaxException(detail, new SourcePosition(line, charPositionInLine + 1));
        }

        /** Primeiro token do statement em que o parser estava, que costuma dizer melhor o que não foi reconhecido. */
        private static Token statementStart(Recognizer<?, ?> recognizer) {
            if (!(recognizer instanceof Parser parser)) {
                return null;
            }
            for (ParserRuleContext ctx = parser.getContext(); ctx != null; ctx = ctx.getParent()) {
                if (ctx instanceof PsqlParser.StatementContext) {
                    return ctx.getStart();
                }
            }
            return null;
        }

        private static SourcePosition position(Token token) {
            return new SourcePosition(token.getLine(), token.getCharPositionInLine() + 1);
        }
    }
}
