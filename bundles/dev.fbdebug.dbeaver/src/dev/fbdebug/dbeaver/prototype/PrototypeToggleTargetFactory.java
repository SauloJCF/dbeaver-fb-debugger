package dev.fbdebug.dbeaver.prototype;

import java.util.Set;

import org.eclipse.debug.ui.actions.IToggleBreakpointsTarget;
import org.eclipse.debug.ui.actions.IToggleBreakpointsTargetFactory;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IWorkbenchPart;

public final class PrototypeToggleTargetFactory implements IToggleBreakpointsTargetFactory {

    private static final String TARGET_ID = "dev.fbdebug.dbeaver.prototype.toggleTarget";

    @Override
    public Set<String> getToggleTargets(IWorkbenchPart part, ISelection selection) {
        return PrototypeToggleTarget.findTextEditor(part) != null ? Set.of(TARGET_ID) : Set.of();
    }

    @Override
    public String getDefaultToggleTarget(IWorkbenchPart part, ISelection selection) {
        return PrototypeToggleTarget.findTextEditor(part) != null ? TARGET_ID : null;
    }

    @Override
    public IToggleBreakpointsTarget createToggleTarget(String targetID) {
        return TARGET_ID.equals(targetID) ? PrototypeToggleTarget.INSTANCE : null;
    }

    @Override
    public String getToggleTargetName(String targetID) {
        return "Firebird PSQL (protótipo)";
    }

    @Override
    public String getToggleTargetDescription(String targetID) {
        return "Breakpoint de linha sem efeito real";
    }
}
