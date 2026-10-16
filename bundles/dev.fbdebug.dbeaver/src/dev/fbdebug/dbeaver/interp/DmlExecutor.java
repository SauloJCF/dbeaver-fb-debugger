package dev.fbdebug.dbeaver.interp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.fbdebug.dbeaver.psql.DynamicSql;
import dev.fbdebug.dbeaver.psql.ast.DmlStatement;
import dev.fbdebug.dbeaver.psql.ast.ExecuteStatement;

/**
 * Executa Fatias DML e {@code EXECUTE STATEMENT} local no servidor, na transação da Sessão de debug, e mantém o
 * {@code ROW_COUNT} do {@link Frame}.
 */
final class DmlExecutor {

    private final Connection connection;
    private final ExpressionEvaluator evaluator;

    DmlExecutor(Connection connection, ExpressionEvaluator evaluator) {
        this.connection = connection;
        this.evaluator = evaluator;
    }

    /**
     * O {@code ROW_COUNT} passa a ser o número de linhas afetadas ou, com {@code INTO}, lidas.
     *
     * @return a linha para os alvos do {@code INTO}, já convertida para os tipos deles; {@code null} se não há
     *         {@code INTO} ou se não veio linha, caso em que o motor deixa os alvos como estavam
     */
    List<Object> execute(DmlStatement dml, Frame frame) throws SQLException {
        BoundSql bound = BoundSql.bind(dml.sql(), frame);
        if (dml.into().isEmpty() && dml.kind() == DmlStatement.Kind.MERGE) {
            frame.rowCount(mergedRows(bound));
            return null;
        }
        if (dml.into().isEmpty()) {
            try (PreparedStatement statement = Statements.prepare(connection, bound.sql(), bound.parameters())) {
                frame.rowCount(statement.executeUpdate());
            }
            return null;
        }
        Savepoints.SqlCall<List<Object>> read =
                () -> singleRow(bound.sql(), bound.parameters(), dml.into().size());
        // No RETURNING, o DML já aconteceu quando a segunda linha aparece; o motor desfaz o statement inteiro.
        List<Object> row = dml.kind() == DmlStatement.Kind.SELECT ? read.run()
                : Savepoints.undoneOnError(connection, read);
        frame.rowCount(row == null ? 0 : 1);
        return row == null ? null : evaluator.assignable(row, types(dml.into(), frame));
    }

    /**
     * {@code EXECUTE STATEMENT} local. Não muda o {@code ROW_COUNT}, como no motor.
     *
     * @return como em {@link #execute(DmlStatement, Frame)}
     */
    List<Object> execute(ExecuteStatement execute, Frame frame) throws SQLException {
        Object text = evaluator.value(execute.sql(), frame);
        if (text == null) {
            throw new PsqlExecutionException("EXECUTE STATEMENT com texto NULL");
        }
        String sql = text.toString();
        List<Object> parameters = new ArrayList<>();
        if (execute.parameters().stream().anyMatch(parameter -> parameter.name() != null)) {
            DynamicSql.Positional positional = DynamicSql.withPositionalParameters(sql);
            sql = positional.sql();
            for (String name : positional.names()) {
                ExecuteStatement.Parameter parameter = execute.parameters().stream()
                        .filter(candidate -> Objects.equals(candidate.name(), name))
                        .findFirst()
                        .orElseThrow(() -> new PsqlExecutionException(
                                "Parâmetro do EXECUTE STATEMENT não informado: " + name));
                parameters.add(evaluator.value(parameter.value(), frame));
            }
        } else {
            for (ExecuteStatement.Parameter parameter : execute.parameters()) {
                parameters.add(evaluator.value(parameter.value(), frame));
            }
        }
        if (execute.into().isEmpty()) {
            try (PreparedStatement statement = Statements.prepare(connection, sql, parameters)) {
                statement.execute();
            }
            return null;
        }
        String dynamicSql = sql;
        List<Object> row = Savepoints.undoneOnError(connection,
                () -> singleRow(dynamicSql, parameters, execute.into().size()));
        return row == null ? null : evaluator.assignable(row, types(execute.into(), frame));
    }

    /**
     * Linhas afetadas por um {@code MERGE}. O update count do Jaybird não soma as linhas inseridas às atualizadas,
     * mas o {@code RETURNING} (multilinha no Firebird 5) devolve uma linha por linha afetada. Em PSQL não existe
     * {@code RETURNING} sem {@code INTO}, então o texto ainda não tem um.
     */
    private long mergedRows(BoundSql bound) throws SQLException {
        try (PreparedStatement statement =
                Statements.prepare(connection, bound.sql() + "\nRETURNING 1", bound.parameters());
                ResultSet resultSet = statement.executeQuery()) {
            long rows = 0;
            while (resultSet.next()) {
                rows++;
            }
            return rows;
        }
    }

    /** Primeira linha, ou {@code null} se não houver; uma segunda linha é o erro de singleton do motor. */
    private List<Object> singleRow(String sql, List<Object> parameters, int columns) throws SQLException {
        try (PreparedStatement statement = Statements.prepare(connection, sql, parameters)) {
            if (!statement.execute()) {
                return null;
            }
            try (ResultSet resultSet = statement.getResultSet()) {
                if (!resultSet.next()) {
                    return null;
                }
                List<Object> row = new ArrayList<>(columns);
                for (int i = 1; i <= columns; i++) {
                    row.add(resultSet.getObject(i));
                }
                if (resultSet.next()) {
                    throw ServerErrors.multipleRowsInSingletonSelect();
                }
                return row;
            }
        }
    }

    private static List<String> types(List<String> targets, Frame frame) {
        return targets.stream().map(target -> frame.get(target).type()).toList();
    }
}
