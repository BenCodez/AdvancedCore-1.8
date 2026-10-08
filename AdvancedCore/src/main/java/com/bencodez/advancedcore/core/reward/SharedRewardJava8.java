package com.bencodez.advancedcore.core.reward;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Internal Java 8 equivalents; not a replay owner or persistence implementation. */
final class SharedRewardJava8 {
    private SharedRewardJava8() { }
    static <T> CompletableFuture<T> failedFuture(Throwable failure) {
        CompletableFuture<T> result=new CompletableFuture<>();result.completeExceptionally(failure);return result;
    }
    static boolean isBlank(String value) { return value.codePoints().allMatch(Character::isWhitespace); }
    static <T> List<T> copyList(List<T> values) {
        ArrayList<T> copy=new ArrayList<>(values);for(T value:copy)Objects.requireNonNull(value,"list element");
        return Collections.unmodifiableList(copy);
    }
    static String hex(byte[] bytes) {
        char[] digits="0123456789abcdef".toCharArray(),result=new char[bytes.length*2];
        for(int i=0;i<bytes.length;i++){int value=bytes[i]&255;result[i*2]=digits[value>>>4];result[i*2+1]=digits[value&15];}
        return new String(result);
    }
    static void requireFingerprint(SharedRewardDurability durability,String path,String fingerprint) {
        if(!durability.durable())return;
        SharedRewardProgress state=durability.loadProgress(path);
        if(state==null || !state.planFingerprint().equals(fingerprint))
            throw new IllegalStateException("Reward checkpoint belongs to a different or unbound plan: "+path);
    }
}
