package dev.fbdebug.dbeaver.interp;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import dev.fbdebug.dbeaver.psql.ast.Assignment;
import dev.fbdebug.dbeaver.psql.ast.Block;
import dev.fbdebug.dbeaver.psql.ast.Continue;
import dev.fbdebug.dbeaver.psql.ast.Exit;
import dev.fbdebug.dbeaver.psql.ast.Expression;
import dev.fbdebug.dbeaver.psql.ast.If;
import dev.fbdebug.dbeaver.psql.ast.Leave;
import dev.fbdebug.dbeaver.psql.ast.ParameterDeclaration;
import dev.fbdebug.dbeaver.psql.ast.ProcedureDefinition;
import dev.fbdebug.dbeaver.psql.ast.Statement;
import dev.fbdebug.dbeaver.psql.ast.VariableDeclaration;
import dev.fbdebug.dbeaver.psql.ast.While;

/**
 * Percorre o AST da Rotina e executa a lógica procedural no cliente (ADR 0001). O servidor só recebe Avaliações
 * de expressão. Não controla a transação: isso é da {@link DebugSession}.
 */
public final class Interpreter {

    private final ExpressionEvaluator evaluator;

    public Interpreter(Connection connection) {
        this.evaluator = new ExpressionEvaluator(connection);
    }

    /**
     * @param inputs valores dos parâmetros de entrada, na ordem declarada; os que faltarem no fim usam o valor
     *            padrão do parâmetro
     */
    public ProcedureResult execute(ProcedureDefinition procedure, List<?> inputs) throws SQLException {
        if (inputs.size() > procedure.inputs().size()) {
            throw new PsqlExecutionException(procedure.name() + " recebe " + procedure.inputs().size()
                    + " parâmetro(s) de entrada, mas foram informados " + inputs.size());
        }
        Frame frame = new Frame();
        for (int i = 0; i < procedure.inputs().size(); i++) {
            bindInput(frame, procedure.inputs().get(i), inputs, i);
        }
        for (ParameterDeclaration output : procedure.outputs()) {
            frame.declare(output.name(), output.type(), output.notNull(), Variable.Kind.OUTPUT);
        }
        for (VariableDeclaration local : procedure.declarations()) {
            Variable variable = frame.declare(local.name(), local.type(), local.notNull(), Variable.Kind.LOCAL);
            if (local.initialValue() != null) {
                assign(variable, local.initialValue(), frame);
            }
        }

        execute(procedure.body(), frame); // o único desvio que chega aqui é o EXIT

        List<String> names = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (ParameterDeclaration output : procedure.outputs()) {
            names.add(output.name());
            values.add(frame.get(output.name()).value());
        }
        return new ProcedureResult(names, values);
    }

    private void bindInput(Frame frame, ParameterDeclaration input, List<?> inputs, int index)
            throws SQLException {
        Variable variable = frame.declare(input.name(), input.type(), input.notNull(), Variable.Kind.INPUT);
        if (index < inputs.size()) {
            store(variable, evaluator.convert(inputs.get(index), variable.type()));
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
                for (Statement inner : block.statements()) {
                    Jump jump = execute(inner, frame);
                    if (jump != null) {
                        return jump;
                    }
                }
                return null;
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
            case Leave leave -> {
                return new Jump(Jump.Kind.LEAVE, leave.label());
            }
            case Continue next -> {
                return new Jump(Jump.Kind.CONTINUE, next.label());
            }
            case Exit exit -> {
                return Jump.EXIT;
            }
        }
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

    private void assign(Variable target, Expression value, Frame frame) throws SQLException {
        store(target, evaluator.evaluate(value, frame, target.type()));
    }

    private static void store(Variable target, Object value) {
        if (value == null && target.notNull()) {
            // Mesma mensagem do motor (isc_not_valid_for_var); o mapeamento de erros é da issue 07.
            throw new PsqlExecutionException(
                    "validation error for variable " + target.name() + ", value \"*** null ***\"");
        }
        target.assign(value);
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
