package dev.fbdebug.dbeaver.debug;

import org.eclipse.debug.core.model.DebugElement;
import org.eclipse.debug.core.model.IDebugTarget;

/** Elemento do modelo de debug do Eclipse sobre uma Sessão de debug (ADR 0004). */
abstract class PsqlDebugElement extends DebugElement {

    static final String MODEL_ID = "dev.fbdebug.dbeaver.psql";

    PsqlDebugElement(IDebugTarget target) {
        super(target);
    }

    @Override
    public String getModelIdentifier() {
        return MODEL_ID;
    }
}
