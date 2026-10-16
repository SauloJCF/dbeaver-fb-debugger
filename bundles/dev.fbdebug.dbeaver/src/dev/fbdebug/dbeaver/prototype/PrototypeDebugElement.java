package dev.fbdebug.dbeaver.prototype;

import org.eclipse.debug.core.model.DebugElement;
import org.eclipse.debug.core.model.IDebugTarget;

abstract class PrototypeDebugElement extends DebugElement {

    static final String MODEL_ID = "dev.fbdebug.dbeaver.prototype";

    PrototypeDebugElement(IDebugTarget target) {
        super(target);
    }

    @Override
    public String getModelIdentifier() {
        return MODEL_ID;
    }
}
