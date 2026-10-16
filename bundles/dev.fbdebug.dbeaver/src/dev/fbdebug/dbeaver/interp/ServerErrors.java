package dev.fbdebug.dbeaver.interp;

import java.sql.SQLException;

/** Erros que o motor levantaria e que o Interpretador detecta no cliente, com os códigos do motor. */
final class ServerErrors {

    private ServerErrors() {
    }

    /** {@code isc_sing_select_err}: {@code SELECT … INTO}/{@code RETURNING … INTO} com mais de uma linha. */
    static SQLException multipleRowsInSingletonSelect() {
        return new SQLException("multiple rows in singleton select", "21000", 335544652);
    }
}
