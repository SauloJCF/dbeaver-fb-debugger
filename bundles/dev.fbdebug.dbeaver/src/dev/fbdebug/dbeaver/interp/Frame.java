package dev.fbdebug.dbeaver.interp;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Estado de uma Rotina em execução: variáveis visíveis (parâmetros de entrada, de saída e locais, na ordem
 * declarada), cursores, o {@code ROW_COUNT}, o erro do handler em execução e os savepoints dos blocos.
 */
public final class Frame {

    private final Map<String, Variable> variables = new LinkedHashMap<>();
    /** Cursores declarados e os nomeados dos laços em execução. */
    private final Map<String, Cursor> cursors = new HashMap<>();
    /** Cursores dos laços em execução, do mais interno para o mais externo. */
    private final Deque<Cursor> loops = new ArrayDeque<>();

    // ROW_COUNT, como o contador de linhas afetadas do motor: mostra as linhas alteradas pela última DML, ou, se a
    // última que zerou o contador foi uma leitura, as linhas lidas desde então.
    private boolean rowsWritten;
    private long rowsFetched;
    private long rowsModified;

    private ErrorStatus handledError = ErrorStatus.NONE;

    private final SavepointStack savepoints;

    Frame(SavepointStack savepoints) {
        this.savepoints = savepoints;
    }

    /** Savepoints dos blocos em execução. */
    SavepointStack savepoints() {
        return savepoints;
    }

    Variable declare(String name, String type, boolean notNull, Variable.Kind kind) {
        Variable variable = new Variable(name, type, notNull, kind);
        if (variables.putIfAbsent(name, variable) != null) {
            throw new PsqlExecutionException("Variável declarada duas vezes: " + name);
        }
        return variable;
    }

    /** @param name nome normalizado (ver {@code Identifiers.normalize}) */
    public Optional<Variable> find(String name) {
        return Optional.ofNullable(variables.get(name));
    }

    Variable get(String name) {
        return find(name).orElseThrow(() -> new PsqlExecutionException("Variável não declarada: " + name));
    }

    public Collection<Variable> variables() {
        return Collections.unmodifiableCollection(variables.values());
    }

    /** {@code ROW_COUNT}: 0 antes da primeira Fatia DML da Rotina. */
    public long rowCount() {
        return rowsWritten ? rowsModified : rowsFetched;
    }

    /** {@code INSERT}/{@code UPDATE}/{@code DELETE}/{@code MERGE}: zera o contador e conta as linhas alteradas. */
    void rowsModified(long count) {
        rowsWritten = true;
        rowsModified = count;
        rowsFetched = 0;
    }

    /** {@code SELECT … INTO} e {@code FETCH}: zeram o contador e contam as linhas lidas. */
    void rowsFetched(long count) {
        rowsWritten = false;
        rowsModified = 0;
        rowsFetched = count;
    }

    /**
     * Linha lida por um {@code FOR SELECT}, que conta sem zerar: depois de uma DML no corpo, o {@code ROW_COUNT}
     * continua mostrando as linhas da DML; depois de um {@code FOR} interno, a contagem segue a dele.
     */
    void rowFetched() {
        rowsFetched++;
    }

    /** {@code FOR SELECT} ao abrir o cursor. */
    void clearRowCount() {
        rowsFetched(0);
    }

    void declareCursor(Cursor cursor) {
        if (cursors.putIfAbsent(cursor.name(), cursor) != null) {
            throw new PsqlExecutionException("Cursor declarado duas vezes: " + cursor.name());
        }
    }

    /**
     * Cursor de um {@code FOR SELECT}/{@code FOR EXECUTE STATEMENT} durante o laço. Se tiver nome
     * ({@code AS CURSOR}), os campos dele ficam visíveis.
     */
    void enterLoop(Cursor cursor) {
        loops.push(cursor);
        if (cursor.name() != null) {
            declareCursor(cursor);
        }
    }

    void leaveLoop(Cursor cursor) {
        loops.remove(cursor);
        if (cursor.name() != null) {
            cursors.remove(cursor.name());
        }
    }

    /** Valor novo para a variável, que nenhum cursor aberto pode estar usando (ver {@link Cursor#checkUnchanged}). */
    void assign(Variable variable, Object value) {
        for (Cursor cursor : cursors.values()) {
            cursor.checkUnchanged(variable);
        }
        for (Cursor cursor : loops) {
            cursor.checkUnchanged(variable);
        }
        variable.assign(value);
    }

    /** @param name nome normalizado */
    Optional<Cursor> findCursor(String name) {
        return Optional.ofNullable(cursors.get(name));
    }

    Cursor cursor(String name) {
        return findCursor(name).orElseThrow(() -> new PsqlExecutionException("Cursor não declarado: " + name));
    }

    /** Fecha os cursores que a Rotina deixou abertos, como o motor faz ao fim da execução. */
    void closeCursors() throws SQLException {
        SQLException failure = null;
        for (Cursor cursor : cursors.values()) {
            try {
                cursor.close();
            } catch (SQLException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Erro do handler em execução, que dá {@code GDSCODE}/{@code SQLCODE}/{@code SQLSTATE}. */
    ErrorStatus handledError() {
        return handledError;
    }

    void handledError(ErrorStatus error) {
        handledError = error;
    }
}
