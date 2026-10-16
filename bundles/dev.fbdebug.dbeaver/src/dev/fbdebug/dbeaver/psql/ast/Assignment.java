package dev.fbdebug.dbeaver.psql.ast;

/** {@code alvo = expressão;} */
public record Assignment(String target, Expression value, SourcePosition position) implements Statement {
}
