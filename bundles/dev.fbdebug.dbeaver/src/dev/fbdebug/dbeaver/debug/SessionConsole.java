package dev.fbdebug.dbeaver.debug;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;

import dev.fbdebug.dbeaver.interp.ProcedureResult;
import dev.fbdebug.dbeaver.psql.ast.ParameterDeclaration;
import dev.fbdebug.dbeaver.psql.ast.RoutineDefinition;

/**
 * Console "Firebird PSQL Debug", onde cada Sessão de debug escreve as entradas, as saídas e como a transação
 * terminou. Pode ser chamado de qualquer thread.
 */
public final class SessionConsole {

    private static final String NAME = "Firebird PSQL Debug";

    private final MessageConsole console;
    private final String isolation;

    /** @param isolation nome do isolamento da transação, para o cabeçalho */
    public SessionConsole(String isolation) {
        this.console = findOrCreate();
        this.isolation = isolation;
    }

    private static MessageConsole findOrCreate() {
        IConsoleManager manager = ConsolePlugin.getDefault().getConsoleManager();
        for (IConsole existing : manager.getConsoles()) {
            if (existing instanceof MessageConsole message && NAME.equals(existing.getName())) {
                return message;
            }
        }
        MessageConsole created = new MessageConsole(NAME, null);
        manager.addConsoles(new IConsole[] {created});
        return created;
    }

    /** Aviso antes do início da sessão, que não a impede. */
    public void warn(String message) {
        ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
        print("\nAviso: " + message + "\n");
    }

    void started(RoutineDefinition routine, List<Object> inputs) {
        ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
        StringBuilder text = new StringBuilder("\n== ").append(routine.displayName()).append(" (").append(isolation)
                .append(")\n");
        for (int i = 0; i < routine.inputs().size(); i++) {
            ParameterDeclaration input = routine.inputs().get(i);
            text.append("   ").append(input.name()).append(" = ")
                    .append(i < inputs.size() ? ValueText.of(inputs.get(i)) : "(padrão)").append('\n');
        }
        print(text.toString());
    }

    void finished(ProcedureResult result, boolean committed) {
        StringBuilder text = new StringBuilder("Saídas:\n");
        if (result.names().isEmpty()) {
            text.append("   (nenhuma)\n");
        }
        for (int i = 0; i < result.names().size(); i++) {
            text.append("   ").append(result.names().get(i)).append(" = ")
                    .append(ValueText.of(result.values().get(i))).append('\n');
        }
        text.append(committed ? "Fim: commit feito.\n" : "Fim: rollback feito.\n");
        print(text.toString());
    }

    void aborted() {
        print("Interrompida pelo Terminate: rollback feito.\n");
    }

    void failed(SQLException error) {
        print("Erro (SQLSTATE " + error.getSQLState() + ", código " + error.getErrorCode() + "): " + error.getMessage()
                + "\nFim: rollback feito.\n");
    }

    void failed(RuntimeException error) {
        print("Erro do debugger: " + error.getMessage() + "\nFim: rollback feito.\n");
    }

    private void print(String text) {
        try (MessageConsoleStream stream = console.newMessageStream()) {
            stream.print(text);
        } catch (IOException e) {
            // o console fechou; não há onde mostrar
        }
    }
}
