package dev.fbdebug.dbeaver.interp;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Pilha de savepoints da Rotina, como o motor a mantém (ADR 0001; {@code BlockNode::execute} e
 * {@code ForNode::execute} do Firebird 5):
 * <ul>
 * <li>todo {@code BEGIN ... END} abre um nível; um {@code FOR SELECT} abre outro se o nível atual já tem
 * alterações;</li>
 * <li>quando um nível termina, normalmente ou por {@code LEAVE}/{@code CONTINUE}/{@code EXIT}, o que ele alterou
 * passa para o nível de baixo;</li>
 * <li>um erro deixa os níveis no lugar até chegar a um bloco com {@code WHEN}; se um handler do bloco casar, só os
 * níveis <em>acima</em> do bloco são desfeitos: o que o bloco já fez em statements terminados fica, e o handler
 * roda no nível do bloco;</li>
 * <li>quando um bloco dentro de um handler trata um erro, o motor libera ali mesmo o nível do bloco desse handler
 * (o resto do handler fica sem a proteção dele). Ver {@link #nestedErrorHandled()}.</li>
 * </ul>
 * Os savepoints do servidor são criados só antes da primeira alteração de um nível, e só quando há embaixo um
 * bloco com {@code WHEN} que possa precisar desfazê-lo; sem isso, um erro desfaz a chamada inteira.
 */
final class SavepointStack {

    /** Nível da pilha. */
    static final class Level {

        /** Ordem de abertura: como no motor, "acima" de um nível é ter sido aberto depois dele. */
        private final long number;
        /** Bloco com {@code WHEN}: desfaz os níveis acima quando trata um erro. */
        private final boolean catches;
        /** Savepoint do servidor, criado antes da primeira alteração; ou herdado de um nível de cima. */
        private Savepoint savepoint;
        private boolean changes;

        private Level(long number, boolean catches) {
            this.number = number;
            this.catches = catches;
        }
    }

    private final Connection connection;
    /** Níveis abertos; o primeiro é o de cima. */
    private final Deque<Level> levels = new ArrayDeque<>();
    /** Blocos cujo handler está em execução; o primeiro é o mais interno. */
    private final Deque<Level> handlers = new ArrayDeque<>();
    private long opened;

    SavepointStack(Connection connection) {
        this.connection = connection;
    }

    /** @param catches bloco com {@code WHEN} */
    Level push(boolean catches) {
        Level level = new Level(++opened, catches);
        levels.push(level);
        return level;
    }

    /** {@code FOR SELECT}: o motor abre um nível só se o atual já tem alterações. */
    Level pushIfChanged() {
        Level top = levels.peek();
        return top != null && top.changes ? push(false) : null;
    }

    /** Antes de um statement que pode alterar dados: garante o savepoint do nível de cima, se ele puder ser desfeito. */
    void beforeChange() throws SQLException {
        Level top = levels.peek();
        if (top == null || top.savepoint != null) {
            return;
        }
        Iterator<Level> below = levels.iterator();
        below.next();
        while (below.hasNext()) {
            if (below.next().catches) {
                top.savepoint = connection.setSavepoint();
                return;
            }
        }
    }

    /** O último statement alterou dados (o motor não conta DML sem linhas). */
    void changed() {
        Level top = levels.peek();
        if (top != null) {
            top.changes = true;
        }
    }

    /**
     * Fim normal (ou por desvio) do nível: ele e os abertos depois dele passam para o nível de baixo. Um nível que
     * já foi liberado (ver {@link #nestedErrorHandled()}) não muda nada.
     */
    void release(Level level) throws SQLException {
        if (level == null) {
            return;
        }
        while (!levels.isEmpty() && levels.peek().number >= level.number) {
            Level top = levels.pop();
            merge(top, levels.peek());
        }
    }

    /** Erro tratado pelo bloco {@code level}: desfaz os níveis abertos depois dele, que deixam de existir. */
    void rollbackAbove(Level level) throws SQLException {
        Savepoint earliest = null;
        while (!levels.isEmpty() && levels.peek().number > level.number) {
            Level top = levels.pop();
            if (top.savepoint != null) {
                earliest = top.savepoint; // os de baixo foram criados antes
            }
        }
        if (earliest != null) {
            connection.rollback(earliest);
            connection.releaseSavepoint(earliest);
        }
    }

    /** O handler do bloco {@code level} começa. */
    void enterHandler(Level level) {
        handlers.push(level);
    }

    void leaveHandler(Level level) {
        handlers.remove(level);
    }

    /**
     * Um bloco acabou de tratar um erro. Se isso aconteceu dentro do handler de outro bloco, o motor sai ali do laço
     * de execução desse handler, e a saída se propaga pelos laços de todos os handlers em execução até o laço
     * principal da Rotina: cada bloco desses handlers dá o erro por tratado e libera o seu nível (e os de cima). Um
     * erro que depois saia desses handlers não desfaz mais o que eles e os blocos deles fizeram até aqui. O resto
     * dos handlers continua no laço principal, onde isso não se repete; só handlers que comecem depois voltam a
     * contar.
     */
    void nestedErrorHandled() throws SQLException {
        Level outermost = handlers.peekLast();
        if (outermost != null) {
            release(outermost);
            handlers.clear();
        }
    }

    /** Passa o nível fechado para o de baixo, como o {@code RELEASE SAVEPOINT} do motor. */
    private void merge(Level closed, Level below) throws SQLException {
        if (below != null) {
            below.changes |= closed.changes;
        }
        if (closed.savepoint == null) {
            return;
        }
        if (below != null && below.savepoint == null) {
            // O nível de baixo não tinha alterado nada antes deste savepoint: ele vale como o savepoint dele.
            below.savepoint = closed.savepoint;
        } else {
            connection.releaseSavepoint(closed.savepoint);
        }
    }
}
