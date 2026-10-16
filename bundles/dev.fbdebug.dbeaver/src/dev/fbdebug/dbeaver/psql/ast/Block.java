package dev.fbdebug.dbeaver.psql.ast;

import java.util.List;

/** {@code BEGIN ... END}. */
public record Block(List<Statement> statements, SourcePosition position) implements Statement {

    public Block {
        statements = List.copyOf(statements);
    }
}
