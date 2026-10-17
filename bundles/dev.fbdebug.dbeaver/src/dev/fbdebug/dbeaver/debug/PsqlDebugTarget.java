package dev.fbdebug.dbeaver.debug;

import java.sql.SQLException;
import java.util.List;

import org.eclipse.core.resources.IMarkerDelta;
import org.eclipse.core.runtime.ILog;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IMemoryBlock;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IThread;

import dev.fbdebug.dbeaver.interp.DebugSession;
import dev.fbdebug.dbeaver.interp.Frame;
import dev.fbdebug.dbeaver.interp.ProcedureResult;
import dev.fbdebug.dbeaver.interp.SessionAbortedException;
import dev.fbdebug.dbeaver.psql.ast.RoutineDefinition;

/**
 * Sessão de debug no modelo do Eclipse: um alvo com uma thread, a execução da Rotina, que roda numa thread própria
 * até o fim. As saídas, o erro ou a interrupção vão para o {@link SessionConsole}. Terminate pede o fim à sessão
 * ({@link DebugSession#abort()}): ela para antes do próximo statement e faz rollback.
 */
public final class PsqlDebugTarget extends PsqlDebugElement implements IDebugTarget {

    /** Abre a Sessão de debug, na thread da execução: a varredura dos Packages temporários já fala com o banco. */
    @FunctionalInterface
    public interface SessionOpener {
        DebugSession open() throws SQLException;
    }

    private static final ILog LOG = ILog.of(PsqlDebugTarget.class);

    private final ILaunch launch;
    private final RoutineDefinition routine;
    private final List<Object> inputs;
    private final boolean commitOnFinish;
    private final SessionOpener opener;
    private final AutoCloseable connectionOwner;
    private final SessionConsole console;
    private final PsqlThread thread;
    private volatile DebugSession session;
    private volatile boolean terminateRequested;
    private volatile boolean terminated;

    /**
     * @param inputs valores das entradas, na ordem; os que faltarem no fim usam o valor padrão
     * @param connectionOwner dono da conexão da sessão (o contexto isolado do DBeaver), fechado ao fim
     */
    public PsqlDebugTarget(ILaunch launch, RoutineDefinition routine, List<Object> inputs, boolean commitOnFinish,
            SessionOpener opener, AutoCloseable connectionOwner, SessionConsole console) {
        super(null);
        this.launch = launch;
        this.routine = routine;
        this.inputs = inputs;
        this.commitOnFinish = commitOnFinish;
        this.opener = opener;
        this.connectionOwner = connectionOwner;
        this.console = console;
        this.thread = new PsqlThread(this);
    }

    /** Anuncia o alvo e a thread e começa a execução. */
    public void start() {
        fireCreationEvent();
        thread.started();
        Thread execution = new Thread(this::execute, "Firebird PSQL debug: " + routine.displayName());
        execution.setDaemon(true);
        execution.start();
    }

    private void execute() {
        console.started(routine, inputs);
        try (DebugSession opened = opener.open()) {
            session = opened;
            if (terminateRequested) {
                opened.abort();
            }
            ProcedureResult result = opened.run(routine, inputs);
            console.finished(result, commitOnFinish);
        } catch (SessionAbortedException e) {
            console.aborted();
        } catch (SQLException e) {
            console.failed(e);
        } catch (RuntimeException e) {
            console.failed(e);
            LOG.error("Falha na Sessão de debug de " + routine.displayName(), e);
        } finally {
            closeConnectionOwner();
            terminated = true;
            thread.terminated();
            fireTerminateEvent();
        }
    }

    private void closeConnectionOwner() {
        try {
            connectionOwner.close();
        } catch (Exception e) {
            LOG.warn("Falha ao fechar a conexão da Sessão de debug", e);
        }
    }

    /** Pilha de frames da execução; só é consistente com a execução pausada. */
    List<Frame> frames() {
        DebugSession current = session;
        return current == null ? List.of() : current.frames();
    }

    @Override
    public IDebugTarget getDebugTarget() {
        return this;
    }

    @Override
    public ILaunch getLaunch() {
        return launch;
    }

    @Override
    public IProcess getProcess() {
        return null;
    }

    @Override
    public IThread[] getThreads() {
        return terminated ? new IThread[0] : new IThread[] {thread};
    }

    @Override
    public boolean hasThreads() {
        return !terminated;
    }

    @Override
    public String getName() {
        return routine.displayName();
    }

    @Override
    public boolean supportsBreakpoint(IBreakpoint breakpoint) {
        return false;
    }

    @Override
    public boolean canTerminate() {
        return !terminated && !terminateRequested;
    }

    @Override
    public boolean isTerminated() {
        return terminated;
    }

    @Override
    public void terminate() {
        terminateRequested = true;
        DebugSession current = session;
        if (current != null) {
            current.abort();
        }
    }

    @Override
    public boolean canResume() {
        return false;
    }

    @Override
    public boolean canSuspend() {
        return false;
    }

    @Override
    public boolean isSuspended() {
        return thread.isSuspended();
    }

    @Override
    public void resume() {
    }

    @Override
    public void suspend() {
    }

    @Override
    public void breakpointAdded(IBreakpoint breakpoint) {
    }

    @Override
    public void breakpointRemoved(IBreakpoint breakpoint, IMarkerDelta delta) {
    }

    @Override
    public void breakpointChanged(IBreakpoint breakpoint, IMarkerDelta delta) {
    }

    @Override
    public boolean canDisconnect() {
        return false;
    }

    @Override
    public void disconnect() {
    }

    @Override
    public boolean isDisconnected() {
        return false;
    }

    @Override
    public boolean supportsStorageRetrieval() {
        return false;
    }

    @Override
    public IMemoryBlock getMemoryBlock(long startAddress, long length) {
        return null;
    }
}
