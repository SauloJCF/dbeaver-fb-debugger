package dev.fbdebug.dbeaver.psql;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.fbdebug.dbeaver.psql.ast.Assignment;
import dev.fbdebug.dbeaver.psql.ast.Block;
import dev.fbdebug.dbeaver.psql.ast.Continue;
import dev.fbdebug.dbeaver.psql.ast.DmlStatement;
import dev.fbdebug.dbeaver.psql.ast.ExecuteStatement;
import dev.fbdebug.dbeaver.psql.ast.Exit;
import dev.fbdebug.dbeaver.psql.ast.If;
import dev.fbdebug.dbeaver.psql.ast.InAutonomousTransaction;
import dev.fbdebug.dbeaver.psql.ast.Leave;
import dev.fbdebug.dbeaver.psql.ast.ProcedureDefinition;
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
        scan(procedure.body(), findings);
        findings.sort(Comparator.comparing(Finding::position,
                Comparator.comparingInt(SourcePosition::line).thenComparingInt(SourcePosition::column)));
        return findings;
    }

    private static void scan(Statement statement, List<Finding> findings) {
        switch (statement) {
            case Block block -> block.statements().forEach(inner -> scan(inner, findings));
            case If branch -> {
                scan(branch.thenBranch(), findings);
                if (branch.elseBranch() != null) {
                    scan(branch.elseBranch(), findings);
                }
            }
            case While loop -> scan(loop.body(), findings);
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
        }
    }

    private static void add(List<UnsupportedUse> uses, List<Finding> findings) {
        for (UnsupportedUse use : uses) {
            findings.add(new Finding(use.position(), use.construct().syntax() + " não é suportado pelo debugger"));
        }
    }
}
