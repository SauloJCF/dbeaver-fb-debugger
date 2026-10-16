package dev.fbdebug.dbeaver.testing;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import dev.fbdebug.dbeaver.interp.DebugSession;
import dev.fbdebug.dbeaver.interp.ProcedureResult;
import dev.fbdebug.dbeaver.psql.Identifiers;
import dev.fbdebug.dbeaver.psql.RoutineParser;
import dev.fbdebug.dbeaver.psql.ast.ProcedureDefinition;
import dev.fbdebug.dbeaver.source.RoutineSourceLoader;

/**
 * Teste diferencial (ADR 0005): executa a mesma Rotina no servidor e no Interpretador, cada uma na sua transação
 * com rollback, e compara as saídas. O servidor é o gabarito.
 */
public final class DifferentialHarness {

    private final TestDatabase database;

    public DifferentialHarness(TestDatabase database) {
        this.database = database;
    }

    /** Executa a procedure executável dos dois jeitos e falha mostrando cada saída divergente. */
    public void assertSameOutputs(String procedure, Object... inputs) throws SQLException {
        List<Object> arguments = Arrays.asList(inputs);
        Outputs expected = runNative(procedure, arguments);
        ProcedureResult actual = runInterpreted(procedure, arguments);
        OutputComparison.assertSame(describeCall(procedure, arguments), expected.names(), expected.values(),
                actual.names(), actual.values());
    }

    private Outputs runNative(String procedure, List<Object> inputs) throws SQLException {
        String placeholders = inputs.isEmpty() ? ""
                : "(" + String.join(", ", Collections.nCopies(inputs.size(), "?")) + ")";
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "EXECUTE PROCEDURE " + Identifiers.quote(procedure) + placeholders)) {
                for (int i = 0; i < inputs.size(); i++) {
                    statement.setObject(i + 1, inputs.get(i));
                }
                statement.execute();
                return readSingleRow(statement.getResultSet());
            } finally {
                connection.rollback();
            }
        }
    }

    private ProcedureResult runInterpreted(String procedure, List<Object> inputs) throws SQLException {
        Connection connection = database.connect();
        try (DebugSession session = new DebugSession(connection, false)) {
            ProcedureDefinition definition =
                    RoutineParser.parseProcedure(RoutineSourceLoader.loadProcedure(connection, procedure));
            return session.run(definition, inputs);
        }
    }

    private static Outputs readSingleRow(ResultSet resultSet) throws SQLException {
        List<String> names = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (resultSet == null) {
            return new Outputs(names, values);
        }
        try (resultSet) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            if (!resultSet.next()) {
                throw new AssertionError("EXECUTE PROCEDURE não devolveu linha");
            }
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                names.add(metaData.getColumnLabel(i));
                values.add(resultSet.getObject(i));
            }
        }
        return new Outputs(names, values);
    }

    private static String describeCall(String procedure, List<Object> inputs) {
        return procedure
                + inputs.stream().map(OutputComparison::describe).collect(Collectors.joining(", ", "(", ")"));
    }

    private record Outputs(List<String> names, List<Object> values) {
    }
}
