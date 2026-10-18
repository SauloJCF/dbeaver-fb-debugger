package dev.fbdebug.dbeaver.debug;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;

import dev.fbdebug.dbeaver.interp.ExecutionControl;
import dev.fbdebug.dbeaver.interp.Frame;
import dev.fbdebug.dbeaver.interp.Variable;

/**
 * A execução da Rotina. Pausa, resume, step over e suspend vão para o {@link ExecutionControl} da sessão; os stack
 * frames são os frames do Interpretador, do de cima até a Rotina raiz, e só existem com a execução pausada. Step into
 * e step return entram na issue 17.
 */
final class PsqlThread extends PsqlDebugElement implements IThread {

    private static final IStackFrame[] NO_FRAMES = new IStackFrame[0];
    private static final IBreakpoint[] NO_BREAKPOINTS = new IBreakpoint[0];

    private final PsqlDebugTarget target;
    private volatile boolean suspended;
    private volatile boolean stepping;
    private volatile IStackFrame[] stackFrames = NO_FRAMES;
    private volatile IBreakpoint[] breakpoints = NO_BREAKPOINTS;
    /** Só a thread da execução: o valor de cada variável na pausa anterior. */
    private Map<Variable, Object> previousValues = Map.of();

    PsqlThread(PsqlDebugTarget target) {
        super(target);
        this.target = target;
    }

    void started() {
        fireCreationEvent();
    }

    void terminated() {
        suspended = false;
        stepping = false;
        stackFrames = NO_FRAMES;
        breakpoints = NO_BREAKPOINTS;
        fireTerminateEvent();
    }

    /**
     * Execução pausada: chamado na thread dela, que fica parada até o próximo comando. Os valores das variáveis ficam
     * guardados para a próxima pausa destacar os que mudaram.
     */
    void paused(ExecutionControl.Pause pause) {
        List<Frame> frames = pause.frames();
        IStackFrame[] built = new IStackFrame[frames.size()];
        Map<Variable, Object> values = new IdentityHashMap<>();
        for (int i = 0; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            built[i] = new PsqlStackFrame(this, target, frame, target.sourceOf(frame), previousValues);
            frame.variables().forEach(variable -> values.put(variable, variable.value()));
        }
        previousValues = values;
        stackFrames = built;
        breakpoints = pause.breakpoint() instanceof IBreakpoint breakpoint ? new IBreakpoint[] {breakpoint}
                : NO_BREAKPOINTS;
        stepping = false;
        suspended = true;
        fireSuspendEvent(switch (pause.reason()) {
            case BREAKPOINT -> DebugEvent.BREAKPOINT;
            case STEP -> DebugEvent.STEP_END;
            case REQUEST -> DebugEvent.CLIENT_REQUEST;
        });
    }

    /** Antes de soltar a execução parada: o debug.ui tira a linha destacada. */
    private void resumed(int detail) {
        suspended = false;
        stackFrames = NO_FRAMES;
        breakpoints = NO_BREAKPOINTS;
        fireResumeEvent(detail);
    }

    @Override
    public IStackFrame[] getStackFrames() {
        return isSuspended() ? stackFrames : NO_FRAMES;
    }

    @Override
    public boolean hasStackFrames() {
        return isSuspended() && stackFrames.length > 0;
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public IStackFrame getTopStackFrame() {
        IStackFrame[] frames = getStackFrames();
        return frames.length == 0 ? null : frames[0];
    }

    @Override
    public String getName() {
        return target.getName();
    }

    @Override
    public IBreakpoint[] getBreakpoints() {
        return isSuspended() ? breakpoints : NO_BREAKPOINTS;
    }

    @Override
    public boolean canResume() {
        return isSuspended();
    }

    @Override
    public boolean canSuspend() {
        return !suspended && !target.isTerminated();
    }

    @Override
    public boolean isSuspended() {
        return suspended && !target.isTerminated();
    }

    @Override
    public synchronized void resume() {
        if (isSuspended()) {
            resumed(DebugEvent.CLIENT_REQUEST);
            target.control().resume();
        }
    }

    /** Pede pausa no próximo statement; o evento de suspensão sai quando a execução parar. */
    @Override
    public void suspend() {
        target.control().suspend();
    }

    @Override
    public boolean canStepInto() {
        return false;
    }

    @Override
    public boolean canStepOver() {
        return isSuspended();
    }

    @Override
    public boolean canStepReturn() {
        return false;
    }

    @Override
    public boolean isStepping() {
        return stepping;
    }

    @Override
    public void stepInto() {
    }

    @Override
    public synchronized void stepOver() {
        if (isSuspended()) {
            stepping = true;
            resumed(DebugEvent.STEP_OVER);
            target.control().stepOver();
        }
    }

    @Override
    public void stepReturn() {
    }

    @Override
    public boolean canTerminate() {
        return target.canTerminate();
    }

    @Override
    public boolean isTerminated() {
        return target.isTerminated();
    }

    @Override
    public void terminate() {
        target.terminate();
    }
}
