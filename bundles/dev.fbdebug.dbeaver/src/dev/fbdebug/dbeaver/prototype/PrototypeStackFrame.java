package dev.fbdebug.dbeaver.prototype;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IRegisterGroup;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IVariable;

final class PrototypeStackFrame extends PrototypeDebugElement implements IStackFrame {

    private final PrototypeThread thread;
    private final IVariable[] variables;

    PrototypeStackFrame(PrototypeThread thread) {
        super(thread.getDebugTarget());
        this.thread = thread;
        this.variables = new IVariable[] {
            new PrototypeVariable(thread.getDebugTarget(), "V_CONTADOR", "INTEGER", "1", true),
            new PrototypeVariable(thread.getDebugTarget(), "V_NOME", "VARCHAR(50)", "'PROTOTIPO'", false),
        };
    }

    @Override
    public IThread getThread() {
        return thread;
    }

    @Override
    public IVariable[] getVariables() {
        return variables;
    }

    @Override
    public boolean hasVariables() {
        return true;
    }

    @Override
    public int getLineNumber() {
        return 1;
    }

    @Override
    public int getCharStart() {
        return -1;
    }

    @Override
    public int getCharEnd() {
        return -1;
    }

    @Override
    public String getName() {
        return "PROTOTIPO linha 1";
    }

    @Override
    public IRegisterGroup[] getRegisterGroups() {
        return new IRegisterGroup[0];
    }

    @Override
    public boolean hasRegisterGroups() {
        return false;
    }

    @Override
    public boolean canStepInto() {
        return thread.canStepInto();
    }

    @Override
    public boolean canStepOver() {
        return thread.canStepOver();
    }

    @Override
    public boolean canStepReturn() {
        return thread.canStepReturn();
    }

    @Override
    public boolean isStepping() {
        return thread.isStepping();
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
    public boolean canResume() {
        return thread.canResume();
    }

    @Override
    public boolean canSuspend() {
        return thread.canSuspend();
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
    public boolean canTerminate() {
        return thread.canTerminate();
    }

    @Override
    public boolean isTerminated() {
        return thread.isTerminated();
    }

    @Override
    public void terminate() {
        thread.terminate();
    }
}
