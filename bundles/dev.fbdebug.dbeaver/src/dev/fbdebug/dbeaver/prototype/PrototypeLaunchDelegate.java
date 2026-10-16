package dev.fbdebug.dbeaver.prototype;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.ILaunchConfigurationDelegate;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.WorkbenchException;

/**
 * Implementa só {@link ILaunchConfigurationDelegate} (sem o {@code ...Delegate2}) para o debug.ui não
 * disparar build do workspace antes do launch.
 */
public final class PrototypeLaunchDelegate implements ILaunchConfigurationDelegate {

    @Override
    public void launch(ILaunchConfiguration configuration, String mode, ILaunch launch, IProgressMonitor monitor) {
        PrototypeDebugTarget target = new PrototypeDebugTarget(launch);
        launch.addDebugTarget(target);
        showDebugPerspective();
        target.start();
    }

    /** Troca antes do evento de suspensão, para o debug.ui não perguntar se deve trocar de perspectiva. */
    private static void showDebugPerspective() {
        IWorkbench workbench = PlatformUI.getWorkbench();
        workbench.getDisplay().syncExec(() -> {
            IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
            if (window == null && workbench.getWorkbenchWindowCount() > 0) {
                window = workbench.getWorkbenchWindows()[0];
            }
            try {
                workbench.showPerspective(IDebugUIConstants.ID_DEBUG_PERSPECTIVE, window);
            } catch (WorkbenchException e) {
                Platform.getLog(PrototypeLaunchDelegate.class).error("Falha ao abrir a perspectiva Debug", e);
            }
        });
    }
}
