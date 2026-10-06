package com.bencodez.advancedcore.api.rewards;

import java.io.File;
import java.util.Map;
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
            synchronized(this) {consumer=checkpointConsumer;}
            if(consumer==null)return CompletableFuture.completedFuture(null);
            ReplayCheckpoint checkpoint=new ReplayCheckpoint(copyProgress(),copyRegistryFingerprints(),placeholders);
            return plugin.getRewardDispatch().dispatchOffPrimary(()->{
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

	/** Ordered injection completion; persisted replay checkpointing is integrated separately. */
	public CompletionStage<Void> giveInjectedRewardsAsync(AdvancedCoreUser user,
			HashMap<String, String> placeholders) {
		ServerThreadRewardDispatch owner = plugin.getRewardDispatch();
		return owner.dispatch(() -> giveInjectedRewardsAsyncOwned(user, placeholders, owner),
				getServerThreadDispatchTimeoutMillis());
	}

	private CompletionStage<Void> giveInjectedRewardsAsyncOwned(AdvancedCoreUser user,
			HashMap<String, String> placeholders, ServerThreadRewardDispatch owner) {
		return giveInjectedRewardsAsyncOwned(user, placeholders, owner,
				new ArrayList<>(plugin.getRewardHandler().getInjectedRewards()));
	}

	private CompletionStage<Void> giveInjectedRewardsAsyncOwned(AdvancedCoreUser user,
			HashMap<String, String> placeholders, ServerThreadRewardDispatch owner, ArrayList<RewardInject> injections) {
		ArrayList<RewardInject> postRewards = new ArrayList<>();
		CompletionStage<Void> sequence = CompletableFuture.completedFuture(null);
		for (RewardInject inject : injections) {
			if (inject.isPostReward()) { postRewards.add(inject); continue; }
			sequence = sequence.thenCompose(ignored -> invokeInjectionAsync(inject, user, placeholders, owner))
					.thenCompose(value -> owner.dispatch(() -> {
						if (inject.isAddAsPlaceholder() && value != null) {
							String text = value instanceof Boolean || value instanceof String || value instanceof Double
									|| value instanceof Integer ? value.toString() : "";
							placeholders.put(inject.getPlaceholderName(), text);
						}
						return CompletableFuture.<Void>completedFuture(null);
					}, getServerThreadDispatchTimeoutMillis()));
		}
		for (RewardInject inject : postRewards) {
			sequence = sequence.thenCompose(ignored -> invokeInjectionAsync(inject, user, placeholders, owner))
					.thenCompose(value -> owner.dispatch(
							() -> CompletableFuture.<Void>completedFuture(null), getServerThreadDispatchTimeoutMillis()));
		}
		return sequence;
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
			HashMap<String, String> placeholders, ServerThreadRewardDispatch owner) {
		Supplier<CompletionStage<Object>> request = () -> owner.dispatch(() -> {
            AdvancedCoreUser.AsyncActionCollection collection=user==null?null:user.beginAsyncActionCollection();
            CompletionStage<Object> result;
            try {
                if(inject.supportsAsyncRequest()) result=inject.onRewardRequestAsync(this,user,getConfig().getConfigData(),placeholders);
                else {
                    try {
                        Object value;
                        if(inject.isSynchronize()) {synchronized(inject.getObject()){value=inject.onRewardRequest(this,user,getConfig().getConfigData(),placeholders);}}
                        else value=inject.onRewardRequest(this,user,getConfig().getConfigData(),placeholders);
                        result=CompletableFuture.completedFuture(value);
                    }catch(Exception failure){failure.printStackTrace();result=CompletableFuture.completedFuture(null);}
                }
                if(result==null)throw new IllegalStateException("Reward injection returned null completion stage");
            }catch(Throwable failure){CompletableFuture<Object> failed=new CompletableFuture<>();failed.completeExceptionally(failure);result=failed;}
            finally {if(user!=null)user.restoreAsyncActionCollectionScope(collection);}
            if(collection==null)return result;
            CompletableFuture<Object> combined=new CompletableFuture<>();
            result.whenComplete((value,failure)->user.endAsyncActionCollection(collection).whenComplete((ignored,actionFailure)->{
                if(failure!=null)combined.completeExceptionally(failure);
                else if(actionFailure!=null)combined.completeExceptionally(actionFailure);
                else combined.complete(value);
            }));
            return combined;
		}, getServerThreadDispatchTimeoutMillis());
		return inject.isSynchronize() && inject.supportsAsyncSynchronization()
				? inject.runSynchronizedAsync(request) : request.get();
	}

	public void giveReward(AdvancedCoreUser user, RewardOptions rewardOptions) {
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
			user.addOfflineRewards(this, rewardOptions.getPlaceholders());
			plugin.getLogger()
					.info("Rewards are paused, saving offline reward " + getRewardName() + ": " + user.getPlayerName());
			return;
		}

		if ((plugin.getOptions().isTreatVanishAsOffline() && user.isVanished())) {
			checkRewardFile();
			user.addOfflineRewards(this, rewardOptions.getPlaceholders());
			plugin.getLogger()
					.info(getRewardName() + ": " + user.getPlayerName() + " is vanished, saving reward offline");
			return;
		}

		// save reward for offline
		if (((((!rewardOptions.isOnline() || rewardOptions.getServer() != null) && !user.isOnline()) || allowOffline)
				&& (!isForceOffline() && !rewardOptions.isForceOffline()))) {
			if (rewardOptions.isGiveOffline()) {
				checkRewardFile();
				user.addOfflineRewards(this, rewardOptions.getPlaceholders());
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
		if (hasAsyncRewardInjection()) {
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
		ServerThreadRewardDispatch owner = plugin.getRewardDispatch();
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
				return giveInjectedRewardsAsyncOwned(user, placeholders, owner, injections)
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

	@SuppressWarnings("deprecation")
	private void setRewardFile() {
		Reward reward = plugin.getRewardHandler().getRewardDirectlyDefined(name);
		ConfigurationSection section = getConfig().getConfigData();
		reward.getConfig().setData(section);
		reward.getConfig().getFileData().options()
				.header("Directly defined reward file. WRONG PLACE TO EDIT THIS! DO NOT EDIT");
		reward.getConfig().setDirectlyDefinedReward(true);
		reward.getConfig().save(reward.getConfig().getFileData());
		plugin.getRewardHandler().updateReward(reward);
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
