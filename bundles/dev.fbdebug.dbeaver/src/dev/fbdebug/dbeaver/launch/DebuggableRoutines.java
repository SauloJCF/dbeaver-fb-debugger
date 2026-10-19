package dev.fbdebug.dbeaver.launch;

import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.jkiss.dbeaver.ext.generic.model.GenericProcedure;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;
import org.jkiss.dbeaver.ui.navigator.INavigatorModelView;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import dev.fbdebug.dbeaver.editor.RoutineEditors;
import dev.fbdebug.dbeaver.editor.SqlEditors;

/**
 * Rotina do DBeaver de onde sai um Launch (procedure, function ou trigger de tabela): a do editor de objeto ativo ou a
 * selecionada no navegador. Em outra parte (views do debug, console), a Rotina selecionada nela ou, sem objeto do banco
 * selecionado, a do editor ativo. Sem Rotina do DBeaver, o editor SQL ativo, de onde sai um {@code EXECUTE BLOCK} ou
 * uma rotina do console de uma package.
 */
final class DebuggableRoutines {

    private DebuggableRoutines() {
    }

    /**
     * @param selection seleção atual
     * @param activePart parte ativa; sendo um editor, vale a Rotina dele
     * @param activeEditor editor ativo, que continua definido com uma view em foco. Vale a entrada atual dele, e não a
     *            variável {@code activeEditorInput}, que fica com a entrada provisória de um editor restaurado
     * @return a procedure, a function ou a trigger de tabela Firebird, ou {@code null} se não há
     */
    static DBSObject find(Object selection, Object activePart, Object activeEditor) {
        Object editorInput = activeEditor instanceof IEditorPart editor ? editor.getEditorInput() : null;
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
        boolean routine = object instanceof GenericProcedure procedure && RoutineEditors.isFirebird(procedure)
                || RoutineEditors.isTableTrigger(object);
        return routine ? object : null;
    }

    /**
     * Editor SQL de conexão Firebird de onde sai o Launch: o ativo ou, com uma parte que não é editor nem o navegador em
     * foco, o editor ativo. Vale só quando {@link #find} não acha Rotina.
     *
     * @param activeEditor editor ativo, que continua definido com uma view em foco
     * @return {@code null} se não há
     */
    static SQLEditor findSqlEditor(Object activePart, Object activeEditor) {
        Object editor = activePart instanceof IEditorPart || activePart instanceof INavigatorModelView ? activePart
                : activeEditor;
        return SqlEditors.isDebuggable(editor) ? (SQLEditor) editor : null;
    }

    /**
     * Conexão Firebird de onde abrir o corpo de uma package: a do objeto (ou da conexão) selecionado no navegador, ou a
     * do editor ativo (de objeto ou SQL).
     *
     * @return {@code null} se não há conexão Firebird no contexto
     */
    static DBPDataSourceContainer findDataSource(Object selection, Object activePart, Object activeEditor) {
        DBPDataSourceContainer container = null;
        if (activePart instanceof INavigatorModelView) {
            if (selection instanceof IStructuredSelection structured && structured.size() == 1) {
                container = containerOf(NavigatorUtils.getSelectedObject((ISelection) structured));
            }
        } else if (activeEditor instanceof SQLEditor editor) {
            container = editor.getDataSourceContainer();
        } else if (activeEditor instanceof IEditorPart editor
                && editor.getEditorInput() instanceof IDatabaseEditorInput input) {
            container = containerOf(input.getDatabaseObject());
        }
        return container != null && RoutineEditors.isFirebird(container) ? container : null;
    }

    private static DBPDataSourceContainer containerOf(DBSObject object) {
        if (object instanceof DBPDataSourceContainer container) {
            return container;
        }
        return object == null || object.getDataSource() == null ? null : object.getDataSource().getContainer();
    }
}
