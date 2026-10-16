package dev.fbdebug.dbeaver.prototype;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IMarkerDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointListener;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.ui.actions.IToggleBreakpointsTarget;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.part.MultiPageEditorPart;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.MarkerAnnotation;

/**
 * Alterna breakpoints de linha em qualquer editor de texto do DBeaver. Como o modelo de anotações desses
 * editores não acompanha markers do workspace, a anotação do ruler é adicionada e removida à mão.
 */
final class PrototypeToggleTarget implements IToggleBreakpointsTarget, IBreakpointListener {

    static final PrototypeToggleTarget INSTANCE = new PrototypeToggleTarget();

    private record Placement(IEditorInput input, int line, IAnnotationModel model, Annotation annotation) {
    }

    private final Map<IBreakpoint, Placement> placements = new ConcurrentHashMap<>();

    private PrototypeToggleTarget() {
        breakpointManager().addBreakpointListener(this);
    }

    static ITextEditor findTextEditor(IWorkbenchPart part) {
        if (part instanceof ITextEditor editor) {
            return editor;
        }
        if (part instanceof MultiPageEditorPart multiPage
            && multiPage.getSelectedPage() instanceof IWorkbenchPart page) {
            return findTextEditor(page);
        }
        return part == null ? null : part.getAdapter(ITextEditor.class);
    }

    @Override
    public boolean canToggleLineBreakpoints(IWorkbenchPart part, ISelection selection) {
        return findTextEditor(part) != null;
    }

    @Override
    public void toggleLineBreakpoints(IWorkbenchPart part, ISelection selection) throws CoreException {
        ITextEditor editor = findTextEditor(part);
        if (editor == null || !(editor.getSelectionProvider().getSelection() instanceof ITextSelection textSelection)) {
            return;
        }
        IEditorInput input = editor.getEditorInput();
        IDocumentProvider provider = editor.getDocumentProvider();
        IDocument document = provider.getDocument(input);
        IAnnotationModel model = provider.getAnnotationModel(input);
        if (document == null || model == null) {
            Platform.getLog(PrototypeToggleTarget.class).warn(
                "Editor sem documento ou modelo de anotações: " + editor.getClass().getName());
            return;
        }
        int line = textSelection.getStartLine();
        for (Map.Entry<IBreakpoint, Placement> entry : placements.entrySet()) {
            Placement placement = entry.getValue();
            if (placement.input().equals(input) && placement.line() == line) {
                breakpointManager().removeBreakpoint(entry.getKey(), true);
                return;
            }
        }
        PrototypeLineBreakpoint breakpoint = new PrototypeLineBreakpoint(
            ResourcesPlugin.getWorkspace().getRoot(), line + 1, input.getName());
        MarkerAnnotation annotation = new MarkerAnnotation(breakpoint.getMarker());
        try {
            model.addAnnotation(annotation, new Position(document.getLineOffset(line), document.getLineLength(line)));
        } catch (BadLocationException e) {
            breakpoint.delete();
            return;
        }
        placements.put(breakpoint, new Placement(input, line, model, annotation));
        breakpointManager().addBreakpoint(breakpoint);
    }

    @Override
    public void breakpointRemoved(IBreakpoint breakpoint, IMarkerDelta delta) {
        Placement placement = placements.remove(breakpoint);
        if (placement != null) {
            placement.model().removeAnnotation(placement.annotation());
        }
    }

    @Override
    public void breakpointAdded(IBreakpoint breakpoint) {
    }

    @Override
    public void breakpointChanged(IBreakpoint breakpoint, IMarkerDelta delta) {
    }

    @Override
    public boolean canToggleMethodBreakpoints(IWorkbenchPart part, ISelection selection) {
        return false;
    }

    @Override
    public void toggleMethodBreakpoints(IWorkbenchPart part, ISelection selection) {
    }

    @Override
    public boolean canToggleWatchpoints(IWorkbenchPart part, ISelection selection) {
        return false;
    }

    @Override
    public void toggleWatchpoints(IWorkbenchPart part, ISelection selection) {
    }

    private static IBreakpointManager breakpointManager() {
        return DebugPlugin.getDefault().getBreakpointManager();
    }
}
