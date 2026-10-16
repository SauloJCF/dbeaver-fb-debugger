package dev.fbdebug.dbeaver.prototype;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.DebugUITools;

/** Menu "PSQL Debug > Depurar protótipo Firebird PSQL": lança sem salvar configuração no workspace. */
public final class PrototypeLaunchHandler extends AbstractHandler {

    static final String LAUNCH_TYPE_ID = "dev.fbdebug.dbeaver.prototype.launchType";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ILaunchConfigurationType type = DebugPlugin.getDefault().getLaunchManager()
            .getLaunchConfigurationType(LAUNCH_TYPE_ID);
        try {
            DebugUITools.launch(type.newInstance(null, "Firebird PSQL (protótipo)"), ILaunchManager.DEBUG_MODE);
        } catch (CoreException e) {
            throw new ExecutionException("Falha ao lançar o protótipo", e);
        }
        return null;
    }
}
