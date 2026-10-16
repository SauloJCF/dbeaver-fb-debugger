package dev.fbdebug.dbeaver.interp;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import dev.fbdebug.dbeaver.psql.ast.ProcedureDefinition;

/**
 * Sessão de debug: uma execução de Rotina numa conexão dedicada, com uma única transação. Ao terminar ou abortar,
 * faz rollback, a menos que "commit ao terminar" tenha sido pedido. Fecha a conexão no {@link #close()}.
 */
public final class DebugSession implements AutoCloseable {

    private final Connection connection;
    private final boolean commitOnFinish;
    private boolean finished;

    /** @param connection conexão dedicada à sessão; passa a ser da sessão */
    public DebugSession(Connection connection, boolean commitOnFinish) throws SQLException {
        this.connection = connection;
        this.commitOnFinish = commitOnFinish;
        connection.setAutoCommit(false);
    }

    public ProcedureResult run(ProcedureDefinition procedure, List<?> inputs) throws SQLException {
        if (finished) {
            throw new IllegalStateException("A Sessão de debug já terminou");
        }
        finished = true;
        ProcedureResult result;
        try {
            result = new Interpreter(connection).execute(procedure, inputs);
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(e);
            throw e;
        }
        if (commitOnFinish) {
            connection.commit();
        } else {
            connection.rollback();
        }
        return result;
    }

    @Override
    public void close() throws SQLException {
        try {
            if (!connection.isClosed() && !connection.getAutoCommit()) {
                connection.rollback();
            }
        } finally {
            connection.close();
        }
    }

    private void rollbackQuietly(Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
    }
}
