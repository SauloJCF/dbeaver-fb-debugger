package dev.fbdebug.dbeaver.prototype;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;

final class PrototypeThread extends PrototypeDebugElement implements IThread {

    private final PrototypeDebugTarget target;
    private final PrototypeStackFrame frame;
    private volatile boolean suspended = true;

    PrototypeThread(PrototypeDebugTarget target) {
        super(target);
        this.target = target;
        this.frame = new PrototypeStackFrame(this);
    }

    void announceSuspended(int detail) {
        fireCreationEvent();
        fireSuspendEvent(detail);
    }

    void announceTerminated() {
        suspended = false;
        fireTerminateEvent();
    }

    @Override
    public IStackFrame[] getStackFrames() {
        return isSuspended() ? new IStackFrame[] {frame} : new IStackFrame[0];
    }

    @Override
    public boolean hasStackFrames() {
        return isSuspended();
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public IStackFrame getTopStackFrame() {
        return isSuspended() ? frame : null;
    }

    @Override
    public String getName() {
        return "PROTOTIPO";
    }

    @Override
    public IBreakpoint[] getBreakpoints() {
        return new IBreakpoint[0];
    }

    @Override
    public boolean canResume() {
        return isSuspended();
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
    public void resume() throws DebugException {
        suspended = false;
        fireResumeEvent(DebugEvent.CLIENT_REQUEST);
        target.terminate();
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
