package dev.fbdebug.dbeaver.psql;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;

import dev.fbdebug.dbeaver.psql.parser.PsqlLexer;

/** Texto de um {@code EXECUTE STATEMENT}, que só existe em tempo de execução. */
public final class DynamicSql {

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_$]*|\"([^\"]|\"\")*\"");

    private DynamicSql() {
    }

    /**
     * Texto com os parâmetros nomeados trocados por {@code ?}.
     *
     * @param names nomes normalizados, na ordem dos {@code ?}
     */
    public record Positional(String sql, List<String> names) {

        public Positional {
            names = List.copyOf(names);
        }
    }

    /**
     * Troca cada {@code :nome} por {@code ?}, como o motor faz antes de preparar o statement dinâmico. Literais e
     * comentários ficam como estão.
     */
    public static Positional withPositionalParameters(String sql) {
        PsqlLexer lexer = new PsqlLexer(CharStreams.fromString(sql));
        lexer.removeErrorListeners();
        List<? extends Token> tokens = lexer.getAllTokens();
        StringBuilder text = new StringBuilder();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            Token next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
            if (token.getType() == PsqlLexer.COLON && next != null && NAME.matcher(next.getText()).matches()) {
                text.append('?');
                names.add(Identifiers.normalize(next.getText()));
                i++;
            } else {
                text.append(token.getText());
            }
        }
        return new Positional(text.toString(), names);
    }
}
