package dev.fbdebug.dbeaver.interp;

/** Falha do Interpretador que não veio do servidor (uso errado, estado inconsistente). */
public final class PsqlExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    PsqlExecutionException(String message) {
        super(message);
    }
}
