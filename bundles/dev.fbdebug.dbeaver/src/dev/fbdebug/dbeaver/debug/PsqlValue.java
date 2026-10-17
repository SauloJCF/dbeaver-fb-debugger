package dev.fbdebug.dbeaver.debug;

import java.util.HexFormat;

import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;

/** Valor de uma variável ou saída, como o Jaybird o devolveu. */
final class PsqlValue extends PsqlDebugElement implements IValue {

    private final String type;
    private final Object value;

    PsqlValue(IDebugTarget target, String type, Object value) {
        super(target);
        this.type = type;
        this.value = value;
    }

    /** Texto para mostrar: {@code NULL}, bytes em hexadecimal, o resto como o Java o escreve. */
    static String format(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof byte[] bytes) {
            return "0x" + HexFormat.of().withUpperCase().formatHex(bytes);
        }
        return value.toString();
    }

    @Override
    public String getReferenceTypeName() {
        return type;
    }

    @Override
    public String getValueString() {
        return format(value);
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
