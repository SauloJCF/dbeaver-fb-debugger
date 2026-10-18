package dev.fbdebug.dbeaver.launch;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.ui.ISources;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.ext.generic.model.GenericProcedure;

/**
 * Comando "Depurar Rotina", no menu de contexto do navegador, no do editor de objeto e no menu "PSQL Debug". Fica
 * habilitado só com uma Rotina Firebird selecionada ou com o editor dela ativo. Com uma view que não é o navegador em
 * foco (Breakpoints, Variables, console), vale o editor ativo, para o menu "PSQL Debug" funcionar na perspectiva Debug.
 */
public final class DebugRoutineHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        GenericProcedure procedure = DebuggableRoutines.find(HandlerUtil.getCurrentSelection(event),
                HandlerUtil.getActivePart(event), HandlerUtil.getActiveEditorInput(event));
        if (procedure != null) {
            RoutineLauncher.launch(HandlerUtil.getActiveShell(event), procedure);
        }
        return null;
    }

    @Override
    public void setEnabled(Object evaluationContext) {
        if (evaluationContext instanceof IEvaluationContext context) {
            setBaseEnabled(DebuggableRoutines.find(variable(context, ISources.ACTIVE_CURRENT_SELECTION_NAME),
                    variable(context, ISources.ACTIVE_PART_NAME),
                    variable(context, ISources.ACTIVE_EDITOR_INPUT_NAME)) != null);
        }
    }

    private static Object variable(IEvaluationContext context, String name) {
        return context.getVariable(name);
    }
}
