package dev.fbdebug.dbeaver.psql.ast;

/** Statement da Rotina: procedural, executado pelo Interpretador, ou Fatia DML, executada pelo servidor. */
public sealed interface Statement
        permits Block, Assignment, If, While, ForSelect, ForExecuteStatement, Leave, Continue, Exit, DmlStatement,
        ExecuteStatement, OpenCursor, Fetch, CloseCursor, Raise, Suspend, Return, InAutonomousTransaction {

    SourcePosition position();
}
