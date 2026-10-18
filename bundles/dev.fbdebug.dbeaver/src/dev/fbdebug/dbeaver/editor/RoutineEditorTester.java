package dev.fbdebug.dbeaver.editor;

import org.eclipse.core.expressions.PropertyTester;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;

/**
 * {@code dev.fbdebug.dbeaver.breakpointSource}: a entrada do editor é de procedure ou function Firebird, que pode
 * receber breakpoints. Liga os comandos de breakpoint só nos editores dessas Rotinas e mostra "Depurar Rotina" no menu
 * de contexto deles.
 */
public final class RoutineEditorTester extends PropertyTester {

    @Override
    public boolean test(Object receiver, String property, Object[] args, Object expectedValue) {
        return receiver instanceof IDatabaseEditorInput input
                && RoutineEditors.sourceOf(input.getDatabaseObject()) != null;
    }
}
