package dev.fbdebug.dbeaver.launch;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.ui.ISources;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;

/**
 * Comando "Depurar Rotina", no menu de contexto do navegador, no do editor de objeto, no do editor SQL e no menu
 * "PSQL Debug". Fica habilitado só com uma Rotina Firebird (procedure, function ou trigger de tabela) selecionada, com o
 * editor dela ativo ou com um editor SQL de conexão Firebird ativo (o {@code EXECUTE BLOCK} selecionado, ou a rotina
 * do console de uma package sob o cursor). Com uma view que não é o navegador em foco (Breakpoints, Variables,
 * console), vale o editor ativo, para o menu "PSQL Debug" funcionar na perspectiva Debug.
 */
public final class DebugRoutineHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        Object activePart = HandlerUtil.getActivePart(event);
        DBSObject routine = DebuggableRoutines.find(HandlerUtil.getCurrentSelection(event), activePart,
                HandlerUtil.getActiveEditor(event));
        if (routine != null) {
            RoutineLauncher.launch(HandlerUtil.getActiveShell(event), routine);
            return null;
        }
        SQLEditor editor = DebuggableRoutines.findSqlEditor(activePart, HandlerUtil.getActiveEditor(event));
        if (editor != null) {
            RoutineLauncher.launch(HandlerUtil.getActiveShell(event), editor);
        }
        return null;
    }

    @Override
    public void setEnabled(Object evaluationContext) {
        if (evaluationContext instanceof IEvaluationContext context) {
            Object activePart = context.getVariable(ISources.ACTIVE_PART_NAME);
            setBaseEnabled(DebuggableRoutines.find(context.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME),
                    activePart, context.getVariable(ISources.ACTIVE_EDITOR_NAME)) != null
                    || DebuggableRoutines.findSqlEditor(activePart,
                            context.getVariable(ISources.ACTIVE_EDITOR_NAME)) != null);
        }
    }
}
