package dev.fbdebug.dbeaver.psql;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

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
            throw new PsqlSyntaxException(msg, new SourcePosition(line, charPositionInLine + 1));
        }
    }
}
