package com.bencodez.advancedcore.api.user;

import java.text.SimpleDateFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.HashSet;
import java.util.Base64;
import java.util.Map;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.item.ItemBuilder;
import com.bencodez.advancedcore.api.messages.PlaceholderUtils;
import com.bencodez.advancedcore.api.misc.PlayerManager;
import com.bencodez.advancedcore.api.rewards.Reward;
import com.bencodez.advancedcore.api.rewards.ServerThreadRewardDispatch;
import com.bencodez.advancedcore.api.rewards.RewardBuilder;
import com.bencodez.advancedcore.api.rewards.RewardHandler;
import com.bencodez.advancedcore.api.rewards.RewardOptions;
import com.bencodez.advancedcore.api.user.usercache.UserDataCache;
import com.bencodez.advancedcore.api.valuerequest.InputMethod;
import com.bencodez.simpleapi.array.ArrayUtils;
import com.bencodez.simpleapi.player.PlayerUtils;
import com.bencodez.simpleapi.sql.Column;

import lombok.Getter;
import lombok.Setter;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.chat.ComponentSerializer;

/**
 * The Class User.
 */
public class AdvancedCoreUser {
    private static final String QUEUED_REFERENCE_PREFIX="\\AdvancedCoreQueue/1/";
    private static final String ASYNC_PROGRESS_DELIMITER="%asyncprogress%";
    private static final String ASYNC_RETRY_DELIMITER="%asyncretry%";
    private static final String ASYNC_OCCURRENCE_DELIMITER="%asyncoccurrence%";
    private static final Object REPLAY_CLAIMS_LOCK=new Object();
    private static final java.util.WeakHashMap<AdvancedCorePlugin,HashMap<String,PersistedReplayClaims>> REPLAY_CLAIMS=new java.util.WeakHashMap<>();
    private static final class PersistedReplayClaims {
        final HashSet<String> occurrences=new HashSet<>();
        final HashMap<String,Integer> legacy=new HashMap<>();
        CompletableFuture<Void> tail=CompletableFuture.completedFuture(null);
        TimedStorageWakeup timedStorageWakeup;
        QueuePublication publication;
        int timedStorageFailures;
    }

	private static final ThreadLocal<AsyncActionCollection> ASYNC_ACTION_COLLECTION = new ThreadLocal<>();
	public static final class AsyncActionCollection {
		private static final String ACTION_SNAPSHOT_SUFFIX = "_snapshot";
		private static final String ACTION_COMPLETION_VERSION = "v2:";
		private static final String ACTION_SNAPSHOT_VERSION = "v1:";
		private final AsyncActionCollection previous;
		private final AdvancedCoreUser owner;
		private final ArrayList<ReplayAction> actions = new ArrayList<>();
		private final HashMap<String, Integer> actionOccurrences = new HashMap<>();
		private final Reward.ReplayState replayState;
		private final HashMap<String, String> placeholders;
		private final String checkpointKey;
		private final String snapshotKey;
		private final AdvancedCorePlugin plugin;
		private final ServerThreadRewardDispatch actionOwner;
        private final com.bencodez.advancedcore.api.item.FullInventoryHandler inventoryOwner;
		private boolean closed;
		private CompletableFuture<Void> completion;

		private AsyncActionCollection(AsyncActionCollection previous, AdvancedCoreUser owner, Reward.ReplayState replayState,
				HashMap<String, String> placeholders, String injectionKey, AdvancedCorePlugin plugin) {
			this.previous = previous;
			this.owner = owner;
			this.replayState = replayState;
			this.placeholders = placeholders;
			this.checkpointKey = Reward.legacyActionReplayKey(injectionKey);
			this.snapshotKey = checkpointKey + ACTION_SNAPSHOT_SUFFIX;
			this.plugin = plugin;
            if (replayState != null && plugin != null) replayState.captureRuntime(plugin);
            this.actionOwner = plugin == null ? null : replayState == null
                    ? plugin.getRewardDispatch() : replayState.getActionDispatchOwner();
            this.inventoryOwner = plugin == null ? null : replayState == null
                    ? plugin.getFullInventoryHandler() : replayState.getInventoryOwner();
		}

		private synchronized boolean add(Supplier<CompletionStage<Void>> action, String descriptor) {
			if (closed) return false;
			String fingerprint = Reward.legacyActionFingerprint(descriptor);
			int occurrence = actionOccurrences.getOrDefault(fingerprint, 0);
			actionOccurrences.put(fingerprint, occurrence + 1);
			actions.add(new ReplayAction(fingerprint + "/" + occurrence, fingerprint, action,
					!descriptor.startsWith("failure:")));
			return true;
		}

		private boolean belongsTo(AdvancedCoreUser user) {
			return owner == user;
		}

		private CompletionStage<Void> closeAndAwait() {
            ArrayList<ReplayAction> captured;
            CompletableFuture<Void> result;
            synchronized(this) {
                if(completion!=null)return completion;
                closed=true;captured=new ArrayList<>(actions);
                completion=new CompletableFuture<Void>() {@Override public boolean cancel(boolean interrupt){return false;}};
                result=completion;
            }
            try {buildSequence(captured).whenComplete((ignored,failure)->{
                if(failure==null)result.complete(null);else result.completeExceptionally(failure);
            });}catch(Throwable failure){result.completeExceptionally(failure);}
            return result;
        }
        private CompletionStage<Void> buildSequence(ArrayList<ReplayAction> captured) {
			HashMap<String, String> currentSnapshot = new HashMap<>();
			for (ReplayAction action : captured) {
				if (action.durable) currentSnapshot.put(action.identity, action.fingerprint);
			}
			HashMap<String, String> persistedSnapshot;
			HashSet<String> completed;
			try {
				persistedSnapshot = snapshot(readReplayValue(snapshotKey));
				completed = completed(readReplayValue(checkpointKey));
				for (String identity : completed) {
					if (!persistedSnapshot.containsKey(identity)) {
						return failedStage(new IllegalStateException(
								"Legacy action checkpoint does not match its persisted action snapshot"));
					}
				}
			} catch (IllegalArgumentException failure) {
				return failedStage(new IllegalStateException(
						"Cannot safely resume legacy reward actions from an ordinal-only or malformed checkpoint", failure));
			}
			boolean snapshotChanged = persistedSnapshot.isEmpty() && !currentSnapshot.isEmpty();
			if (snapshotChanged) persistedSnapshot = new HashMap<>(currentSnapshot);
			else if (!persistedSnapshot.equals(currentSnapshot)) {
				for (Entry<String, String> persisted : persistedSnapshot.entrySet()) {
					if (!completed.contains(persisted.getKey())
							&& !persisted.getValue().equals(currentSnapshot.get(persisted.getKey()))) {
						return failedStage(new IllegalStateException(
								"Cannot safely resume because an unfinished legacy reward action changed or disappeared"));
					}
				}
				// The actions are deliberately matched individually below. Retaining the
				// original snapshot lets a reordered, shortened, or extended config skip
				// only the exact effects known to have completed. New actions are added
				// before they can receive a completion marker.
				persistedSnapshot.putAll(currentSnapshot);
				snapshotChanged = true;
			}
			CompletionStage<Void> result = CompletableFuture.completedFuture(null);
			if (snapshotChanged) {
				recordReplayValue(snapshotKey, encodeSnapshot(persistedSnapshot));
				result = checkpoint();
			}
			HashMap<String, String> snapshotLedger = persistedSnapshot;
			for (ReplayAction action : captured) {
				if (completed.contains(action.identity)) continue;
				result = result.thenCompose(ignored -> {
					try {
						CompletionStage<Void> stage = action.action.get();
						if (stage == null) return failedStage(
								new IllegalStateException("Scheduled reward action returned null"));
						return recoverCompletion(stage, failure -> {
							Throwable cause = unwrapCompletionFailure(failure);
							if (!(cause instanceof LegacyActionNotStartedException)) {
								return failedStage(cause);
							}
							// Scheduler rejection, shutdown-before-dispatch, and the bounded
							// pre-dispatch timeout all prove the action never began. Release its
							// reservation so a retry may safely regenerate a random payload.
							snapshotLedger.remove(action.identity);
							recordReplayValue(snapshotKey, encodeSnapshot(snapshotLedger));
							return checkpoint().thenCompose(unused -> failedStage(cause));
						});
					} catch (Throwable failure) {
						return failedStage(failure);
					}
				}).thenCompose(ignored -> {
					if (!action.durable) return CompletableFuture.completedFuture(null);
					completed.add(action.identity);
					recordReplayValue(checkpointKey, encodeCompleted(completed));
					return checkpoint();
				});
			}
			return result;
		}

		private static Throwable unwrapCompletionFailure(Throwable failure) {
			Throwable current = failure;
			while ((current instanceof java.util.concurrent.CompletionException
					|| current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
				current = current.getCause();
			}
			return current;
		}

		private String readReplayValue(String key) {
			return Reward.replayMetadata(placeholders, replayState, key);
		}

		private void recordReplayValue(String key, String value) {
			Reward.recordReplayMetadata(placeholders, replayState, key, value);
		}

		private CompletionStage<Void> checkpoint() {
			if (replayState == null || placeholders == null) return CompletableFuture.completedFuture(null);
			return replayState.persistCheckpointAsync(plugin, placeholders);
		}

		private static HashSet<String> completed(String encoded) {
			HashSet<String> values = new HashSet<>();
			if (encoded == null || encoded.isEmpty()) return values;
			if (!encoded.startsWith(ACTION_COMPLETION_VERSION)) {
				throw new IllegalArgumentException("Unknown legacy action checkpoint version");
			}
			for (String value : encoded.substring(ACTION_COMPLETION_VERSION.length()).split("\\.", -1)) {
				if (value.isEmpty()) continue;
				values.add(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8));
			}
			return values;
		}

		private static String encodeCompleted(HashSet<String> completed) {
			ArrayList<String> values = new ArrayList<>(completed);
			java.util.Collections.sort(values);
			StringBuilder encoded = new StringBuilder(ACTION_COMPLETION_VERSION);
			for (String value : values) {
				if (encoded.length() > ACTION_COMPLETION_VERSION.length()) encoded.append('.');
				encoded.append(Base64.getUrlEncoder().withoutPadding()
						.encodeToString(value.getBytes(StandardCharsets.UTF_8)));
			}
			return encoded.toString();
		}

		private static HashMap<String, String> snapshot(String encoded) {
			HashMap<String, String> values = new HashMap<>();
			if (encoded == null || encoded.isEmpty()) return values;
			if (!encoded.startsWith(ACTION_SNAPSHOT_VERSION)) {
				throw new IllegalArgumentException("Unknown legacy action snapshot version");
			}
			for (String entry : encoded.substring(ACTION_SNAPSHOT_VERSION.length()).split("\\.", -1)) {
				if (entry.isEmpty()) continue;
				String[] pair = entry.split("~", 2);
				if (pair.length != 2 || values.put(new String(Base64.getUrlDecoder().decode(pair[0]), StandardCharsets.UTF_8),
						new String(Base64.getUrlDecoder().decode(pair[1]), StandardCharsets.UTF_8)) != null) {
					throw new IllegalArgumentException("Malformed legacy action snapshot");
				}
			}
			return values;
		}

		private static String encodeSnapshot(HashMap<String, String> snapshot) {
			ArrayList<String> identities = new ArrayList<>(snapshot.keySet());
			java.util.Collections.sort(identities);
			StringBuilder encoded = new StringBuilder(ACTION_SNAPSHOT_VERSION);
			for (String identity : identities) {
				if (encoded.length() > ACTION_SNAPSHOT_VERSION.length()) encoded.append('.');
				encoded.append(Base64.getUrlEncoder().withoutPadding()
						.encodeToString(identity.getBytes(StandardCharsets.UTF_8))).append('~')
						.append(Base64.getUrlEncoder().withoutPadding().encodeToString(
								snapshot.get(identity).getBytes(StandardCharsets.UTF_8)));
			}
			return encoded.toString();
		}

		private static final class ReplayAction {
			private final String identity;
			private final String fingerprint;
			private final Supplier<CompletionStage<Void>> action;
			private final boolean durable;

			private ReplayAction(String identity, String fingerprint, Supplier<CompletionStage<Void>> action,
					boolean durable) {
				this.identity = identity;
				this.fingerprint = fingerprint;
				this.action = action;
				this.durable = durable;
			}
		}
	}
	public static final class AsyncActionContext {
		private final AsyncActionCollection collection;

		private AsyncActionContext(AsyncActionCollection collection) {
			this.collection = collection;
		}

		/** Wraps a CompletionStage callback that has no return value. */
		public Runnable wrap(Runnable callback) {
			if (callback == null) throw new IllegalArgumentException("callback cannot be null");
			return () -> runInScope(() -> {
				callback.run();
				return null;
			});
		}

		/** Wraps a CompletionStage mapping callback while preserving its result. */
		public <T, R> Function<T, R> wrap(Function<T, R> callback) {
			if (callback == null) throw new IllegalArgumentException("callback cannot be null");
			return value -> runInScope(() -> callback.apply(value));
		}

		/** Wraps a deferred supplier used by an asynchronous injector. */
		public <T> Supplier<T> wrap(Supplier<T> callback) {
			if (callback == null) throw new IllegalArgumentException("callback cannot be null");
			return () -> runInScope(callback);
		}

		private <T> T runInScope(Supplier<T> callback) {
			if (collection == null) return callback.get();
			AsyncActionCollection previous = ASYNC_ACTION_COLLECTION.get();
			ASYNC_ACTION_COLLECTION.set(collection);
			try {
				return callback.get();
			} finally {
				if (previous == null) ASYNC_ACTION_COLLECTION.remove();
				else ASYNC_ACTION_COLLECTION.set(previous);
			}
		}
	}

	public AsyncActionCollection beginAsyncActionCollection() {
		return beginAsyncActionCollection(null, null, null);
	}

	public AsyncActionCollection beginAsyncActionCollection(Reward.ReplayState replayState,
			HashMap<String, String> placeholders, String injectionKey) {
		AsyncActionCollection collection = new AsyncActionCollection(ASYNC_ACTION_COLLECTION.get(), this, replayState,
				placeholders, injectionKey, plugin);
		ASYNC_ACTION_COLLECTION.set(collection);
		return collection;
	}

	public AsyncActionContext captureAsyncActionContext() {
		AsyncActionCollection collection = ASYNC_ACTION_COLLECTION.get();
		return new AsyncActionContext(collection != null && collection.belongsTo(this) ? collection : null);
	}

	public void restoreAsyncActionCollectionScope(AsyncActionCollection collection) {
		if (collection == null || ASYNC_ACTION_COLLECTION.get() != collection) return;
		if (collection.previous == null) ASYNC_ACTION_COLLECTION.remove();
		else ASYNC_ACTION_COLLECTION.set(collection.previous);
	}

	public CompletionStage<Void> endAsyncActionCollection(AsyncActionCollection collection) {
		if (collection == null) return CompletableFuture.completedFuture(null);
		restoreAsyncActionCollectionScope(collection);
		return collection.closeAndAwait();
	}

	private boolean collectAsyncAction(CompletionStage<Void> action) {
		return collectAsyncAction(() -> action, "failure:" + action.getClass().getName());
	}

	private boolean collectAsyncAction(Supplier<CompletionStage<Void>> action, String descriptor) {
		AsyncActionCollection collection = ASYNC_ACTION_COLLECTION.get();
		if (collection != null && collection.belongsTo(this) && collection.add(action, descriptor)) return true;
		// Do not infer ownership from another pending collection. A normal synchronous
		// reward can run while an unrelated async injection is waiting; it must retain
		// its established fire-and-forget scheduling semantics instead of being made a
		// dependency of whichever injection completes next on this thread.
		return false;
	}

	private boolean hasOwnedAsyncActionCollection() {
		AsyncActionCollection collection = ASYNC_ACTION_COLLECTION.get();
		return collection != null && collection.belongsTo(this);
	}

	public void claimAsyncContinuationActions(AsyncActionCollection collection) { }

	private void collectAsyncFailure(Throwable failure) {
		collectAsyncAction(failedStage(failure));
	}

	private void scheduleLegacyItemAction(Player player, ItemStack... item) {
		StringBuilder descriptor = new StringBuilder("item");
		for (ItemStack current : item) {
			descriptor.append('\n').append(itemDescriptor(current));
		}
        AsyncActionCollection collection = ASYNC_ACTION_COLLECTION.get();
        com.bencodez.advancedcore.api.item.FullInventoryHandler inventory = collection != null && collection.belongsTo(this)
                ? collection.inventoryOwner : null;
		if (collectAsyncAction(() -> player == null || inventory == null ? failedStage(
				replayActionNotStarted("Player or admitted inventory handler became unavailable before item reward delivery"))
				: inventory.giveItemAsync(player, item), descriptor.toString())) {
			return;
		}
		// Preserve ordinary fire-and-forget behavior without adding an ignored async
		// timeout or a second scheduler boundary. Replay scopes above await the
		// inventory handler's actual owner-task completion directly.
		plugin.getFullInventoryHandler().giveItem(player, item);
	}

	private static String itemDescriptor(ItemStack item) {
		if (item == null) return "null";
		try {
			return canonicalActionDescriptor(item.serialize());
		} catch (Throwable ignored) {
			// Unit-test and early-bootstrap environments can lack Bukkit's unsafe
			// serializer. Retain every independently accessible item property instead
			// of collapsing different metadata to an unstable display string.
			HashMap<String, Object> fallback = new HashMap<>();
			try {
				fallback.put("type", item.getType());
			} catch (Throwable ignoredAgain) { }
			try {
				fallback.put("amount", item.getAmount());
			} catch (Throwable ignoredAgain) { }
			try {
				fallback.put("durability", item.getDurability());
			} catch (Throwable ignoredAgain) { }
			try {
				fallback.put("meta", item.getItemMeta());
			} catch (Throwable ignoredAgain) { }
			fallback.put("class", item.getClass().getName());
			return canonicalActionDescriptor(fallback);
		}
	}

	private static String canonicalActionDescriptor(Object value) {
		if (value == null) return encodedActionPart("null", "");
		if (value instanceof CharSequence || value instanceof Character || value instanceof Boolean
				|| value instanceof Number || value instanceof Enum<?>) {
			return encodedActionPart(value.getClass().getName(), value instanceof Enum<?>
					? ((Enum<?>) value).name() : String.valueOf(value));
		}
		if (value instanceof ConfigurationSerializable) {
			ConfigurationSerializable serializable = (ConfigurationSerializable) value;
			return encodedActionPart("serializable-class", value.getClass().getName())
					+ canonicalActionDescriptor(serializable.serialize());
		}
		if (value instanceof Map<?, ?>) {
			ArrayList<String> entries = new ArrayList<>();
			for (Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
				entries.add(canonicalActionDescriptor(entry.getKey()) + canonicalActionDescriptor(entry.getValue()));
			}
			java.util.Collections.sort(entries);
			return encodedActionParts("map", entries);
		}
		if (value instanceof Iterable<?>) {
			ArrayList<String> entries = new ArrayList<>();
			for (Object entry : (Iterable<?>) value) entries.add(canonicalActionDescriptor(entry));
			return encodedActionParts("list", entries);
		}
		if (value.getClass().isArray()) {
			ArrayList<String> entries = new ArrayList<>();
			for (int index = 0; index < Array.getLength(value); index++) {
				entries.add(canonicalActionDescriptor(Array.get(value, index)));
			}
			return encodedActionParts("array:" + value.getClass().getComponentType().getName(), entries);
		}
		return encodedActionPart("object-class", value.getClass().getName());
	}

	private static String encodedActionParts(String type, Iterable<String> values) {
		StringBuilder encoded = new StringBuilder(encodedActionPart("collection", type));
		for (String value : values) encoded.append(encodedActionPart("entry", value));
		return encoded.toString();
	}

	private static String encodedActionPart(String type, String value) {
		String encodedType = Base64.getUrlEncoder().withoutPadding().encodeToString(type.getBytes(StandardCharsets.UTF_8));
		String encodedValue = Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
		return encodedType.length() + ":" + encodedType + encodedValue.length() + ":" + encodedValue;
	}

    private static <T> CompletableFuture<T> failedStage(Throwable failure) {
        CompletableFuture<T> stage=new CompletableFuture<>();stage.completeExceptionally(failure);return stage;
    }
    private static <T> CompletionStage<T> recoverCompletion(CompletionStage<T> original,Function<Throwable,CompletionStage<T>> recovery) {
        return original.handle((value,failure)->failure==null?CompletableFuture.completedFuture(value):recovery.apply(failure)).thenCompose(stage->stage);
    }


	private void scheduleLegacyRewardAction(Runnable action, Player player, boolean playerAware, String descriptor) {
		scheduleLegacyRewardActionAsync(() -> {
			action.run();
			return CompletableFuture.completedFuture(null);
		}, player, playerAware, descriptor);
	}
	private boolean scheduleOwnedPlayerAction(Player player, Runnable action, String descriptor) {
		if (!hasOwnedAsyncActionCollection()) return false;
		if (player == null) {
			collectAsyncFailure(new IllegalStateException("Player became unavailable before reward delivery"));
			return true;
		}
		if (!plugin.isEnabled()) {
			collectAsyncFailure(new IllegalStateException("Plugin disabled before player reward delivery"));
			return true;
		}
		scheduleLegacyRewardAction(action, player, true, descriptor);
		return true;
	}
	private void validateLiveScheduledPlayer(Player player) {
		if (player == null || player.getUniqueId() == null) {
			throw replayActionNotStarted("Scheduled player reward has no live player identity");
		}
		Player current = Bukkit.getPlayer(player.getUniqueId());
		if (current != player || !current.isOnline()) {
			throw replayActionNotStarted("Player became unavailable before scheduled reward delivery");
		}
	}
    private void scheduleLegacyRewardActionAsync(Supplier<CompletionStage<Void>> action, Player player,
            boolean playerAware, String descriptor) {
        AsyncActionCollection collection=ASYNC_ACTION_COLLECTION.get();
        ServerThreadRewardDispatch owner=collection!=null&&collection.belongsTo(this)?collection.actionOwner:null;
        if(!collectAsyncAction(()->{
            if(owner==null)return failedStage(replayActionNotStarted("Reward action dispatcher is unavailable"));
            java.util.concurrent.atomic.AtomicBoolean started=new java.util.concurrent.atomic.AtomicBoolean();
            CompletionStage<Void> receipt=owner.dispatch(()->{
                if(playerAware)validateLiveScheduledPlayer(player);
                started.set(true);
                return action.get();
            },TimeUnit.SECONDS.toMillis(30));
            return recoverCompletion(receipt,failure->failedStage(started.get()?failure:
                    replayActionNotStarted("Scheduled reward action did not begin",failure)));
        },descriptor)) {
            Runnable dispatch=()->{
                try {
                    CompletionStage<Void> stage=action.get();
                    if(stage==null)throw new IllegalStateException("Scheduled reward action returned null");
                }catch(Throwable failure) {
                    if(failure instanceof RuntimeException)throw (RuntimeException)failure;
                    if(failure instanceof Error)throw (Error)failure;
                    throw new IllegalStateException("Scheduled reward action failed",failure);
                }
            };
            if(playerAware)getPlugin().getBukkitScheduler().runTask(plugin,dispatch,player);
            else getPlugin().getBukkitScheduler().runTask(plugin,dispatch);
        }
    }

	/** Signals a replay-aware legacy action that was conclusively never started. */
	private static final class LegacyActionNotStartedException extends IllegalStateException {
		private static final long serialVersionUID = 1L;

		private LegacyActionNotStartedException(String message) {
			super(message);
		}

		private LegacyActionNotStartedException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/** Internal cross-package signal for a replay action proven not to have begun. */
	public static RuntimeException replayActionNotStarted(String message) {
		return new LegacyActionNotStartedException(message);
	}

	/** Internal cross-package signal for scheduler rejection before a replay action began. */
	public static RuntimeException replayActionNotStarted(String message, Throwable cause) {
		return new LegacyActionNotStartedException(message, cause);
	}

	/** Returns whether a completion failed before its replay-aware action began. */
	public static boolean isReplayActionNotStarted(Throwable failure) {
		Throwable current = failure;
		while ((current instanceof java.util.concurrent.CompletionException || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null && current.getCause() != current) current = current.getCause();
		return current instanceof LegacyActionNotStartedException;
	}


	@Getter
	private boolean cacheData = true;

	@Setter
	private UserData data;

	private boolean loadName = true;

	/** The player name. */
	private String playerName;

	@Getter
	private AdvancedCorePlugin plugin = null;

	@Getter
	private boolean tempCache = false;

	/** The uuid. */
	private String uuid;

	@Getter
	private boolean waitForCache = true;

	public AdvancedCoreUser(AdvancedCorePlugin plugin, AdvancedCoreUser user) {
		this.waitForCache = user.waitForCache;
		this.cacheData = user.cacheData;
		this.tempCache = user.tempCache;
		this.data = user.getUserData();
		this.uuid = user.getUUID();
		this.playerName = user.getPlayerName();
		this.loadName = user.loadName;
		this.plugin = plugin;
		loadData();
		getUserData().setTempCache(user.getUserData().getTempCache());
	}

	/**
	 * Instantiates a new user.
	 *
	 * @param plugin the plugin
	 * @param player the player
	 */
	@Deprecated
	public AdvancedCoreUser(AdvancedCorePlugin plugin, Player player) {
		this.plugin = plugin;
		loadData();
		uuid = player.getUniqueId().toString();
		setPlayerName(player.getName());
	}

	/**
	 * Instantiates a new user.
	 *
	 * @param plugin     the plugin
	 * @param playerName the player name
	 */
	@Deprecated
	public AdvancedCoreUser(AdvancedCorePlugin plugin, String playerName) {
		this.plugin = plugin;
		loadData();
		uuid = PlayerManager.getInstance().getUUID(playerName);
		setPlayerName(playerName);
	}

	/**
	 * Instantiates a new user.
	 *
	 * @param plugin the plugin
	 * @param uuid   the uuid
	 */
	@Deprecated
	public AdvancedCoreUser(AdvancedCorePlugin plugin, UUID uuid) {
		this.plugin = plugin;
		this.uuid = uuid.toString();
		loadData();
		setPlayerName(PlayerManager.getInstance().getPlayerName(this, this.uuid, false));
	}

	/**
	 * Instantiates a new user.
	 *
	 * @param plugin   the plugin
	 * @param uuid     the uuid
	 * @param loadName the load name
	 */
	@Deprecated
	public AdvancedCoreUser(AdvancedCorePlugin plugin, UUID uuid, boolean loadName) {
		this.plugin = plugin;
		this.uuid = uuid.toString();
		this.loadName = loadName;
		loadData();
		if (this.loadName) {
			setPlayerName(PlayerManager.getInstance().getPlayerName(this, this.uuid));
		}

	}

	@Deprecated
	public AdvancedCoreUser(AdvancedCorePlugin plugin, UUID uuid, boolean loadName, boolean loadData) {
		this.plugin = plugin;
		this.uuid = uuid.toString();
		this.loadName = loadName;
		if (loadData) {
			loadData();
		}
		if (this.loadName) {
			setPlayerName(PlayerManager.getInstance().getPlayerName(this, this.uuid));
		}

	}

	@Deprecated
	public AdvancedCoreUser(AdvancedCorePlugin plugin, UUID uuid, String playerName) {
		this.plugin = plugin;
		this.uuid = uuid.toString();
		if (!plugin.getOptions().isOnlineMode()) {
			this.uuid = PlayerManager.getInstance().getUUID(playerName);
		}
		loadData();
		setPlayerName(playerName);
	}

    public void addOfflineRewards(Reward reward,HashMap<String,String> placeholders) {
        addOfflineRewards(reward,placeholders,null);
    }

    /** Retains the admitted occurrence and completed replay prefix when deferring a public reward. */
    public void addOfflineRewards(Reward reward,HashMap<String,String> placeholders,RewardOptions options) {
        RewardOptions captured=Reward.snapshotReplayOptionsForQueue(options);
        HashMap<String,String> saved=placeholders==null?new HashMap<>():new HashMap<>(placeholders);
        if(captured!=null)saved.putAll(captured.getPlaceholders());
        final String entry=queuedRewardReference(reward,captured)+"%placeholders%"+ArrayUtils.makeString(saved);
        if(Bukkit.isPrimaryThread()) {
            ServerThreadRewardDispatch owner=plugin.getRewardDispatch();
            if(owner==null)throw new IllegalStateException("Reward dispatcher unavailable for queue admission");
            owner.dispatchOffPrimary(()->{mutateOfflineQueue(pending->appendOfflineEntry(pending,entry));return CompletableFuture.<Void>completedFuture(null);},TimeUnit.SECONDS.toMillis(30))
                .whenComplete((ignored,failure)->{if(failure!=null)plugin.getLogger().warning("Offline reward queue admission failed: "+failure.getMessage());});
        }else mutateOfflineQueue(pending->appendOfflineEntry(pending,entry));
    }

    /** Preserve legacy oldest-first trimming, while retaining all admitted occurrences. */
    private ArrayList<String> appendOfflineEntry(ArrayList<String> pending,String added) {
        pending.add(added);
        while(String.join("%line%",pending).getBytes(StandardCharsets.UTF_8).length>65535) {
            int removable=-1;
            synchronized(REPLAY_CLAIMS_LOCK) {
                HashMap<String,PersistedReplayClaims> users=REPLAY_CLAIMS.get(plugin);
                PersistedReplayClaims claims=users==null?null:users.get(getUUID());
                for(int i=0;i<pending.size()-1;i++) {
                    String candidate=pending.get(i),id=occurrenceId(candidate);
                    boolean active=claims!=null && (id==null?claims.legacy.getOrDefault(candidate,0)>0:claims.occurrences.contains(id));
                    if(!active){removable=i;break;}
                }
            }
            if(removable<0)throw new IllegalStateException("Offline queue capacity cannot discard an admitted occurrence");
            pending.remove(removable);
        }
        return pending;
    }

	private String queuedRewardReference(Reward reward) {
		return queuedRewardReference(reward, null);
	}

	private String queuedRewardReference(Reward reward, RewardOptions options) {
		String encodedName = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(reward.getRewardName().getBytes(StandardCharsets.UTF_8));
		String reference = QUEUED_REFERENCE_PREFIX + (reward.isGeneratedSnapshotCreated() ? "snapshot/" : "normal/")
				+ encodedName + ASYNC_OCCURRENCE_DELIMITER
				+ (options == null || options.getAsyncReplayOccurrenceId() == null
						|| options.getAsyncReplayOccurrenceId().isEmpty() ? UUID.randomUUID()
							: options.getAsyncReplayOccurrenceId());
		if (options != null) {
			String serialized = encodeAsyncReplayProgress(options.getAsyncReplayProgress(),
					options.getAsyncReplayRegistryFingerprints());
			if (!serialized.isEmpty()) reference += ASYNC_PROGRESS_DELIMITER + serialized;
			else if (options.getCompletedAsyncInjections() > 0) {
				reference += ASYNC_PROGRESS_DELIMITER + options.getCompletedAsyncInjections();
			}
		}
		return reference;
	}

	private static QueuedReplay parseQueuedReplay(String storedReference) {
		String occurrenceId = occurrenceId(storedReference);
		String withoutOccurrence = stripAsyncOccurrenceMarker(storedReference);
		int marker = withoutOccurrence.lastIndexOf(ASYNC_PROGRESS_DELIMITER);
		if (marker < 0) return new QueuedReplay(withoutOccurrence, 0, new HashMap<>(), new HashMap<>(), false,
				occurrenceId);
		String value = withoutOccurrence.substring(marker + ASYNC_PROGRESS_DELIMITER.length());
		if (value.startsWith("v3-")) {
			try {
				HashMap<String, Integer> progress = new HashMap<>();
				HashMap<String, String> fingerprints = new HashMap<>();
				String decoded = new String(Base64.getUrlDecoder().decode(value.substring(3)), StandardCharsets.UTF_8);
				for (String line : decoded.split("\\n")) {
					String[] pair = line.split("\\t", 3);
					if (pair.length != 3) throw new IllegalArgumentException("Malformed replay checkpoint");
					String key=new String(Base64.getUrlDecoder().decode(pair[0]),StandardCharsets.UTF_8);
                    int count=Integer.parseInt(pair[1]);
                    if(count<0 || progress.containsKey(key) || pair[2].isEmpty())throw new IllegalArgumentException("Invalid replay checkpoint entry");
                    progress.put(key,count);
					fingerprints.put(new String(Base64.getUrlDecoder().decode(pair[0]), StandardCharsets.UTF_8), pair[2]);
				}
				return new QueuedReplay(withoutOccurrence.substring(0, marker), 0, progress, fingerprints, false,
						occurrenceId);
			} catch (IllegalArgumentException ignored) {
				throw new IllegalArgumentException("Malformed persisted replay checkpoint", ignored);
			}
		}
		if (value.startsWith("v2-")) {
			try {
				HashMap<String, Integer> progress = new HashMap<>();
				String decoded = new String(Base64.getUrlDecoder().decode(value.substring(3)), StandardCharsets.UTF_8);
				for (String line : decoded.split("\\n")) {
					String[] pair = line.split("\\t", 2);
					if(pair.length!=2)throw new IllegalArgumentException("Malformed legacy replay checkpoint");
                    int count=Integer.parseInt(pair[1]);
                    if(count<0 || progress.containsKey(pair[0]))throw new IllegalArgumentException("Invalid legacy replay checkpoint entry");
                    progress.put(pair[0],count);
				}
				return new QueuedReplay(withoutOccurrence.substring(0, marker), 0, progress, new HashMap<>(), true,
						occurrenceId);
			} catch (IllegalArgumentException ignored) {
				throw new IllegalArgumentException("Malformed persisted replay checkpoint", ignored);
			}
		}
		try {
			int progress = Integer.parseInt(value);
            if(progress<0)throw new IllegalArgumentException("Negative replay checkpoint");
			return progress > 0 ? new QueuedReplay(withoutOccurrence.substring(0, marker), progress, new HashMap<>(), new HashMap<>(), true,
						occurrenceId)
					: new QueuedReplay(withoutOccurrence.substring(0, marker), 0, new HashMap<>(), new HashMap<>(), false,
							occurrenceId);
		} catch (NumberFormatException ignored) {
			// Malformed protected progress cannot be treated as a fresh occurrence.
			throw new IllegalArgumentException("Malformed persisted replay checkpoint", ignored);
		}
	}

	private static String occurrenceId(String storedReference) {
        int placeholders=storedReference.indexOf("%placeholders%");
        if(placeholders>=0)storedReference=storedReference.substring(0,placeholders);
		int marker = storedReference.indexOf(ASYNC_OCCURRENCE_DELIMITER);
		if (marker < 0) return null;
		int start = marker + ASYNC_OCCURRENCE_DELIMITER.length();
		int end = storedReference.indexOf('%', start);
		String candidate = storedReference.substring(start, end < 0 ? storedReference.length() : end);
		try {
			return UUID.fromString(candidate).toString();
		} catch (IllegalArgumentException ignored) {
			throw new IllegalArgumentException("Malformed persisted replay occurrence", ignored);
		}
	}

	private static String stripAsyncOccurrenceMarker(String storedReference) {
		int marker = storedReference.indexOf(ASYNC_OCCURRENCE_DELIMITER);
		if (marker < 0) return storedReference;
		int start = marker + ASYNC_OCCURRENCE_DELIMITER.length();
		int end = storedReference.indexOf('%', start);
		return storedReference.substring(0, marker) + (end < 0 ? "" : storedReference.substring(end));
	}

	private static String queuedReference(QueuedReplay replay) {
		return replay.rewardReference + (replay.asyncReplayOccurrenceId == null ? ""
				: ASYNC_OCCURRENCE_DELIMITER + replay.asyncReplayOccurrenceId);
	}

	private static String withAsyncOccurrence(String rewardEntry, String occurrenceId) {
		int placeholders = rewardEntry.indexOf("%placeholders%");
		String reference = placeholders < 0 ? rewardEntry : rewardEntry.substring(0, placeholders);
		String suffix = placeholders < 0 ? "" : rewardEntry.substring(placeholders);
		return stripAsyncOccurrenceMarker(reference) + ASYNC_OCCURRENCE_DELIMITER + occurrenceId + suffix;
	}

	private static String encodeAsyncReplayProgress(Map<String, Integer> progress) {
		if (progress.isEmpty()) return "";
		StringBuilder encoded = new StringBuilder();
		for (Entry<String, Integer> entry : progress.entrySet()) {
			if (entry.getValue() != null && entry.getValue() > 0) {
				encoded.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
			}
		}
		return encoded.length() == 0 ? "" : "v2-" + Base64.getUrlEncoder().withoutPadding()
				.encodeToString(encoded.toString().getBytes(StandardCharsets.UTF_8));
	}

	private static String encodeAsyncReplayProgress(Map<String, Integer> progress, Map<String, String> fingerprints) {
		if (fingerprints.isEmpty()) return encodeAsyncReplayProgress(progress);
		StringBuilder encoded = new StringBuilder();
		for (Entry<String, Integer> entry : progress.entrySet()) {
			String fingerprint = fingerprints.get(entry.getKey());
			if (entry.getValue() != null && entry.getValue() > 0 && fingerprint == null) {
				return encodeAsyncReplayProgress(progress);
			}
		}
		for (Entry<String, String> entry : fingerprints.entrySet()) {
			if (entry.getValue() == null) continue;
			encoded.append(Base64.getUrlEncoder().withoutPadding()
						.encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8))).append('\t')
						.append(progress.getOrDefault(entry.getKey(), 0)).append('\t').append(entry.getValue()).append('\n');
		}
		return encoded.length() == 0 ? encodeAsyncReplayProgress(progress) : "v3-"
				+ Base64.getUrlEncoder().withoutPadding().encodeToString(encoded.toString().getBytes(StandardCharsets.UTF_8));
	}

    private static String stripTimedExecutionMarker(String storedReference) {
        int placeholders=storedReference.indexOf("%placeholders%");
        String suffix=placeholders<0?"":storedReference.substring(placeholders);
        String reference=placeholders<0?storedReference:storedReference.substring(0,placeholders);
        int marker=reference.indexOf("%extime%");
        if(marker>=0){int next=reference.indexOf('%',marker+"%extime%".length());reference=reference.substring(0,marker)+(next<0?"":reference.substring(next));}
        return stripAsyncRetryMarker(reference)+suffix;
    }

	private static String stripAsyncRetryMarker(String storedReference) {
		int marker = storedReference.indexOf(ASYNC_RETRY_DELIMITER);
		if (marker < 0) return storedReference;
		int valueStart = marker + ASYNC_RETRY_DELIMITER.length();
		int nextMarker = storedReference.indexOf('%', valueStart);
		return storedReference.substring(0, marker) + (nextMarker < 0 ? "" : storedReference.substring(nextMarker));
	}

    private static int asyncRetryCount(String rewardEntry) {
        int placeholders=rewardEntry.indexOf("%placeholders%");
        if(placeholders>=0)rewardEntry=rewardEntry.substring(0,placeholders);
        int marker=rewardEntry.indexOf(ASYNC_RETRY_DELIMITER);if(marker<0)return 0;
        int start=marker+ASYNC_RETRY_DELIMITER.length(),end=rewardEntry.indexOf('%',start);
        int count=Integer.parseInt(rewardEntry.substring(start,end<0?rewardEntry.length():end));
        if(count<0 || count>8)throw new IllegalArgumentException("Invalid timed retry count");return count;
    }

	private static String withAsyncRetryCount(String rewardEntry, int count) {
		int placeholders = rewardEntry.indexOf("%placeholders%");
		String reference = placeholders < 0 ? rewardEntry : rewardEntry.substring(0, placeholders);
		String suffix = placeholders < 0 ? "" : rewardEntry.substring(placeholders);
		return stripAsyncRetryMarker(reference) + ASYNC_RETRY_DELIMITER + count + suffix;
	}

	private static String withAsyncReplayProgress(String rewardEntry, Throwable failure) {
		Reward.RewardReplayFailure replayFailure = replayFailure(failure);
		int completed = completedAsyncInjections(failure);
		String serializedProgress = replayFailure == null ? "" : encodeAsyncReplayProgress(replayFailure.getReplayProgress(),
				replayFailure.getReplayRegistryFingerprints());
		if (completed <= 0 && serializedProgress.isEmpty()) return rewardEntry;
		int placeholders = rewardEntry.indexOf("%placeholders%");
		String storedReference = placeholders < 0 ? rewardEntry : rewardEntry.substring(0, placeholders);
		String suffix = placeholders < 0 ? "" : rewardEntry.substring(placeholders);
		if (replayFailure != null) suffix = "%placeholders%" + ArrayUtils.makeString(replayFailure.getReplayPlaceholders());
		QueuedReplay queuedReplay = parseQueuedReplay(stripAsyncRetryMarker(storedReference));
		if (!serializedProgress.isEmpty()) return queuedReference(queuedReplay) + ASYNC_PROGRESS_DELIMITER
				+ serializedProgress + suffix;
		return queuedReference(queuedReplay) + ASYNC_PROGRESS_DELIMITER
				+ Math.max(queuedReplay.completedAsyncInjections, completed) + suffix;
	}

	private static String withAsyncReplayProgress(String rewardEntry, Reward.ReplayCheckpoint checkpoint) {
		int marker = rewardEntry.indexOf("%placeholders%");
		String reference = marker < 0 ? rewardEntry : rewardEntry.substring(0, marker);
		QueuedReplay queuedReplay = parseQueuedReplay(stripAsyncRetryMarker(reference));
		String serialized = encodeAsyncReplayProgress(checkpoint.getReplayProgress(), checkpoint.getReplayRegistryFingerprints());
		return queuedReference(queuedReplay) + (serialized.isEmpty() ? "" : ASYNC_PROGRESS_DELIMITER + serialized)
				+ "%placeholders%" + ArrayUtils.makeString(checkpoint.getPlaceholders());
	}

	private static int completedAsyncInjections(Throwable failure) {
		Throwable current = failure;
		while (current != null) {
			if (current instanceof Reward.RewardReplayFailure) {
				return ((Reward.RewardReplayFailure) current).getCompletedInjectionCount();
			}
			current = current.getCause();
		}
		return 0;
	}

	private static Reward.RewardReplayFailure replayFailure(Throwable failure) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current instanceof Reward.RewardReplayFailure) return (Reward.RewardReplayFailure) current;
		}
		return null;
	}

	private static final class QueuedReplay {
		private final String rewardReference;
		private final int completedAsyncInjections;
		private final Map<String, Integer> asyncReplayProgress;
		private final Map<String, String> asyncReplayRegistryFingerprints;
		private final boolean legacyAsyncReplayCheckpoint;
		private final String asyncReplayOccurrenceId;

		private QueuedReplay(String rewardReference, int completedAsyncInjections,
				Map<String, Integer> asyncReplayProgress, Map<String, String> asyncReplayRegistryFingerprints,
				boolean legacyAsyncReplayCheckpoint, String asyncReplayOccurrenceId) {
			this.rewardReference = rewardReference;
			this.completedAsyncInjections = completedAsyncInjections;
			this.asyncReplayProgress = asyncReplayProgress;
			this.asyncReplayRegistryFingerprints = asyncReplayRegistryFingerprints;
			this.legacyAsyncReplayCheckpoint = legacyAsyncReplayCheckpoint;
			this.asyncReplayOccurrenceId = asyncReplayOccurrenceId;
		}
	}

	public void addPermission(String permission) {
		plugin.getPermissionHandler().addPermission(getPlayer(), permission);
	}

	public void addPermission(String permission, long delay) {
		Player player = getPlayer();
		if (scheduleOwnedPlayerAction(player,
				() -> plugin.getPermissionHandler().addPermission(player, permission, delay),
				"temporary-permission:" + permission + ":" + delay)) return;
		plugin.getPermissionHandler().addPermission(player, permission, delay);
	}

    public void addTimedReward(Reward reward,HashMap<String,String> placeholders,long epochMilli) {
        if(epochMilli<0)throw new IllegalArgumentException("Negative timed reward date");
        final String key=queuedRewardReference(reward)+"%extime%"+System.currentTimeMillis()+"%placeholders%"+ArrayUtils.makeString(placeholders);
        Runnable append=()->{mutateTimedQueue(pending->{pending.add(encodeTimedEntry(key,epochMilli));return pending;});loadTimedDelayedTimer(epochMilli);};
        if(Bukkit.isPrimaryThread()) {
            ServerThreadRewardDispatch owner=plugin.getRewardDispatch();
            if(owner==null)throw new IllegalStateException("Reward dispatcher unavailable for timed admission");
            owner.dispatchOffPrimary(()->{append.run();return CompletableFuture.<Void>completedFuture(null);},TimeUnit.SECONDS.toMillis(30))
                .whenComplete((ignored,failure)->{if(failure!=null)plugin.getLogger().warning("Timed reward admission failed: "+failure.getMessage());});
        }else append.run();
    }


	public void addUnClaimedChoiceReward(String name) {
		ArrayList<String> choices = getUnClaimedChoices();
		choices.add(name);
		setUnClaimedChoice(choices);
	}

	public void cache() {
		plugin.getUserManager().getDataManager().cacheUser(UUID.fromString(uuid), getPlayerName());
	}

	public void cacheAsync() {
		getPlugin().getBukkitScheduler().runTaskAsynchronously(plugin, new Runnable() {

			@Override
			public void run() {
				cache();
			}
		});
	}

	public AdvancedCoreUser cacheData() {
		cacheData = true;
		return this;
	}

	public void cacheIfNeeded() {
		plugin.getUserManager().getDataManager().cacheUserIfNeeded(UUID.fromString(uuid));
	}

    public void checkDelayedTimedRewards() {
        checkDelayedTimedRewardsAsync().whenComplete((ignored,failure)->{
            if(failure!=null)plugin.getLogger().warning("Timed reward replay remains pending: "+failure.getMessage());
        });
    }

    public CompletionStage<Void> checkDelayedTimedRewardsAsync() {
        if(!plugin.getOptions().isProcessRewards())return CompletableFuture.completedFuture(null);
        Reward.ReplayState runtime=Reward.replayStateFor(new RewardOptions());runtime.captureRuntime(plugin);
        ServerThreadRewardDispatch owner=runtime.getActionDispatchOwner();
        if(owner==null)return failedStage(new IllegalStateException("Reward dispatcher unavailable"));
        return owner.dispatchOffPrimary(()->dispatchTimedQueue(owner,runtime),TimeUnit.SECONDS.toMillis(30));
    }

    /** One captured storage publication; retries never invoke a reward handler. */
    private final class QueuePublication {
        private final PersistedReplayClaims claims;
        private final ServerThreadRewardDispatch owner;
        private final Runnable edit;
        private final java.util.concurrent.ScheduledExecutorService timer;
        private final CompletableFuture<Void> result=new CompletableFuture<Void>() {
            @Override public boolean cancel(boolean interrupt){return false;}
        };
        private boolean running,retired,finished,acknowledged;
        private int failures;
        private Object retryToken;
        private java.util.concurrent.ScheduledFuture<?> retryFuture;
        QueuePublication(PersistedReplayClaims claims,ServerThreadRewardDispatch owner,Runnable edit) {
            this.claims=claims;this.owner=owner;this.edit=edit;
            this.timer=plugin.getRewardHandler().getDelayedTimer();
        }
        void start() {
            boolean reject;
            synchronized(this){reject=retired || finished; if(!reject){running=true;retryToken=null;retryFuture=null;}}
            if(reject){finish(new IllegalStateException("Queue publication runtime retired"));return;}
            owner.dispatchOffPrimary(()->{edit.run();return CompletableFuture.<Void>completedFuture(null);},TimeUnit.SECONDS.toMillis(30))
                .whenComplete((unused,failure)->settled(failure));
        }
        private void settled(Throwable failure) {
            Object token=null;long delay=0;
            synchronized(this) {
                running=false;
                if(failure==null)acknowledged=true;
                if(failure!=null && !retired && !finished && isTimedStorageFailure(failure) && timer!=null && !timer.isShutdown()) {
                    failures=Math.min(8,failures+1);token=new Object();retryToken=token;
                    delay=Math.min(TimeUnit.MINUTES.toMillis(5),TimeUnit.SECONDS.toMillis(1L<<failures));
                }
            }
            if(token==null){finish(failure);return;}
            if(failures==1)plugin.getLogger().warning("Queued reward publication is pending; retrying storage acknowledgement");
            final Object captured=token;
            try {
                java.util.concurrent.ScheduledFuture<?> future=timer.schedule(()->{
                    synchronized(this){if(finished || retired || retryToken!=captured)return;}
                    start();
                },delay,TimeUnit.MILLISECONDS);
                boolean stale;
                synchronized(this){stale=finished || retired || retryToken!=captured;if(!stale)retryFuture=future;}
                if(stale)future.cancel(false);
            }catch(RuntimeException rejected){finish(rejected);}
        }
        void retire() {
            java.util.concurrent.ScheduledFuture<?> future;boolean complete;
            synchronized(this){retired=true;future=retryFuture;retryFuture=null;retryToken=null;complete=!running;}
            if(future!=null)future.cancel(false);
            if(complete)finish(new IllegalStateException("Queue publication retired before acknowledgement"));
        }
        private void finish(Throwable failure) {
            java.util.concurrent.ScheduledFuture<?> future;
            synchronized(this){if(finished)return;finished=true;retryToken=null;future=retryFuture;retryFuture=null;if(acknowledged)failure=null;}
            if(future!=null)future.cancel(false);
            synchronized(REPLAY_CLAIMS_LOCK){if(claims.publication==this)claims.publication=null;}
            if(failure==null)result.complete(null);else result.completeExceptionally(failure);
        }
    }

    private CompletionStage<Void> publishQueueEdit(ServerThreadRewardDispatch owner,PersistedReplayClaims claims,Runnable edit) {
        QueuePublication publication=new QueuePublication(claims,owner,edit);
        synchronized(REPLAY_CLAIMS_LOCK) {
            if(claims.publication!=null)throw new IllegalStateException("Queued publication owner already active");
            claims.publication=publication;
        }
        publication.start();return publication.result;
    }

    /** Admission is already closed; wait for a running physical write, cancel only queued retries. */
    public static void retireQueuePublications(AdvancedCorePlugin plugin) {
        ArrayList<QueuePublication> publications=new ArrayList<>();
        synchronized(REPLAY_CLAIMS_LOCK) {
            HashMap<String,PersistedReplayClaims> users=REPLAY_CLAIMS.get(plugin);
            if(users!=null)for(PersistedReplayClaims claims:users.values())if(claims.publication!=null)publications.add(claims.publication);
        }
        for(QueuePublication publication:publications)publication.retire();
    }

    private static final class TimedStorageWakeup {
        volatile java.util.concurrent.ScheduledFuture<?> future;
        final ServerThreadRewardDispatch owner;
        TimedStorageWakeup(ServerThreadRewardDispatch owner){this.owner=owner;}
    }

    private static boolean isTimedStorageFailure(Throwable failure) {
        for(int depth=0;failure!=null && depth<32;depth++,failure=failure.getCause())
            if(failure instanceof java.sql.SQLException || failure instanceof java.io.IOException)return true;
        return false;
    }

    /** Retry only before effects: post-effect failures require recovery of their retained progress. */
    private void requestTimedStorageWakeup(ServerThreadRewardDispatch owner,Reward.ReplayState runtime) {
        if(!plugin.isEnabled() || plugin.getRewardDispatch()!=owner)return;
        java.util.concurrent.ScheduledExecutorService timer=plugin.getRewardHandler().getDelayedTimer();
        if(timer==null || timer.isShutdown())return;
        PersistedReplayClaims claims;TimedStorageWakeup wakeup=new TimedStorageWakeup(owner);TimedStorageWakeup predecessor;long delay;
        synchronized(REPLAY_CLAIMS_LOCK) {
            claims=REPLAY_CLAIMS.computeIfAbsent(plugin,unused->new HashMap<>()).computeIfAbsent(getUUID(),unused->new PersistedReplayClaims());
            predecessor=claims.timedStorageWakeup;
            if(predecessor!=null && predecessor.owner==owner)return;
            claims.timedStorageWakeup=wakeup;
            claims.timedStorageFailures=Math.min(8,claims.timedStorageFailures+1);
            delay=Math.min(TimeUnit.MINUTES.toMillis(5),TimeUnit.SECONDS.toMillis(1L<<claims.timedStorageFailures));
        }
        if(predecessor!=null && predecessor.future!=null)predecessor.future.cancel(false);
        final PersistedReplayClaims captured=claims;
        try {
            java.util.concurrent.ScheduledFuture<?> future=timer.schedule(()->{
                synchronized(REPLAY_CLAIMS_LOCK) {
                    if(captured.timedStorageWakeup!=wakeup)return;
                    captured.timedStorageWakeup=null;
                }
                if(!plugin.isEnabled() || plugin.getRewardDispatch()!=owner){releaseTimedWakeupOwner(captured);return;}
                owner.dispatchOffPrimary(()->dispatchTimedQueue(owner,runtime),TimeUnit.SECONDS.toMillis(30))
                    .whenComplete((unused,failure)->{
                        if(failure!=null)plugin.getLogger().warning("Timed storage recovery remains pending: "+failure.getMessage());
                        releaseTimedWakeupOwner(captured);
                    });
            },delay,TimeUnit.MILLISECONDS);
            boolean stale;
            synchronized(REPLAY_CLAIMS_LOCK){wakeup.future=future;stale=captured.timedStorageWakeup!=wakeup;}
            if(stale)future.cancel(false);
        }catch(RuntimeException rejected) {
            synchronized(REPLAY_CLAIMS_LOCK){if(captured.timedStorageWakeup==wakeup)captured.timedStorageWakeup=null;}
            releaseTimedWakeupOwner(captured);
            plugin.getLogger().warning("Timed storage recovery timer unavailable: "+rejected.getMessage());
        }
    }

    /** Called after timer admission closes; a racing future publication observes the removed token. */
    public static void cancelTimedStorageWakeups(AdvancedCorePlugin plugin) {
        ArrayList<java.util.concurrent.ScheduledFuture<?>> futures=new ArrayList<>();
        synchronized(REPLAY_CLAIMS_LOCK) {
            HashMap<String,PersistedReplayClaims> users=REPLAY_CLAIMS.get(plugin);
            if(users==null)return;
            java.util.Iterator<PersistedReplayClaims> entries=users.values().iterator();
            while(entries.hasNext()) {
                PersistedReplayClaims claims=entries.next();TimedStorageWakeup wakeup=claims.timedStorageWakeup;
                claims.timedStorageWakeup=null;
                if(wakeup!=null && wakeup.future!=null)futures.add(wakeup.future);
                if(claims.occurrences.isEmpty() && claims.tail.isDone() && claims.publication==null)entries.remove();
            }
            if(users.isEmpty())REPLAY_CLAIMS.remove(plugin);
        }
        for(java.util.concurrent.ScheduledFuture<?> future:futures)future.cancel(false);
    }

    private void releaseTimedWakeupOwner(PersistedReplayClaims captured) {
        synchronized(REPLAY_CLAIMS_LOCK) {
            HashMap<String,PersistedReplayClaims> users=REPLAY_CLAIMS.get(plugin);
            if(captured.occurrences.isEmpty() && captured.tail.isDone() && captured.timedStorageWakeup==null && captured.publication==null && users!=null && users.get(getUUID())==captured){users.remove(getUUID());if(users.isEmpty())REPLAY_CLAIMS.remove(plugin);}
        }
    }

    private CompletionStage<Void> dispatchTimedQueue(ServerThreadRewardDispatch owner,Reward.ReplayState runtime) {
        if(!plugin.getOptions().isProcessRewards())return CompletableFuture.completedFuture(null);
        ArrayList<String> snapshot;
        try{snapshot=getUserData().getStringListStrict("TimedRewards");}
        catch(RuntimeException failure){if(isTimedStorageFailure(failure))requestTimedStorageWakeup(owner,runtime);throw failure;}
        HashSet<String> observed=new HashSet<>(),keys=new HashSet<>();
        for(String stored:snapshot){TimedQueueEntry entry=decodeTimedEntry(stored);if(!keys.add(entry.key))throw new IllegalStateException("Duplicate timed reward key");String id=occurrenceId(entry.key);if(id!=null && !observed.add(id))throw new IllegalStateException("Duplicate timed reward occurrence");}
        ArrayList<CompletableFuture<Void>> outcomes=new ArrayList<>();
        for(String stored:snapshot) {
            TimedQueueEntry entry=decodeTimedEntry(stored);
            if(entry.time==0 || entry.time>=System.currentTimeMillis())continue;
            String existing=occurrenceId(entry.key);final String id=existing==null?UUID.randomUUID().toString():existing;
            PersistedReplayClaims claims;CompletableFuture<Void> previous,tail=new CompletableFuture<>();
            synchronized(REPLAY_CLAIMS_LOCK) {
                claims=REPLAY_CLAIMS.computeIfAbsent(plugin,unused->new HashMap<>()).computeIfAbsent(getUUID(),unused->new PersistedReplayClaims());
                if(claims.occurrences.contains(id))continue;
                if(existing==null){int count=0;for(String pending:snapshot)if(stored.equals(pending))count++;int active=claims.legacy.getOrDefault(stored,0);if(active>=count)continue;claims.legacy.put(stored,active+1);}
                claims.occurrences.add(id);previous=claims.tail;claims.tail=tail;
            }
            final PersistedReplayClaims captured=claims;
            java.util.concurrent.atomic.AtomicReference<String> current=new java.util.concurrent.atomic.AtomicReference<>(stored);
            java.util.concurrent.atomic.AtomicBoolean effectsStarted=new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicBoolean retryPublished=new java.util.concurrent.atomic.AtomicBoolean();
            CompletableFuture<Void> outcome=new CompletableFuture<Void>() {@Override public boolean cancel(boolean interrupt){return false;}};outcomes.add(outcome);
            previous.whenComplete((unused,priorFailure)->{
                CompletionStage<Void> replay=owner.dispatchOffPrimary(()->{
                    String key=existing==null?withAsyncOccurrence(entry.key,id):entry.key;
                    String admitted=encodeTimedEntry(key,entry.time);
                    mutateTimedQueue(pending->{int index=pending.indexOf(stored);if(index<0)throw new IllegalStateException("Timed occurrence disappeared before admission");pending.set(index,admitted);return pending;});current.set(admitted);
                    asyncRetryCount(key);
                    String[] parts=key.split("%placeholders%",2);QueuedReplay metadata=parseQueuedReplay(stripTimedExecutionMarker(parts[0]));
                    RewardOptions options=new RewardOptions().setCheckTimed(false).withPlaceHolder(ArrayUtils.fromString(parts.length>1?parts[1]:""));
                    options.addPlaceholder("date",new SimpleDateFormat("EEE, d MMM yyyy HH:mm").format(new Date(entry.time)));
                    options.setCompletedAsyncInjections(metadata.completedAsyncInjections);options.setAsyncReplayProgress(metadata.asyncReplayProgress);options.setAsyncReplayRegistryFingerprints(metadata.asyncReplayRegistryFingerprints);options.setLegacyAsyncReplayCheckpoint(metadata.legacyAsyncReplayCheckpoint);options.setAsyncReplayOccurrenceId(id);
                    options.setAsyncReplayCheckpointConsumer(checkpoint->{String before=current.get();TimedQueueEntry pendingEntry=decodeTimedEntry(before);String updated=encodeTimedEntry(withAsyncReplayProgress(stripTimedExecutionMarker(pendingEntry.key),checkpoint),pendingEntry.time);
                        mutateTimedQueue(pending->{int index=pending.indexOf(before);if(index<0)throw new IllegalStateException("Timed occurrence disappeared before checkpoint");pending.set(index,updated);return pending;});current.set(updated);
                    });
                    Reward.ReplayState replayState=Reward.replayStateFor(options);replayState.captureAdmittedRuntime(runtime);options.setAsyncReplayState(replayState);
                    effectsStarted.set(true);
                    CompletionStage<Void> effect=plugin.getRewardHandler().givePersistedQueueRewardAsync(this,new PersistedQueueReference(metadata.rewardReference),options);
                    if(effect==null)throw new IllegalStateException("Timed reward omitted its completion stage");return effect;
                },TimeUnit.SECONDS.toMillis(30));
                replay.handle((ignored,failure)->owner.dispatchOffPrimary(()->{
                    if(failure==null)return publishQueueEdit(owner,captured,()->mutateTimedQueue(pending->{if(!pending.remove(current.get()))throw new IllegalStateException("Timed occurrence disappeared before completion");return pending;}));
                    else {
                        String before=current.get();TimedQueueEntry pendingEntry=decodeTimedEntry(before);
                        int retry=Math.min(8,asyncRetryCount(pendingEntry.key)+1);
                        long retryTime=System.currentTimeMillis()+Math.min(TimeUnit.MINUTES.toMillis(5),TimeUnit.SECONDS.toMillis(1L<<retry));
                        String key=withAsyncRetryCount(withAsyncReplayProgress(stripTimedExecutionMarker(pendingEntry.key),failure),retry);
                        String restored=encodeTimedEntry(key,retryTime);
                        Runnable edit=()->{mutateTimedQueue(pending->{int index=pending.indexOf(before);if(index<0)throw new IllegalStateException("Timed occurrence disappeared during recovery");pending.set(index,restored);return pending;});current.set(restored);};
                        CompletionStage<Void> publication;
                        if(effectsStarted.get())publication=publishQueueEdit(owner,captured,edit);
                        else {edit.run();publication=CompletableFuture.completedFuture(null);}
                        return publication.thenCompose(published->{retryPublished.set(true);loadTimedDelayedTimer(retryTime);return AdvancedCoreUser.<Void>failedStage(failure);});
                    }
                },TimeUnit.SECONDS.toMillis(30))).thenCompose(stage->stage).whenComplete((ignored,failure)->{
                    synchronized(REPLAY_CLAIMS_LOCK){captured.occurrences.remove(id);if(existing==null){int count=captured.legacy.getOrDefault(stored,0);if(count<=1)captured.legacy.remove(stored);else captured.legacy.put(stored,count-1);}}
                    if(failure!=null && !effectsStarted.get() && !retryPublished.get() && isTimedStorageFailure(failure))requestTimedStorageWakeup(owner,runtime);
                    if(failure==null)outcome.complete(null);else outcome.completeExceptionally(failure);tail.complete(null);
                    synchronized(REPLAY_CLAIMS_LOCK){HashMap<String,PersistedReplayClaims> users=REPLAY_CLAIMS.get(plugin);if(captured.occurrences.isEmpty() && captured.tail.isDone() && captured.timedStorageWakeup==null && captured.publication==null && users!=null && users.get(getUUID())==captured){users.remove(getUUID());if(users.isEmpty())REPLAY_CLAIMS.remove(plugin);}}
                });
            });
        }
        return CompletableFuture.allOf(outcomes.toArray(new CompletableFuture<?>[0]));
    }

    private static final String TIMED_EXECUTION_DELIMITER="%ExecutionTime/%";
    private static final class TimedQueueEntry {
        final String key;final long time;
        TimedQueueEntry(String key,long time){this.key=key;this.time=time;}
    }
    private static TimedQueueEntry decodeTimedEntry(String stored) {
        if(stored==null)throw new IllegalArgumentException("Timed entry omitted");
        int marker=stored.lastIndexOf(TIMED_EXECUTION_DELIMITER);
        if(marker<=0)throw new IllegalArgumentException("Timed execution date omitted");
        long time=Long.parseLong(stored.substring(marker+TIMED_EXECUTION_DELIMITER.length()));
        if(time<0)throw new IllegalArgumentException("Negative timed execution date");
        return new TimedQueueEntry(stored.substring(0,marker),time);
    }
    private static String encodeTimedEntry(String key,long time){return key+TIMED_EXECUTION_DELIMITER+time;}
    private ArrayList<String> mutateTimedQueue(java.util.function.UnaryOperator<ArrayList<String>> transform) {
        try{return getUserData().mutateStringListStrict("TimedRewards",transform);}
        catch(com.bencodez.advancedcore.api.user.usercache.CommittedUserDataMutationException committed){plugin.getLogger().warning("Timed queue edit committed; change notification failed");String stored=committed.getCommittedValue().getString();return stored==null || stored.isEmpty()?new ArrayList<>():new ArrayList<>(java.util.Arrays.asList(stored.split("%line%")));}
    }

	/**
	 * Check offline rewards.
	 */
    public void checkOfflineRewards() {
        checkOfflineRewardsAsync().whenComplete((ignored,failure)->{
            if(failure!=null)plugin.getLogger().warning("Offline reward replay remains pending: "+failure.getMessage());
        });
    }

    /** Completion covers durable occurrence admission, effects/checkpoints and removal. */
    public CompletionStage<Void> checkOfflineRewardsAsync() {
        return checkOfflineRewardsAsync(false);
    }

    private CompletionStage<Void> checkOfflineRewardsAsync(boolean force) {
        if(!plugin.getOptions().isProcessRewards())return CompletableFuture.completedFuture(null);
        Reward.ReplayState capturedRuntime=Reward.replayStateFor(new RewardOptions());capturedRuntime.captureRuntime(plugin);
        ServerThreadRewardDispatch owner=capturedRuntime.getActionDispatchOwner();
        if(owner==null)return failedStage(new IllegalStateException("Reward dispatcher unavailable"));
        return owner.dispatchOffPrimary(()->dispatchOfflineQueue(owner,capturedRuntime,force),TimeUnit.SECONDS.toMillis(30));
    }

    private CompletionStage<Void> dispatchOfflineQueue(ServerThreadRewardDispatch owner,Reward.ReplayState capturedRuntime,boolean force) {
        if(!plugin.getOptions().isProcessRewards())return CompletableFuture.completedFuture(null);
        setCheckWorld(false);
        ArrayList<String> snapshot=getUserData().getStringListStrict(plugin.getUserManager().getOfflineRewardsPath());
        HashSet<String> observedIds=new HashSet<>();
        for(String stored:snapshot){if(stored==null || stored.equals("null"))continue;String id=occurrenceId(stored);if(id!=null && !observedIds.add(id))throw new IllegalStateException("Duplicate persisted reward occurrence");}
        ArrayList<CompletableFuture<Void>> outcomes=new ArrayList<>();
        for(String stored:snapshot) {
            if(stored==null || stored.equals("null"))continue;
            final String existingId=occurrenceId(stored);
            final String id=existingId==null?UUID.randomUUID().toString():existingId;
            PersistedReplayClaims claims;
            CompletableFuture<Void> previous,tail=new CompletableFuture<>();
            synchronized(REPLAY_CLAIMS_LOCK) {
                claims=REPLAY_CLAIMS.computeIfAbsent(plugin,unused->new HashMap<>()).computeIfAbsent(getUUID(),unused->new PersistedReplayClaims());
                if(claims.occurrences.contains(id))continue;
                if(existingId==null) {
                    int count=0;for(String pending:snapshot)if(stored.equals(pending))count++;
                    int claimed=claims.legacy.getOrDefault(stored,0);if(claimed>=count)continue;
                    claims.legacy.put(stored,claimed+1);
                }
                claims.occurrences.add(id);previous=claims.tail;claims.tail=tail;
            }
            final PersistedReplayClaims captured=claims;
            java.util.concurrent.atomic.AtomicReference<String> current=new java.util.concurrent.atomic.AtomicReference<>(stored);
            java.util.concurrent.atomic.AtomicBoolean effectsStarted=new java.util.concurrent.atomic.AtomicBoolean();
            CompletableFuture<Void> result=new CompletableFuture<Void>() {@Override public boolean cancel(boolean interrupt){return false;}};
            outcomes.add(result);
            previous.whenComplete((ignored,priorFailure)->{
                CompletionStage<Void> replay=owner.dispatchOffPrimary(()->{
                    String admitted=existingId==null?withAsyncOccurrence(stored,id):stored;
                    mutateOfflineQueue(pending->{int index=pending.indexOf(stored);if(index<0)throw new IllegalStateException("Queued occurrence disappeared before admission");pending.set(index,admitted);return pending;});
                    current.set(admitted);
                    String[] parts=admitted.split("%placeholders%",2);QueuedReplay metadata=parseQueuedReplay(parts[0]);
                    RewardOptions options=new RewardOptions().setOnline(false).setCheckTimed(false)
                        .withPlaceHolder(ArrayUtils.fromString(parts.length>1?parts[1]:""));
                    if(force)options.setGiveOffline(false).forceOffline();
                    options.setCompletedAsyncInjections(metadata.completedAsyncInjections);options.setAsyncReplayProgress(metadata.asyncReplayProgress);
                    options.setAsyncReplayRegistryFingerprints(metadata.asyncReplayRegistryFingerprints);options.setLegacyAsyncReplayCheckpoint(metadata.legacyAsyncReplayCheckpoint);
                    options.setAsyncReplayOccurrenceId(id);options.setAsyncReplayCheckpointConsumer(checkpoint->{
                        String before=current.get(),updated=withAsyncReplayProgress(before,checkpoint);
                        mutateOfflineQueue(pending->{int index=pending.indexOf(before);if(index<0)throw new IllegalStateException("Queued occurrence disappeared before checkpoint");pending.set(index,updated);return pending;});
                        current.set(updated);
                    });
                    Reward.ReplayState replayState=Reward.replayStateFor(options);replayState.captureAdmittedRuntime(capturedRuntime);options.setAsyncReplayState(replayState);
                    effectsStarted.set(true);
                    CompletionStage<Void> effect=plugin.getRewardHandler().givePersistedQueueRewardAsync(this,new PersistedQueueReference(metadata.rewardReference),options);
                    if(effect==null)throw new IllegalStateException("Queued reward omitted its completion stage");return effect;
                },TimeUnit.SECONDS.toMillis(30));
                replay.handle((value,failure)->owner.dispatchOffPrimary(()->{
                    if(failure==null)return publishQueueEdit(owner,captured,()->mutateOfflineQueue(pending->{if(!pending.remove(current.get()))throw new IllegalStateException("Queued occurrence disappeared before completion");return pending;}));
                    if(Reward.isOfflineReplayDeferred(failure))return CompletableFuture.<Void>completedFuture(null);
                    String before=current.get(),updated=withAsyncReplayProgress(before,failure);
                    if(before.equals(updated))return AdvancedCoreUser.<Void>failedStage(failure);
                    Runnable edit=()->{mutateOfflineQueue(pending->{int index=pending.indexOf(before);if(index<0)throw new IllegalStateException("Queued occurrence disappeared during recovery");pending.set(index,updated);return pending;});current.set(updated);};
                    CompletionStage<Void> publication;
                    if(effectsStarted.get())publication=publishQueueEdit(owner,captured,edit);
                    else {edit.run();publication=CompletableFuture.completedFuture(null);}
                    return publication.thenCompose(published->AdvancedCoreUser.<Void>failedStage(failure));
                },TimeUnit.SECONDS.toMillis(30))).thenCompose(stage->stage).whenComplete((value,failure)->{
                    synchronized(REPLAY_CLAIMS_LOCK) {
                        captured.occurrences.remove(id);
                        if(existingId==null){int count=captured.legacy.getOrDefault(stored,0);if(count<=1)captured.legacy.remove(stored);else captured.legacy.put(stored,count-1);}
                    }
                    if(failure==null)result.complete(null);else result.completeExceptionally(failure);
                    tail.complete(null);
                    synchronized(REPLAY_CLAIMS_LOCK) {
                        HashMap<String,PersistedReplayClaims> users=REPLAY_CLAIMS.get(plugin);
                        if(captured.occurrences.isEmpty() && captured.tail.isDone() && captured.timedStorageWakeup==null && captured.publication==null && users!=null && users.get(getUUID())==captured){users.remove(getUUID());if(users.isEmpty())REPLAY_CLAIMS.remove(plugin);}
                    }
                });
            });
        }
        return CompletableFuture.allOf(outcomes.toArray(new CompletableFuture<?>[0]));
    }

    private ArrayList<String> mutateOfflineQueue(java.util.function.UnaryOperator<ArrayList<String>> transform) {
        try {return getUserData().mutateStringListStrict(plugin.getUserManager().getOfflineRewardsPath(),transform);}
        catch(com.bencodez.advancedcore.api.user.usercache.CommittedUserDataMutationException committed) {
            plugin.getLogger().warning("Offline queue edit committed; change notification failed");
            String stored=committed.getCommittedValue().getString();
            return stored==null || stored.isEmpty()?new ArrayList<>():new ArrayList<>(java.util.Arrays.asList(stored.split("%line%")));
        }
    }

	public void clearCache() {
		if (isCached()) {
			getCache().clearCache();
		}
	}

	public void clearTempCache() {
		getUserData().clearTempCache();
	}

	public void closeInv() {
		if (plugin.isEnabled()) {
			getPlugin().getBukkitScheduler().runTask(plugin, new Runnable() {

				@Override
				public void run() {
					Player player = getPlayer();
					if (player != null) {
						player.closeInventory();
					}
				}
			}, getPlayer());
		}
	}

	public AdvancedCoreUser dontCache() {
		cacheData = false;
		return this;
	}

    public void forceRunOfflineRewards() {
        checkOfflineRewardsAsync(true).whenComplete((ignored,failure)->{
            if(failure!=null)plugin.getLogger().warning("Forced offline reward replay remains pending: "+failure.getMessage());
        });
    }

	public UserDataCache getCache() {
		return plugin.getUserManager().getDataManager().getCache(java.util.UUID.fromString(getUUID()));
	}

	public String getChoicePreference(String rewardName) {
		ArrayList<String> data = getChoicePreferenceData();

		for (String str : data) {
			String[] data1 = str.split(":");
			if (data1.length > 1) {
				if (data1[0].equals(rewardName)) {
					return data1[1];
				}
			}
		}
		return "";
	}

	public ArrayList<String> getChoicePreferenceData() {
		return getData().getStringList("ChoicePreference", cacheData, waitForCache);
	}

	public UserData getData() {
		if (data == null) {
			loadData();
		}
		return data;
	}

	public String getInputMethod() {
		return getUserData().getString("InputMethod", cacheData, waitForCache);
	}

	public UUID getJavaUUID() {
		return UUID.fromString(uuid);
	}

	public long getLastOnline() {
		String d = getData().getString("LastOnline", cacheData, waitForCache);
		long time = 0;
		if (d != null && !d.equals("") && !d.equals("null")) {
			time = Long.valueOf(d);
		}
		if (time == 0) {
			time = getOfflinePlayer().getLastPlayed();
			if (time > 0) {
				setLastOnline(time);
			}
		}
		return time;
	}

	public int getNumberOfDaysSinceLogin() {
		long time = getLastOnline();
		if (time > 0) {
			LocalDateTime online = LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.systemDefault());
			LocalDateTime now = LocalDateTime.now();
			Duration dur = Duration.between(online, now);
			return (int) dur.toDays();
		}

		return -1;
	}

	public OfflinePlayer getOfflinePlayer() {
		if (uuid != null && !uuid.equals("")) {
			return Bukkit.getOfflinePlayer(java.util.UUID.fromString(uuid));
		}
		return null;
	}

	public ArrayList<String> getOfflineRewards() {
		return getUserData().getStringList(plugin.getUserManager().getOfflineRewardsPath(), cacheData, waitForCache);
	}

	/**
	 * Gets the player.
	 *
	 * @return the player
	 */
	public Player getPlayer() {
		if (uuid != null && !uuid.isEmpty()) {
			return Bukkit.getPlayer(java.util.UUID.fromString(uuid));
		}
		return null;
	}

	public ItemStack getPlayerHead() {
		return PlayerManager.getInstance().getPlayerSkull(getJavaUUID(), getPlayerName(), false);
	}

	/**
	 * Gets the player name.
	 *
	 * @return the player name
	 */
	public String getPlayerName() {
		if (playerName != null) {
			return playerName;
		}
		if (isTempCache()) {
			return getUserData().getString("PlayerName", false, false);
		}
		return "";
	}

	public int getRepeatAmount(Reward reward) {
		return getData().getInt("Repeat" + reward.getName(), cacheData, waitForCache);
	}

    public HashMap<String,Long> getTimedRewards() {
        ArrayList<String> stored=getUserData().getStringList("TimedRewards",cacheData,waitForCache);
        HashMap<String,Long> result=new HashMap<>();
        for(String record:stored)if(record!=null && !record.equals("null")){TimedQueueEntry entry=decodeTimedEntry(record);if(result.put(entry.key,entry.time)!=null)throw new IllegalArgumentException("Duplicate timed reward key");}
        return result;
    }

	public ArrayList<String> getUnClaimedChoices() {
		return getData().getStringList("UnClaimedChoices", cacheData, waitForCache);
	}

	public UserData getUserData() {
		if (data == null) {
			loadData();
		}
		return data;
	}

	public InputMethod getUserInputMethod() {
		String inputMethod = getInputMethod();
		if (inputMethod == null) {
			return InputMethod.getMethod(plugin.getOptions().getDefaultRequestMethod());
		}
		return InputMethod.getMethod(inputMethod);

	}

	/**
	 * Gets the uuid.
	 *
	 * @return the uuid
	 */
	public String getUUID() {
		return uuid;
	}

	/**
	 * Give exp.
	 *
	 * @param exp the exp
	 */
	public void giveExp(int exp) {
		Player player = getPlayer();
		if (scheduleOwnedPlayerAction(player, () -> player.giveExp(exp), "exp:" + exp)) return;
		if (player != null) {
			player.giveExp(exp);
		}
	}

	public void giveExpLevels(int num) {
		Player p = getPlayer();
		if (scheduleOwnedPlayerAction(p, () -> p.setLevel(p.getLevel() + num), "exp-levels:" + num)) return;
		if (p != null) {
			p.setLevel(p.getLevel() + num);
		}
	}

	public void giveItem(ItemBuilder builder) {
		Player player = getPlayer();
		if (player == null && hasOwnedAsyncActionCollection()) {
			collectAsyncFailure(new IllegalStateException("Player became unavailable before item reward delivery"));
			return;
		}
		giveItem(builder.toItemStack(player));
	}

	/**
	 * Give item.
	 *
	 * @param item the item
	 */
	public void giveItem(ItemStack item) {
		if ((item == null) || (item.getAmount() == 0)) {
			return;
		}

		final Player player = getPlayer();

		if (plugin.isEnabled()) {
			scheduleLegacyItemAction(player, item);
		} else {
			collectAsyncFailure(new IllegalStateException("Plugin disabled before item reward was scheduled"));
		}

	}

	public void giveItem(ItemStack itemStack, HashMap<String, String> placeholders) {
		giveItem(new ItemBuilder(itemStack).setPlaceholders(placeholders));
	}

	public void giveItems(ItemStack... item) {
		if (item == null) {
			return;
		}

		final Player player = getPlayer();

		if (plugin.isEnabled()) {
			scheduleLegacyItemAction(player, item);
		} else {
			collectAsyncFailure(new IllegalStateException("Plugin disabled before item reward was scheduled"));
		}

	}

	/**
	 * Give user money, needs vault installed
	 *
	 * @param m Amount of money to give
	 */
	public void giveMoney(double m) {
		if (!plugin.isEnabled()) {
			collectAsyncFailure(new IllegalStateException("Plugin disabled before money reward was scheduled"));
			return;
		}
		if (plugin.getVaultHandler() != null && plugin.getVaultHandler().getEcon() != null) {
			try {
				if (m > 0) {
					final double money = m;
					scheduleLegacyRewardAction(
							() -> plugin.getVaultHandler().getEcon().depositPlayer(getOfflinePlayer(), money), null, false,
							"money:deposit:" + Double.toString(money));

				} else if (m < 0) {
					m = m * -1;
					final double money = m;
					scheduleLegacyRewardAction(
							() -> plugin.getVaultHandler().getEcon().withdrawPlayer(getOfflinePlayer(), money), null, false,
							"money:withdraw:" + Double.toString(money));

				}
			} catch (

			IllegalStateException e) {
				e.printStackTrace();
				collectAsyncFailure(e);
			}
		}
	}

	/**
	 * Give money.
	 *
	 * @param money the money
	 */
	public void giveMoney(int money) {
		giveMoney((double) money);
	}

	/**
	 * Give potion effect.
	 *
	 * @param potionName the potion name
	 * @param duration   the duration
	 * @param amplifier  the amplifier
	 */
	public void givePotionEffect(String potionName, int duration, int amplifier) {
		Player player = getPlayer();
		if (player == null) {
			if (hasOwnedAsyncActionCollection()) collectAsyncFailure(new IllegalStateException(
					"Player became unavailable before potion reward delivery"));
			return;
		}
		if (!plugin.isEnabled()) {
			if (hasOwnedAsyncActionCollection()) collectAsyncFailure(new IllegalStateException(
					"Potion reward could not be scheduled because the plugin is unavailable"));
			return;
		}
		scheduleLegacyRewardAction(() -> player.addPotionEffect(
				new PotionEffect(PotionEffectType.getByName(potionName), 20 * duration, amplifier)), player, true,
				"potion:" + potionName + ":" + duration + ":" + amplifier);
	}

	public void giveReward(FileConfiguration data, String path, RewardOptions rewardOptions) {
		plugin.getRewardHandler().giveReward(this, data, path, rewardOptions);
	}

	public void giveReward(Reward reward, RewardOptions rewardOptions) {
		reward.giveReward(this, rewardOptions);
	}

	public boolean hasChoices() {
		return getUnClaimedChoices().size() > 0;
	}

	/**
	 * Check if player joined before
	 *
	 * @return true, if successful
	 */
	public boolean hasLoggedOnBefore() {
		OfflinePlayer player = Bukkit.getOfflinePlayer(java.util.UUID.fromString(uuid));
		if (player != null) {
			if (player.hasPlayedBefore() || player.isOnline()) {
				return true;
			}

		}
		ArrayList<String> uuids = plugin.getUserManager().getAllUUIDs();
		if (uuids.contains(getUUID())) {
			return true;
		}
		return false;
	}

	public boolean hasPermission(String perm) {
		Player player = getPlayer();
		if (!plugin.getOptions().isOnlineMode() && player == null) {
			player = Bukkit.getPlayer(getPlayerName());
		}
		if (player == null) {
			plugin.debug("Unable to get player for " + getPlayerName() + "/" + getUUID());
			return false;
		}
		if (perm.startsWith("!")) {
			perm = perm.substring(1);
			return !player.hasPermission(perm);
		}
		return player.hasPermission(perm);
	}

	public boolean isBanned() {
		if (plugin.getBannedPlayers().contains(getUUID())) {
			return true;
		}
		return false;
	}

	public boolean isBedrockPlayer() {
		return plugin.getGeyserHandle().isGeyserPlayer(getJavaUUID());
	}

	public boolean isCached() {
		return plugin.getUserManager().getDataManager().isCached(UUID.fromString(uuid));
	}

	public boolean isCheckWorld() {
		if (!plugin.isLoadUserData()) {
			return false;
		}
		return Boolean.valueOf(getData().getString("CheckWorld", true));
	}

	public boolean isInWorld(ArrayList<String> worlds) {
		Player p = getPlayer();
		if (p != null) {
			for (String world : worlds) {
				if (p.getWorld().getName().equalsIgnoreCase(world)) {
					return true;
				}
			}
		}

		return false;
	}

	public boolean isInWorld(String world) {
		Player p = getPlayer();
		if (p != null) {
			return p.getWorld().getName().equalsIgnoreCase(world);
		}

		return false;
	}

	/**
	 * Checks if is online.
	 *
	 * @return true, if is online
	 */
	public boolean isOnline() {
		boolean online = PlayerUtils.isPlayerOnline(getPlayerName());
		if (!online) {
			return false;
		}
		if (plugin.getOptions().isTreatVanishAsOffline()) {
			if (isVanished()) {
				return false;
			}
		}
		return true;
	}

	public boolean isVanished() {
		Player player = getPlayer();
		if (player != null) {
			for (MetadataValue meta : player.getMetadata("vanished")) {
				if (meta.asBoolean()) {
					return true;
				}
			}

			try {
				try {
					if (plugin.getCmiHandle() != null) {
						return plugin.getCmiHandle().isVanished(player);
					}
				} catch (Exception e) {
				}
			} catch (Exception e) {
				plugin.debug(e);
			}
		}
		return false;
	}

	public void loadCache() {
		plugin.getUserManager().getDataManager().cacheUser(UUID.fromString(uuid), getPlayerName());
	}

	public void loadData() {
		data = new UserData(this);
	}

	public void loadTimedDelayedTimer(long time) {
		long delay = time - System.currentTimeMillis();
		if (delay < 0) {
			delay = 0;
		}
		delay += 500;
		plugin.getRewardHandler().getDelayedTimer().schedule(new Runnable() {

			@Override
			public void run() {
				checkDelayedTimedRewards();
			}
		}, delay, TimeUnit.MILLISECONDS);
	}

	/**
	 * Play particle effect.
	 *
	 * @param effectName the effect name
	 * @param data       the data
	 * @param particles  the particles
	 * @param radius     the radius
	 */
	public void playEffect(String effectName, int data, int particles, int radius) {
		Player player = getPlayer();
		if ((player != null) && (effectName != null)) {
			try {
				Effect effect = Effect.valueOf(effectName);
				for (int i = 0; i < particles; i++) {
					player.getWorld().playEffect(player.getLocation(), effect, data, radius);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}

		}
	}

	public void playParticle(String effectName, int data, int particles, int radius) {
		Player player = getPlayer();
		if ((player != null) && (effectName != null)) {
			try {
				/*
				 * Particle effect = Particle.valueOf(effectName); for (int i = 0; i <
				 * particles; i++) { player.getWorld().spawnParticle(effect,
				 * player.getLocation(), particles, radius, radius, radius, data); }
				 */

			} catch (Exception e) {
				plugin.getLogger().warning(
						"Failed to create particle: " + effectName + ", " + data + ", " + particles + ", " + radius);
				e.printStackTrace();
			}
		}
	}

	@Deprecated
	public void playParticleEffect(String effectName, int data, int particles, int radius) {
		playParticle(effectName, data, particles, radius);
	}

	/**
	 * Play sound.
	 *
	 * @param soundName the sound name
	 * @param volume    the volume
	 * @param pitch     the pitch
	 */
	public void playSound(String soundName, float volume, float pitch) {
		Player player = Bukkit.getPlayer(java.util.UUID.fromString(uuid));
		if (player != null) {
			Sound sound = null;
			try {
				sound = Sound.valueOf(soundName);
			} catch (Exception e) {
				plugin.debug(e);
			}
			if (sound != null) {
				player.playSound(player.getLocation(), sound, volume, pitch);
			} else {
				plugin.debug("Invalid sound: " + soundName);
			}
		}
	}

	public void preformCommand(ArrayList<String> commands, HashMap<String, String> placeholders) {
		if (commands != null && !commands.isEmpty()) {
			final ArrayList<String> cmds = PlaceholderUtils.replaceJavascript(getPlayer(),
					PlaceholderUtils.replacePlaceHolder(commands, placeholders));

			final Player player = getPlayer();
			if (player != null && plugin.isEnabled()) {
				for (final String cmd : cmds) {
					plugin.debug("Executing player command for " + getPlayerName() + ": " + cmd);
					getPlugin().getBukkitScheduler().runTask(plugin, new Runnable() {

						@Override
						public void run() {
							player.chat("/" + cmd);
						}
					});
				}
			}
		}
	}

    /** Completion-aware player chat commands; the existing void APIs stay unchanged. */
    public CompletionStage<Void> preformCommandAsync(ArrayList<String> commands,HashMap<String,String> placeholders) {
        return preformCommandAsync(commands,placeholders,Reward.currentReplayState(),Reward.currentReplayKey());
    }

    public CompletionStage<Void> preformCommandAsync(ArrayList<String> commands,HashMap<String,String> placeholders,
            Reward.ReplayState state,String key) {
        if(state!=null)state.captureRuntime(plugin);
        ServerThreadRewardDispatch owner=state==null?plugin.getRewardDispatch():state.getActionDispatchOwner();
        ArrayList<String> templates=commands==null?new ArrayList<>():new ArrayList<>(commands);
        return owner.dispatch(()->{
            ArrayList<String> expanded=templates.isEmpty()?new ArrayList<>():PlaceholderUtils.replaceJavascript(getPlayer(),PlaceholderUtils.replacePlaceHolder(templates,placeholders));
            return Reward.replayCommandSequence(plugin,placeholders,"player",templates,expanded,state,key,(command,index)->{
                Player player=getPlayer();
                if(player==null)return failedStage(new IllegalStateException("Player command requires an available player"));
                return owner.dispatchAfterTicks(()->{
                    validateLiveScheduledPlayer(player);player.chat("/"+command);return CompletableFuture.<Void>completedFuture(null);
                },0,30000);
            });
        },30000);
    }

    /** Fail before mixed console/player sections issue any side effect. */
    public CompletionStage<Void> validatePlayerCommandAvailabilityAsync() {
        Reward.ReplayState state=Reward.currentReplayState();if(state!=null)state.captureRuntime(plugin);
        ServerThreadRewardDispatch owner=state==null?plugin.getRewardDispatch():state.getActionDispatchOwner();
        return owner.dispatch(()->getPlayer()==null?failedStage(new IllegalStateException("Player command requires an available player")):CompletableFuture.<Void>completedFuture(null),30000);
    }

	public void preformCommand(String command, HashMap<String, String> placeholders) {
		if (command != null && !command.isEmpty()) {
			final String cmd = PlaceholderUtils.replaceJavascript(getPlayer(),
					PlaceholderUtils.replacePlaceHolder(command, placeholders));
			plugin.debug("Executing player command for " + getPlayerName() + ": " + command);
			if (plugin.isEnabled()) {
				getPlugin().getBukkitScheduler().runTask(plugin, new Runnable() {

					@Override
					public void run() {
						Player player = getPlayer();
						if (player != null) {
							player.chat("/" + cmd);
						}
					}
				});
			}
		}
	}

	public void remove() {
		plugin.debug("Removing " + getUUID() + " (" + getPlayerName() + ") from storage...");
		getData().remove();
	}

	public void removePermission(String permission) {
		plugin.getPermissionHandler().removePermission(UUID.fromString(getUUID()), getPlayerName(), permission);
	}

	public void removeUnClaimedChoiceReward(String name) {
		ArrayList<String> choices = getUnClaimedChoices();
		choices.remove(name);
		setUnClaimedChoice(choices);
	}

	/**
	 * Send action bar.
	 *
	 * @param msg   the msg
	 * @param delay the delay
	 */
	public void sendActionBar(String msg, int delay) {
		
	}

	/**
	 * Send boss bar.
	 *
	 * @param msg      the msg
	 * @param color    the color
	 * @param style    the style
	 * @param progress the progress
	 * @param delay    the delay
	 */
	public void sendBossBar(String msg, String color, String style, double progress, int delay) {
		
	}

	/**
	 * Send json.
	 *
	 * @param messages the messages
	 */
	public void sendJson(ArrayList<TextComponent> messages) {
		sendJson(messages, true);
	}

	public void sendJson(ArrayList<TextComponent> messages, boolean javascript) {
		Player player = getPlayer();
		if ((player != null) && (messages != null)) {
			ArrayList<BaseComponent> texts = new ArrayList<>();
			TextComponent newLine = new TextComponent(ComponentSerializer.parse("{text: \"\n\"}"));
			for (int i = 0; i < messages.size(); i++) {
				TextComponent txt = messages.get(i);
				if (javascript) {
					txt.setText(PlaceholderUtils.replaceJavascript(getPlayer(), txt.getText()));
				}
				texts.add(txt);
				if (i + 1 < messages.size()) {
					texts.add(newLine);
				}

			}

			PlayerUtils.getServerHandle().sendMessage(player, ArrayUtils.convertBaseComponent(texts));
		}

	}

	/**
	 * Send json.
	 *
	 * @param message the message
	 */
	public void sendJson(TextComponent message) {
		Player player = getPlayer();
		if ((player != null) && (message != null)) {
			message.setText(PlaceholderUtils.replaceJavascript(getPlayer(), message.getText()));
			PlayerUtils.getServerHandle().sendMessage(player, message);
		}
	}

	/**
	 * Send message.
	 *
	 * @param msg the msg
	 */
	public void sendMessage(ArrayList<String> msg) {
		sendMessage(ArrayUtils.convert(msg));
	}

	public void sendMessage(ArrayList<String> msg, HashMap<String, String> placeholders) {
		sendMessage(ArrayUtils.convert(PlaceholderUtils.replacePlaceHolder(msg, placeholders)));
	}

	/**
	 * Send message.
	 *
	 * @param msg the msg
	 */
	public void sendMessage(String msg) {
		Player player = getPlayer();
		if ((player != null) && (msg != null)) {
			if (!msg.equals("")) {
				for (String str : msg.split("%NewLine%")) {
					PlayerUtils.getServerHandle().sendMessage(player,
							PlaceholderUtils.parseJson(PlaceholderUtils.parseText(player, str)));
				}
			}
		}
	}

	public void sendMessage(String msg, HashMap<String, String> placeholders) {
		sendMessage(PlaceholderUtils.replacePlaceHolder(msg, placeholders));
	}

	public void sendMessage(String msg, String toReplace, String replace) {
		sendMessage(PlaceholderUtils.replacePlaceHolder(msg, toReplace, replace));
	}

	/**
	 * Send message.
	 *
	 * @param msg the msg
	 */
	public void sendMessage(String[] msg) {
		Player player = Bukkit.getPlayer(java.util.UUID.fromString(uuid));
		if ((player != null) && (msg != null)) {

			ArrayList<TextComponent> texts = new ArrayList<>();
			for (String str : msg) {
				if ((player != null) && (msg != null)) {
					if (!str.equals("")) {
						for (String str1 : str.split("%NewLine%")) {
							TextComponent text = PlaceholderUtils.parseJson(PlaceholderUtils.parseText(player, str1));
							text.setText(PlaceholderUtils.replaceJavascript(getPlayer(), text.getText()));
							texts.add(text);
						}
					}

				}
			}
			if (texts.size() > 0) {
				sendJson(texts, false);
			}
		}

	}

	/**
	 * Send title.
	 *
	 * @param title    the title
	 * @param subTitle the sub title
	 * @param fadeIn   the fade in
	 * @param showTime the show time
	 * @param fadeOut  the fade out
	 */
	public void sendTitle(String title, String subTitle, int fadeIn, int showTime, int fadeOut) {
		
	}

	public void setCheckWorld(boolean b) {
		getData().setString("CheckWorld", "" + b);
	}

	public void setChoicePreference(String reward, String preference) {
		ArrayList<String> data = getChoicePreferenceData();
		ArrayList<String> choices = new ArrayList<>();

		boolean added = false;
		for (String str : data) {
			String[] data1 = str.split(":");
			if (data1.length > 1) {
				if (data1[0].equals(reward)) {
					choices.add(reward + ":" + preference);
					added = true;
				} else {
					choices.add(str);
				}
			}
		}
		if (!added) {
			choices.add(reward + ":" + preference);
		}
		getData().setStringList("ChoicePreference", choices);
	}

	public void setInputMethod(String inputMethod) {
		data.setString("InputMethod", inputMethod);
	}

	public void setLastOnline(long online) {
		getData().setString("LastOnline", "" + online);
	}

	public void setOfflineRewards(ArrayList<String> offlineRewards) {
		// MySQL TEXT max length is 65535 bytes
		int maxLength = 65535;
		String str = String.join("%line%", offlineRewards);

		// Remove oldest rewards until within limit
		while (str.getBytes().length > maxLength && !offlineRewards.isEmpty()) {
			offlineRewards.remove(0);
			str = String.join("%line%", offlineRewards);
		}

		data.setStringList(plugin.getUserManager().getOfflineRewardsPath(), offlineRewards);
	}

	public void setPlayerName(String playerName) {
		this.playerName = playerName;
	}

	public void setRepeatAmount(Reward reward, int amount) {
		getData().setInt("Repeat" + reward.getName(), amount);
	}

	public void setTimedRewards(HashMap<String, Long> timed) {
		ArrayList<String> timedRewards = new ArrayList<>();
		for (Entry<String, Long> entry : timed.entrySet()) {

			String str = "";
			str += entry.getKey() + "%ExecutionTime/%";
			str += entry.getValue();
			timedRewards.add(str);

		}
		data.setStringList("TimedRewards", timedRewards);
	}

	public void setUnClaimedChoice(ArrayList<String> rewards) {
		getData().setStringList("UnClaimedChoices", rewards);
	}

	public void setUserInputMethod(InputMethod method) {
		setInputMethod(method.toString());
	}

	/**
	 * Sets the uuid.
	 *
	 * @param uuid the new uuid
	 */
	public void setUUID(String uuid) {
		this.uuid = uuid;
	}

	public void setWaitForCache(boolean b) {
		waitForCache = b;
	}

	public AdvancedCoreUser tempCache() {
		tempCache = true;
		getUserData().tempCache();
		return this;
	}

	public void updateName(boolean force) {
		if (getData().hasData() || force) {
			String playerName = getData().getString("PlayerName", true);
			if (playerName == null || !playerName.equals(getPlayerName())) {
				getData().setString("PlayerName", getPlayerName(), true);
			}
		}
	}

	public void updateTempCacheWithColumns(ArrayList<Column> cols) {
		tempCache = true;
		getUserData().updateTempCacheWithColumns(cols);
	}

}