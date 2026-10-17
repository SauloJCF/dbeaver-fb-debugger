package dev.fbdebug.dbeaver.interp;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import dev.fbdebug.dbeaver.psql.Identifiers;
import dev.fbdebug.dbeaver.psql.ast.Expression;
import dev.fbdebug.dbeaver.psql.ast.ParameterDeclaration;
import dev.fbdebug.dbeaver.psql.ast.RoutineDefinition;
import dev.fbdebug.dbeaver.psql.ast.SourcePosition;

/**
 * Estado de uma Rotina em execução: a Rotina e o statement em execução, variáveis visíveis (parâmetros de entrada,
 * de saída e locais, na ordem declarada), cursores, o {@code ROW_COUNT}, o erro do handler em execução e os
 * savepoints dos blocos.
 */
public final class Frame {

    private final RoutineDefinition routine;
    private final boolean steppedInto;
    /** Rotinas da package da Rotina, que uma chamada sem package dentro dela alcança. */
    private final Set<String> packageRoutines;
    private SourcePosition position;

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
    private final Consumer<List<Object>> rows;
    private Object returned;

    /**
     * @param rows destino das linhas do {@code SUSPEND}, ou {@code null} se a Rotina roda como executável
     * @param steppedInto ver {@link #steppedInto()}
     * @param packageRoutines nomes das rotinas da package da Rotina; vazio fora de package
     */
    Frame(RoutineDefinition routine, boolean steppedInto, SavepointStack savepoints, Consumer<List<Object>> rows,
            Set<String> packageRoutines) {
        this.routine = routine;
        this.steppedInto = steppedInto;
        this.savepoints = savepoints;
        this.rows = rows;
        this.packageRoutines = Set.copyOf(packageRoutines);
        this.position = routine.body().position();
    }

    public RoutineDefinition routine() {
        return routine;
    }

    /**
     * A Rotina é a raiz ou foi interpretada por step into. Falso numa Rotina que o Interpretador interpreta por
     * conta própria num step over, porque no servidor a chamada não seria fiel ao motor ({@code FOR SELECT} sobre
     * procedure) ou não acharia a Rotina (privada de package); ver {@link CallPolicy}. A camada de debug não para
     * dentro dela.
     */
    public boolean steppedInto() {
        return steppedInto;
    }

    /** Statement em execução (ou, numa Rotina que chama outra, o da chamada); antes do primeiro, o bloco principal. */
    public SourcePosition position() {
        return position;
    }

    void position(SourcePosition statement) {
        position = statement;
    }

    /**
     * Nome da Rotina chamada para o servidor. Dentro do corpo de uma package, uma rotina dela chamada sem package
     * ganha o nome da package, como o motor a resolve; fora dela, o nome sem package seria outra Rotina.
     */
    String serverName(String packageName, String name) {
        String pkg = packageName != null ? packageName : packageRoutines.contains(name) ? routine.packageName() : null;
        return (pkg == null ? "" : Identifiers.quote(pkg) + ".") + Identifiers.quote(name);
    }

    /** Texto de um candidato a chamada numa expressão enviada ao servidor (ver {@link #serverName}). */
    String serverText(Expression.Call call) {
        if (call.packageName() == null && call.arguments() && packageRoutines.contains(call.name())) {
            return Identifiers.quote(routine.packageName()) + "." + call.sql();
        }
        return call.sql();
    }

    /** Tipo de retorno da function; {@code null} nos outros tipos de Rotina. */
    ParameterDeclaration returnValue() {
        return routine.returnValue();
    }

    /** Valor do {@code RETURN} executado; {@code null} antes dele. */
    Object returned() {
        return returned;
    }

    void returned(Object value) {
        returned = value;
    }

    /** Savepoints dos blocos em execução. */
    SavepointStack savepoints() {
        return savepoints;
    }

    /** Destino das linhas do {@code SUSPEND} numa procedure selecionável; {@code null} num {@code EXECUTE PROCEDURE}. */
    Consumer<List<Object>> rows() {
        return rows;
    }

    /**
     * Valores atuais dos parâmetros de saída, na ordem do {@code RETURNS}. Como no motor, {@code NULL} numa saída
     * {@code NOT NULL} é erro, citando a primeira.
     */
    List<Object> outputValues() throws SQLException {
        List<Object> values = new ArrayList<>();
        for (Variable variable : variables.values()) {
            if (variable.kind() == Variable.Kind.OUTPUT) {
                if (variable.value() == null && variable.notNull()) {
                    throw ServerErrors.nullInNotNullVariable(variable.name());
                }
                values.add(variable.value());
            }
        }
        return values;
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
