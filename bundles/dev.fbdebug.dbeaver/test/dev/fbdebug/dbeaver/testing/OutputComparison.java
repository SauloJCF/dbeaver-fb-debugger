package dev.fbdebug.dbeaver.testing;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Compara as saídas da execução nativa com as do Interpretador e descreve cada divergência. */
public final class OutputComparison {

    private OutputComparison() {
    }

    /**
     * @param call descrição da chamada, para a mensagem (ex.: {@code P(1, 'a')})
     * @throws AssertionError com uma linha {@code nome: nativo=... × interpretado=...} por saída divergente
     */
    public static void assertSame(String call, List<String> nativeNames, List<Object> nativeValues,
            List<String> interpretedNames, List<Object> interpretedValues) {
        if (!nativeNames.equals(interpretedNames)) {
            throw new AssertionError(call + ": parâmetros de saída diferentes: nativo=" + nativeNames
                    + " × interpretado=" + interpretedNames);
        }
        List<String> differences = new ArrayList<>();
        for (int i = 0; i < nativeNames.size(); i++) {
            Object expected = nativeValues.get(i);
            Object actual = interpretedValues.get(i);
            if (!Objects.deepEquals(expected, actual)) {
                differences.add("  " + nativeNames.get(i) + ": nativo=" + describe(expected)
                        + " × interpretado=" + describe(actual));
            }
        }
        if (!differences.isEmpty()) {
            throw new AssertionError(call + ": " + differences.size()
                    + " saída(s) divergem entre a execução nativa e o Interpretador\n"
                    + String.join("\n", differences));
        }
    }

    /** Valor com o tipo Java, e texto entre aspas para deixar espaços visíveis. */
    public static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        String text = switch (value) {
            case String s -> "'" + s + "'";
            case byte[] bytes -> "x'" + HexFormat.of().formatHex(bytes) + "'";
            default -> value.toString();
        };
        return text + " (" + value.getClass().getSimpleName() + ")";
    }
}
