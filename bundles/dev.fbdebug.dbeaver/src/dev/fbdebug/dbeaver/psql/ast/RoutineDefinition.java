package dev.fbdebug.dbeaver.psql.ast;

import java.util.List;

/**
 * Rotina: cabeçalho (parâmetros de entrada, saídas ou tipo de retorno), declarações e o bloco principal.
 *
 * @param packageName package da rotina, ou {@code null} fora de package
 * @param name nome da rotina; {@code null} num {@code EXECUTE BLOCK}
 * @param outputs parâmetros de saída ({@code RETURNS (...)}); vazio numa function
 * @param returnValue tipo de retorno de uma function, com o nome dela; {@code null} nos outros tipos
 * @param selectable tem {@code SUSPEND} (o motor a cadastra como procedure selecionável)
 * @param unsupported construções recusadas que aparecem dentro de expressões, fora de qualquer statement em
 *            particular (ver {@link UnsupportedConstruct})
 */
public record RoutineDefinition(
        Kind kind,
        String packageName,
        String name,
        List<ParameterDeclaration> inputs,
        List<ParameterDeclaration> outputs,
        ParameterDeclaration returnValue,
        List<VariableDeclaration> declarations,
        List<CursorDeclaration> cursors,
        Block body,
        boolean selectable,
        List<UnsupportedUse> unsupported) {

    /** Tipo de Rotina raiz. */
    public enum Kind {
        PROCEDURE, FUNCTION, EXECUTE_BLOCK
    }

    public RoutineDefinition {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        declarations = List.copyOf(declarations);
        cursors = List.copyOf(cursors);
        unsupported = List.copyOf(unsupported);
    }

    /** Nome para mensagens: {@code P}, {@code PKG.P} ou {@code EXECUTE BLOCK}. */
    public String displayName() {
        if (kind == Kind.EXECUTE_BLOCK) {
            return "EXECUTE BLOCK";
        }
        return packageName == null ? name : packageName + "." + name;
    }
}
