package dev.fbdebug.dbeaver.prototype;

import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;

final class PrototypeValue extends PrototypeDebugElement implements IValue {

    private final String type;
    private final String text;

    PrototypeValue(IDebugTarget target, String type, String text) {
        super(target);
        this.type = type;
        this.text = text;
    }

    @Override
    public String getReferenceTypeName() {
        return type;
    }

    @Override
    public String getValueString() {
        return text;
    }

    @Override
    public boolean isAllocated() {
        return true;
    }

    @Override
    public IVariable[] getVariables() {
        return new IVariable[0];
    }

    @Override
    public boolean hasVariables() {
        return false;
    }
}
