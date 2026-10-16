package dev.fbdebug.dbeaver.prototype;

import org.eclipse.core.resources.IMarkerDelta;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IMemoryBlock;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IThread;

/** Alvo com uma única thread, já suspensa no "PROTOTIPO linha 1". Resume encerra a sessão. */
final class PrototypeDebugTarget extends PrototypeDebugElement implements IDebugTarget {

    private final ILaunch launch;
    private final PrototypeThread thread;
    private volatile boolean terminated;

    PrototypeDebugTarget(ILaunch launch) {
        super(null);
        this.launch = launch;
        this.thread = new PrototypeThread(this);
    }

    void start() {
        fireCreationEvent();
        thread.announceSuspended(DebugEvent.BREAKPOINT);
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
        return "Firebird PSQL (protótipo)";
    }

    @Override
    public boolean supportsBreakpoint(IBreakpoint breakpoint) {
        return false;
    }

    @Override
    public boolean canTerminate() {
        return !terminated;
    }

    @Override
    public boolean isTerminated() {
        return terminated;
    }

    @Override
    public void terminate() {
        if (terminated) {
            return;
        }
        terminated = true;
        thread.announceTerminated();
        fireTerminateEvent();
    }

    @Override
    public boolean canResume() {
        return thread.canResume();
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
    public void resume() throws DebugException {
        thread.resume();
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
