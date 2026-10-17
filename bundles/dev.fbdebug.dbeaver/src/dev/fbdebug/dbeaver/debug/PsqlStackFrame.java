package dev.fbdebug.dbeaver.debug;

import org.eclipse.debug.core.model.IRegisterGroup;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IVariable;

import dev.fbdebug.dbeaver.interp.Frame;

/**
 * Frame do Interpretador numa pausa: a Rotina, a linha do statement em execução e as variáveis dela. A linha é a do
 * texto da Fonte do banco montado pelo {@code RoutineSourceLoader}, que segue o do editor do DBeaver (issue 09).
 */
final class PsqlStackFrame extends PsqlDebugElement implements IStackFrame {

    private final PsqlThread thread;
    private final Frame frame;
    private final int line;
    private final IVariable[] variables;

    PsqlStackFrame(PsqlThread thread, Frame frame) {
        super(thread.getDebugTarget());
        this.thread = thread;
        this.frame = frame;
        this.line = frame.position().line();
        this.variables = frame.variables().stream()
                .map(variable -> new PsqlVariable(thread.getDebugTarget(), variable))
                .toArray(IVariable[]::new);
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
        return variables.length > 0;
    }

    @Override
    public int getLineNumber() {
        return line;
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
        return frame.routine().displayName() + " linha " + line;
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
        thread.stepInto();
    }

    @Override
    public void stepOver() {
        thread.stepOver();
    }

    @Override
    public void stepReturn() {
        thread.stepReturn();
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
    public void resume() {
        thread.resume();
    }

    @Override
    public void suspend() {
        thread.suspend();
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
