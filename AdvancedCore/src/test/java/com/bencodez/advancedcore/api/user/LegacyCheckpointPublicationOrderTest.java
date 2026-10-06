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
        queue(timed, null, check, context -> {});
    }

    private void queue(boolean timed, Throwable failure, Consumer<Context> check, Consumer<Context> settled) {
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
            Context context = new Context(x, key, options.get(0).getAsyncReplayCheckpointConsumer());
            try {
                assertFalse(completion.toCompletableFuture().isDone());
                assertEquals(1, options.size());
                check.accept(context);
            } finally {
                if (failure == null) {
                    effect.complete(null);
                    completion.toCompletableFuture().join();
                } else {
                    effect.completeExceptionally(failure);
                    assertThrows(CompletionException.class, () -> completion.toCompletableFuture().join());
                }
            }
            settled.accept(context);
            if (failure == null) assertEquals("", x.f.cache.getCachedValue(key).getString());
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

    private Reward.ReplayCheckpoint metadataCheckpoint(String key, String value, String marker) {
        return metadataCheckpoint(Collections.singletonMap(key, value), marker);
    }

    private Reward.ReplayCheckpoint metadataCheckpoint(Map<String,String> metadata, String marker) {
        Reward.ReplayCheckpoint checkpoint = checkpoint(0, "registry", marker);
        // Checkpoints own a copied placeholder map; construct the producer payload explicitly.
        HashMap<String,String> placeholders = new HashMap<>(metadata);
        placeholders.put("marker", marker);
        try {
            java.lang.reflect.Constructor<Reward.ReplayCheckpoint> constructor = Reward.ReplayCheckpoint.class
                    .getDeclaredConstructor(Map.class, Map.class, HashMap.class);
            constructor.setAccessible(true);
            return constructor.newInstance(checkpoint.getReplayProgress(), checkpoint.getReplayRegistryFingerprints(), placeholders);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    @Test void olderCommandAndNestedCursorsCannotRegressAtEqualInjectorProgress() {
        for (boolean timed : new boolean[] {false, true})
            for (String prefix : new String[] {"__advancedcore_replay_commands_", "__advancedcore_replay_nested_list_"})
                queue(timed, context -> {
                    String key = prefix + "cm9vdA";
                    HashMap<String,String> metadata = new HashMap<>();
                    metadata.put(key + "_snapshot", "v1:YQ.Yg");
                    metadata.put(key, "2");
                    context.publish.accept(metadataCheckpoint(metadata, "newer"));
                    String acknowledged = context.pending();
                    metadata.put(key, "1");
                    context.publish.accept(metadataCheckpoint(metadata, "older"));
                    assertEquals(acknowledged, context.pending());
                });
    }

    @Test void missingStableMetadataCannotEraseFrozenChoiceOrCompletedChild() {
        for (boolean timed : new boolean[] {false, true})
            for (String prefix : new String[] {"__advancedcore_replay_selection_", "__advancedcore_replay_single_child_"})
                queue(timed, context -> {
                    context.publish.accept(metadataCheckpoint(prefix + "cm9vdA", "1", "newer"));
                    String acknowledged = context.pending();
                    context.publish.accept(checkpoint(0, "registry", "older"));
                    assertEquals(acknowledged, context.pending());
                });
    }

    @Test void changedFrozenSnapshotsAndSelectionsFailBeforeQueueMutation() {
        for (boolean timed : new boolean[] {false, true})
            for (String key : new String[] {"__advancedcore_replay_selection_cm9vdA",
                    "__advancedcore_replay_commands_cm9vdA_snapshot", "__advancedcore_replay_nested_list_cm9vdA_snapshot"})
                queue(timed, context -> {
                    context.publish.accept(metadataCheckpoint(key, "v1:YQ", "first"));
                    String acknowledged = context.pending();
                    assertThrows(IllegalStateException.class,
                            () -> context.publish.accept(metadataCheckpoint(key, "v1:Yg", "conflict")));
                    assertEquals(acknowledged, context.pending());
                });
    }

    @Test void advancingStableCursorAtEqualInjectorProgressStillPersists() {
        for (boolean timed : new boolean[] {false, true}) queue(timed, context -> {
            String key = "__advancedcore_replay_commands_cm9vdA";
            context.publish.accept(metadataCheckpoint(key, "1", "first"));
            context.publish.accept(metadataCheckpoint(key, "2", "second"));
            assertTrue(context.pending().contains("marker%pair%second"));
        });
    }

    @Test void provenUnstartedLegacyReservationMayStillBeReleased() {
        for (boolean timed : new boolean[] {false, true}) queue(timed, context -> {
            String key = "__advancedcore_replay_legacy_actions_cm9vdA_snapshot";
            context.publish.accept(metadataCheckpoint(key, "v1:YQ~Yg", "reserved"));
            context.publish.accept(metadataCheckpoint(key, "v1:", "released"));
            assertTrue(context.pending().contains("marker%pair%released"));
        });
    }

    private Reward.RewardReplayFailure failureCheckpoint(String key, String value) {
        RewardOptions options = new RewardOptions();
        options.setAsyncReplayProgress(Collections.singletonMap("daily", 0));
        options.setAsyncReplayRegistryFingerprints(Collections.singletonMap("daily", "registry"));
        Reward.ReplayState state = Reward.replayStateFor(options);
        HashMap<String,String> placeholders = new HashMap<>();
        placeholders.put(key, value);
        placeholders.put("marker", "failed-older");
        try {
            java.lang.reflect.Constructor<Reward.RewardReplayFailure> constructor = Reward.RewardReplayFailure.class
                    .getDeclaredConstructor(Reward.ReplayState.class, HashMap.class, Throwable.class);
            constructor.setAccessible(true);
            return constructor.newInstance(state, placeholders, new IllegalStateException("effect failed"));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    @Test void failedEffectCannotReplaceNewerAcknowledgedCommandCursorOnEitherQueue() {
        for (boolean timed : new boolean[] {false, true}) {
            String key = "__advancedcore_replay_commands_cm9vdA";
            queue(timed, failureCheckpoint(key, "1"), context ->
                    context.publish.accept(metadataCheckpoint(key, "2", "acknowledged-newer")), context -> {
                assertTrue(context.pending().contains(key + "%pair%2"));
                assertTrue(context.pending().contains("marker%pair%acknowledged-newer"));
                assertFalse(context.pending().contains("failed-older"));
            });
        }
    }

    @Test void advancingInjectorCountCannotDiscardNewerCommandProgress() {
        for (boolean timed : new boolean[] {false, true}) queue(timed, context -> {
            String key = "__advancedcore_replay_commands_cm9vdA";
            context.publish.accept(metadataCheckpoint(key, "2", "newer-command"));
            String acknowledged = context.pending();
            HashMap<String,String> placeholders = new HashMap<>();
            placeholders.put(key, "1");
            try {
                java.lang.reflect.Constructor<Reward.ReplayCheckpoint> constructor = Reward.ReplayCheckpoint.class
                        .getDeclaredConstructor(Map.class, Map.class, HashMap.class);
                constructor.setAccessible(true);
                Reward.ReplayCheckpoint crossed = constructor.newInstance(Collections.singletonMap("daily", 1),
                        Collections.singletonMap("daily", "registry"), placeholders);
                assertThrows(IllegalStateException.class, () -> context.publish.accept(crossed));
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            assertEquals(acknowledged, context.pending());
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
