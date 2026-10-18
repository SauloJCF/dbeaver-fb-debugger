package dev.fbdebug.dbeaver.launch;

import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.jkiss.dbeaver.ext.generic.model.GenericProcedure;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;
import org.jkiss.dbeaver.ui.navigator.INavigatorModelView;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import dev.fbdebug.dbeaver.editor.RoutineEditors;

/**
 * Rotina do DBeaver de onde sai um Launch: a do editor de objeto ativo ou a selecionada no navegador. Em outra parte
 * (views do debug, console), a Rotina selecionada nela ou, sem objeto do banco selecionado, a do editor ativo.
 */
final class DebuggableRoutines {

    private DebuggableRoutines() {
    }

    /**
     * @param selection seleção atual
     * @param activePart parte ativa; sendo um editor, vale a Rotina dele
     * @param editorInput entrada do editor ativo, que continua definida com uma view em foco
     * @return a procedure ou function Firebird, ou {@code null} se não há
     */
    static GenericProcedure find(Object selection, Object activePart, Object editorInput) {
        DBSObject object = null;
        if (activePart instanceof IEditorPart && editorInput instanceof IDatabaseEditorInput input) {
            object = input.getDatabaseObject();
        } else if (selection instanceof IStructuredSelection structured && structured.size() == 1) {
            object = NavigatorUtils.getSelectedObject((ISelection) structured);
        }
        if (object == null && !(activePart instanceof IEditorPart) && !(activePart instanceof INavigatorModelView)
                && editorInput instanceof IDatabaseEditorInput input) {
            object = input.getDatabaseObject();
        }
        return object instanceof GenericProcedure procedure && RoutineEditors.isFirebird(procedure) ? procedure : null;
    }
}
