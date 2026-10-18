package dev.fbdebug.dbeaver.interp;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import dev.fbdebug.dbeaver.psql.ast.Block;
import dev.fbdebug.dbeaver.psql.ast.RoutineDefinition;
import dev.fbdebug.dbeaver.psql.ast.SourcePosition;
import dev.fbdebug.dbeaver.psql.ast.Statement;

/**
 * Pausa da execução de uma Sessão de debug: breakpoint, step over e pedido de pausa. É o {@link StatementHook} da
 * sessão: roda na thread da execução e, numa pausa, a deixa parada até o próximo comando, que vem de outra thread
 * ({@link #resume()}, {@link #stepOver()}, {@link #release()}). Parada, ela roda as tarefas de
 * {@link #whilePaused}.
 * <p>
 * Só para num frame que a camada de debug acompanha ({@link Frame#steppedInto()}), e nunca num bloco
 * ({@code BEGIN}). Um breakpoint vale ao entrar na linha: não para de novo num statement que vem depois de outro da
 * mesma linha, no mesmo frame ({@code IF (...) THEN X = 1;}), mas para de novo quando um laço volta à linha. O step
 * over para no próximo statement do mesmo frame ou de quem o chamou, e não dentro das Rotinas que ele chama.
 */
public final class ExecutionControl implements StatementHook {

    /** Breakpoints ativos, consultados na thread da execução a cada statement. */
    @FunctionalInterface
    public interface Breakpoints {

        /** Nenhum breakpoint. */
        Breakpoints NONE = (source, line) -> null;

        /**
         * @param source Rotina de cujo texto é a linha ({@link Frame#sourceRoutine()})
         * @param line linha, a partir de 1
         * @return o breakpoint ativo na linha, ou {@code null}
         */
        Object at(RoutineDefinition source, int line);
    }

    /** Por que a execução parou. */
    public enum Reason {
        BREAKPOINT, STEP, REQUEST
    }

    /**
     * Execução parada.
     *
     * @param breakpoint o breakpoint, quando a razão é {@link Reason#BREAKPOINT}; senão {@code null}
     * @param frames pilha de frames, do de cima (onde a execução parou) até a Rotina raiz
     */
    public record Pause(Reason reason, Object breakpoint, List<Frame> frames) {

        public Pause {
            frames = List.copyOf(frames);
        }
    }

    /** Quem mostra a pausa. */
    @FunctionalInterface
    public interface Listener {

        /** Chamado na thread da execução, que depois fica parada até o próximo comando. */
        void paused(Pause pause);
    }

    private enum Command {
        RESUME, STEP_OVER
    }

    private final Breakpoints breakpoints;
    private final Listener listener;
    private final Supplier<List<Frame>> stack;

    // Só a thread da execução: o último statement visto, para o breakpoint valer ao entrar na linha.
    private Frame lastFrame;
    private SourcePosition lastPosition;

    // Protegidos por this.
    private boolean paused;
    private Command command;
    /** Com um step over pendente, a profundidade da pilha em que ele para; senão -1. */
    private int stepDepth = -1;
    private boolean pauseRequested;
    private boolean released;
    private final Queue<Runnable> tasks = new ArrayDeque<>();

    /** @param stack pilha de frames da execução ({@link DebugSession#frames()}), lida na thread da execução */
    public ExecutionControl(Breakpoints breakpoints, Listener listener, Supplier<List<Frame>> stack) {
        this.breakpoints = breakpoints;
        this.listener = listener;
        this.stack = stack;
    }

    @Override
    public void beforeStatement(Frame frame, Statement statement) {
        if (statement instanceof Block) {
            return;
        }
        SourcePosition position = statement.position();
        boolean enteringLine = frame != lastFrame || position.line() != lastPosition.line()
                || position.column() <= lastPosition.column();
        lastFrame = frame;
        lastPosition = position;
        if (!frame.steppedInto()) {
            return;
        }
        Object breakpoint = enteringLine ? breakpoints.at(frame.sourceRoutine(), position.line()) : null;
        List<Frame> frames;
        Reason reason;
        synchronized (this) {
            if (released) {
                return;
            }
            frames = breakpoint != null || stepDepth >= 0 || pauseRequested ? stack.get() : null;
            if (breakpoint != null) {
                reason = Reason.BREAKPOINT;
            } else if (stepDepth >= 0 && frames.size() <= stepDepth) {
                reason = Reason.STEP;
            } else if (pauseRequested) {
                reason = Reason.REQUEST;
            } else {
                return;
            }
            stepDepth = -1;
            pauseRequested = false;
            command = null;
            paused = true;
        }
        listener.paused(new Pause(reason, breakpoint, frames));
        await(frames.size());
    }

    /**
     * Fica parado até um comando, rodando as tarefas pedidas por {@link #whilePaused} antes dele; com step over,
     * guarda a profundidade em que ele para.
     */
    private void await(int depth) {
        boolean interrupted = false;
        while (true) {
            Runnable task;
            synchronized (this) {
                while (command == null && tasks.isEmpty() && !released) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                        released = true;
                    }
                }
                task = tasks.poll();
                if (task == null) {
                    if (command == Command.STEP_OVER && !released) {
                        stepDepth = depth;
                    }
                    command = null;
                    paused = false;
                    break;
                }
            }
            task.run();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Roda uma tarefa na thread da execução parada, que é a única a usar a conexão e o estado do Interpretador (editar
     * uma variável, avaliar um watch). As tarefas rodam na ordem pedida, antes do comando que solta a execução.
     *
     * @return o resultado; falha com {@link IllegalStateException} se a execução não está parada
     */
    public synchronized <T> CompletableFuture<T> whilePaused(Callable<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (!paused || released) {
            result.completeExceptionally(new IllegalStateException("A execução não está pausada"));
            return result;
        }
        tasks.add(() -> {
            try {
                result.complete(task.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        notifyAll();
        return result;
    }

    /** A execução está parada. */
    public synchronized boolean isPaused() {
        return paused;
    }

    /** Continua a execução parada até o próximo breakpoint. Sem efeito se ela não está parada. */
    public synchronized void resume() {
        if (paused) {
            command = Command.RESUME;
            notifyAll();
        }
    }

    /**
     * Executa o statement em que a execução parou e para no próximo (ver a classe). Sem efeito se ela não está
     * parada.
     */
    public synchronized void stepOver() {
        if (paused) {
            command = Command.STEP_OVER;
            notifyAll();
        }
    }

    /** Pede pausa no próximo statement em que a execução pode parar. Sem efeito com ela já parada. */
    public synchronized void suspend() {
        if (!paused) {
            pauseRequested = true;
        }
    }

    /**
     * Solta a execução parada e não para mais, para a sessão terminar: com {@link DebugSession#abort()}, ela é
     * interrompida logo depois.
     */
    public synchronized void release() {
        released = true;
        notifyAll();
    }
}
