package dev.fbdebug.dbeaver.interp;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.fbdebug.dbeaver.psql.ast.Expression;

/**
 * Texto opaco pronto para o servidor: cada variável do escopo e cada campo de cursor ({@code cursor.campo}) vira
 * {@code CAST(? AS <tipo declarado>)}. As variáveis de contexto que só existem dentro de PSQL ({@code ROW_COUNT},
 * {@code GDSCODE}, {@code SQLCODE}, {@code SQLSTATE}) viram o valor que o Interpretador mantém no {@link Frame}.
 *
 * @param parameters valores dos {@code ?}, na ordem do texto
 */
record BoundSql(String sql, List<Object> parameters) {

    BoundSql {
        parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
    }

    /**
     * @throws SQLException o erro do motor para um campo de cursor fechado ou sem linha atual
     */
    static BoundSql bind(Expression expression, Frame frame) throws SQLException {
        StringBuilder sql = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        for (Expression.Part part : expression.parts()) {
            switch (part) {
                case Expression.Sql text -> sql.append(text.sql());
                case Expression.Name name -> {
                    Variable variable = frame.find(name.name()).orElse(null);
                    if (variable != null) {
                        parameter(sql, parameters, variable.type(), variable.value());
                    } else {
                        context(name, frame, sql, parameters);
                    }
                }
                case Expression.Field field -> {
                    Cursor cursor = frame.findCursor(field.qualifier()).orElse(null);
                    if (cursor == null) {
                        sql.append(field.sql()); // nome qualificado de tabela, numa subquery
                    } else {
                        Cursor.FieldValue value = cursor.field(field.name());
                        parameter(sql, parameters, value.type(), value.value());
                    }
                }
            }
        }
        return new BoundSql(sql.toString(), parameters);
    }

    private static void context(Expression.Name name, Frame frame, StringBuilder sql, List<Object> parameters) {
        ErrorStatus error = frame.handledError();
        switch (name.name()) {
            case "ROW_COUNT" -> parameter(sql, parameters, "BIGINT", frame.rowCount());
            case "GDSCODE" -> parameter(sql, parameters, "INTEGER", error.gdsCode());
            case "SQLCODE" -> parameter(sql, parameters, "INTEGER", error.sqlCode());
            case "SQLSTATE" -> parameter(sql, parameters, "CHAR(5)", error.sqlState());
            default -> sql.append(name.sql());
        }
    }

    private static void parameter(StringBuilder sql, List<Object> parameters, String type, Object value) {
        sql.append("CAST(? AS ").append(type).append(')');
        parameters.add(value);
    }
}
