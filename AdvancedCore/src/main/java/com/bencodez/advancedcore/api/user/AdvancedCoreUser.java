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
			this.actionOwner = plugin == null ? null : plugin.getRewardDispatch();
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
		if (collectAsyncAction(() -> player == null ? failedStage(
				replayActionNotStarted("Player became unavailable before item reward delivery"))
				: plugin.getFullInventoryHandler().giveItemAsync(player, item), descriptor.toString())) {
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

	public void addOfflineRewards(Reward reward, HashMap<String, String> placeholders) {
		synchronized (plugin) {
			ArrayList<String> offlineRewards = getOfflineRewards();
			offlineRewards.add(reward.getRewardName() + "%placeholders%" + ArrayUtils.makeString(placeholders));
			setOfflineRewards(offlineRewards);
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

	public synchronized void addTimedReward(Reward reward, HashMap<String, String> placeholders, long epochMilli) {
		HashMap<String, Long> timed = getTimedRewards();
		String rewardName = reward.getRewardName();
		rewardName += "%extime%" + System.currentTimeMillis();

		timed.put(rewardName + "%placeholders%" + ArrayUtils.makeString(placeholders), epochMilli);
		setTimedRewards(timed);
		loadTimedDelayedTimer(epochMilli);
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
		plugin.debug("Checking timed/delayed for " + getPlayerName());
		HashMap<String, Long> timed = getTimedRewards();
		HashMap<String, Long> newTimed = new HashMap<>();
		for (Entry<String, Long> entry : timed.entrySet()) {
			long time = entry.getValue();

			if (time != 0) {
				Date timeDate = new Date(time);
				if (new Date().after(timeDate)) {
					String[] data = entry.getKey().split("%placeholders%");
					String rewardName = data[0];
					rewardName = rewardName.split("%extime%")[0];
					String placeholders = "";
					if (data.length > 1) {
						placeholders = data[1];
					}
					new RewardBuilder(plugin.getRewardHandler().getReward(rewardName)).setCheckTimed(false)
							.withPlaceHolder(ArrayUtils.fromString(placeholders))
							.withPlaceHolder("date",
									"" + new SimpleDateFormat("EEE, d MMM yyyy HH:mm").format(new Date(time)))
							.send(this);
					plugin.debug("Giving timed/delayed reward " + rewardName + " for " + getPlayerName()
							+ " with placeholders " + ArrayUtils.fromString(placeholders));
				} else {
					newTimed.put(entry.getKey(), time);
				}
			}

		}
		setTimedRewards(newTimed);
	}

	/**
	 * Check offline rewards.
	 */
	public void checkOfflineRewards() {
		if (!plugin.getOptions().isProcessRewards()) {
			plugin.debug("Processing rewards is disabled");
			return;
		}
		if (isCheckWorld()) {
			setCheckWorld(false);
		}
		ArrayList<String> rewards = getOfflineRewards();
		if (rewards.isEmpty()) {
			return;
		}

		setOfflineRewards(new ArrayList<>());
		RewardHandler rewardHandler = plugin.getRewardHandler();
		AdvancedCoreUser user = this;

		for (String rewardEntry : rewards) {
			if (rewardEntry == null || rewardEntry.equals("null")) {
				continue;
			}

			String[] parts = rewardEntry.split("%placeholders%", 2);
			String rewardName = parts[0];
			String placeholderStr = parts.length > 1 ? parts[1] : "";

			RewardOptions options = new RewardOptions().setOnline(false).setGiveOffline(false).forceOffline()
					.setCheckTimed(false).withPlaceHolder(ArrayUtils.fromString(placeholderStr));

			rewardHandler.giveReward(user, rewardName, options);
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
		if (!plugin.getOptions().isProcessRewards()) {
			plugin.debug("Processing rewards is disabled");
			return;
		}

		setCheckWorld(false);
		ArrayList<String> rewards = getOfflineRewards();
		if (rewards.isEmpty()) {
			return;
		}

		setOfflineRewards(new ArrayList<>());
		RewardHandler rewardHandler = plugin.getRewardHandler();
		AdvancedCoreUser user = this;

		for (String rewardEntry : rewards) {
			if (rewardEntry == null || rewardEntry.equals("null")) {
				continue;
			}

			String[] parts = rewardEntry.split("%placeholders%", 2);
			String rewardName = parts[0];
			String placeholderStr = parts.length > 1 ? parts[1] : "";

			RewardOptions options = new RewardOptions().setOnline(false).setGiveOffline(false).forceOffline()
					.setCheckTimed(false).withPlaceHolder(ArrayUtils.fromString(placeholderStr));

			rewardHandler.giveReward(user, rewardName, options);
		}
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

	public HashMap<String, Long> getTimedRewards() {
		ArrayList<String> timedReward = getUserData().getStringList("TimedRewards", cacheData, waitForCache);
		HashMap<String, Long> timedRewards = new HashMap<>();
		for (String str : timedReward) {
			if (str != null && !str.equals("null")) {
				String[] data = str.split("%ExecutionTime/%");
				plugin.extraDebug("TimedReward: " + str);
				if (data.length > 1) {
					String name = data[0];

					String timeStr = data[1];
					timedRewards.put(name, Long.valueOf(timeStr));
				}
			}
		}
		return timedRewards;
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