package dev.fbdebug.dbeaver.editor;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.ui.IDebugEditorPresentation;
import org.eclipse.debug.ui.IDebugModelPresentation;
import org.eclipse.debug.ui.IValueDetailListener;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.editors.entity.EntityEditor;

import dev.fbdebug.dbeaver.debug.PsqlLineBreakpoint;

/**
 * Apresentação do modelo de debug no debug.ui: o texto dos breakpoints, o detalhe dos valores e o editor da linha da
 * pausa. O elemento de fonte de um stack frame é o objeto do DBeaver ({@code PsqlSourceLocator}); a linha é destacada
 * no editor do objeto, na pasta Source, cujo editor de texto o debug.ui acha por {@code getAdapter(ITextEditor.class)}.
 * O que não está aqui fica com a apresentação padrão.
 */
public final class PsqlModelPresentation extends LabelProvider
        implements IDebugModelPresentation, IDebugEditorPresentation {

    @Override
    public void setAttribute(String attribute, Object value) {
    }

    @Override
    public Image getImage(Object element) {
        return null;
    }

    @Override
    public String getText(Object element) {
        return element instanceof PsqlLineBreakpoint breakpoint ? breakpoint.label() : null;
    }

    @Override
    public void computeDetail(IValue value, IValueDetailListener listener) {
        String detail;
        try {
            detail = value.getValueString();
        } catch (DebugException e) {
            detail = e.getMessage();
        }
        listener.detailComputed(value, detail);
    }

    /** Chamado fora da thread de UI. */
    @Override
    public IEditorInput getEditorInput(Object element) {
        return element instanceof DBSObject object ? RoutineEditors.showSource(object) : null;
    }

    @Override
    public String getEditorId(IEditorInput input, Object element) {
        return EntityEditor.class.getName();
    }

    /**
     * Antes de o debug.ui destacar a linha: põe o editor na pasta Source, onde fica o editor de texto. O destaque em
     * si é o padrão do debug.ui ({@code false}), que também o tira quando a execução continua.
     */
    @Override
    public boolean addAnnotations(IEditorPart editorPart, IStackFrame frame) {
        RoutineEditors.showSourceFolder(editorPart);
        return false;
    }

    @Override
    public void removeAnnotations(IEditorPart editorPart, IThread thread) {
    }
}
