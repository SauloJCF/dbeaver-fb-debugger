package dev.fbdebug.dbeaver.prototype;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.LineBreakpoint;

/**
 * Breakpoint sem efeito real. O editor do DBeaver não é baseado em arquivo do workspace, então o marker
 * fica na raiz do workspace e não é persistido.
 */
public final class PrototypeLineBreakpoint extends LineBreakpoint {

    static final String MARKER_TYPE = "dev.fbdebug.dbeaver.prototypeLineBreakpointMarker";

    /** Exigido pelo debug.core para recriar breakpoints a partir de markers. */
    public PrototypeLineBreakpoint() {
    }

    PrototypeLineBreakpoint(IResource resource, int lineNumber, String sourceName) throws CoreException {
        run(getMarkerRule(resource), monitor -> {
            IMarker marker = resource.createMarker(MARKER_TYPE);
            setMarker(marker);
            marker.setAttribute(IBreakpoint.ID, getModelIdentifier());
            marker.setAttribute(IBreakpoint.ENABLED, true);
            marker.setAttribute(IBreakpoint.PERSISTED, false);
            marker.setAttribute(IMarker.LINE_NUMBER, lineNumber);
            marker.setAttribute(IMarker.MESSAGE, sourceName + " [linha " + lineNumber + "]");
        });
    }

    @Override
    public String getModelIdentifier() {
        return PrototypeDebugElement.MODEL_ID;
    }
}
