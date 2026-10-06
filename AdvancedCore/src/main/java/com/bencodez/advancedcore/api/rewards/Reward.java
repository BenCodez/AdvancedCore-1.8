package com.bencodez.advancedcore.api.rewards;

import java.io.File;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map.Entry;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Consumer;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.item.ItemBuilder;
import com.bencodez.advancedcore.api.rewards.injected.RewardInject;
import com.bencodez.advancedcore.api.rewards.injectedrequirement.RequirementInject;
import com.bencodez.advancedcore.api.user.AdvancedCoreUser;
import com.bencodez.advancedcore.listeners.PlayerRewardEvent;
import com.bencodez.simpleapi.file.annotation.AnnotationHandler;

import lombok.Getter;
import lombok.Setter;

/**
 * The Class Reward.
 */
public class Reward {
    private static final ThreadLocal<ReplayState> ACTIVE_REPLAY_STATE = new ThreadLocal<>();
    private static final ThreadLocal<String> ACTIVE_REPLAY_KEY = new ThreadLocal<>();
    private static final ThreadLocal<String> ACTIVE_REPLAY_OCCURRENCE_ID = new ThreadLocal<>();
	private static final String REPLAY_SELECTION_PREFIX = "__advancedcore_replay_selection_";
	private static final String REPLAY_COMMAND_PREFIX = "__advancedcore_replay_commands_";
	private static final String REPLAY_NESTED_LIST_PREFIX = "__advancedcore_replay_nested_list_";
	private static final String REPLAY_SINGLE_CHILD_PREFIX = "__advancedcore_replay_single_child_";
	private static final String REPLAY_LEGACY_ACTION_PREFIX = "__advancedcore_replay_legacy_actions_";
	public static String replayMetadata(HashMap<String, String> placeholders, ReplayState replayState, String key) {
		String value = placeholders == null || (replayState != null && !replayState.acceptsPersistedMetadata())
				? null : placeholders.get(key);
		return value == null && replayState != null ? replayState.replayMetadata(key) : value;
	}

	public static void recordReplayMetadata(HashMap<String, String> placeholders, ReplayState replayState,
			String key, String value) {
		if (replayState != null) replayState.recordReplayMetadata(key, value);
		if (placeholders != null && (replayState == null || replayState.hasCheckpointConsumer())) {
			placeholders.put(key, value);
		}
	}

	private static String digest(String value) {
		try {
			byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(bytes.length * 2);
			for (byte item : bytes) hex.append(String.format("%02x", item & 0xff));
			return hex.toString();
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 is unavailable for reward replay checkpoints", failure);
		}
	}

	public static void mergeReplayMetadata(HashMap<String, String> target, Map<String, String> source) {
		if (target == null || source == null) return;
		for (Entry<String, String> entry : source.entrySet()) {
			if (isReplayMetadataKey(entry.getKey())) target.put(entry.getKey(), entry.getValue());
		}
	}

	/**
	 * Compares the monotonic part of persisted replay metadata. Frozen selections
	 * and sequence snapshots never change; sequence cursors only advance and a
	 * completed single child stays completed. Legacy action reservations are not
	 * included: a proven unstarted action may legitimately release its snapshot.
	 */
	public static boolean stableReplayMetadataCovers(Map<String, String> newer, Map<String, String> older) {
		for (Entry<String, String> entry : older.entrySet()) {
			String key = entry.getKey();
			if (key == null) continue;
			boolean sequence = key.startsWith(REPLAY_COMMAND_PREFIX) || key.startsWith(REPLAY_NESTED_LIST_PREFIX);
			if (!sequence && !key.startsWith(REPLAY_SELECTION_PREFIX) && !key.startsWith(REPLAY_SINGLE_CHILD_PREFIX)) continue;
			String previous = entry.getValue();
			if (previous == null) throw new IllegalStateException("Persisted replay metadata has no value");
			String proposed = newer.get(key);
			if (proposed == null) return false;
			if (sequence && !key.endsWith("_snapshot")) {
				try {
					int oldCursor = Integer.parseInt(previous), newCursor = Integer.parseInt(proposed);
					if (oldCursor < 0 || newCursor < 0) throw new IllegalStateException("Negative replay sequence cursor");
					if (newCursor < oldCursor) return false;
				} catch (NumberFormatException failure) {
					throw new IllegalStateException("Malformed replay sequence cursor", failure);
				}
			} else if (!previous.equals(proposed)) return false;
		}
		return true;
	}

	private static boolean isReplayMetadataKey(String key) {
		return key != null && (key.startsWith(REPLAY_SELECTION_PREFIX) || key.startsWith(REPLAY_COMMAND_PREFIX)
				|| key.startsWith(REPLAY_NESTED_LIST_PREFIX) || key.startsWith(REPLAY_SINGLE_CHILD_PREFIX)
				|| key.startsWith(REPLAY_LEGACY_ACTION_PREFIX));
	}

	public static String legacyActionReplayKey(String injectionKey) {
		String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
				(injectionKey == null ? "" : injectionKey).getBytes(StandardCharsets.UTF_8));
		return REPLAY_LEGACY_ACTION_PREFIX + encoded;
	}

	public static String legacyActionFingerprint(String descriptor) {
		return digest(descriptor == null ? "" : descriptor);
	}

	public static final class ReplayState {
		private final HashMap<String, Integer> completed = new HashMap<>();
		private final HashMap<String, String> registryFingerprints = new HashMap<>();
		private final HashMap<String, String> replayMetadata = new HashMap<>();
		private final boolean legacyCheckpoint;
		private final boolean restoredCheckpoint;
		private boolean livePlayerStateSet;
		private boolean livePlayerOnline;
		private boolean livePlayerVanished;
		private Consumer<ReplayCheckpoint> checkpointConsumer;
        private ServerThreadRewardDispatch checkpointOwner;
        private com.bencodez.advancedcore.api.item.FullInventoryHandler inventoryOwner;
        private boolean runtimeCaptured;

        /** Internal action scopes share the root's admitted runtime, including deferred continuations. */
        public synchronized void captureRuntime(AdvancedCorePlugin plugin) {
            if (!runtimeCaptured) bindOwner(plugin.getRewardDispatch(), plugin.getFullInventoryHandler());
        }

        /** New queued occurrences retain the runtime admitted before storage work. */
        public void captureAdmittedRuntime(ReplayState admitted) {
            ServerThreadRewardDispatch owner;
            com.bencodez.advancedcore.api.item.FullInventoryHandler inventory;
            synchronized(admitted) {
                if(!admitted.runtimeCaptured)throw new IllegalStateException("Queue runtime has not been admitted");
                owner=admitted.checkpointOwner;inventory=admitted.inventoryOwner;
            }
            bindOwner(owner,inventory);
        }

        public synchronized ServerThreadRewardDispatch getActionDispatchOwner() { return checkpointOwner; }
        public synchronized com.bencodez.advancedcore.api.item.FullInventoryHandler getInventoryOwner() { return inventoryOwner; }
        private synchronized void bindOwner(ServerThreadRewardDispatch owner,
                com.bencodez.advancedcore.api.item.FullInventoryHandler inventory) {
            if (checkpointOwner != null && checkpointOwner != owner)
                throw new IllegalStateException("Reward replay belongs to a retired dispatcher generation");
            if (!runtimeCaptured) {
                checkpointOwner = owner;
                inventoryOwner = inventory;
                runtimeCaptured = true;
            }
        }
		private ReplayState(Map<String, Integer> initial) { this(initial, null, false); }
		private ReplayState(Map<String, Integer> initial, Map<String, String> initialFingerprints,
				boolean legacyCheckpoint) {
			if (initial != null) completed.putAll(initial);
			if (initialFingerprints != null) registryFingerprints.putAll(initialFingerprints);
			this.legacyCheckpoint = legacyCheckpoint;
			this.restoredCheckpoint = legacyCheckpoint || (initial != null && !initial.isEmpty())
					|| (initialFingerprints != null && !initialFingerprints.isEmpty());
		}
		private synchronized int getCompleted(String rewardName, int fallback) {
			return completed.getOrDefault(rewardName, fallback);
		}
		private synchronized void setCompleted(String rewardName, int count) { completed.put(rewardName, count); }
		private synchronized Map<String, Integer> copyProgress() { return new HashMap<>(completed); }
		private synchronized void setRegistryFingerprint(String rewardName, String fingerprint) {
			registryFingerprints.put(rewardName, fingerprint);
		}
		private synchronized Map<String, String> copyRegistryFingerprints() {
			return new HashMap<>(registryFingerprints);
		}
		/** Records a reserved marker produced by a nested replay-aware injector. */
		public synchronized void recordReplayMetadata(String key, String value) {
			if (isReplayMetadataKey(key)) replayMetadata.put(key, value);
		}
		public synchronized void mergeReplayMetadataInto(HashMap<String, String> target) {
			Reward.mergeReplayMetadata(target, replayMetadata);
		}
		public synchronized String replayMetadata(String key) { return replayMetadata.get(key); }
		private synchronized boolean hasPersistedCheckpoint() {
			return legacyCheckpoint || !completed.isEmpty() || !registryFingerprints.isEmpty();
		}
		private synchronized void copyTo(RewardOptions options) {
			options.setCompletedAsyncInjections(Math.max(options.getCompletedAsyncInjections(), highestCompletedCount()));
			options.setAsyncReplayProgress(copyProgress());
			options.setAsyncReplayRegistryFingerprints(copyRegistryFingerprints());
			options.setLegacyAsyncReplayCheckpoint(legacyCheckpoint);
			mergeReplayMetadataInto(options.getPlaceholders());
		}
		private synchronized boolean matchesRegistryFingerprint(String currentFingerprint) {
			if (legacyCheckpoint) return false;
			for (Entry<String, Integer> entry : completed.entrySet()) {
				if (entry.getValue() != null && entry.getValue() > 0
						&& !currentFingerprint.equals(registryFingerprints.get(entry.getKey()))) return false;
			}
			for (String fingerprint : registryFingerprints.values()) {
				if (!currentFingerprint.equals(fingerprint)) return false;
			}
			return true;
		}
		private synchronized int highestCompletedCount() {
			int highest = 0;
			for (int count : completed.values()) highest = Math.max(highest, count);
			return highest;
		}
		private synchronized void setCheckpointConsumer(Consumer<ReplayCheckpoint> consumer) {
			checkpointConsumer = consumer;
		}
		private synchronized void captureLivePlayerState(RewardOptions options) {
			if (!options.isLivePlayerStateSet()) return;
			livePlayerStateSet = true;
			livePlayerOnline = options.isOnline();
			livePlayerVanished = options.isLivePlayerVanished();
		}
		private synchronized void applyLivePlayerState(RewardOptions options) {
			if (livePlayerStateSet) options.captureLivePlayerState(livePlayerOnline, livePlayerVanished);
		}
		private synchronized boolean hasCheckpointConsumer() {
			return checkpointConsumer != null;
		}
		private synchronized boolean acceptsPersistedMetadata() {
			return restoredCheckpoint || checkpointConsumer != null;
		}
		public CompletionStage<Void> persistCheckpointAsync(AdvancedCorePlugin plugin,
				HashMap<String, String> placeholders) {
			return persistCheckpointAsync(plugin, placeholders, 30, TimeUnit.SECONDS);
		}

		private CompletionStage<Void> persistCheckpointAsync(AdvancedCorePlugin plugin,
                HashMap<String,String> placeholders, long timeout, TimeUnit timeoutUnit) {
            Consumer<ReplayCheckpoint> consumer;
            ServerThreadRewardDispatch owner;
            synchronized(this) {
                consumer=checkpointConsumer;
                if(consumer==null)return CompletableFuture.completedFuture(null);
                if(!runtimeCaptured)captureRuntime(plugin);
                owner=checkpointOwner;
            }
            if(consumer==null)return CompletableFuture.completedFuture(null);
            HashMap<String,String> checkpointPlaceholders=new HashMap<>(placeholders);
            mergeReplayMetadataInto(checkpointPlaceholders);
            ReplayCheckpoint checkpoint=new ReplayCheckpoint(copyProgress(),copyRegistryFingerprints(),checkpointPlaceholders);
            return owner.dispatchOffPrimary(()->{
                consumer.accept(checkpoint);
                return CompletableFuture.<Void>completedFuture(null);
            },timeoutUnit.toMillis(timeout));
        }

	}

	public static final class ReplayCheckpoint {
		@Getter private final Map<String, Integer> replayProgress;
		@Getter private final Map<String, String> replayRegistryFingerprints;
		@Getter private final HashMap<String, String> placeholders;
		private ReplayCheckpoint(Map<String, Integer> replayProgress, HashMap<String, String> placeholders) {
			this(replayProgress, new HashMap<>(), placeholders);
		}
		private ReplayCheckpoint(Map<String, Integer> replayProgress, Map<String, String> replayRegistryFingerprints,
				HashMap<String, String> placeholders) {
			this.replayProgress = new HashMap<>(replayProgress);
			this.replayRegistryFingerprints = new HashMap<>(replayRegistryFingerprints);
			this.placeholders = new HashMap<>(placeholders);
		}
	}



	@Getter
	@Setter
	private RewardFileData config;

	@Getter
	@Setter
	private boolean delayEnabled;

	@Getter
	@Setter
	private int delayHours;

	@Getter
	@Setter
	private int delayMinutes;

	@Getter
	@Setter
	private int delaySeconds;

	@Getter
	@Setter
	private int delayMilliSeconds;

	@Getter
	@Setter
	private File file;

	@Getter
	@Setter
	private boolean forceOffline;

	@Getter
	@Setter
	private String name;

	@Getter
	private boolean needsRewardFile = true;
    @Getter private boolean generatedSnapshotCreated;

	/** The plugin. */
	AdvancedCorePlugin plugin = AdvancedCorePlugin.getInstance();

	@Getter
	private RepeatHandle repeatHandle;

	@Getter
	@Setter
	private boolean timedEnabled;

	@Getter
	@Setter
	private int timedHour;

	@Getter
	@Setter
	private int timedMinute;

	/**
	 * Instantiates a new reward.
	 *
	 * @param file   the file
	 * @param reward the reward
	 */
	public Reward(File file, String reward) {
		load(file, reward);
	}

	/**
	 * Instantiates a new reward.
	 *
	 * @param reward the reward
	 */
	public Reward(String reward) {
		load(plugin.getRewardHandler().getDefaultFolder(), reward);
	}

	public Reward(String name, ConfigurationSection section) {
		load(name, section);
	}

	public boolean canGiveReward(AdvancedCoreUser user, RewardOptions options) {
		for (RequirementInject inject : plugin.getRewardHandler().getInjectedRequirements()) {
			try {
				plugin.extraDebug(getRewardName() + ": Checking " + inject.getPath() + ":" + inject.getPriority());
				if (!inject.onRequirementRequest(this, user, getConfig().getConfigData(), options)) {
					return false;
				}
			} catch (Exception e) {
				plugin.debug("Failed to check requirement");
				e.printStackTrace();
				return false;
			}
		}
		return true;
	}

	public boolean checkDelayed(AdvancedCoreUser user, HashMap<String, String> placeholders) {
		if (!isDelayEnabled()) {
			return false;
		}

		LocalDateTime time = LocalDateTime.now();
		time = time.plus(getDelayHours(), ChronoUnit.HOURS);
		time = time.plus(getDelayMinutes(), ChronoUnit.MINUTES);
		time = time.plus(getDelaySeconds(), ChronoUnit.SECONDS);
		time = time.plus(getDelayMilliSeconds(), ChronoUnit.MILLIS);
		checkRewardFile();
		user.addTimedReward(this, placeholders, time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());

		plugin.debug("Giving reward " + name + " in " + getDelayHours() + " hours, " + getDelayMinutes() + " minutes, "
				+ getDelaySeconds() + " seconds (" + time.toString() + ")");
		return true;
	}

	public void checkRewardFile() {
		if (!getConfig().hasRewardFile() && needsRewardFile) {
			setRewardFile();
		}
	}

	public boolean checkTimed(AdvancedCoreUser user, HashMap<String, String> placeholders) {
		if (!isTimedEnabled()) {
			return false;
		}

		LocalDateTime time = LocalDateTime.now();
		time = time.withHour(getTimedHour());
		time = time.withMinute(getTimedMinute());

		if (LocalDateTime.now().isAfter(time)) {
			time = time.plusDays(1);
		}
		checkRewardFile();
		user.addTimedReward(this, placeholders, time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());

		plugin.debug("Giving reward " + name + " at " + time.toString());
		return true;
	}

	public ItemStack getItem() {
		return new ItemStack(Material.STONE);
	}

	public ItemStack getItemStack(AdvancedCoreUser user, String item) {
		return new ItemBuilder(getConfig().getItemSection(item)).setSkullOwner(user.getOfflinePlayer().getName())
				.toItemStack(user.getPlayer());
	}

	/**
	 * Gets the reward name.
	 *
	 * @return the reward name
	 */
	public String getRewardName() {
		return name;
	}

	public void giveInjectedRewards(AdvancedCoreUser user, HashMap<String, String> placeholders) {

		ArrayList<RewardInject> postReward = new ArrayList<>();

		for (final RewardInject inject : plugin.getRewardHandler().getInjectedRewards()) {
			boolean Addplaceholder = inject.isAddAsPlaceholder();
			try {
				Object obj = null;
				plugin.extraDebug(
						getRewardName() + ": Attempting to give " + inject.getPath() + ":" + inject.getPriority());
				if (!inject.isPostReward()) {
					if (inject.isSynchronize()) {
						synchronized (inject.getObject()) {
							obj = inject.onRewardRequest(this, user, getConfig().getConfigData(), placeholders);
						}
					} else {
						obj = inject.onRewardRequest(this, user, getConfig().getConfigData(), placeholders);
					}
					if (Addplaceholder && obj != null) {
						String placeholderName = inject.getPlaceholderName();
						String value = "";
						if (obj instanceof Boolean) {
							Boolean b = (Boolean) obj;
							value = b.toString();
						} else if (obj instanceof String) {
							String b = (String) obj;
							value = b;
						} else if (obj instanceof Double) {
							Double b = (Double) obj;
							value = b.toString();
						} else if (obj instanceof Integer) {
							Integer b = (Integer) obj;
							value = b.toString();
						}
						plugin.extraDebug("Adding placeholder " + placeholderName + ":" + value);
						placeholders.put(placeholderName, value);
					}
				} else {
					postReward.add(inject);

				}

			} catch (Exception e) {
				e.printStackTrace();
			}

		}

		for (RewardInject inject : postReward) {
			try {
				inject.onRewardRequest(this, user, getConfig().getConfigData(), placeholders);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

    /** Ordered effect and checkpoint completion on one admitted dispatcher generation. */
    public CompletionStage<Void> giveInjectedRewardsAsync(AdvancedCoreUser user, HashMap<String,String> placeholders) {
        ServerThreadRewardDispatch owner=plugin.getRewardDispatch();
        ReplayState state=new ReplayState(null);state.bindOwner(owner,plugin.getFullInventoryHandler());
        return owner.dispatch(()->giveInjectedRewardsAsyncOwned(user,placeholders,owner,
            new ArrayList<>(plugin.getRewardHandler().getInjectedRewards()),state,0,getRewardName(),UUID.randomUUID().toString()),getServerThreadDispatchTimeoutMillis());
    }

    private CompletionStage<Void> giveInjectedRewardsAsyncOwned(AdvancedCoreUser user,
            HashMap<String,String> placeholders,ServerThreadRewardDispatch owner,ArrayList<RewardInject> injections,
            ReplayState state,int fallback,String replayKey,String occurrenceId) {
        List<RewardInject> ordered=new ArrayList<>();List<RewardInject> post=new ArrayList<>();
        for(RewardInject inject:injections) {if(inject.isPostReward())post.add(inject);else ordered.add(inject);}
        ordered.addAll(post);
        int resumeAfter=state.getCompleted(replayKey,fallback);
        if(resumeAfter<0 || resumeAfter>ordered.size())return failedStage(new IllegalStateException("Reward replay progress exceeds the injection registry"));
        String fingerprint=injectionRegistryFingerprint(ordered);
        if(!state.matchesRegistryFingerprint(fingerprint))return failedStage(new IncompatibleReplayCheckpointException(replayKey));
        // A count without a corresponding registry identity is not a safe persisted checkpoint.
        if(resumeAfter>0 && !fingerprint.equals(state.copyRegistryFingerprints().get(replayKey)))
            return failedStage(new IncompatibleReplayCheckpointException(replayKey));
        AtomicInteger completed=new AtomicInteger(resumeAfter);
        CompletionStage<Void> sequence=CompletableFuture.completedFuture(null);
        for(int index=0;index<ordered.size();index++) {
            RewardInject inject=ordered.get(index);String injectionKey=replayKey+"/"+index;
            if(index<resumeAfter) {
                sequence=sequence.thenCompose(ignored->owner.dispatch(()->notifyReplayCheckpointPersisted(inject,user,occurrenceId,injectionKey),getServerThreadDispatchTimeoutMillis()));
                continue;
            }
            sequence=sequence.thenCompose(ignored->{
                state.setRegistryFingerprint(replayKey,fingerprint);
                return invokeInjectionAsync(inject,user,placeholders,owner,state,injectionKey,occurrenceId);
            }).thenCompose(value->owner.dispatch(()->{
                if(inject.isAddAsPlaceholder() && value!=null) {
                    String text=value instanceof Boolean || value instanceof String || value instanceof Double || value instanceof Integer?value.toString():"";
                    placeholders.put(inject.getPlaceholderName(),text);
                }
                state.setCompleted(replayKey,completed.incrementAndGet());
                return state.persistCheckpointAsync(plugin,placeholders).thenCompose(ignored->owner.dispatch(
                    ()->notifyReplayCheckpointPersisted(inject,user,occurrenceId,injectionKey),getServerThreadDispatchTimeoutMillis()));
            },getServerThreadDispatchTimeoutMillis()));
        }
        return sequence.handle((ignored,failure)->{
            if(failure==null)return null;
            // Ordinary awaited callers retain their existing exception contract.
            if(!state.hasCheckpointConsumer()) {
                if(failure instanceof java.util.concurrent.CompletionException)throw (java.util.concurrent.CompletionException)failure;
                throw new java.util.concurrent.CompletionException(failure);
            }
            RewardReplayFailure nested=findReplayFailure(failure);
            if(nested!=null)throw nested;
            throw new RewardReplayFailure(state,placeholders,failure);
        });
    }

    private CompletionStage<Void> notifyReplayCheckpointPersisted(RewardInject inject,AdvancedCoreUser user,
            String occurrenceId,String injectionKey) {
        try {
            CompletionStage<Void> notification=inject.onReplayCheckpointPersisted(this,user,occurrenceId,injectionKey);
            return notification==null?failedStage(new IllegalStateException("Reward injection returned a null replay-checkpoint result: "+inject.getPath())):notification;
        }catch(Throwable failure){return failedStage(failure);}
    }

    private static <T> CompletableFuture<T> failedStage(Throwable failure) {
        CompletableFuture<T> result=new CompletableFuture<>();result.completeExceptionally(failure);return result;
    }

	private static String injectionRegistryFingerprint(List<RewardInject> orderedRewards) {
		StringBuilder registry = new StringBuilder();
		for (RewardInject inject : orderedRewards) {
			String path = inject.getPath() == null ? "" : inject.getPath();
			registry.append(inject.getClass().getName()).append('\t').append(Base64.getUrlEncoder().withoutPadding()
					.encodeToString(path.getBytes(StandardCharsets.UTF_8))).append('\t')
					.append(inject.getPriority()).append('\t').append(inject.isPostReward()).append('\n');
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(registry.toString().getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
			return hex.toString();
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 is unavailable for reward replay checkpoints", failure);
		}
	}

	public static final class RewardReplayFailure extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final ReplayState replayState;
		@Getter private final int completedInjectionCount;
		@Getter private final HashMap<String, String> replayPlaceholders;

		private RewardReplayFailure(ReplayState replayState, HashMap<String, String> replayPlaceholders, Throwable cause) {
			super("Asynchronous reward replay failed after a completed asynchronous reward stage",
					cause);
			this.replayState = replayState;
			this.completedInjectionCount = replayState.highestCompletedCount();
			HashMap<String, String> placeholders = new HashMap<>(replayPlaceholders);
			replayState.mergeReplayMetadataInto(placeholders);
			this.replayPlaceholders = placeholders;
		}

		public Map<String, Integer> getReplayProgress() { return replayState.copyProgress(); }
		public Map<String, String> getReplayRegistryFingerprints() { return replayState.copyRegistryFingerprints(); }
	}

	private static final class IncompatibleReplayCheckpointException extends IllegalStateException {
		private static final long serialVersionUID = 1L;
		private IncompatibleReplayCheckpointException(String replayKey) {
			super("Cannot safely resume asynchronous reward replay '" + replayKey
					+ "' because its injector registry changed or its legacy checkpoint has no registry fingerprint");
		}
	}

	private static RewardReplayFailure findReplayFailure(Throwable failure) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current instanceof RewardReplayFailure) return (RewardReplayFailure) current;
		}
		return null;
	}

	public static ReplayState currentReplayState() { return ACTIVE_REPLAY_STATE.get(); }

	public static String currentReplayKey() { return ACTIVE_REPLAY_KEY.get(); }

	public static String currentReplayOccurrenceId() { return ACTIVE_REPLAY_OCCURRENCE_ID.get(); }

	public static String currentReplaySideEffectOccurrenceId() {
		return replaySideEffectOccurrenceId(currentReplayOccurrenceId(), currentReplayKey());
	}

	public static String replaySideEffectOccurrenceId(String occurrenceId, String replayKey) {
		if (occurrenceId == null || occurrenceId.isEmpty() || replayKey == null || replayKey.isEmpty()) {
			return occurrenceId;
		}
		return occurrenceId + ":" + digest(replayKey);
	}

	public static ReplayState replayStateFor(RewardOptions options) {
		ReplayState replayState = options.getAsyncReplayState();
		if (replayState == null) {
			replayState = ACTIVE_REPLAY_STATE.get();
			if (replayState == null) {
				// A caller may reuse its RewardOptions for independent recipients. Keep
				// fresh top-level execution state local to this dispatch so completed
				// stages cannot leak into the next send.
				replayState = new ReplayState(options.getAsyncReplayProgress(),
						options.getAsyncReplayRegistryFingerprints(), options.isLegacyAsyncReplayCheckpoint());
			} else {
				// Deferred/nested options must retain their explicitly inherited parent
				// state after the current thread-local scope ends.
				options.setAsyncReplayState(replayState);
			}
		}
		if (options.getAsyncReplayCheckpointConsumer() != null) {
			replayState.setCheckpointConsumer(options.getAsyncReplayCheckpointConsumer());
		}
		replayState.captureLivePlayerState(options);
		return replayState;
	}

	public static String replaySelection(HashMap<String, String> placeholders, Supplier<String> selector) {
		return replaySelection(placeholders, null, selector);
	}

	/** Records an independent nondeterministic decision within the active injection. */
	public static String replaySelection(HashMap<String, String> placeholders, String lane, Supplier<String> selector) {
		String activeKey = ACTIVE_REPLAY_KEY.get();
		String scopedKey = activeKey == null ? "root" : activeKey;
		if (lane != null && !lane.isEmpty()) scopedKey += "\u0000" + lane;
		String storageKey = REPLAY_SELECTION_PREFIX + Base64.getUrlEncoder().withoutPadding()
				.encodeToString(scopedKey.getBytes(StandardCharsets.UTF_8));
		ReplayState replayState = ACTIVE_REPLAY_STATE.get();
		String stored = replayMetadata(placeholders, replayState, storageKey);
		if (stored != null) {
			if (replayState != null) replayState.recordReplayMetadata(storageKey, stored);
			return stored.isEmpty() ? null : new String(Base64.getUrlDecoder().decode(stored), StandardCharsets.UTF_8);
		}
		String selected = selector.get();
		String encoded = selected == null ? "" : Base64.getUrlEncoder().withoutPadding()
				.encodeToString(selected.getBytes(StandardCharsets.UTF_8));
		recordReplayMetadata(placeholders, replayState, storageKey, encoded);
		return selected;
	}

	/** Returns whether the active injection already persisted a selected branch. */
	public static boolean hasReplaySelection(HashMap<String, String> placeholders) {
		String activeKey = ACTIVE_REPLAY_KEY.get();
		if (activeKey == null) return false;
		String storageKey = REPLAY_SELECTION_PREFIX + Base64.getUrlEncoder().withoutPadding()
				.encodeToString(activeKey.getBytes(StandardCharsets.UTF_8));
		return replayMetadata(placeholders, ACTIVE_REPLAY_STATE.get(), storageKey) != null;
	}

	/** Durably records replay metadata before dispatching its selected side effect. */
	public static CompletionStage<Void> persistReplayMetadataAsync(AdvancedCorePlugin plugin,
			HashMap<String, String> placeholders) {
		ReplayState replayState = ACTIVE_REPLAY_STATE.get();
		return replayState == null ? CompletableFuture.completedFuture(null)
				: replayState.persistCheckpointAsync(plugin, placeholders);
	}

	/** Executes a command list sequentially and durably records each successful item. */
	public static CompletionStage<Void> replayCommandSequence(AdvancedCorePlugin plugin,
			HashMap<String, String> placeholders, String lane, List<String> commands,
			Function<String, CompletionStage<Void>> dispatch) {
		return replayCommandSequence(plugin, placeholders, lane, commands,
				(command, ignoredIndex) -> dispatch.apply(command));
	}

	/** Executes an indexed command list sequentially and durably records each successful item. */
	public static CompletionStage<Void> replayCommandSequence(AdvancedCorePlugin plugin,
			HashMap<String, String> placeholders, String lane, List<String> commands,
			BiFunction<String, Integer, CompletionStage<Void>> dispatch) {
		return replayCommandSequence(plugin, placeholders, lane, commands, commands, currentReplayState(), currentReplayKey(), dispatch);
	}

	/** Executes expanded commands while identifying the sequence by stable templates. */
	public static CompletionStage<Void> replayCommandSequence(AdvancedCorePlugin plugin,
			HashMap<String, String> placeholders, String lane, List<String> commandTemplates,
			List<String> expandedCommands, BiFunction<String, Integer, CompletionStage<Void>> dispatch) {
		return replayCommandSequence(plugin, placeholders, lane, commandTemplates, expandedCommands,
				currentReplayState(), currentReplayKey(), dispatch);
	}

	/** Explicit replay context for continuations that cross scheduler boundaries. */
	public static CompletionStage<Void> replayCommandSequence(AdvancedCorePlugin plugin,
			HashMap<String, String> placeholders, String lane, List<String> commandTemplates,
			List<String> expandedCommands,
			ReplayState replayState, String activeKey,
			BiFunction<String, Integer, CompletionStage<Void>> dispatch) {
		if (replayState == null || activeKey == null) {
			if (commandTemplates == null || commandTemplates.isEmpty()) return CompletableFuture.completedFuture(null);
			if (expandedCommands == null || expandedCommands.size() != commandTemplates.size()) {
				return failedStage(
						new IllegalArgumentException("Command templates and expansions differ in size"));
			}
			CompletionStage<Void> sequence = CompletableFuture.completedFuture(null);
			for (int index = 0; index < expandedCommands.size(); index++) {
				String command = expandedCommands.get(index);
				int commandIndex = index;
				sequence = sequence.thenCompose(ignored -> dispatch.apply(command, commandIndex));
			}
			return sequence;
		}
		String storageKey = replaySequenceKey(REPLAY_COMMAND_PREFIX, activeKey,
				lane == null ? "commands" : lane);
		String snapshotKey = storageKey + "_snapshot";
		List<String> commands;
		String storedSnapshot = replayMetadata(placeholders, replayState, snapshotKey);
		if (storedSnapshot == null) {
			List<String> templates = commandTemplates == null ? java.util.Collections.emptyList() : commandTemplates;
			List<String> expansions = expandedCommands == null ? java.util.Collections.emptyList() : expandedCommands;
			if (expansions.size() != templates.size()) {
				return failedStage(
						new IllegalArgumentException("Command templates and expansions differ in size"));
			}
			commands = new ArrayList<>(expansions);
			String encodedSnapshot = encodeCommandSnapshot(commands);
			recordReplayMetadata(placeholders, replayState, snapshotKey, encodedSnapshot);
		} else {
			replayState.recordReplayMetadata(snapshotKey, storedSnapshot);
			try {
				commands = decodeCommandSnapshot(storedSnapshot);
			} catch (IllegalArgumentException failure) {
				return failedStage(new IllegalStateException("Malformed command replay snapshot", failure));
			}
		}
		String storedProgress = replayMetadata(placeholders, replayState, storageKey);
		int completed;
		try {
			completed = storedProgress == null ? 0 : Integer.parseInt(storedProgress);
		} catch (NumberFormatException failure) {
			return failedStage(
					new IllegalStateException("Malformed command replay progress", failure));
		}
		if (storedProgress != null) replayState.recordReplayMetadata(storageKey, storedProgress);
		if (completed < 0 || completed > commands.size()) {
			return failedStage(new IllegalStateException("Command replay progress exceeds snapshot"));
		}
		// Persist the concrete expansion before issuing its first side effect. A
		// restart during an indeterminate dispatch must reuse this exact payload,
		// even though no command-progress marker exists yet.
		// A previous write may have failed after recording snapshot/cursor in memory.
		// A retry must acknowledge that retained metadata before dispatch or return.
		CompletionStage<Void> sequence = replayState.persistCheckpointAsync(plugin, placeholders);
		for (int index = completed; index < commands.size(); index++) {
			String command = commands.get(index);
			int completedCount = index + 1;
			int commandIndex = index;
			sequence = sequence.thenCompose(ignored -> dispatch.apply(command, commandIndex)).thenCompose(ignored -> {
				recordReplayMetadata(placeholders, replayState, storageKey, String.valueOf(completedCount));
				return replayState == null ? CompletableFuture.completedFuture(null)
						: replayState.persistCheckpointAsync(plugin, placeholders);
			});
		}
		return sequence;
	}

	public static boolean hasReplayCommandSnapshot(HashMap<String, String> placeholders, String lane) {
		ReplayState replayState = currentReplayState();
		String activeKey = currentReplayKey();
		if (replayState == null || activeKey == null) return false;
		String storageKey = replaySequenceKey(REPLAY_COMMAND_PREFIX, activeKey,
				lane == null ? "commands" : lane);
		return replayMetadata(placeholders, replayState, storageKey + "_snapshot") != null;
	}

	/** Returns whether a frozen command lane still has an undispatched command. */
	public static boolean hasPendingReplayCommandWork(HashMap<String, String> placeholders, String lane) {
		ReplayState replayState = currentReplayState();
		String activeKey = currentReplayKey();
		if (replayState == null || activeKey == null) return false;
		String storageKey = replaySequenceKey(REPLAY_COMMAND_PREFIX, activeKey,
				lane == null ? "commands" : lane);
		String storedSnapshot = replayMetadata(placeholders, replayState, storageKey + "_snapshot");
		if (storedSnapshot == null) return false;
		List<String> commands;
		try {
			commands = decodeCommandSnapshot(storedSnapshot);
		} catch (IllegalArgumentException failure) {
			throw new IllegalStateException("Malformed command replay snapshot", failure);
		}
		String storedProgress = replayMetadata(placeholders, replayState, storageKey);
		int completed;
		try {
			completed = storedProgress == null ? 0 : Integer.parseInt(storedProgress);
		} catch (NumberFormatException failure) {
			throw new IllegalStateException("Malformed command replay progress", failure);
		}
		if (completed < 0 || completed > commands.size()) {
			throw new IllegalStateException("Command replay progress exceeds snapshot");
		}
		return completed < commands.size();
	}

	public static CompletionStage<List<String>> replayNestedRewardSnapshot(AdvancedCorePlugin plugin,
			HashMap<String, String> placeholders, String lane, List<String> configuredRewards,
			ReplayState replayState, String activeKey) {
		if (replayState == null || activeKey == null) {
			return CompletableFuture.completedFuture(configuredRewards == null ? java.util.Collections.<String>emptyList()
					: new ArrayList<>(configuredRewards));
		}
		String snapshotKey = replaySequenceKey(REPLAY_NESTED_LIST_PREFIX, activeKey,
				lane == null ? "nested" : lane) + "_snapshot";
		String storedSnapshot = replayMetadata(placeholders, replayState, snapshotKey);
		if (storedSnapshot != null) {
			replayState.recordReplayMetadata(snapshotKey, storedSnapshot);
			try {
				return CompletableFuture.completedFuture(decodeCommandSnapshot(storedSnapshot));
			} catch (IllegalArgumentException failure) {
				return failedStage(
						new IllegalStateException("Malformed nested reward replay snapshot", failure));
			}
		}
		List<String> snapshot = configuredRewards == null ? java.util.Collections.<String>emptyList() : new ArrayList<>(configuredRewards);
		String encodedSnapshot = encodeCommandSnapshot(snapshot);
		recordReplayMetadata(placeholders, replayState, snapshotKey, encodedSnapshot);
		return replayState.persistCheckpointAsync(plugin, placeholders).thenApply(ignored -> snapshot);
	}

	/**
	 * Executes a frozen nested reward list sequentially and checkpoints each
	 * completed child. The parent cursor is consulted before dispatch so a child
	 * that disappeared from the reward registry after completing is never
	 * resolved again during replay.
	 */
	public static CompletionStage<Void> replayNestedRewardSequence(AdvancedCorePlugin plugin,
			HashMap<String, String> placeholders, String lane, List<String> configuredRewards,
			ReplayState replayState, String activeKey,
			BiFunction<String, Integer, CompletionStage<Void>> dispatch) {
		if (replayState == null || activeKey == null) {
			CompletionStage<Void> sequence = CompletableFuture.completedFuture(null);
			List<String> rewards = configuredRewards == null ? java.util.Collections.<String>emptyList() : configuredRewards;
			for (int index = 0; index < rewards.size(); index++) {
				String rewardName = rewards.get(index);
				int rewardIndex = index;
				sequence = sequence.thenCompose(ignored -> dispatch.apply(rewardName, rewardIndex));
			}
			return sequence;
		}
		String stableLane = lane == null ? "nested" : lane;
		String storageKey = replaySequenceKey(REPLAY_NESTED_LIST_PREFIX, activeKey, stableLane);
		return replayNestedRewardSnapshot(plugin, placeholders, stableLane, configuredRewards, replayState, activeKey)
				.thenCompose(rewards -> {
					String storedProgress = replayMetadata(placeholders, replayState, storageKey);
					int completed;
					try {
						completed = storedProgress == null ? 0 : Integer.parseInt(storedProgress);
					} catch (NumberFormatException failure) {
						return failedStage(
								new IllegalStateException("Malformed nested reward replay progress", failure));
					}
					if (storedProgress != null) replayState.recordReplayMetadata(storageKey, storedProgress);
					if (completed < 0 || completed > rewards.size()) {
						return failedStage(
								new IllegalStateException("Nested reward replay progress exceeds snapshot"));
					}
					CompletionStage<Void> sequence = CompletableFuture.completedFuture(null);
					for (int index = completed; index < rewards.size(); index++) {
						String rewardName = rewards.get(index);
						int rewardIndex = index;
						int completedCount = index + 1;
						sequence = sequence.thenCompose(ignored -> dispatch.apply(rewardName, rewardIndex))
								.thenCompose(ignored -> {
									String progress = String.valueOf(completedCount);
									recordReplayMetadata(placeholders, replayState, storageKey, progress);
									return replayState.persistCheckpointAsync(plugin, placeholders);
								});
					}
					return sequence;
				});
	}

	/** Returns whether the active replay already froze this nested reward lane. */
	public static boolean hasReplayNestedRewardSnapshot(HashMap<String, String> placeholders, String lane) {
		return hasReplayNestedRewardSnapshot(placeholders, lane, currentReplayState(), currentReplayKey());
	}

	/** Returns whether the supplied replay already froze this nested reward lane. */
	public static boolean hasReplayNestedRewardSnapshot(HashMap<String, String> placeholders, String lane,
			ReplayState replayState, String activeKey) {
		if (replayState == null || activeKey == null) return false;
		String storageKey = replaySequenceKey(REPLAY_NESTED_LIST_PREFIX, activeKey,
				lane == null ? "nested" : lane);
		return replayMetadata(placeholders, replayState, storageKey + "_snapshot") != null;
	}

	/** Returns whether every child in a frozen nested-reward lane is complete. */
	public static boolean hasCompletedNestedRewardSequence(HashMap<String, String> placeholders, String lane) {
		ReplayState replayState = currentReplayState();
		String activeKey = currentReplayKey();
		if (replayState == null || activeKey == null) return false;
		String storageKey = replaySequenceKey(REPLAY_NESTED_LIST_PREFIX, activeKey,
				lane == null ? "nested" : lane);
		String storedSnapshot = replayMetadata(placeholders, replayState, storageKey + "_snapshot");
		if (storedSnapshot == null) return false;
		List<String> rewards;
		try {
			rewards = decodeCommandSnapshot(storedSnapshot);
		} catch (IllegalArgumentException failure) {
			throw new IllegalStateException("Malformed nested reward replay snapshot", failure);
		}
		String storedProgress = replayMetadata(placeholders, replayState, storageKey);
		int completed;
		try {
			completed = storedProgress == null ? 0 : Integer.parseInt(storedProgress);
		} catch (NumberFormatException failure) {
			throw new IllegalStateException("Malformed nested reward replay progress", failure);
		}
		if (completed < 0 || completed > rewards.size()) {
			throw new IllegalStateException("Nested reward replay progress exceeds snapshot");
		}
		return completed == rewards.size();
	}

	private static String encodeCommandSnapshot(List<String> commands) {
		StringBuilder encoded = new StringBuilder("v1:");
		for (String command : commands) {
			if (encoded.length() > 3) encoded.append('.');
			if (command == null) encoded.append('~');
			else if (command.isEmpty()) encoded.append('=');
			else encoded.append(Base64.getUrlEncoder().withoutPadding()
					.encodeToString(command.getBytes(StandardCharsets.UTF_8)));
		}
		return encoded.toString();
	}

	private static List<String> decodeCommandSnapshot(String encoded) {
		if (!encoded.startsWith("v1:")) throw new IllegalArgumentException("Unknown command snapshot version");
		String body = encoded.substring(3);
		ArrayList<String> commands = new ArrayList<>();
		if (!body.isEmpty()) {
			for (String item : body.split("\\.", -1)) {
				if (item.equals("~")) {
					commands.add(null);
					continue;
				}
				if (item.equals("=")) {
					commands.add("");
					continue;
				}
				try {
					commands.add(new String(Base64.getUrlDecoder().decode(item), StandardCharsets.UTF_8));
				} catch (IllegalArgumentException failure) {
					throw new IllegalArgumentException("Invalid command snapshot encoding", failure);
				}
			}
		}
		return commands;
	}

	public static CompletionStage<Void> replaySingleNestedReward(AdvancedCorePlugin plugin,
			HashMap<String, String> placeholders, String lane, ReplayState replayState, String activeKey,
			Supplier<CompletionStage<Void>> dispatch) {
		if (replayState == null || activeKey == null) {
			try {
				CompletionStage<Void> result = dispatch.get();
				return result == null ? failedStage(
						new IllegalStateException("Nested reward dispatch returned no completion stage")) : result;
			} catch (Throwable failure) {
				return failedStage(failure);
			}
		}
		String storageKey = replaySequenceKey(REPLAY_SINGLE_CHILD_PREFIX, activeKey,
				lane == null ? "selected" : lane);
		String stored = replayMetadata(placeholders, replayState, storageKey);
		if (stored != null) {
			if (!"1".equals(stored)) return failedStage(
					new IllegalStateException("Malformed nested reward completion marker"));
			replayState.recordReplayMetadata(storageKey, stored);
			return CompletableFuture.completedFuture(null);
		}
		CompletionStage<Void> result;
		try {
			result = dispatch.get();
			if (result == null) return failedStage(
					new IllegalStateException("Nested reward dispatch returned no completion stage"));
		} catch (Throwable failure) {
			return failedStage(failure);
		}
		return result.thenCompose(ignored -> {
			recordReplayMetadata(placeholders, replayState, storageKey, "1");
			return replayState.persistCheckpointAsync(plugin, placeholders);
		});
	}

	/** Returns whether the active single nested child is already durably complete. */
	public static boolean hasCompletedSingleNestedReward(HashMap<String, String> placeholders, String lane) {
		ReplayState replayState = currentReplayState();
		String activeKey = currentReplayKey();
		if (replayState == null || activeKey == null) return false;
		String storageKey = replaySequenceKey(REPLAY_SINGLE_CHILD_PREFIX, activeKey,
				lane == null ? "selected" : lane);
		String stored = replayMetadata(placeholders, replayState, storageKey);
		if (!"1".equals(stored)) return false;
		replayState.recordReplayMetadata(storageKey, stored);
		return true;
	}

	private static String replaySequenceKey(String prefix, String activeKey, String lane) {
		return prefix + digest(activeKey.length() + ":" + activeKey + lane.length() + ":" + lane);
	}

	public static RewardOptions withReplayState(RewardOptions options, ReplayState replayState,
			String parentKey, String childOccurrence, String occurrenceId) {
		if (replayState != null) {
			options.setAsyncReplayState(replayState);
			replayState.applyLivePlayerState(options);
		}
		if (occurrenceId != null) {
			String effectiveOccurrence = occurrenceId;
			// Fresh nested children can each be deferred as independent offline queue
			// entries. Give those siblings stable distinct identities so adding the next
			// child cannot replace the previous one. A durable parent keeps its shared
			// occurrence because its checkpoint owns and resumes the complete sequence.
			if (childOccurrence != null && (replayState == null || !replayState.hasCheckpointConsumer())) {
				String childKey = parentKey == null ? childOccurrence : parentKey + "/" + childOccurrence;
				effectiveOccurrence = replaySideEffectOccurrenceId(occurrenceId, childKey);
			}
			options.setAsyncReplayOccurrenceId(effectiveOccurrence);
		}
		if (parentKey != null && childOccurrence != null) {
			options.setAsyncReplayKey(parentKey + "/" + childOccurrence);
		}
		return options;
	}


	public static boolean isDurableReplay(RewardOptions options) {
		if (options == null) return false;
		if (options.getAsyncReplayCheckpointConsumer() != null) return true;
		ReplayState replayState = options.getAsyncReplayState();
		return replayState != null && replayState.hasCheckpointConsumer();
	}

	/** Bukkit1.8 has a single server owner; no modern player-region scheduler is required. */
	public static <T> CompletionStage<T> continueOnServerThread(AdvancedCorePlugin plugin, AdvancedCoreUser user,
			Supplier<CompletionStage<T>> request) {
		return plugin.getRewardDispatch().dispatch(request, TimeUnit.SECONDS.toMillis(30));
	}

	/** Continue asynchronous event/storage setup without creating a separate execution owner. */
	public static <T> CompletionStage<T> continueOffServerThread(AdvancedCorePlugin plugin,
			Supplier<CompletionStage<T>> request) {
		return plugin.getRewardDispatch().dispatchOffPrimary(request, TimeUnit.SECONDS.toMillis(30));
	}

	protected long getServerThreadDispatchTimeoutMillis() { return TimeUnit.SECONDS.toMillis(30); }

	private CompletionStage<Object> invokeInjectionAsync(RewardInject inject, AdvancedCoreUser user,
			HashMap<String, String> placeholders, ServerThreadRewardDispatch owner, ReplayState replayState,
            String injectionKey,String occurrenceId) {
		Supplier<CompletionStage<Object>> request = () -> owner.dispatch(() -> {
            ReplayState previous=ACTIVE_REPLAY_STATE.get();String previousKey=ACTIVE_REPLAY_KEY.get();String previousOccurrence=ACTIVE_REPLAY_OCCURRENCE_ID.get();
            ACTIVE_REPLAY_STATE.set(replayState);ACTIVE_REPLAY_KEY.set(injectionKey);
            if(occurrenceId==null)ACTIVE_REPLAY_OCCURRENCE_ID.remove();else ACTIVE_REPLAY_OCCURRENCE_ID.set(occurrenceId);
            AdvancedCoreUser.AsyncActionCollection collection=null;
            CompletionStage<Object> result;
            try {
                collection=user==null?null:user.beginAsyncActionCollection(replayState,placeholders,injectionKey);
                if(inject.supportsAsyncRequest()) result=inject.onRewardRequestAsync(this,user,getConfig().getConfigData(),placeholders);
                else {
                    try {
                        Object value;
                        if(inject.isSynchronize()) {synchronized(inject.getObject()){value=inject.onRewardRequest(this,user,getConfig().getConfigData(),placeholders);}}
                        else value=inject.onRewardRequest(this,user,getConfig().getConfigData(),placeholders);
                        result=CompletableFuture.completedFuture(value);
                    }catch(Exception failure){failure.printStackTrace();result=replayState.hasCheckpointConsumer()?failedStage(failure):CompletableFuture.completedFuture(null);}
                }
                if(result==null)throw new IllegalStateException("Reward injection returned null completion stage");
            }catch(Throwable failure){CompletableFuture<Object> failed=new CompletableFuture<>();failed.completeExceptionally(failure);result=failed;}
            finally {
                if(user!=null && collection!=null)user.restoreAsyncActionCollectionScope(collection);
                if(previous==null)ACTIVE_REPLAY_STATE.remove();else ACTIVE_REPLAY_STATE.set(previous);
                if(previousKey==null)ACTIVE_REPLAY_KEY.remove();else ACTIVE_REPLAY_KEY.set(previousKey);
                if(previousOccurrence==null)ACTIVE_REPLAY_OCCURRENCE_ID.remove();else ACTIVE_REPLAY_OCCURRENCE_ID.set(previousOccurrence);
            }
            if(collection==null)return result;
            CompletableFuture<Object> combined=new CompletableFuture<>();
            AdvancedCoreUser.AsyncActionCollection capturedCollection=collection;
            result.whenComplete((value,failure)->user.endAsyncActionCollection(capturedCollection).whenComplete((ignored,actionFailure)->{
                if(failure!=null)combined.completeExceptionally(failure);
                else if(actionFailure!=null)combined.completeExceptionally(actionFailure);
                else combined.complete(value);
            }));
            return combined;
		}, getServerThreadDispatchTimeoutMillis());
		return inject.isSynchronize() && inject.supportsAsyncSynchronization()
				? inject.runSynchronizedAsync(request) : request.get();
	}

    /** Await preparation, native effects and replay checkpoints without blocking the Bukkit owner. */
    public CompletionStage<Void> giveRewardAsync(AdvancedCoreUser user, RewardOptions rewardOptions) {
        RewardOptions options=rewardOptions==null?new RewardOptions():rewardOptions.copyForDispatch();
        ReplayState state=replayStateFor(options);state.captureRuntime(plugin);options.setAsyncReplayState(state);
        ServerThreadRewardDispatch owner=state.getActionDispatchOwner();
        return owner.dispatchOffPrimary(()->giveRewardAsyncOffPrimary(user,options,owner),getServerThreadDispatchTimeoutMillis());
    }

	private CompletionStage<Void> giveRewardAsyncOffPrimary(AdvancedCoreUser user, RewardOptions rewardOptions, ServerThreadRewardDispatch owner) {
		if (!plugin.getOptions().isProcessRewards()) {
			plugin.debug("Processing rewards is disabled");
			if (isDurableReplay(rewardOptions)) {
				return failedStage(
						new IllegalStateException("Reward processing was disabled before queued delivery"));
			}
			return CompletableFuture.completedFuture(null);
		}

		rewardOptions = rewardOptions == null ? new RewardOptions() : rewardOptions.copyForDispatch();
		if (!rewardOptions.getPlaceholders().containsKey("ExecDate")) {
			rewardOptions.addPlaceholder("ExecDate", "" + System.currentTimeMillis());
		}
		if (!rewardOptions.getPlaceholders().containsKey("date")) {
			try {
				LocalDateTime ldt = LocalDateTime.now();
				Date date = Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
				rewardOptions.addPlaceholder("Date", "" + new SimpleDateFormat(
						plugin.getOptions().getFormatRewardTimeFormat()).format(date));
			} catch (Exception e) {
				return failedStage(e);
			}
		}

		PlayerRewardEvent event = new PlayerRewardEvent(this, user, rewardOptions);
		Bukkit.getPluginManager().callEvent(event);
		if (event.isCancelled()) {
			plugin.debug("Reward " + name + " was cancelled for " + user.getPlayerName());
			return CompletableFuture.completedFuture(null);
		}
		if (rewardOptions.isCheckTimed() && (checkDelayed(user, rewardOptions.getPlaceholders())
				|| checkTimed(user, rewardOptions.getPlaceholders()))) {
			return CompletableFuture.completedFuture(null);
		}
		RewardOptions stableOptions = rewardOptions;
		return owner.dispatch(() -> evaluateLiveRewardDecision(user, stableOptions),getServerThreadDispatchTimeoutMillis())
                .thenCompose(decision -> owner.dispatchOffPrimary(
                    () -> finishRewardAsync(user, stableOptions, decision, owner),getServerThreadDispatchTimeoutMillis()));
	}

	private CompletionStage<LiveRewardDecision> evaluateLiveRewardDecision(AdvancedCoreUser user,
			RewardOptions rewardOptions) {
		boolean liveOnline = rewardOptions.isLivePlayerStateSet() ? rewardOptions.isOnline() : user.isOnline();
		boolean vanished = plugin.getOptions().isTreatVanishAsOffline()
				&& (rewardOptions.isLivePlayerStateSet() ? rewardOptions.isLivePlayerVanished() : user.isVanished());
		if (!rewardOptions.isLivePlayerStateSet()) rewardOptions.captureLivePlayerState(liveOnline, vanished);
		for (RewardPlaceholderHandle handle : plugin.getRewardHandler().getPlaceholders()) {
			if (handle.isPreProcess()) rewardOptions.addPlaceholder(handle.getKey(), handle.getValue(this, user));
		}

		boolean allowOffline = false;
		boolean canGive = true;
		if (!rewardOptions.isIgnoreRequirements()) {
			for (RequirementInject inject : plugin.getRewardHandler().getInjectedRequirements()) {
				try {
					if (!inject.onRequirementRequest(this, user, getConfig().getConfigData(), rewardOptions)) {
						canGive = false;
						if (!inject.isAllowReattempt()) {
							return CompletableFuture.completedFuture(
									new LiveRewardDecision(false, false, vanished, false, true));
						}
						allowOffline = true;
					}
				} catch (Exception e) {
					plugin.debug("Failed to check requirement " + inject.getPath());
					e.printStackTrace();
					if (isDurableReplay(rewardOptions)) {
						return failedStage(
								new IllegalStateException("Failed to evaluate reward requirement " + inject.getPath(), e));
					}
					canGive = false;
				}
			}
		}
		boolean verifyOnline = !rewardOptions.isOnline() || rewardOptions.getServer() != null;
		boolean liveOffline = verifyOnline && !liveOnline;
		return CompletableFuture.completedFuture(
				new LiveRewardDecision(canGive, allowOffline, vanished, liveOffline, false));
	}

	private CompletionStage<Void> finishRewardAsync(AdvancedCoreUser user, RewardOptions rewardOptions,
			LiveRewardDecision decision, ServerThreadRewardDispatch owner) {
		if (decision.terminalDenied()) return CompletableFuture.completedFuture(null);
		boolean vanished = decision.vanished();
		if (plugin.getOptions().isPauseRewards() || vanished) {
			return deferRewardWithoutBlockingOwner(user, rewardOptions, owner);
		}
		if ((decision.liveOffline() || decision.allowOffline()) && !isForceOffline()
				&& !rewardOptions.isForceOffline()) {
			if (rewardOptions.isGiveOffline()) {
				return deferRewardWithoutBlockingOwner(user, rewardOptions, owner);
			}
			return CompletableFuture.completedFuture(null);
		}
		if (decision.canGive() || isForceOffline() || rewardOptions.isForceOffline()) {
			plugin.debug(name + ": Passed requirements, attempting to give to " + user.getPlayerName() + "/"
					+ user.getUUID());
			return giveRewardUserAsync(user, rewardOptions.getPlaceholders(), rewardOptions);
		}
		return CompletableFuture.completedFuture(null);
	}

    private CompletionStage<Void> deferRewardWithoutBlockingOwner(AdvancedCoreUser user,
            RewardOptions options,ServerThreadRewardDispatch owner) {
        // Durable replay keeps its admitted occurrence in the original queue. The queue
        // adapter recognizes this signal and releases the claim without deleting it.
        if(options.isTimedQueueReplay())return failedStage(new IllegalStateException("Timed reward replay remains deferred in its original queue"));
        if(isDurableReplay(options))return failedStage(new OfflineReplayDeferredException());
        return owner.dispatchOffPrimary(()->{
            checkRewardFile();user.addOfflineRewards(this,options.getPlaceholders(),options);
            return CompletableFuture.<Void>completedFuture(null);
        },getServerThreadDispatchTimeoutMillis());
    }

    private static final class LiveRewardDecision {
        private final boolean canGive,allowOffline,vanished,liveOffline,terminalDenied;
        private LiveRewardDecision(boolean canGive,boolean allowOffline,boolean vanished,boolean liveOffline,boolean terminalDenied) {
            this.canGive=canGive;this.allowOffline=allowOffline;this.vanished=vanished;
            this.liveOffline=liveOffline;this.terminalDenied=terminalDenied;
        }
        private boolean canGive(){return canGive;}
        private boolean allowOffline(){return allowOffline;}
        private boolean vanished(){return vanished;}
        private boolean liveOffline(){return liveOffline;}
        private boolean terminalDenied(){return terminalDenied;}
    }

	private static final class OfflineReplayDeferredException extends IllegalStateException {
		private static final long serialVersionUID = 1L;
		private OfflineReplayDeferredException() {
			super("Persisted offline reward remains deferred");
		}
	}

	public static boolean isOfflineReplayDeferred(Throwable failure) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current instanceof OfflineReplayDeferredException) return true;
		}
		return false;
	}

	public static void preserveReplayState(RewardOptions options) {
		if (options == null || options.getAsyncReplayState() == null) return;
		options.getAsyncReplayState().copyTo(options);
	}

    /** Internal queue bridge: freeze replay metadata without changing caller-owned options. */
    public static RewardOptions snapshotReplayOptionsForQueue(RewardOptions options) {
        if(options==null)return null;
        RewardOptions snapshot=options.copyForDispatch();
        preserveReplayState(snapshot);
        return snapshot;
    }

	public void giveReward(AdvancedCoreUser user, RewardOptions rewardOptions) {
        if (hasAsyncRewardInjection() || isDurableReplay(rewardOptions)) {
            giveRewardAsync(user,rewardOptions).exceptionally(failure->{
                plugin.getLogger().log(java.util.logging.Level.WARNING,"Failed asynchronous reward dispatch",failure);
                return null;
            });
            return;
        }

		if (!AdvancedCorePlugin.getInstance().getOptions().isProcessRewards()) {
			AdvancedCorePlugin.getInstance().debug("Processing rewards is disabled");
			return;
		}

		if (rewardOptions == null) {
			rewardOptions = new RewardOptions();
		}

		if (!rewardOptions.getPlaceholders().containsKey("ExecDate")) {
			rewardOptions.addPlaceholder("ExecDate", "" + System.currentTimeMillis());
		}

		if (!rewardOptions.getPlaceholders().containsKey("date")) {
			try {
				LocalDateTime ldt = LocalDateTime.now();
				Date date = Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
				rewardOptions.addPlaceholder("Date",
						"" + new SimpleDateFormat(plugin.getOptions().getFormatRewardTimeFormat()).format(date));
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		PlayerRewardEvent event = new PlayerRewardEvent(this, user, rewardOptions);
		Bukkit.getPluginManager().callEvent(event);

		if (event.isCancelled()) {
			plugin.debug("Reward " + name + " was cancelled for " + user.getPlayerName());
			return;
		}

		if (rewardOptions.isCheckTimed()) {
			if (checkDelayed(user, rewardOptions.getPlaceholders())
					|| checkTimed(user, rewardOptions.getPlaceholders())) {
				return;
			}
		}

		if (!rewardOptions.isOnlineSet()) {
			rewardOptions.setOnline(user.isOnline());
		}

		for (RewardPlaceholderHandle handle : plugin.getRewardHandler().getPlaceholders()) {
			if (handle.isPreProcess()) {
				rewardOptions.addPlaceholder(handle.getKey(), handle.getValue(this, user));
			}
		}

		// Check requirements
		boolean allowOffline = false;
		boolean canGive = true;
		if (!rewardOptions.isIgnoreRequirements()) {
			for (RequirementInject inject : plugin.getRewardHandler().getInjectedRequirements()) {
				try {
					plugin.extraDebug(getRewardName() + ": Checking requirement " + inject.getPath() + ":"
							+ inject.getPriority());
					if (!inject.onRequirementRequest(this, user, getConfig().getConfigData(), rewardOptions)) {
						plugin.debug(getRewardName() + ": Requirement failed " + inject.getPath() + ":"
								+ inject.isAllowReattempt());
						canGive = false;
						if (!inject.isAllowReattempt()) {
							return;
						}
						allowOffline = true;
					}
				} catch (Exception e) {
					plugin.debug("Failed to check requirement " + inject.getPath());
					e.printStackTrace();
					canGive = false;
				}
			}
		}

		if (plugin.getOptions().isPauseRewards()) {
			checkRewardFile();
			user.addOfflineRewards(this, rewardOptions.getPlaceholders(), rewardOptions);
			plugin.getLogger()
					.info("Rewards are paused, saving offline reward " + getRewardName() + ": " + user.getPlayerName());
			return;
		}

		if ((plugin.getOptions().isTreatVanishAsOffline() && user.isVanished())) {
			checkRewardFile();
			user.addOfflineRewards(this, rewardOptions.getPlaceholders(), rewardOptions);
			plugin.getLogger()
					.info(getRewardName() + ": " + user.getPlayerName() + " is vanished, saving reward offline");
			return;
		}

		// save reward for offline
		if (((((!rewardOptions.isOnline() || rewardOptions.getServer() != null) && !user.isOnline()) || allowOffline)
				&& (!isForceOffline() && !rewardOptions.isForceOffline()))) {
			if (rewardOptions.isGiveOffline()) {
				checkRewardFile();
				user.addOfflineRewards(this, rewardOptions.getPlaceholders(), rewardOptions);
				plugin.debug("Saving offline reward " + getRewardName() + " for " + user.getPlayerName());
			}
			return;
		}

		// give reward
		if (canGive || isForceOffline() || rewardOptions.isForceOffline()) {
			plugin.debug(name + ": Passed requirements, attempting to give to " + user.getPlayerName() + "/"
					+ user.getUUID());
			giveRewardUser(user, rewardOptions.getPlaceholders(), rewardOptions);
		}
	}

	/**
	 * Give reward user.
	 *
	 * @param user          the user
	 * @param phs           placeholders
	 * @param rewardOptions rewardOptions
	 */
	public void giveRewardUser(AdvancedCoreUser user, HashMap<String, String> phs, RewardOptions rewardOptions) {
		RewardOptions effectiveOptions = rewardOptions == null ? new RewardOptions() : rewardOptions;
		if (hasAsyncRewardInjection() || isDurableReplay(effectiveOptions) || effectiveOptions.isLegacyAsyncReplayCheckpoint()
                || effectiveOptions.getCompletedAsyncInjections()>0 || !effectiveOptions.getAsyncReplayProgress().isEmpty()
                || !effectiveOptions.getAsyncReplayRegistryFingerprints().isEmpty()) {
			giveRewardUserAsync(user, phs, effectiveOptions).exceptionally(failure -> {
				plugin.getLogger().log(java.util.logging.Level.WARNING, "Failed asynchronous reward delivery", failure);
				return null;
			});
			return;
		}
		HashMap<String, String> placeholders = prepareRewardUser(user, phs);
		if (placeholders == null) return;
		giveInjectedRewards(user, placeholders);
		finishRewardUser(user, effectiveOptions);
	}

	/** Await opted-in effects and schedule repeats only after successful completion. */
	public CompletionStage<Void> giveRewardUserAsync(AdvancedCoreUser user, HashMap<String, String> phs,
			RewardOptions rewardOptions) {
		HashMap<String, String> requested = phs == null ? new HashMap<>() : new HashMap<>(phs);
		RewardOptions options = rewardOptions == null ? new RewardOptions() : rewardOptions.copyForDispatch();
        ReplayState replayState=replayStateFor(options);
        replayState.captureRuntime(plugin);
        ServerThreadRewardDispatch owner=replayState.getActionDispatchOwner();
        String key=options.getAsyncReplayKey();String parent=ACTIVE_REPLAY_KEY.get();
        final String replayKey=key==null?(parent==null?getRewardName():parent+"/"+getRewardName()):key;
        String occurrence=options.getAsyncReplayOccurrenceId();
        if(occurrence==null || occurrence.isEmpty()) {
            occurrence=ACTIVE_REPLAY_OCCURRENCE_ID.get();
            if(occurrence==null && options.getAsyncReplayCheckpointConsumer()==null)occurrence=UUID.randomUUID().toString();
        }
        final String occurrenceId=occurrence;
        try {replayState.bindOwner(owner,plugin.getFullInventoryHandler());}catch(Throwable failure){return failedStage(failure);}
		return owner.dispatch(() -> {
			// Freeze registration on its owner before identity preflight crosses a storage boundary.
			ArrayList<RewardInject> injections = new ArrayList<>(plugin.getRewardHandler().getInjectedRewards());
			return owner.dispatchOffPrimary(() -> CompletableFuture.completedFuture(user.getPlayerName()),
					getServerThreadDispatchTimeoutMillis()).thenCompose(playerName -> owner.dispatch(() -> {
				HashMap<String, String> placeholders = prepareRewardUser(user, requested, playerName);
				if (placeholders == null) {
					CompletableFuture<Void> unavailable = new CompletableFuture<>();
					unavailable.completeExceptionally(new IllegalStateException("Player unavailable before asynchronous reward delivery"));
					return unavailable;
				}
				return giveInjectedRewardsAsyncOwned(user, placeholders, owner, injections,replayState,options.getCompletedAsyncInjections(),replayKey,occurrenceId)
						.thenCompose(ignored -> owner.dispatch(() -> {
							finishRewardUser(user, options, playerName);
							return CompletableFuture.<Void>completedFuture(null);
						}, getServerThreadDispatchTimeoutMillis()));
			}, getServerThreadDispatchTimeoutMillis()));
		}, getServerThreadDispatchTimeoutMillis());
	}

	private boolean hasAsyncRewardInjection() {
		for (RewardInject inject : plugin.getRewardHandler().getInjectedRewards()) {
			if (inject.supportsAsyncRequest() && (!inject.requiresConfiguredDataForAsync()
					|| inject.isAlwaysForceNoData() || getConfig().getConfigData().contains(inject.getPath()))) return true;
		}
		return false;
	}

	private void finishRewardUser(AdvancedCoreUser user, RewardOptions options) {
		finishRewardUser(user, options, user.getPlayerName());
	}

	private void finishRewardUser(AdvancedCoreUser user, RewardOptions options, String playerName) {
		plugin.debug("Gave " + playerName + " reward " + name);
		if (options.isCheckRepeat() && repeatHandle.isEnabled() && !repeatHandle.isRepeatOnStartup()) {
			repeatHandle.giveRepeat(plugin, user);
		}
	}

	private HashMap<String, String> prepareRewardUser(AdvancedCoreUser user, HashMap<String, String> phs) {
		return prepareRewardUser(user, phs, user.getPlayerName());
	}

	private HashMap<String, String> prepareRewardUser(AdvancedCoreUser user, HashMap<String, String> phs,
			String playerName) {
		Player player = user.getPlayer();
		if (player == null) {
			player = Bukkit.getPlayer(playerName);
		}
		if (player != null || isForceOffline()) {

			// placeholders
			if (phs == null) {
				phs = new HashMap<>();
			}
			phs.put("player", playerName);
			if (player != null) {
				phs.put("displayname", player.getDisplayName());
			}
			phs.put("@p", playerName);
			LocalDateTime ldt = LocalDateTime.now();
			Date date = Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
			phs.put("CurrentDate", "" + new SimpleDateFormat("EEE, d MMM yyyy HH:mm").format(date));
			phs.put("uuid", user.getUUID());

			for (RewardPlaceholderHandle handle : plugin.getRewardHandler().getPlaceholders()) {
				if (!handle.isPreProcess()) {
					phs.put(handle.getKey(), handle.getValue(this, user));
				}
			}

			return new HashMap<>(phs);

		} else {
			plugin.debug(getRewardName() + ": Player == null & forceoffline false, player: " + playerName
					+ "/" + user.getUUID());
			return null;
		}
	}

	/**
	 * Load.
	 *
	 * @param folder the folder
	 * @param reward the reward
	 */
	public void load(File folder, String reward) {
		name = reward;
		if (folder.isDirectory()) {
			file = new File(folder, reward + ".yml");
		} else {
			file = folder;
		}
		config = new RewardFileData(this, folder);
		loadValues();
	}

	public void load(String name, ConfigurationSection section) {
		config = new RewardFileData(this, section);
		this.name = name;
		loadValues();
	}

	public void loadValues() {
		forceOffline = getConfig().getForceOffline();

		setDelayEnabled(getConfig().getDelayedEnabled());
		if (delayEnabled) {
			setDelayHours(getConfig().getDelayedHours());
			setDelayMinutes(getConfig().getDelayedMinutes());
			setDelaySeconds(getConfig().getDelayedSeconds());
			setDelayMilliSeconds(getConfig().getDelayedMilliSeconds());
		}

		setTimedEnabled(getConfig().getTimedEnabled());
		if (timedEnabled) {
			setTimedHour(getConfig().getTimedHour());
			setTimedMinute(getConfig().getTimedMinute());
		}

		repeatHandle = new RepeatHandle(this);

		new AnnotationHandler().load(getConfig().getConfigData(), this);
	}

	public Reward needsRewardFile(boolean value) {
		needsRewardFile = value;
		return this;
	}

	private void setRewardFile() {
        RewardHandler handler=plugin.getRewardHandler();
        Reward predecessor=handler.getRewardDirectlyDefined(name);
        try {
            Reward candidate=predecessor.getConfig().prepareGeneratedSnapshot(getConfig().getConfigData());
            candidate.getConfig().saveStrict();
            candidate.generatedSnapshotCreated=true;
            handler.updateReward(candidate);
            generatedSnapshotCreated=true;
        }catch(java.io.IOException failure){throw new IllegalStateException("Generated reward snapshot publication failed",failure);}
	}

	public void validate() {
		if (getName().equalsIgnoreCase("examplebasic") || getName().equalsIgnoreCase("exampleadvanced")) {
			return;
		}
		for (RequirementInject inject : plugin.getRewardHandler().getInjectedRequirements()) {
			inject.validate(this, getConfig().getConfigData());
		}
		for (RewardInject inject : plugin.getRewardHandler().getInjectedRewards()) {
			inject.validate(this, getConfig().getConfigData());
		}
		for (String str : getConfig().getConfigData().getKeys(false)) {
			boolean valid = false;
			for (RequirementInject inject : plugin.getRewardHandler().getInjectedRequirements()) {
				if (inject.hasValidator()) {
					if (inject.getValidate().isValid(inject, str)) {
						valid = true;
					}
				} else if (inject.getPath().startsWith(str)) {
					valid = true;
				}
			}
			for (RewardInject inject : plugin.getRewardHandler().getInjectedRewards()) {
				if (inject.isAlwaysValid()) {
					valid = true;
				} else {
					if (inject.hasValidator()) {
						if (inject.getValidate().isValid(inject, str)) {
							valid = true;
						}
					} else if (inject.getPath().startsWith(str)) {
						valid = true;
					}
				}
			}
			if (plugin.getRewardHandler().getValidPaths().contains(str)) {
				valid = true;
			}
			if (!valid) {
				plugin.getLogger().warning(str + " possibly not valid in reward " + getRewardName());
			}
		}
	}

}
