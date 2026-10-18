package dev.fbdebug.dbeaver.editor;

import java.util.Objects;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.MultiPageEditorSite;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.IEditorStatusLine;
import org.eclipse.ui.texteditor.ITextEditor;
import org.jkiss.dbeaver.ext.generic.model.GenericProcedure;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNUtils;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedureType;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.folders.ITabbedFolderContainer;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;
import org.jkiss.dbeaver.ui.navigator.actions.NavigatorHandlerObjectOpen;

import dev.fbdebug.dbeaver.debug.SourceId;

/**
 * Editor do objeto do DBeaver de uma Rotina Firebird e o editor de texto da pasta Source dele, onde ficam os
 * breakpoints e a linha da pausa. O plugin do Firebird do DBeaver não exporta as classes dele: a Rotina é a procedure
 * do plugin genérico, e a conexão Firebird é reconhecida pelo provedor do driver. Só na thread de UI.
 */
public final class RoutineEditors {

    /** Provedor de conexão do plugin {@code org.jkiss.dbeaver.ext.firebird}. */
    private static final String FIREBIRD_PROVIDER = "jaybird";

    /**
     * Pasta Source do editor de procedure e function do plugin genérico do DBeaver, que o do Firebird usa. É também o
     * id do site do editor de texto dela.
     */
    static final String SOURCE_FOLDER = "generic.source.view";

    private RoutineEditors() {
    }

    /** A procedure (ou function) é de uma conexão Firebird. */
    public static boolean isFirebird(GenericProcedure procedure) {
        DBPDataSourceContainer container = procedure.getDataSource().getContainer();
        return container != null && FIREBIRD_PROVIDER.equals(container.getDriver().getProviderId());
    }

    /** @return o texto da Rotina, ou {@code null} se o objeto não é procedure nem function Firebird */
    public static SourceId sourceOf(DBSObject object) {
        if (!(object instanceof GenericProcedure procedure) || !isFirebird(procedure)) {
            return null;
        }
        String dataSourceId = procedure.getDataSource().getContainer().getId();
        if (procedure.getPackage() != null) {
            return new SourceId(dataSourceId, SourceId.Kind.PACKAGE, procedure.getPackage().getName());
        }
        SourceId.Kind kind = procedure.getProcedureType() == DBSProcedureType.FUNCTION ? SourceId.Kind.FUNCTION
                : SourceId.Kind.PROCEDURE;
        return new SourceId(dataSourceId, kind, procedure.getName());
    }

    /** @return o texto da Rotina do editor do objeto, ou {@code null} */
    static SourceId sourceOf(IEditorPart editor) {
        return editor != null && editor.getEditorInput() instanceof IDatabaseEditorInput input
                ? sourceOf(input.getDatabaseObject()) : null;
    }

    /** Editor do objeto: a própria parte ou, num editor de texto de uma pasta, o editor que a contém. */
    static IEditorPart objectEditor(IWorkbenchPart part) {
        if (part != null && part.getSite() instanceof MultiPageEditorSite site) {
            return site.getMultiPageEditor();
        }
        return part instanceof IEditorPart editor ? editor : null;
    }

    /**
     * Editor de texto da pasta Source: a própria parte, se é ele, ou o da pasta ativa do editor do objeto, que o
     * repassa pelo {@code getAdapter}.
     *
     * @return {@code null} se a pasta ativa não é a Source
     */
    static ITextEditor sourceText(IWorkbenchPart part) {
        ITextEditor text = part instanceof ITextEditor editor ? editor
                : part == null ? null : part.getAdapter(ITextEditor.class);
        return text != null && text.getSite() != null && SOURCE_FOLDER.equals(text.getSite().getId()) ? text : null;
    }

    static IDocument document(ITextEditor text) {
        IDocumentProvider provider = text.getDocumentProvider();
        return provider == null ? null : provider.getDocument(text.getEditorInput());
    }

    static IAnnotationModel annotationModel(ITextEditor text) {
        IDocumentProvider provider = text.getDocumentProvider();
        return provider == null ? null : provider.getAnnotationModel(text.getEditorInput());
    }

    /**
     * Editor do objeto aberto para a Rotina, em qualquer janela.
     *
     * @param restore carregar o editor de uma página que ainda não o mostrou
     */
    static IEditorPart findEditor(DBSObject object, boolean restore) {
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            for (IWorkbenchPage page : window.getPages()) {
                for (IEditorReference reference : page.getEditorReferences()) {
                    if (isEditorOf(reference, object)) {
                        return reference.getEditor(restore);
                    }
                }
            }
        }
        return null;
    }

    private static boolean isEditorOf(IEditorReference reference, DBSObject object) {
        try {
            return reference.getEditorInput() instanceof IDatabaseEditorInput input
                    && Objects.equals(input.getDatabaseObject(), object);
        } catch (PartInitException e) {
            return false;
        }
    }

    /**
     * Editor do objeto da Rotina, aberto se preciso já na pasta Source, para o debug.ui destacar nele a linha da pausa.
     * Um editor já aberto não é recarregado (o que tiraria do ruler as marcas dos breakpoints); a pasta Source é
     * escolhida depois, por {@link #showSourceFolder}. De qualquer thread.
     *
     * @return a entrada do editor, ou {@code null} se não deu para abri-lo
     */
    static IEditorInput showSource(DBSObject object) {
        IEditorInput[] input = new IEditorInput[1];
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            IEditorPart editor = findEditor(object, true);
            if (editor == null) {
                DBNDatabaseNode node = DBNUtils.getNodeByObject(object);
                editor = node == null ? null
                        : NavigatorHandlerObjectOpen.openEntityEditor(node, null, SOURCE_FOLDER, null,
                                UIUtils.getActiveWorkbenchWindow(), false);
            }
            input[0] = editor == null ? null : editor.getEditorInput();
        });
        return input[0];
    }

    /** Passa o editor do objeto para a pasta Source. */
    static void showSourceFolder(IEditorPart editor) {
        if (editor instanceof ITabbedFolderContainer folders) {
            folders.switchFolder(SOURCE_FOLDER);
        }
    }

    /**
     * Texto do editor aberto da Rotina, com a pasta Source ativa.
     *
     * @return {@code null} se não há editor aberto ou se a pasta ativa é outra
     */
    public static String openText(DBSObject object) {
        ITextEditor text = sourceText(findEditor(object, false));
        IDocument document = text == null ? null : document(text);
        return document == null ? null : document.get();
    }

    /** Mensagem na linha de status da janela, com bipe se é erro. */
    static void status(IWorkbenchPart part, String message, boolean error) {
        IEditorStatusLine statusLine = part == null ? null : part.getAdapter(IEditorStatusLine.class);
        if (statusLine != null) {
            statusLine.setMessage(error, message, null);
        }
        if (error) {
            Display.getCurrent().beep();
        }
    }
}
