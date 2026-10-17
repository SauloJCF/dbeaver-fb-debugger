package dev.fbdebug.dbeaver.debug;

import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;

import dev.fbdebug.dbeaver.interp.Variable;

/** Parâmetro ou variável local, com o valor do momento da pausa. A edição entra na issue 15. */
final class PsqlVariable extends PsqlDebugElement implements IVariable {

    private final Variable variable;
    private final IValue value;

    PsqlVariable(IDebugTarget target, Variable variable) {
        super(target);
        this.variable = variable;
        this.value = new PsqlValue(target, variable.type(), variable.value());
    }

    @Override
    public IValue getValue() {
        return value;
    }

    @Override
    public String getName() {
        return variable.name();
    }

    @Override
    public String getReferenceTypeName() {
        return variable.type();
    }

    @Override
    public boolean hasValueChanged() {
        return false;
    }

    @Override
    public void setValue(String expression) {
    }

    @Override
    public void setValue(IValue newValue) {
    }

    @Override
    public boolean supportsValueModification() {
        return false;
    }

    @Override
    public boolean verifyValue(String expression) {
        return false;
    }

    @Override
    public boolean verifyValue(IValue newValue) {
        return false;
    }
}
