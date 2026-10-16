package dev.fbdebug.dbeaver.interp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.fbdebug.dbeaver.psql.ast.Expression;

/**
 * Texto opaco pronto para o servidor: cada variável do escopo vira {@code CAST(? AS <tipo declarado>)} e o
 * {@code ROW_COUNT}, que só existe dentro de PSQL, vira o valor que o Interpretador mantém no {@link Frame}.
 *
 * @param parameters valores dos {@code ?}, na ordem do texto
 */
record BoundSql(String sql, List<Object> parameters) {

    private static final String ROW_COUNT = "ROW_COUNT";

    BoundSql {
        parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
    }

    static BoundSql bind(Expression expression, Frame frame) {
        StringBuilder sql = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        for (Expression.Part part : expression.parts()) {
            if (!(part instanceof Expression.Name name)) {
                sql.append(part.sql());
                continue;
            }
            Variable variable = frame.find(name.name()).orElse(null);
            if (variable != null) {
                sql.append("CAST(? AS ").append(variable.type()).append(')');
                parameters.add(variable.value());
            } else if (ROW_COUNT.equals(name.name())) {
                sql.append("CAST(? AS BIGINT)");
                parameters.add(frame.rowCount());
            } else {
                sql.append(part.sql());
            }
        }
        return new BoundSql(sql.toString(), parameters);
    }
}
