package dev.fbdebug.dbeaver.psql;

import dev.fbdebug.dbeaver.psql.ast.SourcePosition;

/** Texto que a gramática da camada procedural não reconhece. */
public final class PsqlSyntaxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient SourcePosition position;

    PsqlSyntaxException(String message, SourcePosition position) {
        super(position + ": " + message);
        this.position = position;
    }

    public SourcePosition position() {
        return position;
    }
}
