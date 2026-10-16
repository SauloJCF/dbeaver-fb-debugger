package dev.fbdebug.dbeaver.prototype;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.actions.IToggleBreakpointsTarget;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

/** Liga Ctrl+Shift+B ({@code org.eclipse.debug.ui.commands.ToggleBreakpoint}) aos toggle targets do debug.ui. */
public final class PrototypeToggleBreakpointHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        IToggleBreakpointsTarget target = DebugUITools.getToggleBreakpointsTargetManager()
            .getToggleBreakpointsTarget(part, selection);
        if (target == null || !target.canToggleLineBreakpoints(part, selection)) {
            return null;
        }
        try {
            target.toggleLineBreakpoints(part, selection);
        } catch (CoreException e) {
            throw new ExecutionException("Falha ao alternar breakpoint", e);
        }
        return null;
    }
}
