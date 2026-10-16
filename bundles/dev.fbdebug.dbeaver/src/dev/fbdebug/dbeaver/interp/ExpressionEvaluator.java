package dev.fbdebug.dbeaver.interp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.fbdebug.dbeaver.psql.ast.Expression;

/**
 * Avaliação de expressão no servidor (Modo Careful, ADR 0002): {@code SELECT <expr> FROM RDB$DATABASE}, com
 * cada variável enviada como {@code CAST(? AS <tipo declarado>)}.
 */
final class ExpressionEvaluator {

    private final Connection connection;

    ExpressionEvaluator(Connection connection) {
        this.connection = connection;
    }

    /**
     * Valor da expressão convertido para {@code targetType}, como numa atribuição: o {@code CAST} do servidor
     * arredonda, completa {@code CHAR} e recusa truncamento do mesmo jeito.
     */
    Object evaluate(Expression expression, Frame frame, String targetType) throws SQLException {
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT CAST((");
        appendBound(sql, parameters, expression, frame);
        sql.append(") AS ").append(targetType).append(") FROM RDB$DATABASE");
        return selectSingleValue(sql.toString(), parameters);
    }

    /**
     * Condição de {@code IF}/{@code WHILE}. Vai no {@code WHERE}, então só {@code TRUE} devolve linha:
     * {@code FALSE} e desconhecido ({@code NULL}) valem como falso, com a lógica de três valores do servidor.
     */
    boolean isTrue(Expression condition, Frame frame) throws SQLException {
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT 1 FROM RDB$DATABASE WHERE (");
        appendBound(sql, parameters, condition, frame);
        sql.append(')');
        try (PreparedStatement statement = prepare(sql.toString(), parameters);
                ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next();
        }
    }

    /** Valor externo (ex.: parâmetro de entrada do launch) convertido para o tipo, como o servidor faria. */
    Object convert(Object value, String targetType) throws SQLException {
        return selectSingleValue("SELECT CAST(? AS " + targetType + ") FROM RDB$DATABASE",
                Collections.singletonList(value));
    }

    /** Texto da expressão com cada variável do escopo trocada por {@code CAST(? AS <tipo>)}. */
    private static void appendBound(StringBuilder sql, List<Object> parameters, Expression expression,
            Frame frame) {
        for (Expression.Part part : expression.parts()) {
            Variable variable = part instanceof Expression.Name name ? frame.find(name.name()).orElse(null) : null;
            if (variable != null) {
                sql.append("CAST(? AS ").append(variable.type()).append(')');
                parameters.add(variable.value());
            } else {
                sql.append(part.sql());
            }
        }
    }

    private Object selectSingleValue(String sql, List<Object> parameters) throws SQLException {
        try (PreparedStatement statement = prepare(sql, parameters);
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getObject(1);
        }
    }

    private PreparedStatement prepare(String sql, List<Object> parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < parameters.size(); i++) {
                statement.setObject(i + 1, parameters.get(i));
            }
        } catch (SQLException | RuntimeException e) {
            statement.close();
            throw e;
        }
        return statement;
    }
}
