package dev.fbdebug.dbeaver.interp;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;

/** Savepoint para desfazer uma unidade inteira (statement ou chamada de Rotina) quando ela falha. */
final class Savepoints {

    private Savepoints() {
    }

    /** Trecho que fala com o servidor. */
    @FunctionalInterface
    interface SqlCall<T> {
        T run() throws SQLException;
    }

    /** Executa sob savepoint: se falhar, desfaz o que o trecho alterou e repassa o erro. */
    static <T> T undoneOnError(Connection connection, SqlCall<T> call) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        T result;
        try {
            result = call.run();
        } catch (SQLException | RuntimeException e) {
            try {
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
            } catch (SQLException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        connection.releaseSavepoint(savepoint);
        return result;
    }
}
