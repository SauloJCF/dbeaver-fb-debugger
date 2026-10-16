package dev.fbdebug.dbeaver.psql.ast;

/**
 * Construções que a gramática reconhece mas o Interpretador não executa: o Pré-check recusa o launch quando a
 * Rotina usa alguma (ADR 0001: nada é executado "mais ou menos"). Esta é a lista única das construções recusadas,
 * fonte da seção "Fora do MVP" da spec.
 *
 * <p>
 * O que a gramática nem reconhece também é recusado pelo Pré-check, como trecho não reconhecido, com a posição
 * do erro de sintaxe. É o caso, por enquanto, de {@code FOR SELECT}, cursores, {@code EXCEPTION}/{@code WHEN},
 * {@code SUSPEND}, {@code EXECUTE PROCEDURE} e sub-rotinas, que entram nas próximas issues.
 */
public enum UnsupportedConstruct {

    /** {@code UPDATE}/{@code DELETE} posicionado: o cursor é do servidor e o Interpretador não o expõe. */
    WHERE_CURRENT_OF("WHERE CURRENT OF"),

    /** Conexão a outro banco, fora da Sessão de debug. */
    EXECUTE_STATEMENT_ON_EXTERNAL("EXECUTE STATEMENT ... ON EXTERNAL"),

    /** Transação própria, fora da transação única da Sessão de debug. */
    EXECUTE_STATEMENT_AUTONOMOUS("EXECUTE STATEMENT ... WITH AUTONOMOUS TRANSACTION"),

    /** Outra conexão com outro usuário/role, fora da Sessão de debug. */
    EXECUTE_STATEMENT_AS_USER("EXECUTE STATEMENT ... AS USER / PASSWORD / ROLE"),

    /**
     * {@code ROW_COUNT} dentro de uma Fatia DML: o motor dá o número de linhas que o próprio statement já processou
     * (0, 1, 2… a cada linha), o que uma Fatia opaca não reproduz. Fora de DML ele vale o da DML anterior.
     */
    ROW_COUNT_IN_DML("ROW_COUNT dentro de SELECT/INSERT/UPDATE/DELETE/MERGE"),

    /** Transação própria, fora da transação única da Sessão de debug. */
    IN_AUTONOMOUS_TRANSACTION("IN AUTONOMOUS TRANSACTION");

    private final String syntax;

    UnsupportedConstruct(String syntax) {
        this.syntax = syntax;
    }

    /** Como a construção aparece na Fonte, para a mensagem do Pré-check. */
    public String syntax() {
        return syntax;
    }
}
