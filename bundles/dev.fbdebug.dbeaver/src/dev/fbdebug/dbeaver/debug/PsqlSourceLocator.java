package dev.fbdebug.dbeaver.debug;

import java.util.Map;

import org.eclipse.debug.core.model.ISourceLocator;
import org.eclipse.debug.core.model.IStackFrame;

/**
 * Objeto do DBeaver de cada stack frame, que a model presentation ({@code PsqlModelPresentation}) abre no editor
 * para destacar a linha. Um frame de Rotina sem objeto conhecido não tem fonte.
 */
public final class PsqlSourceLocator implements ISourceLocator {

    private final Map<SourceId, Object> sources;

    /** @param sources objeto do DBeaver de cada texto de Rotina */
    public PsqlSourceLocator(Map<SourceId, Object> sources) {
        this.sources = Map.copyOf(sources);
    }

    @Override
    public Object getSourceElement(IStackFrame stackFrame) {
        return stackFrame instanceof PsqlStackFrame frame ? sources.get(frame.source()) : null;
    }
}
