package dev.fbdebug.dbeaver.psql.ast;

/** {@code CLOSE cursor;} */
public record CloseCursor(String cursor, SourcePosition position) implements Statement {
}
