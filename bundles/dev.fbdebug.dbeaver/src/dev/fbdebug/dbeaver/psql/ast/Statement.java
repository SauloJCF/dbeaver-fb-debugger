package dev.fbdebug.dbeaver.psql.ast;

/** Statement procedural executado pelo Interpretador. */
public sealed interface Statement permits Block, Assignment, If, While, Leave, Continue, Exit {

    SourcePosition position();
}
