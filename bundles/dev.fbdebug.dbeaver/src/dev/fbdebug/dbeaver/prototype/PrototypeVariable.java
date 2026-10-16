package dev.fbdebug.dbeaver.prototype;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;

/** Variável fixa; a editável aceita qualquer texto como novo valor, sem avaliação. */
final class PrototypeVariable extends PrototypeDebugElement implements IVariable {

    private final String name;
    private final String type;
    private final boolean editable;
    private volatile IValue value;
    private volatile boolean changed;

    PrototypeVariable(IDebugTarget target, String name, String type, String value, boolean editable) {
        super(target);
        this.name = name;
        this.type = type;
        this.editable = editable;
        this.value = new PrototypeValue(target, type, value);
    }

    @Override
    public IValue getValue() {
        return value;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getReferenceTypeName() {
        return type;
    }

    @Override
    public boolean hasValueChanged() {
        return changed;
    }

    @Override
    public void setValue(String expression) {
        setValue(new PrototypeValue(getDebugTarget(), type, expression));
    }

    @Override
    public void setValue(IValue newValue) {
        value = newValue;
        changed = true;
        fireChangeEvent(DebugEvent.CONTENT);
    }

    @Override
    public boolean supportsValueModification() {
        return editable;
    }

    @Override
    public boolean verifyValue(String expression) {
        return editable;
    }

    @Override
    public boolean verifyValue(IValue newValue) {
        return editable;
    }
}
