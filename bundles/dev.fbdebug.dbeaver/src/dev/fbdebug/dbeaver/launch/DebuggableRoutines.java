package dev.fbdebug.dbeaver.launch;

import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.jkiss.dbeaver.ext.generic.model.GenericProcedure;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

/**
 * Rotina do DBeaver de onde sai um Launch: a selecionada no navegador ou a do editor de objeto ativo. O plugin do
 * Firebird do DBeaver não exporta as classes dele; a conexão Firebird é reconhecida pelo provedor do driver.
 */
final class DebuggableRoutines {

    /** Provedor de conexão do plugin {@code org.jkiss.dbeaver.ext.firebird}. */
    private static final String FIREBIRD_PROVIDER = "jaybird";

    private DebuggableRoutines() {
    }

    /**
     * @param selection seleção atual
     * @param activePart parte ativa; sendo um editor, vale a Rotina dele
     * @param editorInput entrada do editor ativo
     * @return a procedure ou function Firebird, ou {@code null} se não há
     */
    static GenericProcedure find(Object selection, Object activePart, Object editorInput) {
        DBSObject object = null;
        if (activePart instanceof IEditorPart && editorInput instanceof IDatabaseEditorInput input) {
            object = input.getDatabaseObject();
        } else if (selection instanceof IStructuredSelection structured && structured.size() == 1) {
            object = NavigatorUtils.getSelectedObject((ISelection) structured);
        }
        return object instanceof GenericProcedure procedure && isFirebird(procedure) ? procedure : null;
    }

    private static boolean isFirebird(GenericProcedure procedure) {
        DBPDataSourceContainer container = procedure.getDataSource().getContainer();
        return container != null && FIREBIRD_PROVIDER.equals(container.getDriver().getProviderId());
    }
}
