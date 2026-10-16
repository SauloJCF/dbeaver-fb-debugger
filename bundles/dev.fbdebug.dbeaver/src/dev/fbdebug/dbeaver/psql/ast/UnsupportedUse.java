package dev.fbdebug.dbeaver.psql.ast;

/** Uso de uma {@link UnsupportedConstruct} na Fonte, na posição da cláusula que a introduz. */
public record UnsupportedUse(UnsupportedConstruct construct, SourcePosition position) {
}
