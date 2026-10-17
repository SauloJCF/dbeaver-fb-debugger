package dev.fbdebug.dbeaver.interp;

import dev.fbdebug.dbeaver.psql.ast.Statement;

/**
 * Chamado pelo Interpretador antes de cada statement, na thread da execução, com o frame já na posição dele. É onde
 * quem controla a Sessão de debug pausa a execução ou a interrompe: o que o gancho lançar sobe sem passar por nenhum
 * {@code WHEN} da Rotina, que só trata {@link java.sql.SQLException}.
 */
@FunctionalInterface
public interface StatementHook {

    /** Não faz nada. */
    StatementHook NONE = (frame, statement) -> {
    };

    /** @param frame frame da Rotina em execução, o de cima de {@link Interpreter#frames()} */
    void beforeStatement(Frame frame, Statement statement);
}
