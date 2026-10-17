package dev.fbdebug.dbeaver.debug;

import java.util.List;

import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;

import dev.fbdebug.dbeaver.interp.Frame;

/**
 * A execução da Rotina. Os stack frames são os frames do Interpretador, do de cima até a Rotina raiz, e só existem
 * com a execução pausada; pausa, step e resume entram com os breakpoints (issue 14).
 */
final class PsqlThread extends PsqlDebugElement implements IThread {

    private final PsqlDebugTarget target;
    private volatile boolean suspended;
    private volatile IStackFrame[] stackFrames = new IStackFrame[0];

    PsqlThread(PsqlDebugTarget target) {
        super(target);
        this.target = target;
    }

    void started() {
        fireCreationEvent();
    }

    void terminated() {
        suspended = false;
        stackFrames = new IStackFrame[0];
        fireTerminateEvent();
    }

    /** Monta os stack frames da pausa; chamado com a execução parada. */
    void suspended(int detail) {
        List<Frame> frames = target.frames();
        IStackFrame[] built = new IStackFrame[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            built[i] = new PsqlStackFrame(this, frames.get(i));
        }
        stackFrames = built;
        suspended = true;
        fireSuspendEvent(detail);
    }

    @Override
    public IStackFrame[] getStackFrames() {
        return isSuspended() ? stackFrames : new IStackFrame[0];
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
        return new IBreakpoint[0];
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
        return suspended && !target.isTerminated();
    }

    @Override
    public void resume() {
    }

    @Override
    public void suspend() {
    }

    @Override
    public boolean canStepInto() {
        return false;
    }

    @Override
    public boolean canStepOver() {
        return false;
    }

    @Override
    public boolean canStepReturn() {
        return false;
    }

    @Override
    public boolean isStepping() {
        return false;
    }

    @Override
    public void stepInto() {
    }

    @Override
    public void stepOver() {
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
