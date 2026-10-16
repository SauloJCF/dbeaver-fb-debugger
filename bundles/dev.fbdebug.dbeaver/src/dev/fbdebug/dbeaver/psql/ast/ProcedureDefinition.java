package dev.fbdebug.dbeaver.psql.ast;

import java.util.List;

/** Procedure: cabeçalho (parâmetros de entrada e {@code RETURNS}), declarações e o bloco principal. */
public record ProcedureDefinition(
        String name,
        List<ParameterDeclaration> inputs,
        List<ParameterDeclaration> outputs,
        List<VariableDeclaration> declarations,
        Block body) {

    public ProcedureDefinition {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        declarations = List.copyOf(declarations);
    }
}
