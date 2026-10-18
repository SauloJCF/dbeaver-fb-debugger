package dev.fbdebug.dbeaver.psql;

import java.util.NavigableSet;
import java.util.TreeSet;

import dev.fbdebug.dbeaver.psql.ast.Block;
import dev.fbdebug.dbeaver.psql.ast.ForExecuteStatement;
import dev.fbdebug.dbeaver.psql.ast.ForSelect;
import dev.fbdebug.dbeaver.psql.ast.Handler;
import dev.fbdebug.dbeaver.psql.ast.If;
import dev.fbdebug.dbeaver.psql.ast.RoutineDefinition;
import dev.fbdebug.dbeaver.psql.ast.Statement;
import dev.fbdebug.dbeaver.psql.ast.Subroutine;
import dev.fbdebug.dbeaver.psql.ast.While;

/**
 * Linhas em que o debugger pode parar: as que têm o início de um statement que não é bloco ({@code BEGIN}), no corpo
 * da Rotina e no das sub-rotinas dela. Um breakpoint numa linha sem statement vai para a próxima destas.
 */
public final class StatementLines {

    private StatementLines() {
    }

    /** @return as linhas, a partir de 1, do texto de que vêm as posições do AST */
    public static NavigableSet<Integer> of(RoutineDefinition routine) {
        NavigableSet<Integer> lines = new TreeSet<>();
        add(routine.body(), lines);
        for (Subroutine subroutine : routine.subroutines()) {
            add(subroutine.routine().body(), lines);
        }
        return lines;
    }

    private static void add(Statement statement, NavigableSet<Integer> lines) {
        switch (statement) {
            case Block block -> {
                block.statements().forEach(inner -> add(inner, lines));
                block.handlers().stream().map(Handler::action).forEach(action -> add(action, lines));
                return; // o BEGIN não é onde o debugger para
            }
            case If branch -> {
                add(branch.thenBranch(), lines);
                if (branch.elseBranch() != null) {
                    add(branch.elseBranch(), lines);
                }
            }
            case While loop -> add(loop.body(), lines);
            case ForSelect loop -> add(loop.body(), lines);
            case ForExecuteStatement loop -> add(loop.body(), lines);
            default -> {
            }
        }
        lines.add(statement.position().line());
    }
}
