package dev.fbdebug.dbeaver.interp;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import dev.fbdebug.dbeaver.psql.PreCheck;
import dev.fbdebug.dbeaver.psql.ast.RoutineDefinition;

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

    /**
     * Executa a Rotina como executável (ver {@link Interpreter#execute}).
     *
     * @throws PsqlExecutionException se o Pré-check recusar a Rotina, antes de executar qualquer coisa
     */
    public ProcedureResult run(RoutineDefinition routine, List<?> inputs) throws SQLException {
        return finish(routine, interpreter -> interpreter.execute(routine, inputs));
    }

    /**
     * Executa a Rotina como selecionável. As linhas do {@code SUSPEND} chegam a {@code rows} à medida que são emitidas,
     * e são o resultado da sessão (ver {@link Interpreter#select}).
     *
     * @throws PsqlExecutionException se o Pré-check recusar a Rotina, antes de executar qualquer coisa
     */
    public void select(RoutineDefinition routine, List<?> inputs, Consumer<List<Object>> rows)
            throws SQLException {
        finish(routine, interpreter -> {
            interpreter.select(routine, inputs, rows);
            return null;
        });
    }

    /** Pré-check, execução e fim da transação. */
    private <T> T finish(RoutineDefinition routine, Execution<T> execution) throws SQLException {
        if (finished) {
            throw new IllegalStateException("A Sessão de debug já terminou");
        }
        List<PreCheck.Finding> findings = PreCheck.scan(routine);
        if (!findings.isEmpty()) {
            throw new PsqlExecutionException("Pré-check recusou " + routine.displayName() + ":\n"
                    + findings.stream().map(PreCheck.Finding::toString).collect(Collectors.joining("\n")));
        }
        finished = true;
        T result;
        try {
            result = execution.run(new Interpreter(connection));
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

    @FunctionalInterface
    private interface Execution<T> {
        T run(Interpreter interpreter) throws SQLException;
    }

    private void rollbackQuietly(Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
    }
}
