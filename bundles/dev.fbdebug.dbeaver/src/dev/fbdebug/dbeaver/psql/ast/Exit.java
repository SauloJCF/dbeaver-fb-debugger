package dev.fbdebug.dbeaver.psql.ast;

/** {@code EXIT;}: termina a Rotina. */
public record Exit(SourcePosition position) implements Statement {
}
