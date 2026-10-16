package dev.fbdebug.dbeaver.testing;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.fbdebug.dbeaver.interp.Interpreter;
import dev.fbdebug.dbeaver.interp.ProcedureResult;
import dev.fbdebug.dbeaver.psql.Identifiers;
import dev.fbdebug.dbeaver.psql.PreCheck;
import dev.fbdebug.dbeaver.psql.RoutineParser;
import dev.fbdebug.dbeaver.psql.ast.ProcedureDefinition;
import dev.fbdebug.dbeaver.source.RoutineSourceLoader;

/**
 * Teste diferencial (ADR 0005): executa a mesma Rotina no servidor e no Interpretador, cada uma na sua transação
 * com rollback, e compara parâmetros de saída, exceção e o estado das tabelas tocadas antes do rollback. O servidor
 * é o gabarito.
 */
public final class DifferentialHarness {

    private final TestDatabase database;
    private final List<String> tables;

    public DifferentialHarness(TestDatabase database) {
        this(database, List.of());
    }

    private DifferentialHarness(TestDatabase database, List<String> tables) {
        this.database = database;
        this.tables = List.copyOf(tables);
    }

    /** Harness que compara também o conteúdo destas tabelas, lido antes do rollback de cada execução. */
    public DifferentialHarness tables(String... names) {
        return new DifferentialHarness(database, Arrays.asList(names));
    }

    /**
     * Executa a procedure executável dos dois jeitos e falha mostrando cada divergência: saída, exceção (código,
     * SQLSTATE e mensagem) ou linha de tabela.
     */
    public void assertSameOutputs(String procedure, Object... inputs) throws SQLException {
        List<Object> arguments = Arrays.asList(inputs);
        Outcome expected = runNative(procedure, arguments);
        Outcome actual = runInterpreted(procedure, arguments);
        String call = describeCall(procedure, arguments);
        OutputComparison.assertSameError(call, expected.error(), actual.error());
        if (expected.error() == null) {
            OutputComparison.assertSame(call, expected.names(), expected.values(), actual.names(), actual.values());
        }
        OutputComparison.assertSameTables(call, expected.tables(), actual.tables());
    }

    private Outcome runNative(String procedure, List<Object> inputs) throws SQLException {
        String placeholders = inputs.isEmpty() ? ""
                : "(" + String.join(", ", Collections.nCopies(inputs.size(), "?")) + ")";
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                Outputs outputs;
                try (PreparedStatement statement = connection.prepareStatement(
                        "EXECUTE PROCEDURE " + Identifiers.quote(procedure) + placeholders)) {
                    for (int i = 0; i < inputs.size(); i++) {
                        statement.setObject(i + 1, inputs.get(i));
                    }
                    statement.execute();
                    outputs = readSingleRow(statement.getResultSet());
                } catch (SQLException e) {
                    return new Outcome(List.of(), List.of(), e, snapshot(connection));
                }
                return new Outcome(outputs.names(), outputs.values(), null, snapshot(connection));
            } finally {
                connection.rollback();
            }
        }
    }

    private Outcome runInterpreted(String procedure, List<Object> inputs) throws SQLException {
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                ProcedureDefinition definition =
                        RoutineParser.parseProcedure(RoutineSourceLoader.loadProcedure(connection, procedure));
                List<PreCheck.Finding> findings = PreCheck.scan(definition);
                if (!findings.isEmpty()) {
                    throw new AssertionError(procedure + ": Pré-check recusou a Rotina: " + findings);
                }
                ProcedureResult result;
                try {
                    result = new Interpreter(connection).execute(definition, inputs);
                } catch (SQLException e) {
                    return new Outcome(List.of(), List.of(), e, snapshot(connection));
                }
                return new Outcome(result.names(), result.values(), null, snapshot(connection));
            } finally {
                connection.rollback();
            }
        }
    }

    /** Linhas de cada tabela, descritas e ordenadas, para não depender da ordem física. */
    private Map<String, List<String>> snapshot(Connection connection) throws SQLException {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (String table : tables) {
            List<String> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery("SELECT * FROM " + Identifiers.quote(table))) {
                int columns = resultSet.getMetaData().getColumnCount();
                while (resultSet.next()) {
                    List<String> values = new ArrayList<>();
                    for (int i = 1; i <= columns; i++) {
                        values.add(OutputComparison.describe(resultSet.getObject(i)));
                    }
                    rows.add(String.join(", ", values));
                }
            }
            Collections.sort(rows);
            snapshot.put(table, rows);
        }
        return snapshot;
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

    /** Resultado de uma execução: saídas, ou a exceção que a terminou; e as tabelas antes do rollback. */
    private record Outcome(List<String> names, List<Object> values, SQLException error,
            Map<String, List<String>> tables) {
    }
}
