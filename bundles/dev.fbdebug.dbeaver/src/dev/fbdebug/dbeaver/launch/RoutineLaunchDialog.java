package dev.fbdebug.dbeaver.launch;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import dev.fbdebug.dbeaver.interp.DebugSession;
import dev.fbdebug.dbeaver.psql.ast.ParameterDeclaration;
import dev.fbdebug.dbeaver.psql.ast.RoutineDefinition;

/**
 * Diálogo do Launch: valor de cada parâmetro de entrada (texto convertido pelo servidor, {@code NULL} ou o valor
 * padrão), isolamento da transação ({@code READ COMMITTED} por padrão) e "commit ao terminar" (desmarcado). Os
 * valores informados ficam lembrados para a próxima vez, por Rotina.
 */
final class RoutineLaunchDialog extends TitleAreaDialog {

    /** O que o diálogo decidiu. */
    record Settings(List<Object> inputs, DebugSession.Isolation isolation, boolean commitOnFinish) {
    }

    private static final String VALUE = "Valor";
    private static final String NULL = "NULL";
    private static final String DEFAULT = "Padrão";

    private final RoutineDefinition routine;
    private final IDialogSettings history;
    private final List<Combo> modes = new ArrayList<>();
    private final List<Text> values = new ArrayList<>();
    private Combo isolation;
    private Button commit;
    private Settings settings;

    /** @param history onde ficam os valores lembrados desta Rotina */
    RoutineLaunchDialog(Shell shell, RoutineDefinition routine, IDialogSettings history) {
        super(shell);
        this.routine = routine;
        this.history = history;
    }

    /** @return o que foi escolhido, ou {@code null} se o diálogo foi cancelado */
    Settings settings() {
        return settings;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Firebird PSQL Debugger");
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle("Depurar " + routine.displayName());
        setMessage("Cada valor é convertido pelo servidor para o tipo do parâmetro, como num CAST.");
        Composite area = (Composite) super.createDialogArea(parent);
        Composite body = new Composite(area, SWT.NONE);
        body.setLayout(new GridLayout(1, false));
        body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Group parameters = new Group(body, SWT.NONE);
        parameters.setText("Parâmetros de entrada");
        parameters.setLayout(new GridLayout(4, false));
        parameters.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        if (routine.inputs().isEmpty()) {
            new Label(parameters, SWT.NONE).setText("A Rotina não tem parâmetros de entrada.");
        }
        for (ParameterDeclaration input : routine.inputs()) {
            parameterRow(parameters, input);
        }

        Group transaction = new Group(body, SWT.NONE);
        transaction.setText("Transação");
        transaction.setLayout(new GridLayout(2, false));
        transaction.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        new Label(transaction, SWT.NONE).setText("Isolamento:");
        isolation = new Combo(transaction, SWT.READ_ONLY);
        isolation.setItems("READ COMMITTED", "SNAPSHOT");
        isolation.select(0);
        commit = new Button(transaction, SWT.CHECK);
        commit.setText("Commit ao terminar");
        commit.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));
        Label hint = new Label(transaction, SWT.WRAP);
        hint.setText("Sem o commit, tudo o que a Rotina fizer é desfeito ao fim da sessão (rollback).");
        hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        return area;
    }

    private void parameterRow(Composite parent, ParameterDeclaration input) {
        new Label(parent, SWT.NONE).setText(input.name());
        Label type = new Label(parent, SWT.NONE);
        type.setText(input.type().strip() + (input.notNull() ? " NOT NULL" : ""));
        Combo mode = new Combo(parent, SWT.READ_ONLY);
        mode.add(VALUE);
        mode.add(NULL);
        if (input.defaultValue() != null) {
            mode.add(DEFAULT);
        }
        Text value = new Text(parent, SWT.BORDER);
        GridData valueLayout = new GridData(SWT.FILL, SWT.CENTER, true, false);
        valueLayout.widthHint = 260;
        value.setLayoutData(valueLayout);

        String rememberedMode = history.get("mode." + input.name());
        String rememberedValue = history.get("value." + input.name());
        int selected = rememberedMode == null ? -1 : mode.indexOf(rememberedMode);
        if (selected < 0) {
            selected = input.defaultValue() != null ? mode.indexOf(DEFAULT) : 0;
        }
        mode.select(selected);
        value.setText(rememberedValue == null ? "" : rememberedValue);
        value.setEnabled(VALUE.equals(mode.getText()));
        mode.addListener(SWT.Selection, event -> value.setEnabled(VALUE.equals(mode.getText())));
        modes.add(mode);
        values.add(value);
    }

    /**
     * Como no {@code EXECUTE PROCEDURE}, só os últimos parâmetros podem ficar com o valor padrão: os que vêm antes de
     * um informado precisam de valor.
     */
    @Override
    protected void okPressed() {
        List<ParameterDeclaration> inputs = routine.inputs();
        int defaults = inputs.size();
        while (defaults > 0 && DEFAULT.equals(modes.get(defaults - 1).getText())) {
            defaults--;
        }
        for (int i = 0; i < defaults; i++) {
            if (DEFAULT.equals(modes.get(i).getText())) {
                setErrorMessage(inputs.get(i).name() + ": só os últimos parâmetros podem ficar com o valor padrão.");
                return;
            }
        }
        List<Object> chosen = new ArrayList<>();
        for (int i = 0; i < defaults; i++) {
            chosen.add(NULL.equals(modes.get(i).getText()) ? null : values.get(i).getText());
        }
        for (int i = 0; i < inputs.size(); i++) {
            history.put("mode." + inputs.get(i).name(), modes.get(i).getText());
            history.put("value." + inputs.get(i).name(), values.get(i).getText());
        }
        settings = new Settings(chosen,
                isolation.getSelectionIndex() == 1 ? DebugSession.Isolation.SNAPSHOT
                        : DebugSession.Isolation.READ_COMMITTED,
                commit.getSelection());
        super.okPressed();
    }
}
