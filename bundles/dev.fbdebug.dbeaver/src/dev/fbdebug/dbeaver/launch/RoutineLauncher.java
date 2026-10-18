package dev.fbdebug.dbeaver.launch;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.Launch;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.WorkbenchException;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.generic.model.GenericProcedure;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedureType;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;
import org.osgi.framework.FrameworkUtil;

import dev.fbdebug.dbeaver.debug.PsqlDebugTarget;
import dev.fbdebug.dbeaver.debug.PsqlSourceLocator;
import dev.fbdebug.dbeaver.debug.SessionConsole;
import dev.fbdebug.dbeaver.debug.SourceId;
import dev.fbdebug.dbeaver.editor.EditorBreakpoints;
import dev.fbdebug.dbeaver.editor.RoutineEditors;
import dev.fbdebug.dbeaver.interp.DebugSession;
import dev.fbdebug.dbeaver.psql.PreCheck;
import dev.fbdebug.dbeaver.psql.RoutineParser;
import dev.fbdebug.dbeaver.psql.StatementLines;
import dev.fbdebug.dbeaver.psql.ast.RoutineDefinition;
import dev.fbdebug.dbeaver.source.RoutineSourceLoader;

/**
 * Launch de uma Rotina do DBeaver: recusa com o editor dela sujo, abre a conexão dedicada (um contexto isolado do
 * DBeaver, fora de qualquer editor SQL), carrega a Fonte do banco, passa pelo Pré-check, mostra o diálogo e inicia a
 * Sessão de debug na perspectiva Debug.
 */
final class RoutineLauncher {

    private static final String TITLE = "Firebird PSQL Debugger";
    private static final String LAUNCH_TYPE_ID = "dev.fbdebug.dbeaver.launchType";
    private static final ILog LOG = ILog.of(RoutineLauncher.class);

    private RoutineLauncher() {
    }

    /**
     * Fonte do banco já carregada, na conexão dedicada, e o que o Pré-check achou.
     *
     * @param source o texto da Fonte do banco
     * @param routine a Rotina; {@code null} se o Pré-check a recusou
     */
    private record Prepared(DBCExecutionContext context, Connection connection, String source,
            RoutineDefinition routine, List<PreCheck.Finding> findings) {
    }

    static void launch(Shell shell, GenericProcedure procedure) {
        if (procedure.getProcedureType() != DBSProcedureType.PROCEDURE || procedure.getPackage() != null) {
            MessageDialog.openInformation(shell, TITLE,
                    "Por enquanto só procedure executável, fora de package, pode ser depurada.");
            return;
        }
        if (hasDirtyEditor(procedure)) {
            MessageDialog.openError(shell, TITLE, "Salve (compile) ou descarte as alterações antes de depurar.");
            return;
        }
        Prepared prepared;
        try {
            prepared = prepare(procedure);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            MessageDialog.openError(shell, TITLE,
                    "Não foi possível carregar " + procedure.getName() + ": " + cause.getMessage());
            return;
        }
        boolean started = false;
        try {
            started = configureAndStart(shell, procedure, prepared);
        } catch (CoreException | RuntimeException e) {
            LOG.error("Falha ao iniciar a Sessão de debug", e);
            MessageDialog.openError(shell, TITLE, "Falha ao iniciar a Sessão de debug: " + e.getMessage());
        } finally {
            if (!started) {
                closeQuietly(prepared.context());
            }
        }
    }

    private static void closeQuietly(DBCExecutionContext context) {
        try {
            context.close();
        } catch (DBException e) {
            LOG.warn("Falha ao fechar a conexão da Sessão de debug", e);
        }
    }

    /** @return se a sessão começou; a partir daí a conexão é dela */
    private static boolean configureAndStart(Shell shell, GenericProcedure procedure, Prepared prepared)
            throws CoreException {
        if (!prepared.findings().isEmpty()) {
            MessageDialog.openError(shell, TITLE, "O Pré-check recusou " + procedure.getName() + ":\n\n"
                    + prepared.findings().stream().map(PreCheck.Finding::toString).collect(Collectors.joining("\n")));
            return false;
        }
        RoutineDefinition routine = prepared.routine();
        if (routine.selectable()) {
            MessageDialog.openInformation(shell, TITLE, "Procedure selecionável (com SUSPEND) ainda não pode ser "
                    + "depurada: por enquanto, só procedure executável.");
            return false;
        }
        RoutineLaunchDialog dialog = new RoutineLaunchDialog(shell, routine, history(procedure));
        if (dialog.open() != Window.OK) {
            return false;
        }
        start(procedure, prepared, dialog.settings());
        return true;
    }

    /** Conexão dedicada, Fonte do banco e Pré-check, fora da thread de UI. */
    private static Prepared prepare(GenericProcedure procedure) throws InvocationTargetException {
        Prepared[] prepared = new Prepared[1];
        UIUtils.runInProgressDialog(monitor -> {
            try {
                prepared[0] = open(monitor, procedure);
            } catch (DBException | SQLException | RuntimeException e) {
                throw new InvocationTargetException(e);
            }
        });
        return prepared[0];
    }

    private static Prepared open(DBRProgressMonitor monitor, GenericProcedure procedure)
            throws DBException, SQLException {
        monitor.beginTask("Abrindo a conexão da Sessão de debug", 2);
        DBCExecutionContext context = procedure.getDataSource().getDefaultInstance()
                .openIsolatedContext(monitor, "Firebird PSQL Debugger", null);
        try {
            Connection connection = ((JDBCExecutionContext) context).getConnection(monitor);
            connection.setAutoCommit(true); // a sessão escolhe o isolamento antes da primeira transação
            monitor.worked(1);
            monitor.subTask("Carregando a Fonte do banco");
            String source = RoutineSourceLoader.loadProcedure(connection, procedure.getName());
            List<PreCheck.Finding> findings = PreCheck.check(source);
            RoutineDefinition routine = findings.isEmpty() ? RoutineParser.parse(source) : null;
            monitor.done();
            return new Prepared(context, connection, source, routine, findings);
        } catch (SQLException | RuntimeException e) {
            closeQuietly(context);
            throw e;
        }
    }

    /**
     * Acerta os breakpoints pela Fonte do banco ({@link EditorBreakpoints#prepareLaunch}) e inicia a sessão. Os stack
     * frames mostram a linha no editor do objeto da Rotina deles: o da Rotina do launch ou, depois de um step into, o
     * da Rotina chamada, achado no DBeaver pelo nome ({@link PsqlSourceLocator}). O controle da sessão é também a
     * política das chamadas: step into na chamada do statement em que a execução parou, ou breakpoint ao alcance dela.
     */
    private static void start(GenericProcedure procedure, Prepared prepared, RoutineLaunchDialog.Settings settings)
            throws CoreException {
        RoutineDefinition routine = prepared.routine();
        String dataSourceId = procedure.getDataSource().getContainer().getId();
        SourceId source = SourceId.of(dataSourceId, routine);
        EditorBreakpoints.prepareLaunch(source, routine);
        ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
        ILaunchConfigurationType type = manager.getLaunchConfigurationType(LAUNCH_TYPE_ID);
        ILaunchConfigurationWorkingCopy configuration =
                type.newInstance(null, manager.generateLaunchConfigurationName(routine.displayName()));
        Launch launch = new Launch(configuration, ILaunchManager.DEBUG_MODE,
                new PsqlSourceLocator(text -> RoutineEditors.routine(procedure, text)));
        manager.addLaunch(launch);
        SessionConsole console = new SessionConsole(
                settings.isolation() == DebugSession.Isolation.SNAPSHOT ? "SNAPSHOT" : "READ COMMITTED");
        warnIfEditorDiffers(console, procedure, prepared);
        PsqlDebugTarget target = new PsqlDebugTarget(launch, dataSourceId, routine, settings.inputs(),
                settings.commitOnFinish(),
                control -> new DebugSession(prepared.connection(), settings.isolation(), settings.commitOnFinish(),
                        control, control),
                prepared.context()::close, console);
        launch.addDebugTarget(target);
        showDebugPerspective();
        target.start();
    }

    /**
     * As linhas dos breakpoints e da pausa são as da Fonte do banco montada pelo {@code RoutineSourceLoader}, que
     * segue o cabeçalho que o DBeaver gera no editor (issue 09). Se, numa linha de statement, o texto do editor aberto
     * não é o da Fonte do banco, o console avisa: a linha destacada e os breakpoints podem não bater.
     */
    private static void warnIfEditorDiffers(SessionConsole console, GenericProcedure procedure, Prepared prepared) {
        String editorText = RoutineEditors.openText(procedure);
        if (editorText == null) {
            return;
        }
        String[] editor = editorText.split("\\R", -1);
        String[] source = prepared.source().split("\\R", -1);
        for (int line : StatementLines.of(prepared.routine())) {
            if (line > editor.length || line > source.length
                    || !editor[line - 1].strip().equals(source[line - 1].strip())) {
                console.warn("Em " + procedure.getName() + ", a linha " + line + " do editor não é a da Fonte do banco "
                        + "que o debugger executa: a linha destacada e os breakpoints podem não bater.");
                return;
            }
        }
    }

    /** Editor do DBeaver aberto para a Rotina com alterações não salvas. */
    private static boolean hasDirtyEditor(DBSObject object) {
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            for (IWorkbenchPage page : window.getPages()) {
                for (IEditorReference reference : page.getEditorReferences()) {
                    if (reference.isDirty() && isEditorOf(reference, object)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isEditorOf(IEditorReference reference, DBSObject object) {
        try {
            IEditorInput input = reference.getEditorInput();
            return input instanceof IDatabaseEditorInput database
                    && Objects.equals(database.getDatabaseObject(), object);
        } catch (PartInitException e) {
            return false;
        }
    }

    /** Valores lembrados do diálogo, por conexão e Rotina. */
    private static IDialogSettings history(GenericProcedure procedure) {
        IDialogSettings root = PlatformUI.getDialogSettingsProvider(FrameworkUtil.getBundle(RoutineLauncher.class))
                .getDialogSettings();
        String key = "launch:" + procedure.getDataSource().getContainer().getId() + ":" + procedure.getName();
        IDialogSettings section = root.getSection(key);
        return section != null ? section : root.addNewSection(key);
    }

    /**
     * Antes de a sessão começar, para o debug.ui não perguntar se deve trocar de perspectiva. A view Debug fica
     * visível mesmo se o usuário a fechou: na pausa, é ela que seleciona o frame do topo, e sem frame selecionado o
     * debug.ui não destaca a linha nem enche a view Variables. O debug.ui só a reabre na pausa para um tipo de launch
     * com perspectiva, e o nosso não tem. {@code VIEW_VISIBLE}, como o debug.ui, para não tirar o foco do editor.
     */
    private static void showDebugPerspective() {
        IWorkbench workbench = PlatformUI.getWorkbench();
        IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
        if (window == null && workbench.getWorkbenchWindowCount() > 0) {
            window = workbench.getWorkbenchWindows()[0];
        }
        IWorkbenchPage page;
        try {
            page = workbench.showPerspective(IDebugUIConstants.ID_DEBUG_PERSPECTIVE, window);
        } catch (WorkbenchException e) {
            LOG.error("Falha ao abrir a perspectiva Debug", e);
            return;
        }
        try {
            page.showView(IDebugUIConstants.ID_DEBUG_VIEW, null, IWorkbenchPage.VIEW_VISIBLE);
        } catch (PartInitException e) {
            LOG.error("Falha ao abrir a view Debug", e);
        }
    }
}
