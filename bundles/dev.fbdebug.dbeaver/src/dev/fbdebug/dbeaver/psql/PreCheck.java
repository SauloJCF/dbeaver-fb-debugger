package dev.fbdebug.dbeaver.psql;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

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
import dev.fbdebug.dbeaver.psql.ast.ProcedureDefinition;
import dev.fbdebug.dbeaver.psql.ast.Raise;
import dev.fbdebug.dbeaver.psql.ast.SourcePosition;
import dev.fbdebug.dbeaver.psql.ast.Statement;
import dev.fbdebug.dbeaver.psql.ast.UnsupportedConstruct;
import dev.fbdebug.dbeaver.psql.ast.UnsupportedUse;
import dev.fbdebug.dbeaver.psql.ast.While;

/**
 * Pré-check: varredura da Rotina antes do launch, que lista as construções não suportadas com linha e coluna. O
 * launch é recusado se a lista não estiver vazia. As construções recusadas estão em {@link UnsupportedConstruct}.
 */
public final class PreCheck {

    private PreCheck() {
    }

    /**
     * Construção recusada.
     *
     * @param description o que foi recusado, para mostrar a quem fez o launch
     */
    public record Finding(SourcePosition position, String description) {

        @Override
        public String toString() {
            return position + ": " + description;
        }
    }

    /**
     * @param text {@code CREATE PROCEDURE ...} completo
     * @return construções recusadas em ordem de posição; texto que não parseia dá um único item, na posição do
     *         erro
     */
    public static List<Finding> check(String text) {
        ProcedureDefinition procedure;
        try {
            procedure = RoutineParser.parseProcedure(text);
        } catch (PsqlSyntaxException e) {
            return List.of(new Finding(e.position(), "não suportado pelo debugger, " + e.detail()));
        }
        return scan(procedure);
    }

    /** @return construções recusadas do AST, em ordem de posição */
    public static List<Finding> scan(ProcedureDefinition procedure) {
        List<Finding> findings = new ArrayList<>();
        add(procedure.unsupported(), findings);
        for (CursorDeclaration cursor : procedure.cursors()) {
            add(cursor.select().unsupported(), findings);
        }
        scan(procedure.body(), findings);
        findings.sort(Comparator.comparing(Finding::position,
                Comparator.comparingInt(SourcePosition::line).thenComparingInt(SourcePosition::column)));
        return findings;
    }

    private static void scan(Statement statement, List<Finding> findings) {
        switch (statement) {
            case Block block -> {
                block.statements().forEach(inner -> scan(inner, findings));
                block.handlers().stream().map(Handler::action).forEach(action -> scan(action, findings));
            }
            case If branch -> {
                scan(branch.thenBranch(), findings);
                if (branch.elseBranch() != null) {
                    scan(branch.elseBranch(), findings);
                }
            }
            case While loop -> scan(loop.body(), findings);
            case ForSelect loop -> {
                add(loop.select().unsupported(), findings);
                add(cursorVariablesChanged(loop), findings);
                scan(loop.body(), findings);
            }
            case ForExecuteStatement loop -> {
                add(loop.statement().unsupported(), findings);
                scan(loop.body(), findings);
            }
            case DmlStatement dml -> add(dml.unsupported(), findings);
            case ExecuteStatement execute -> add(execute.unsupported(), findings);
            case InAutonomousTransaction autonomous -> {
                add(List.of(new UnsupportedUse(UnsupportedConstruct.IN_AUTONOMOUS_TRANSACTION,
                        autonomous.position())), findings);
                scan(autonomous.body(), findings);
            }
            case Assignment assignment -> {
            }
            case Leave leave -> {
            }
            case Continue next -> {
            }
            case Exit exit -> {
            }
            case OpenCursor open -> {
            }
            case Fetch fetch -> {
            }
            case CloseCursor close -> {
            }
            case Raise raise -> {
            }
        }
    }

    /**
     * Atribuições, no corpo do {@code FOR SELECT} ou no {@code INTO} dele, a variáveis que o {@code SELECT} usa
     * ({@link UnsupportedConstruct#CURSOR_VARIABLE_CHANGED}), na posição de cada atribuição.
     */
    private static List<UnsupportedUse> cursorVariablesChanged(ForSelect loop) {
        Set<String> used = new HashSet<>();
        for (Expression.Part part : loop.select().sql().parts()) {
            if (part instanceof Expression.Name name) {
                used.add(name.name());
            }
        }
        List<UnsupportedUse> uses = new ArrayList<>();
        if (loop.select().into().stream().anyMatch(used::contains)) {
            uses.add(new UnsupportedUse(UnsupportedConstruct.CURSOR_VARIABLE_CHANGED, loop.select().position()));
        }
        assignments(loop.body(), (target, position) -> {
            if (used.contains(target)) {
                uses.add(new UnsupportedUse(UnsupportedConstruct.CURSOR_VARIABLE_CHANGED, position));
            }
        });
        return uses;
    }

    /** Cada variável que o statement (ou algum dentro dele) pode alterar, com a posição do statement. */
    private static void assignments(Statement statement, BiConsumer<String, SourcePosition> sink) {
        switch (statement) {
            case Block block -> {
                block.statements().forEach(inner -> assignments(inner, sink));
                block.handlers().forEach(handler -> assignments(handler.action(), sink));
            }
            case Assignment assignment -> sink.accept(assignment.target(), assignment.position());
            case If branch -> {
                assignments(branch.thenBranch(), sink);
                if (branch.elseBranch() != null) {
                    assignments(branch.elseBranch(), sink);
                }
            }
            case While loop -> assignments(loop.body(), sink);
            case ForSelect loop -> {
                loop.select().into().forEach(target -> sink.accept(target, loop.position()));
                assignments(loop.body(), sink);
            }
            case ForExecuteStatement loop -> {
                loop.statement().into().forEach(target -> sink.accept(target, loop.position()));
                assignments(loop.body(), sink);
            }
            case DmlStatement dml -> dml.into().forEach(target -> sink.accept(target, dml.position()));
            case ExecuteStatement execute -> execute.into().forEach(target -> sink.accept(target, execute.position()));
            case Fetch fetch -> fetch.into().forEach(target -> sink.accept(target, fetch.position()));
            case InAutonomousTransaction autonomous -> assignments(autonomous.body(), sink);
            case Leave leave -> {
            }
            case Continue next -> {
            }
            case Exit exit -> {
            }
            case OpenCursor open -> {
            }
            case CloseCursor close -> {
            }
            case Raise raise -> {
            }
        }
    }

    private static void add(List<UnsupportedUse> uses, List<Finding> findings) {
        for (UnsupportedUse use : uses) {
            findings.add(new Finding(use.position(), use.construct().syntax() + " não é suportado pelo debugger"));
        }
    }
}
