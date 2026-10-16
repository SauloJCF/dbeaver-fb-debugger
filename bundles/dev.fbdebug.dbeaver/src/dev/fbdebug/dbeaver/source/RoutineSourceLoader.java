package dev.fbdebug.dbeaver.source;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import dev.fbdebug.dbeaver.psql.Identifiers;

/**
 * Lê a Fonte do banco de uma Rotina. O corpo vem de {@code RDB$PROCEDURE_SOURCE}; o cabeçalho, que o Firebird não
 * guarda como texto, é montado a partir de {@code RDB$PROCEDURE_PARAMETERS} e {@code RDB$FIELDS}.
 */
public final class RoutineSourceLoader {

    private static final String PROCEDURE_SQL = """
            SELECT RDB$PROCEDURE_SOURCE
            FROM RDB$PROCEDURES
            WHERE RDB$PROCEDURE_NAME = ? AND RDB$PACKAGE_NAME IS NULL""";

    private static final String PARAMETERS_SQL = """
            SELECT P.RDB$PARAMETER_NAME, P.RDB$PARAMETER_TYPE, P.RDB$NULL_FLAG, P.RDB$PARAMETER_MECHANISM,
                   P.RDB$FIELD_SOURCE, P.RDB$RELATION_NAME, P.RDB$FIELD_NAME, P.RDB$DEFAULT_SOURCE,
                   F.RDB$FIELD_TYPE, F.RDB$FIELD_SUB_TYPE, F.RDB$FIELD_SCALE, F.RDB$FIELD_PRECISION,
                   F.RDB$CHARACTER_LENGTH, CS.RDB$CHARACTER_SET_NAME
            FROM RDB$PROCEDURE_PARAMETERS P
            JOIN RDB$FIELDS F ON F.RDB$FIELD_NAME = P.RDB$FIELD_SOURCE
            LEFT JOIN RDB$CHARACTER_SETS CS ON CS.RDB$CHARACTER_SET_ID = F.RDB$CHARACTER_SET_ID
            WHERE P.RDB$PROCEDURE_NAME = ? AND P.RDB$PACKAGE_NAME IS NULL
            ORDER BY P.RDB$PARAMETER_TYPE, P.RDB$PARAMETER_NUMBER""";

    private RoutineSourceLoader() {
    }

    /**
     * @param name nome da procedure como está nos metadados
     * @return {@code CREATE PROCEDURE ...} completo, com o corpo idêntico ao gravado no banco
     */
    public static String loadProcedure(Connection connection, String name) throws SQLException {
        String body;
        try (PreparedStatement statement = connection.prepareStatement(PROCEDURE_SQL)) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Procedure não encontrada: " + name);
                }
                body = resultSet.getString(1);
            }
        }
        if (body == null) {
            throw new SQLException("Procedure sem Fonte do banco (externa ou sem fonte): " + name);
        }

        List<String> inputs = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(PARAMETERS_SQL)) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    (resultSet.getInt("RDB$PARAMETER_TYPE") == 0 ? inputs : outputs).add(parameter(resultSet));
                }
            }
        }

        StringBuilder text = new StringBuilder("CREATE PROCEDURE ").append(Identifiers.quote(name));
        if (!inputs.isEmpty()) {
            text.append(" (").append(String.join(", ", inputs)).append(')');
        }
        if (!outputs.isEmpty()) {
            text.append("\nRETURNS (").append(String.join(", ", outputs)).append(')');
        }
        return text.append("\nAS\n").append(body).toString();
    }

    private static String parameter(ResultSet row) throws SQLException {
        StringBuilder text = new StringBuilder(Identifiers.quote(trimmed(row, "RDB$PARAMETER_NAME")))
                .append(' ').append(parameterType(row));
        if (row.getInt("RDB$NULL_FLAG") == 1) {
            text.append(" NOT NULL");
        }
        String defaultSource = row.getString("RDB$DEFAULT_SOURCE");
        if (defaultSource != null) {
            text.append(' ').append(defaultSource.strip());
        }
        return text.toString();
    }

    private static String parameterType(ResultSet row) throws SQLException {
        String relation = trimmed(row, "RDB$RELATION_NAME");
        String column = trimmed(row, "RDB$FIELD_NAME");
        if (relation != null && column != null) {
            return "TYPE OF COLUMN " + Identifiers.quote(relation) + '.' + Identifiers.quote(column);
        }
        String fieldSource = trimmed(row, "RDB$FIELD_SOURCE");
        if (!fieldSource.startsWith("RDB$")) {
            boolean typeOf = row.getInt("RDB$PARAMETER_MECHANISM") == 1;
            return (typeOf ? "TYPE OF " : "") + Identifiers.quote(fieldSource);
        }
        return FieldTypes.render(row.getInt("RDB$FIELD_TYPE"), intOrNull(row, "RDB$FIELD_SUB_TYPE"),
                intOrNull(row, "RDB$FIELD_SCALE"), intOrNull(row, "RDB$FIELD_PRECISION"),
                intOrNull(row, "RDB$CHARACTER_LENGTH"), trimmed(row, "RDB$CHARACTER_SET_NAME"));
    }

    private static String trimmed(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        return value == null ? null : value.strip();
    }

    private static Integer intOrNull(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }
}
