package com.bencodez.advancedcore.api.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.api.rewards.*;
import com.bencodez.simpleapi.sql.data.*;

/** Both production queue dispatchers must retain monotonically acknowledged progress. */
class LegacyCheckpointPublicationOrderTest {
    private Reward.ReplayCheckpoint checkpoint(int count, String registry, String marker) {
        return checkpoint(Collections.singletonMap("daily", count), Collections.singletonMap("daily", registry), marker);
    }

    private Reward.ReplayCheckpoint checkpoint(Map<String,Integer> progress, Map<String,String> registries, String marker) {
        try {
            java.lang.reflect.Constructor<Reward.ReplayCheckpoint> constructor = Reward.ReplayCheckpoint.class
                    .getDeclaredConstructor(Map.class, Map.class, HashMap.class);
            constructor.setAccessible(true);
            HashMap<String,String> placeholders = new HashMap<>();
            placeholders.put("marker", marker);
            return constructor.newInstance(progress, registries, placeholders);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private void queue(boolean timed, Consumer<Context> check) {
        String reference = "daily%asyncoccurrence%" + UUID.randomUUID();
        String entry = timed ? reference + "%ExecutionTime/%" + (System.currentTimeMillis() - 1000) : reference;
        new LegacyOfflineQueueReplayTest().fixture(timed ? Collections.emptyList() : Collections.singletonList(entry), x -> {
            String key = timed ? "TimedRewards" : "OfflineRewards";
            if (timed) {
                HashMap<String,DataValue> values = new HashMap<>();
                values.put("OfflineRewards", new DataValueString(""));
                values.put(key, new DataValueString(entry));
                x.f.cache.updateCache(values);
                doCallRealMethod().when(x.f.user).checkDelayedTimedRewardsAsync();
            }
            CompletableFuture<Void> effect = new CompletableFuture<>();
            List<RewardOptions> options = new ArrayList<>();
            when(x.rewards.givePersistedQueueRewardAsync(any(), any(), any())).thenAnswer(call -> {
                options.add(call.getArgument(2)); return effect;
            });
            CompletionStage<Void> completion = timed ? x.f.user.checkDelayedTimedRewardsAsync() : x.f.user.checkOfflineRewardsAsync();
            try {
                assertFalse(completion.toCompletableFuture().isDone());
                assertEquals(1, options.size());
                check.accept(new Context(x, key, options.get(0).getAsyncReplayCheckpointConsumer()));
            } finally {
                effect.complete(null);
                completion.toCompletableFuture().join();
            }
            assertEquals("", x.f.cache.getCachedValue(key).getString());
        });
    }

    @Test void olderAcknowledgementCannotRegressEitherPersistedQueue() {
        for (boolean timed : new boolean[] {false, true}) queue(timed, context -> {
            context.publish.accept(checkpoint(2, "registry", "newer"));
            String acknowledged = context.pending();
            context.publish.accept(checkpoint(1, "registry", "older"));
            assertEquals(acknowledged, context.pending());
            assertTrue(context.pending().contains("marker%pair%newer"));
        });
    }

    @Test void emptyAcknowledgementCannotEraseEitherNonemptyPersistedCheckpoint() {
        for (boolean timed : new boolean[] {false, true}) queue(timed, context -> {
            context.publish.accept(checkpoint(2, "registry", "newer"));
            String acknowledged = context.pending();
            context.publish.accept(checkpoint(Collections.emptyMap(), Collections.emptyMap(), "empty"));
            assertEquals(acknowledged, context.pending());
        });
    }

    @Test void initialEmptyMetadataAndThenZeroCursorRemainValidOnBothQueues() {
        for (boolean timed : new boolean[] {false, true}) queue(timed, context -> {
            context.publish.accept(checkpoint(Collections.emptyMap(), Collections.emptyMap(), "initial"));
            assertTrue(context.pending().contains("marker%pair%initial"));
            context.publish.accept(checkpoint(0, "registry", "zero"));
            assertTrue(context.pending().contains("marker%pair%zero"));
            context.publish.accept(checkpoint(1, "registry", "first"));
            assertTrue(context.pending().contains("marker%pair%first"));
        });
    }

    @Test void changedRegistryCannotOverwriteEitherAcknowledgedQueue() {
        for (boolean timed : new boolean[] {false, true}) queue(timed, context -> {
            context.publish.accept(checkpoint(2, "registry", "newer"));
            String acknowledged = context.pending();
            assertThrows(IllegalStateException.class,
                    () -> context.publish.accept(checkpoint(3, "different-registry", "conflict")));
            assertEquals(acknowledged, context.pending());
        });
    }

    @Test void increasingAcknowledgementStillAdvancesBothQueues() {
        for (boolean timed : new boolean[] {false, true}) queue(timed, context -> {
            context.publish.accept(checkpoint(1, "registry", "first"));
            String first = context.pending();
            context.publish.accept(checkpoint(2, "registry", "second"));
            assertNotEquals(first, context.pending());
            assertTrue(context.pending().contains("marker%pair%second"));
        });
    }

    private static final class Context {
        final LegacyOfflineQueueReplayTest.Fixture fixture;
        final String key;
        final Consumer<Reward.ReplayCheckpoint> publish;
        Context(LegacyOfflineQueueReplayTest.Fixture fixture, String key, Consumer<Reward.ReplayCheckpoint> publish) {
            this.fixture = fixture; this.key = key; this.publish = publish;
        }
        String pending() { return fixture.f.cache.getCachedValue(key).getString(); }
    }
}
