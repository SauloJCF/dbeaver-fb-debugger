package dev.fbdebug.dbeaver.interp;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Variáveis visíveis numa Rotina em execução: parâmetros de entrada, de saída e locais, na ordem declarada. */
public final class Frame {

    private final Map<String, Variable> variables = new LinkedHashMap<>();

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
}
