package utils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class KeyedExecutor {
    private final Executor[] executor;

    public KeyedExecutor(final int threads) {
        executor = new Executor[threads];
        for (int i = 0; i < threads; i++) {
            executor[i] = Executors.newSingleThreadExecutor();
        }
    }

    public CompletionStage<Void> submit(final String id, final Runnable task) {
        return CompletableFuture.runAsync(task, executor[id.hashCode() % executor.length]);
    }

    public <T> CompletionStage<T> get(final String id, final Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, executor[id.hashCode() % executor.length]);
    }
}
