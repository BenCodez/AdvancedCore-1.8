package com.bencodez.advancedcore.core.reward;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Durable state for one path in one logical reward occurrence, including cursor zero. */
public final class SharedRewardProgress {
    private final String planFingerprint;
    private final boolean eligible;
    private final int completedSteps;
    private final Map<String, String> placeholders;
    private final Instant notBefore;

    public SharedRewardProgress(String planFingerprint, boolean eligible, int completedSteps, Map<String, String> placeholders, Instant notBefore) {
        Objects.requireNonNull(planFingerprint, "planFingerprint");
        if (SharedRewardJava8.isBlank(planFingerprint)) throw new IllegalArgumentException("Plan fingerprint must not be blank");
        if (completedSteps < 0 || (!eligible && completedSteps != 0)) {
            throw new IllegalArgumentException("Invalid reward progress");
        }
        // Retain the existing context's support for null placeholder values.
        placeholders = Collections.unmodifiableMap(new HashMap<>(Objects.requireNonNull(placeholders, "placeholders")));
        this.planFingerprint = planFingerprint;
        this.eligible = eligible;
        this.completedSteps = completedSteps;
        this.placeholders = placeholders;
        this.notBefore = notBefore;
    }

    /** Legacy snapshots have no timing proof; delayed cursor-zero recovery rejects them. */
    public SharedRewardProgress(String planFingerprint, boolean eligible, int completedSteps,
            Map<String, String> placeholders) {
        this(planFingerprint, eligible, completedSteps, placeholders, null);
    }

    public SharedRewardProgress advance(int nextStep, SharedRewardContext context) {
        if (nextStep < completedSteps) throw new IllegalArgumentException("Reward progress cannot move backwards");
        return new SharedRewardProgress(planFingerprint, eligible, nextStep, context.placeholders(), notBefore);
    }

    public String planFingerprint() { return planFingerprint; }
    public boolean eligible() { return eligible; }
    public int completedSteps() { return completedSteps; }
    public Map<String, String> placeholders() { return placeholders; }
    public Instant notBefore() { return notBefore; }

    @Override public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof SharedRewardProgress)) return false;
        SharedRewardProgress other = (SharedRewardProgress) value;
        return Objects.equals(planFingerprint, other.planFingerprint) && eligible == other.eligible && completedSteps == other.completedSteps && Objects.equals(placeholders, other.placeholders) && Objects.equals(notBefore, other.notBefore);
    }
    @Override public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(planFingerprint);
        result = 31 * result + Boolean.hashCode(eligible);
        result = 31 * result + Integer.hashCode(completedSteps);
        result = 31 * result + Objects.hashCode(placeholders);
        result = 31 * result + Objects.hashCode(notBefore);
        return result;
    }
    @Override public String toString() { return "SharedRewardProgress[planFingerprint=" + planFingerprint + ", eligible=" + eligible + ", completedSteps=" + completedSteps + ", placeholders=" + placeholders + ", notBefore=" + notBefore + "]"; }
}
