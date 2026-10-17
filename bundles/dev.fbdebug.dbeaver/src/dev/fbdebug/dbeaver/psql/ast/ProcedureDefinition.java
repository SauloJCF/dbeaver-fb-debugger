package dev.fbdebug.dbeaver.psql.ast;

import java.util.List;

/**
 * Procedure: cabeçalho (parâmetros de entrada e {@code RETURNS}), declarações e o bloco principal.
 *
 * @param unsupported construções recusadas que aparecem dentro de expressões, fora de qualquer statement em
 *            particular (ver {@link UnsupportedConstruct})
 */
public record ProcedureDefinition(
        String name,
        List<ParameterDeclaration> inputs,
        List<ParameterDeclaration> outputs,
        List<VariableDeclaration> declarations,
        List<CursorDeclaration> cursors,
        Block body,
        List<UnsupportedUse> unsupported) {

    public ProcedureDefinition {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        declarations = List.copyOf(declarations);
        cursors = List.copyOf(cursors);
        unsupported = List.copyOf(unsupported);
    }
}
