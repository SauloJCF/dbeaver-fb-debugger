package dev.fbdebug.dbeaver.interp;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import dev.fbdebug.dbeaver.psql.ast.Assignment;
import dev.fbdebug.dbeaver.psql.ast.Block;
import dev.fbdebug.dbeaver.psql.ast.CloseCursor;
import dev.fbdebug.dbeaver.psql.ast.Continue;
import dev.fbdebug.dbeaver.psql.ast.CursorDeclaration;
import dev.fbdebug.dbeaver.psql.ast.DmlStatement;
import dev.fbdebug.dbeaver.psql.ast.ExecuteStatement;
import dev.fbdebug.dbeaver.psql.ast.Exit;
import dev.fbdebug.dbeaver.psql.ast.Expression;
import dev.fbdebug.dbeaver.psql.ast.Fetch;
import dev.fbdebug.dbeaver.psql.ast.ForExecuteStatement;
import dev.fbdebug.dbeaver.psql.ast.ForSelect;
import dev.fbdebug.dbeaver.psql.ast.Handler;
import dev.fbdebug.dbeaver.psql.ast.If;
import dev.fbdebug.dbeaver.psql.ast.InAutonomousTransaction;
import dev.fbdebug.dbeaver.psql.ast.Leave;
import dev.fbdebug.dbeaver.psql.ast.OpenCursor;
import dev.fbdebug.dbeaver.psql.ast.ParameterDeclaration;
import dev.fbdebug.dbeaver.psql.ast.Raise;
import dev.fbdebug.dbeaver.psql.ast.Return;
import dev.fbdebug.dbeaver.psql.ast.RoutineDefinition;
import dev.fbdebug.dbeaver.psql.ast.Statement;
import dev.fbdebug.dbeaver.psql.ast.Suspend;
import dev.fbdebug.dbeaver.psql.ast.UnsupportedConstruct;
import dev.fbdebug.dbeaver.psql.ast.UnsupportedUse;
import dev.fbdebug.dbeaver.psql.ast.VariableDeclaration;
import dev.fbdebug.dbeaver.psql.ast.While;

/**
 * Percorre o AST da Rotina e executa a lógica procedural no cliente (ADR 0001). O servidor recebe só Fatias DML e
 * Avaliações de expressão. Não controla a transação: isso é da {@link DebugSession}.
 */
public final class Interpreter {

    private final Connection connection;
    private final ExpressionEvaluator evaluator;
    private final DmlExecutor dml;
    private final ExceptionRaiser exceptions;

    public Interpreter(Connection connection) {
        this.connection = connection;
        this.evaluator = new ExpressionEvaluator(connection);
        this.dml = new DmlExecutor(connection, evaluator);
        this.exceptions = new ExceptionRaiser(connection, evaluator);
    }

    /**
     * Executa a Rotina como executável: uma procedure como no {@code EXECUTE PROCEDURE} nativo, uma function como
     * no {@code SELECT f(...) FROM RDB$DATABASE}, um {@code EXECUTE BLOCK} sem {@code SUSPEND} como executado
     * direto. Se ela falhar, o que ela alterou é desfeito (savepoint). Um {@code SUSPEND} devolve os valores das
     * saídas e termina a Rotina, como no motor.
     *
     * @param inputs valores dos parâmetros de entrada, na ordem declarada; os que faltarem no fim usam o valor
     *            padrão do parâmetro
     * @return as saídas; numa function, uma só, com o nome da function e o valor do {@code RETURN}
     */
    public ProcedureResult execute(RoutineDefinition routine, List<?> inputs) throws SQLException {
        return call(routine, inputs, null);
    }

    /**
     * Executa a procedure selecionável como um {@code SELECT * FROM sp(...)} que lê todas as linhas: cada
     * {@code SUSPEND} entrega a {@code rows}, na hora, os valores das saídas na ordem do {@code RETURNS}. O fim da
     * Rotina não emite linha. Se ela falhar, as linhas já entregues ficam e só o que ela alterou depois do último
     * {@code SUSPEND} é desfeito, como no motor, que desfaz apenas a busca em andamento.
     *
     * @param inputs como em {@link #execute}
     */
    public void select(RoutineDefinition routine, List<?> inputs, Consumer<List<Object>> rows)
            throws SQLException {
        call(routine, inputs, Objects.requireNonNull(rows));
    }

    private ProcedureResult call(RoutineDefinition routine, List<?> inputs, Consumer<List<Object>> rows)
            throws SQLException {
        SavepointStack savepoints = new SavepointStack(connection);
        savepoints.beginCall();
        ProcedureResult result;
        try {
            result = run(routine, inputs, new Frame(savepoints, rows, routine.returnValue()));
        } catch (SQLException | RuntimeException e) {
            savepoints.undoCall(e);
            throw e;
        }
        savepoints.endCall();
        return result;
    }

    /** @return as saídas, ou {@code null} se a Rotina roda como selecionável */
    private ProcedureResult run(RoutineDefinition routine, List<?> inputs, Frame frame) throws SQLException {
        refuse(routine.unsupported());
        if (inputs.size() > routine.inputs().size()) {
            throw new PsqlExecutionException(routine.displayName() + " recebe " + routine.inputs().size()
                    + " parâmetro(s) de entrada, mas foram informados " + inputs.size());
        }
        for (int i = 0; i < routine.inputs().size(); i++) {
            bindInput(frame, routine.inputs().get(i), inputs, i);
        }
        for (ParameterDeclaration output : routine.outputs()) {
            frame.declare(output.name(), output.type(), output.notNull(), Variable.Kind.OUTPUT);
        }
        for (VariableDeclaration local : routine.declarations()) {
            Variable variable = frame.declare(local.name(), local.type(), local.notNull(), Variable.Kind.LOCAL);
            if (local.initialValue() != null) {
                assign(variable, local.initialValue(), frame);
            }
        }
        for (CursorDeclaration cursor : routine.cursors()) {
            frame.declareCursor(new Cursor(cursor.name(), cursor.scroll(), cursor.select(), cursor.position()));
        }

        ProcedureResult result = null;
        try {
            execute(routine.body(), frame); // o único desvio que chega aqui é o EXIT (ou o RETURN)
            // Na executável, o fim devolve as saídas e valida o NOT NULL delas, fora do alcance de qualquer WHEN.
            // Na selecionável, o fim não emite linha nem valida nada.
            if (routine.returnValue() != null) {
                result = new ProcedureResult(List.of(routine.name()), Collections.singletonList(returned(frame)));
            } else if (frame.rows() == null) {
                result = new ProcedureResult(routine.outputs().stream().map(ParameterDeclaration::name).toList(),
                        frame.outputValues());
            }
        } catch (SQLException | RuntimeException e) {
            try {
                frame.closeCursors();
            } catch (SQLException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        frame.closeCursors(); // o motor fecha os cursores que a Rotina deixou abertos
        return result;
    }

    private void bindInput(Frame frame, ParameterDeclaration input, List<?> inputs, int index)
            throws SQLException {
        Variable variable = frame.declare(input.name(), input.type(), input.notNull(), Variable.Kind.INPUT);
        if (index < inputs.size()) {
            store(variable, evaluator.convert(inputs.get(index), variable.type()), frame);
        } else if (input.defaultValue() != null) {
            assign(variable, input.defaultValue(), frame);
        } else {
            throw new PsqlExecutionException("Parâmetro de entrada sem valor e sem padrão: " + input.name());
        }
    }

    /** @return o desvio que interrompeu o statement, ou {@code null} se ele terminou normalmente */
    private Jump execute(Statement statement, Frame frame) throws SQLException {
        switch (statement) {
            case Block block -> {
                return executeBlock(block, frame);
            }
            case Assignment assignment -> {
                assign(frame.get(assignment.target()), assignment.value(), frame);
                return null;
            }
            case If branch -> {
                if (evaluator.isTrue(branch.condition(), frame)) {
                    return execute(branch.thenBranch(), frame);
                }
                return branch.elseBranch() == null ? null : execute(branch.elseBranch(), frame);
            }
            case While loop -> {
                return executeLoop(loop, frame);
            }
            case ForSelect loop -> {
                return executeLoop(loop, frame);
            }
            case ForExecuteStatement loop -> {
                return executeLoop(loop, frame);
            }
            case Leave leave -> {
                return new Jump(Jump.Kind.LEAVE, leave.label());
            }
            case Continue next -> {
                return new Jump(Jump.Kind.CONTINUE, next.label());
            }
            case Exit exit -> {
                return Jump.EXIT;
            }
            case DmlStatement slice -> {
                refuse(slice.unsupported());
                assignInto(slice.into(), dml.execute(slice, frame), frame);
                return null;
            }
            case ExecuteStatement dynamic -> {
                refuse(dynamic.unsupported());
                assignInto(dynamic.into(), dml.execute(dynamic, frame), frame);
                return null;
            }
            case OpenCursor open -> {
                Cursor cursor = frame.cursor(open.cursor());
                refuse(cursor.select().unsupported());
                dml.open(cursor, cursor.select(), frame);
                return null;
            }
            case Fetch fetch -> {
                fetch(fetch, frame);
                return null;
            }
            case CloseCursor close -> {
                frame.cursor(close.cursor()).close();
                return null;
            }
            case Raise raise -> {
                raise(raise, frame);
                return null;
            }
            case Suspend suspend -> {
                return suspend(frame);
            }
            case Return value -> {
                return returnValue(value, frame);
            }
            case InAutonomousTransaction autonomous -> {
                refuse(List.of(new UnsupportedUse(UnsupportedConstruct.IN_AUTONOMOUS_TRANSACTION,
                        autonomous.position())));
                return null;
            }
        }
    }

    private Jump execute(List<Statement> statements, Frame frame) throws SQLException {
        for (Statement inner : statements) {
            Jump jump = execute(inner, frame);
            if (jump != null) {
                return jump;
            }
        }
        return null;
    }

    /**
     * Bloco: um nível de savepoint ({@link SavepointStack}). Um erro sai do bloco deixando o nível no lugar, a
     * menos que um {@code WHEN} do bloco o trate.
     */
    private Jump executeBlock(Block block, Frame frame) throws SQLException {
        SavepointStack.Level level = frame.savepoints().push(!block.handlers().isEmpty());
        Jump jump;
        try {
            jump = execute(block.statements(), frame);
        } catch (SQLException e) {
            if (block.handlers().isEmpty()) {
                throw e; // quem desfaz é o bloco que tratar o erro
            }
            return handle(block, level, e, frame);
        }
        frame.savepoints().release(level);
        return jump;
    }

    /**
     * Erro que chegou a um bloco com {@code WHEN}. Como no motor, primeiro é desfeito o que está acima do bloco (o
     * statement em andamento), não o que os statements já terminados do bloco fizeram. Depois o handler é escolhido:
     * o motor testa todos, na ordem, e o que casa limpa o erro. Por isso um {@code WHEN} específico depois de outro
     * que casou nunca casa, mas um {@code WHEN ANY} depois dele casa e é o que roda, já sem erro ({@code GDSCODE} 0,
     * {@code EXCEPTION;} sem efeito). Sem handler que case, o erro continua subindo. {@code GDSCODE},
     * {@code SQLCODE} e {@code SQLSTATE} valem o erro só até o fim do handler, mesmo dentro de outro handler.
     */
    private Jump handle(Block block, SavepointStack.Level level, SQLException error, Frame frame)
            throws SQLException {
        SavepointStack savepoints = frame.savepoints();
        savepoints.rollbackAbove(level);
        ErrorStatus status = ErrorStatus.of(error);
        Handler chosen = null;
        ErrorStatus seen = null;
        for (Handler handler : block.handlers()) {
            ErrorStatus current = status;
            if (handler.conditions().stream().anyMatch(current::matches)) {
                chosen = handler;
                seen = current;
                status = ErrorStatus.NONE;
            }
        }
        if (chosen == null) {
            throw error;
        }
        frame.handledError(seen);
        savepoints.enterHandler(level);
        Jump jump;
        try {
            jump = execute(chosen.action(), frame);
        } finally {
            savepoints.leaveHandler(level);
            frame.handledError(ErrorStatus.NONE);
        }
        savepoints.release(level);
        savepoints.nestedErrorHandled();
        return jump;
    }

    private Jump executeLoop(While loop, Frame frame) throws SQLException {
        while (evaluator.isTrue(loop.condition(), frame)) {
            Jump jump = execute(loop.body(), frame);
            if (jump == null) {
                continue;
            }
            if (!jump.targets(loop.label())) {
                return jump; // EXIT, ou LEAVE/CONTINUE com o label de um laço de fora
            }
            if (jump.kind() == Jump.Kind.LEAVE) {
                break;
            }
        }
        return null;
    }

    /**
     * {@code FOR SELECT}: o cursor é aberto no servidor e lido linha a linha. Sair do laço, por fim, desvio ou erro,
     * fecha o cursor. Como no motor, o laço tem nível de savepoint próprio só se o nível atual já tinha alterações.
     */
    private Jump executeLoop(ForSelect loop, Frame frame) throws SQLException {
        DmlStatement select = loop.select();
        refuse(select.unsupported());
        SavepointStack.Level level = frame.savepoints().pushIfChanged();
        Jump result = null;
        try (Cursor cursor = new Cursor(loop.cursor(), false, null, loop.position())) {
            dml.open(cursor, select, frame);
            frame.clearRowCount();
            frame.enterLoop(cursor);
            try {
                for (List<Object> row = next(cursor); row != null; row = next(cursor)) {
                    frame.rowFetched();
                    if (!select.into().isEmpty()) {
                        assignInto(select.into(), dml.assignable(row, select.into(), frame), frame);
                    }
                    Jump jump = execute(loop.body(), frame);
                    if (jump != null && !jump.targets(loop.label())) {
                        result = jump;
                        break;
                    }
                    if (jump != null && jump.kind() == Jump.Kind.LEAVE) {
                        break;
                    }
                }
            } finally {
                frame.leaveLoop(cursor);
            }
        }
        frame.savepoints().release(level);
        return result;
    }

    /** {@code FOR EXECUTE STATEMENT}: como o {@code FOR SELECT}, mas não muda o {@code ROW_COUNT}. */
    private Jump executeLoop(ForExecuteStatement loop, Frame frame) throws SQLException {
        ExecuteStatement statement = loop.statement();
        refuse(statement.unsupported());
        try (Cursor cursor = new Cursor(null, false, null, loop.position())) {
            dml.open(cursor, statement, frame);
            frame.enterLoop(cursor);
            try {
                for (List<Object> row = next(cursor); row != null; row = next(cursor)) {
                    assignInto(statement.into(), dml.assignable(row, statement.into(), frame), frame);
                    Jump jump = execute(loop.body(), frame);
                    if (jump != null && !jump.targets(loop.label())) {
                        return jump;
                    }
                    if (jump != null && jump.kind() == Jump.Kind.LEAVE) {
                        break;
                    }
                }
            } finally {
                frame.leaveLoop(cursor);
            }
        }
        return null;
    }

    private static List<Object> next(Cursor cursor) throws SQLException {
        return cursor.fetch(Fetch.Orientation.NEXT, null);
    }

    /**
     * {@code FETCH}: o {@code ROW_COUNT} passa a ser 1 se veio linha, senão 0. {@code FETCH RELATIVE 0} relê a linha
     * atual e a atribui, mas o motor não a conta.
     */
    private void fetch(Fetch fetch, Frame frame) throws SQLException {
        Cursor cursor = frame.cursor(fetch.cursor());
        Long offset = null;
        if (fetch.offset() != null) {
            Object value = evaluator.evaluate(fetch.offset(), frame, "BIGINT");
            offset = value == null ? null : ((Number) value).longValue();
        }
        List<Object> row = cursor.fetch(fetch.orientation(), offset);
        boolean reread = fetch.orientation() == Fetch.Orientation.RELATIVE && offset != null && offset == 0;
        frame.rowsFetched(row != null && !reread ? 1 : 0);
        if (row != null && !fetch.into().isEmpty()) {
            assignInto(fetch.into(), dml.assignable(row, fetch.into(), frame), frame);
        }
    }

    /**
     * {@code EXCEPTION nome ...} levanta a exceção pelo servidor; {@code EXCEPTION;} relança o erro do handler, e
     * não faz nada se não houver erro (fora de handler, ou num {@code WHEN ANY} que veio depois de outro handler
     * que casou), como no motor.
     */
    private void raise(Raise raise, Frame frame) throws SQLException {
        if (raise.exception() != null) {
            throw exceptions.raise(raise, frame);
        }
        SQLException handled = frame.handledError().error();
        if (handled != null) {
            throw handled;
        }
    }

    /**
     * {@code SUSPEND}: {@code NULL} numa saída {@code NOT NULL} é erro, que um {@code WHEN} pode tratar. Na
     * selecionável, a linha é emitida e a Rotina continua; o que ela alterou até aqui não é mais desfeito
     * ({@link SavepointStack#suspended()}). Num {@code EXECUTE PROCEDURE}, termina a Rotina como um {@code EXIT}.
     */
    private static Jump suspend(Frame frame) throws SQLException {
        List<Object> row = frame.outputValues();
        if (frame.rows() == null) {
            return Jump.EXIT;
        }
        frame.rows().accept(Collections.unmodifiableList(row));
        frame.savepoints().suspended();
        return null;
    }

    /**
     * {@code RETURN}: o valor é convertido para o tipo de retorno, como numa atribuição, e termina a function.
     * {@code NULL} num retorno {@code NOT NULL} é erro ali mesmo, que um {@code WHEN} pode tratar.
     */
    private Jump returnValue(Return statement, Frame frame) throws SQLException {
        ParameterDeclaration declared = frame.returnValue();
        if (declared == null) {
            throw new PsqlExecutionException(statement.position() + ": RETURN fora de function");
        }
        Object value = evaluator.evaluate(statement.value(), frame, declared.type());
        if (value == null && declared.notNull()) {
            throw ServerErrors.nullReturned();
        }
        frame.returned(value);
        return Jump.EXIT;
    }

    /**
     * Valor que a function devolve: o do {@code RETURN}, ou {@code NULL} se ela terminou sem {@code RETURN}. Nesse
     * caso, um retorno {@code NOT NULL} é erro, fora do alcance de qualquer {@code WHEN}.
     */
    private static Object returned(Frame frame) throws SQLException {
        Object value = frame.returned();
        if (value == null && frame.returnValue().notNull()) {
            throw ServerErrors.nullReturned();
        }
        return value;
    }

    /** @param row linha já convertida para os tipos dos alvos, ou {@code null} se não veio linha */
    private static void assignInto(List<String> targets, List<Object> row, Frame frame) throws SQLException {
        if (row == null) {
            return; // sem linha, o motor deixa os alvos como estavam
        }
        for (int i = 0; i < targets.size(); i++) {
            store(frame.get(targets.get(i)), row.get(i), frame);
        }
    }

    /** O Pré-check recusa estas construções antes do launch; aqui é a garantia de nunca executá-las. */
    private static void refuse(List<UnsupportedUse> unsupported) {
        if (!unsupported.isEmpty()) {
            UnsupportedUse use = unsupported.get(0);
            throw new PsqlExecutionException(use.position() + ": " + use.construct().syntax()
                    + " não é suportado pelo debugger");
        }
    }

    private void assign(Variable target, Expression value, Frame frame) throws SQLException {
        store(target, evaluator.evaluate(value, frame, target.type()), frame);
    }

    private static void store(Variable target, Object value, Frame frame) throws SQLException {
        if (value == null && target.notNull()) {
            throw ServerErrors.nullInNotNullVariable(target.name());
        }
        frame.assign(target, value);
    }

    /**
     * Desvio de fluxo que sobe pelos statements até quem o trata: o laço alvo, para {@code LEAVE} e
     * {@code CONTINUE}, ou a Rotina, para {@code EXIT}. O Firebird só compila {@code LEAVE}/{@code CONTINUE}
     * dentro de laço e com label existente, então todo desvio tem quem o trate.
     *
     * @param label label do laço alvo, ou {@code null} para o laço mais interno
     */
    private record Jump(Kind kind, String label) {

        enum Kind {
            LEAVE, CONTINUE, EXIT
        }

        static final Jump EXIT = new Jump(Kind.EXIT, null);

        boolean targets(String loopLabel) {
            return kind != Kind.EXIT && (label == null || label.equals(loopLabel));
        }
    }
}
