package com.bencodez.advancedcore.core.user.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Java 8 observation-only stages; the runtime retains the actual close future. */
final class RuntimeCompletionStages {
    private RuntimeCompletionStages() { }

    static <T> Set<T> copySet(Set<T> values) {
        HashSet<T> copy = new HashSet<>(values);
        for (T value : copy) Objects.requireNonNull(value, "set element");
        return Collections.unmodifiableSet(copy);
    }

    @SuppressWarnings("unchecked")
    static <T> CompletionStage<T> readOnly(CompletionStage<T> source) {
        return (CompletionStage<T>) Proxy.newProxyInstance(CompletionStage.class.getClassLoader(),
                new Class<?>[] { CompletionStage.class }, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if (method.getName().equals("equals")) return proxy == args[0];
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        return "ReadOnlyCompletionStage";
                    }
                    if (method.getName().equals("toCompletableFuture")) {
                        // Every conversion observes, rather than exposes, the owned future.
                        CompletableFuture<T> observer = new CompletableFuture<>();
                        source.whenComplete((value, failure) -> {
                            if (failure == null) observer.complete(value);
                            else observer.completeExceptionally(failure);
                        });
                        return observer;
                    }
                    try {
                        Object result = method.invoke(source, args);
                        return result instanceof CompletionStage ? readOnly((CompletionStage<?>) result) : result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
