package dev.fbdebug.dbeaver.interp;

import dev.fbdebug.dbeaver.psql.CallSites;

/**
 * Escolha, a cada chamada Step into elegível, entre interpretar a Rotina chamada (step into) ou executar a chamada
 * no servidor (step over). Quem controla a Sessão de debug decide; o Interpretador só pergunta.
 * <p>
 * Exceção: no {@code FOR SELECT … FROM sp(...)}, o step over também interpreta a procedure, sem que a camada de
 * debug pare nela ({@link Frame#steppedInto()} falso). Um cursor do servidor não é fiel ao motor: o servidor remoto
 * busca linhas à frente do laço, rodando a procedure além do ponto em que o laço sai (com o DML dela), e num erro
 * no meio do lote o Jaybird descarta as linhas boas que vieram junto. O motor, em PSQL, busca uma linha por vez. Só
 * uma procedure que não dá para interpretar vai ao cursor do servidor. O mesmo vale para uma rotina privada da
 * package de quem chama: fora da package ela não existe, e o servidor não a acharia. E para uma sub-rotina que usa
 * variáveis da Rotina que a declara: a cópia dela no Package temporário de debug não as teria. As outras sub-rotinas
 * vão ao servidor por essa cópia.
 */
@FunctionalInterface
public interface CallPolicy {

    /** Toda chamada executada no servidor. */
    CallPolicy SERVER = (call, caller) -> false;

    /** Toda chamada elegível interpretada. */
    CallPolicy INTERPRET = (call, caller) -> true;

    /**
     * Chamado antes de executar a chamada, com o frame de quem chama no topo de {@link Interpreter#frames()}. Se o
     * nome não for de uma Rotina PSQL com Fonte do banco ({@code UPPER(...)}, uma tabela, uma procedure externa) ou
     * se a Rotina não passar no Pré-check, a chamada vai para o servidor mesmo com {@code true}.
     *
     * @return {@code true} para interpretar a Rotina chamada
     */
    boolean stepInto(CallSites.CallSite call, Frame caller);
}
