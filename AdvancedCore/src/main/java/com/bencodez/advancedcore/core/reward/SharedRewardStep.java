package com.bencodez.advancedcore.core.reward;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** One already-configured reward operation. Native adapters provide the action. */
public final class SharedRewardStep {
    private final String id;
    private final boolean requiresOnlinePlayer;
    private final Action action;

    public SharedRewardStep(String id, boolean requiresOnlinePlayer, Action action) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(action, "action");
        if (SharedRewardJava8.isBlank(id)) throw new IllegalArgumentException("Reward step id cannot be blank");
        this.id = id;
        this.requiresOnlinePlayer = requiresOnlinePlayer;
        this.action = action;
    }

    @FunctionalInterface
    public interface Action {
        CompletionStage<SharedRewardResult> execute(SharedRewardContext context, String executionPath);
    }

    public String id() { return id; }
    public boolean requiresOnlinePlayer() { return requiresOnlinePlayer; }
    public Action action() { return action; }

    @Override public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof SharedRewardStep)) return false;
        SharedRewardStep other = (SharedRewardStep) value;
        return Objects.equals(id, other.id) && requiresOnlinePlayer == other.requiresOnlinePlayer && Objects.equals(action, other.action);
    }
    @Override public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(id);
        result = 31 * result + Boolean.hashCode(requiresOnlinePlayer);
        result = 31 * result + Objects.hashCode(action);
        return result;
    }
    @Override public String toString() { return "SharedRewardStep[id=" + id + ", requiresOnlinePlayer=" + requiresOnlinePlayer + ", action=" + action + "]"; }
}
