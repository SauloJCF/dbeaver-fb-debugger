package dev.fbdebug.dbeaver.psql;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;

import dev.fbdebug.dbeaver.psql.ast.ProcedureDefinition;
import dev.fbdebug.dbeaver.psql.ast.SourcePosition;
import dev.fbdebug.dbeaver.psql.parser.PsqlLexer;
import dev.fbdebug.dbeaver.psql.parser.PsqlParser;

/** Converte o texto de uma Rotina no AST que o Interpretador executa. */
public final class RoutineParser {

    private RoutineParser() {
    }

    /**
     * @param text {@code CREATE [OR ALTER] PROCEDURE ...} completo
     * @throws PsqlSyntaxException no primeiro trecho não reconhecido
     */
    public static ProcedureDefinition parseProcedure(String text) {
        PsqlLexer lexer = new PsqlLexer(CharStreams.fromString(text));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PsqlParser parser = new PsqlParser(tokens);
        lexer.removeErrorListeners();
        lexer.addErrorListener(FailFast.INSTANCE);
        parser.removeErrorListeners();
        parser.addErrorListener(FailFast.INSTANCE);
        return new AstBuilder(tokens).procedure(parser.procedureDefinition());
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
