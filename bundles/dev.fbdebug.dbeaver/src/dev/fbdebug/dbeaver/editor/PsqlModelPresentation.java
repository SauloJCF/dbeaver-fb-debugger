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
import org.eclipse.ui.texteditor.ITextEditor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.editors.entity.EntityEditor;

import dev.fbdebug.dbeaver.debug.PsqlLineBreakpoint;

/**
 * Apresentação do modelo de debug no debug.ui: o texto dos breakpoints, o detalhe dos valores e o editor da linha da
 * pausa. O elemento de fonte de um stack frame é o objeto do DBeaver ({@code PsqlSourceLocator}): o da Rotina raiz ou
 * o de uma Rotina alcançada por step into, cujo editor é aberto se preciso. A linha é destacada no editor do objeto, na
 * pasta Source, cujo editor de texto o debug.ui acha por {@code getAdapter(ITextEditor.class)}. O que não está aqui
 * fica com a apresentação padrão.
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
     * Antes de o debug.ui destacar a linha: põe o editor na pasta Source, onde fica o editor de texto, e os avisos das
     * chamadas executadas no servidor ({@link ServerCallAnnotations}). O destaque em si é o padrão do debug.ui
     * ({@code false}), que também o tira quando a execução continua. Um editor com alterações não salvas mostra um
     * texto que não é o depurado: a linha de status avisa.
     */
    @Override
    public boolean addAnnotations(IEditorPart editorPart, IStackFrame frame) {
        RoutineEditors.showSourceFolder(editorPart);
        ServerCallAnnotations.show(editorPart, frame);
        if (editorPart.isDirty()) {
            ITextEditor text = RoutineEditors.sourceText(editorPart);
            RoutineEditors.status(text != null ? text : editorPart, "O editor tem alterações não salvas: a linha "
                    + "destacada é a da Fonte do banco, que é o código depurado.", false);
        }
        return false;
    }

    @Override
    public void removeAnnotations(IEditorPart editorPart, IThread thread) {
    }
}
