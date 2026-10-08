package com.bencodez.advancedcore.core.reward;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * A prepared configuration snapshot. Durable plans supply a stable definition
 * fingerprint covering requirements, native payloads and injection-registry
 * versions; lambda identities are deliberately not used as persistent identity.
 */
public final class SharedRewardPlan {
    private final String id;
    private final double chance;
    private final Duration delay;
    private final List<SharedRewardRequirement> requirements;
    private final List<SharedRewardStep> steps;
    private final String definitionFingerprint;

    public SharedRewardPlan(String id, double chance, Duration delay, List<SharedRewardRequirement> requirements, List<SharedRewardStep> steps, String definitionFingerprint) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(delay, "delay");
        requirements = SharedRewardJava8.copyList(Objects.requireNonNull(requirements, "requirements"));
        steps = SharedRewardJava8.copyList(Objects.requireNonNull(steps, "steps"));
        if (SharedRewardJava8.isBlank(id)) throw new IllegalArgumentException("Reward plan id cannot be blank");
        if (chance < 0.0 || chance > 1.0 || Double.isNaN(chance)) {
            throw new IllegalArgumentException("chance must be between 0 and 1");
        }
        if (delay.isNegative()) throw new IllegalArgumentException("delay cannot be negative");
        this.id = id;
        this.chance = chance;
        this.delay = delay;
        this.requirements = requirements;
        this.steps = steps;
        this.definitionFingerprint = definitionFingerprint;
    }

    /** Retained for synchronous/non-durable callers; bind a definition before durable execution. */
    public SharedRewardPlan(String id, double chance, Duration delay,
            List<SharedRewardRequirement> requirements, List<SharedRewardStep> steps) {
        this(id, chance, delay, requirements, steps, null);
    }

    public static SharedRewardPlan immediate(String id, List<SharedRewardStep> steps) {
        return new SharedRewardPlan(id, 1.0, Duration.ZERO, java.util.Collections.emptyList(), steps);
    }

    public SharedRewardPlan withDefinitionFingerprint(String fingerprint) {
        if (fingerprint == null || SharedRewardJava8.isBlank(fingerprint)) {
            throw new IllegalArgumentException("Definition fingerprint must not be blank");
        }
        return new SharedRewardPlan(id, chance, delay, requirements, steps, fingerprint);
    }

    /** Versioned, length-delimited identity includes ordered step IDs and execution policy. */
    public String fingerprint() {
        if (definitionFingerprint == null || SharedRewardJava8.isBlank(definitionFingerprint)) {
            throw new IllegalStateException("Durable reward plans require a definition fingerprint");
        }
        StringBuilder value = new StringBuilder("shared-reward-plan-v1");
        field(value, id);
        field(value, definitionFingerprint);
        field(value, Double.toHexString(chance));
        field(value, delay.toString());
        field(value, Integer.toString(requirements.size()));
        field(value, Integer.toString(steps.size()));
        for (SharedRewardStep step : steps) {
            field(value, step.id());
            field(value, Boolean.toString(step.requiresOnlinePlayer()));
        }
        try {
            return SharedRewardJava8.hex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void field(StringBuilder target, String value) {
        target.append(':').append(value.length()).append(':').append(value);
    }

    public String id() { return id; }
    public double chance() { return chance; }
    public Duration delay() { return delay; }
    public List<SharedRewardRequirement> requirements() { return requirements; }
    public List<SharedRewardStep> steps() { return steps; }
    public String definitionFingerprint() { return definitionFingerprint; }

    @Override public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof SharedRewardPlan)) return false;
        SharedRewardPlan other = (SharedRewardPlan) value;
        return Objects.equals(id, other.id) && Double.compare(chance, other.chance) == 0 && Objects.equals(delay, other.delay) && Objects.equals(requirements, other.requirements) && Objects.equals(steps, other.steps) && Objects.equals(definitionFingerprint, other.definitionFingerprint);
    }
    @Override public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(id);
        result = 31 * result + Double.hashCode(chance);
        result = 31 * result + Objects.hashCode(delay);
        result = 31 * result + Objects.hashCode(requirements);
        result = 31 * result + Objects.hashCode(steps);
        result = 31 * result + Objects.hashCode(definitionFingerprint);
        return result;
    }
    @Override public String toString() { return "SharedRewardPlan[id=" + id + ", chance=" + chance + ", delay=" + delay + ", requirements=" + requirements + ", steps=" + steps + ", definitionFingerprint=" + definitionFingerprint + "]"; }
}
