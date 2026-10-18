package dev.fbdebug.dbeaver.debug;

import java.util.List;

import dev.fbdebug.dbeaver.interp.ServerCalls;

/**
 * Chamadas sem step into no texto de um stack frame ({@link ServerCalls}), para o aviso "executado no servidor" no
 * editor da Rotina. O stack frame a dá por {@code getAdapter(ServerCallWarnings.class)}.
 *
 * @param calls as chamadas, com as posições no texto do frame
 */
public record ServerCallWarnings(List<ServerCalls.Call> calls) {

    public ServerCallWarnings {
        calls = List.copyOf(calls);
    }
}
