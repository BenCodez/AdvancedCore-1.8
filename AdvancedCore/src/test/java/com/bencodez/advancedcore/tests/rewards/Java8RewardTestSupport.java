package com.bencodez.advancedcore.tests.rewards;

import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;

final class Java8RewardTestSupport {
    private Java8RewardTestSupport() { }
    @SafeVarargs static <T> List<T> list(T... values) {
        ArrayList<T> copy=new ArrayList<>();for(T value:values)copy.add(Objects.requireNonNull(value));
        return Collections.unmodifiableList(copy);
    }
    @SuppressWarnings("unchecked") static <K,V> Map<K,V> map(Object... values) {
        if(values.length%2!=0)throw new IllegalArgumentException("Unpaired map entry");
        HashMap<K,V> copy=new HashMap<>();
        for(int i=0;i<values.length;i+=2){K key=(K)Objects.requireNonNull(values[i]);V value=(V)Objects.requireNonNull(values[i+1]);
            if(copy.put(key,value)!=null)throw new IllegalArgumentException("Duplicate key");}
        return Collections.unmodifiableMap(copy);
    }
    static <T> CompletableFuture<T> failedFuture(Throwable failure) {
        CompletableFuture<T> result=new CompletableFuture<>();result.completeExceptionally(failure);return result;
    }
    /** Opaque CompletionStage exercises chaining without requiring a CompletableFuture implementation. */
    @SuppressWarnings("unchecked") static <T> CompletionStage<T> minimal(CompletionStage<T> stage) {
        return (CompletionStage<T>)Proxy.newProxyInstance(CompletionStage.class.getClassLoader(),new Class<?>[]{CompletionStage.class},
            (proxy,method,args)->{
                if(method.getName().equals("toCompletableFuture"))throw new UnsupportedOperationException("Opaque stage");
                try{return method.invoke(stage,args);}catch(InvocationTargetException failure){throw failure.getCause();}
            });
    }
}
