package dev.fbdebug.dbeaver.psql.ast;

/** {@code OPEN cursor;} */
public record OpenCursor(String cursor, SourcePosition position) implements Statement {
}
