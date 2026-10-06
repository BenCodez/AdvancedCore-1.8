package com.bencodez.advancedcore.api.user.usercache;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import com.bencodez.advancedcore.api.user.AdvancedCoreUser;
import com.bencodez.advancedcore.api.user.usercache.change.UserDataChange;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKey;
import com.bencodez.simpleapi.array.ArrayUtils;
import com.bencodez.simpleapi.sql.data.DataValue;

import lombok.Getter;

public class UserDataCache {
	@Getter
	private HashMap<String, DataValue> cache;

	private Queue<UserDataChange> cachedChanges;

	private final UserDataManager manager;
	// Acquired outside the cache monitor. Never held across extension callbacks.
	private final ReentrantLock batchOwner = new ReentrantLock(true);
	private boolean removing;
	private boolean inFlight;
	private boolean flushFailureReported;
	private long snapshotVersion;
	private long replacementVersion;
	private final HashMap<String, Long> changedAt = new HashMap<>();
	private final HashMap<String, DataValue> inFlightValues = new HashMap<>();
	private boolean scheduled = false;
	@Getter
	private volatile UUID uuid;

	public UserDataCache(UserDataManager manager, UUID uuid) {
		this.uuid = uuid;
		this.manager = manager;
		cachedChanges = new ConcurrentLinkedQueue<>();
		cache = new HashMap<>();
	}

	public synchronized void addChange(UserDataChange change, boolean queue) {
		if (cache == null || cachedChanges == null || removing) {
			throw new IllegalStateException("User cache is retiring or retired");
		}
		cache.put(change.getKey(), change.toUserDataValue());
		changedAt.put(change.getKey(), ++snapshotVersion);
		if (queue) {
			cachedChanges.add(change);
			if (!scheduled) {
				scheduleChanges();
			}
		}

	}

	public UserDataCache cache() {
		final UUID currentUuid;
		final long expectedVersion;
		final HashMap<String, DataValue> before;
		synchronized (this) {
			if (uuid == null || cache == null || removing) return this;
			currentUuid = uuid;
			expectedVersion = snapshotVersion;
			before = new HashMap<>(cache);
		}
		AdvancedCoreUser user = manager.getPlugin().getUserManager().getUser(currentUuid, false);
		final HashMap<String, DataValue> refreshed;
		try { refreshed = new HashMap<>(user.getUserData().getValuesStrict()); }
		catch (SQLException | IOException failure) { throw new IllegalStateException("User cache snapshot was not read", failure); }
		ArrayList<String> keys = new ArrayList<>(refreshed.keySet());
		// Keep dynamic stored fields as well as registered defaults, as pinned main does.
		for (UserDataKey dataKey : manager.getKeys()) {
			keys.remove(dataKey.getKey());
			if (!refreshed.containsKey(dataKey.getKey())) refreshed.put(dataKey.getKey(), dataKey.getDefault());
		}
		final HashMap<String, DataValue> published;
		synchronized (this) {
			if (uuid == null || cache == null || removing || !uuid.equals(currentUuid)
					|| replacementVersion > expectedVersion) return this;
			preservePendingValues(refreshed);
			for (Entry<String, Long> entry : changedAt.entrySet()) {
				if (entry.getValue() > expectedVersion && cache.containsKey(entry.getKey())) {
					refreshed.put(entry.getKey(), cache.get(entry.getKey()));
				}
			}
			cache = refreshed;
			recordSnapshotReplacement();
			published = new HashMap<>(cache);
		}
		ArrayList<String> changedKeys = new ArrayList<>();
		for (Entry<String, DataValue> entry : published.entrySet()) {
			DataValue prior = before.get(entry.getKey());
			if (prior != null && entry.getValue() != null
					&& !Objects.equals(prior.toString(), entry.getValue().toString())) changedKeys.add(entry.getKey());
		}
		if (!changedKeys.isEmpty()) manager.getPlugin().getUserManager().onChange(user, ArrayUtils.convert(changedKeys));
		if (!keys.isEmpty()) manager.getPlugin().devDebug("Caching additional keys: " + ArrayUtils.makeStringList(keys));
		return this;
	}

	public void clearCache() {
		Runnable notification = finishCache(false);
		if (notification != null) notification.run();
	}

	private Runnable finishCache(boolean retire) {
		Runnable notification = null;
		boolean markedRemoval = false;
		batchOwner.lock();
		try {
			synchronized (this) {
				if (inFlight) throw new IllegalStateException("Cannot retire a cache from its own storage write");
				if (cache == null) return null;
				removing = true;
				markedRemoval = true;
			}
			notification = flushClaimedChanges();
			synchronized (this) {
				if (retire) {
					cache = null;
					cachedChanges = null;
					uuid = null;
					scheduled = false;
				} else cache.clear();
				changedAt.clear();
				inFlightValues.clear();
				replacementVersion = ++snapshotVersion;
			}
		} finally {
			if (markedRemoval) synchronized (this) { removing = false; }
			batchOwner.unlock();
		}
		return notification;
	}

	// Registry removal must happen before callbacks can populate a new generation.
	Runnable retireForManager() {
		return finishCache(true);
	}

	boolean isRetired() {
		return uuid == null;
	}

	public void clearChanges() {
		if (hasChangesToProcess()) {
			processChanges();
		}
	}

	public void displayCache() {
		manager.getPlugin().devDebug(displayCacheStringList().toString());
	}

	public ArrayList<String> displayCacheStringList() {
		ArrayList<String> list = new ArrayList<>();
		list.add("Current cache for " + uuid + ": ");
		for (Entry<String, DataValue> entry : getCache().entrySet()) {
			if (entry.getValue().isBoolean()) {
				list.add(entry.getKey() + "=" + entry.getValue().getBoolean());
			} else if (entry.getValue().isString()) {
				list.add(entry.getKey() + "=" + entry.getValue().getString());
			} else if (entry.getValue().isInt()) {
				list.add(entry.getKey() + "=" + entry.getValue().getInt());
			}
		}
		return list;
	}

	public void dump() {
		Runnable notification = finishCache(true);
		if (notification != null) notification.run();
	}

	public AdvancedCoreUser getUser() {
		return manager.getPlugin().getUserManager().getUser(uuid, false);
	}

	public synchronized boolean hasCache() {
		return cache != null && !cache.isEmpty();
	}

	public synchronized boolean hasChangesToProcess() {
		return inFlight || (cachedChanges != null && !cachedChanges.isEmpty());
	}

	/** One coherent internal read; the legacy mutable getCache() API remains available. */
	public synchronized DataValue getCachedValue(String key) {
		return cache == null ? null : cache.get(key);
	}

	public synchronized boolean isCached(String key) {
		if (cache != null) {
			return cache.containsKey(key);
		}
		return false;
	}

	public void processChanges() {
		Runnable notification;
		batchOwner.lock();
		try { notification = flushClaimedChanges(); }
		finally { batchOwner.unlock(); }
		if (notification != null) notification.run();
	}

	/** Called only by the batch owner; claims a finite batch under the cache monitor. */
	private Runnable flushClaimedChanges() {
		final ArrayList<UserDataChange> changes;
		final HashMap<String, DataValue> values = new HashMap<>();
		final ArrayList<String> keys = new ArrayList<>();
		final HashMap<String, Long> claimedVersions = new HashMap<>();
		synchronized (this) {
			if (inFlight) throw new IllegalStateException("Cannot recursively flush a user cache storage write");
			if (uuid == null || cachedChanges == null || cachedChanges.isEmpty()) return null;
			changes = new ArrayList<>(cachedChanges);
			// Preparation failure leaves the original queue intact.
			for (UserDataChange change : changes) {
				values.put(change.getKey(), change.toUserDataValue());
				keys.add(change.getKey());
			}
			for (String key : values.keySet()) claimedVersions.put(key, changedAt.get(key));
			inFlightValues.putAll(values);
			cachedChanges.clear();
			inFlight = true;
		}
		final AdvancedCoreUser user;
		boolean committed = false;
		try {
			user = getUser();
			user.getUserData().setValuesStrict(values);
			committed = true;
		} catch (SQLException | IOException failure) {
			throw new IllegalStateException("User cache batch was not acknowledged", failure);
		} finally {
			synchronized (this) {
				if (!committed) {
					Queue<UserDataChange> restored = new ConcurrentLinkedQueue<>();
					restored.addAll(changes);
					restored.addAll(cachedChanges);
					cachedChanges = restored;
				} else {
					// A write completing during a read must fence that read even when
					// its original queued mutation predates the read's version.
					for (String key : values.keySet()) {
						if (Objects.equals(claimedVersions.get(key), changedAt.get(key))) {
							changedAt.put(key, ++snapshotVersion);
						}
					}
				}
				inFlightValues.clear();
				inFlight = false;
			}
		}
		// Post-commit callbacks may reenter clear/dump. Their failures cannot retry the write.
		return () -> {
			try { manager.getPlugin().getUserManager().onChange(user, ArrayUtils.convert(keys)); }
			finally { for (UserDataChange change : changes) change.dump(); }
		};
	}

	public void processChangesAsync() {
		if (hasChangesToProcess()) {
			manager.getPlugin().getTimer().execute(new Runnable() {

				@Override
				public void run() {
					processChanges();
				}
			});
		}
	}

	private synchronized void scheduleChanges() {
		if (scheduled || cachedChanges == null || cachedChanges.isEmpty()) {
			return;
		}
		manager.getPlugin().debug("Schedule changes");
		scheduled = true;
		try {
			manager.getTimer().schedule(new Runnable() {
				@Override
				public void run() {
					try {
						processChanges();
						synchronized (UserDataCache.this) { flushFailureReported = false; }
					} catch (Exception e) {
						reportBackgroundFailure(e);
					} finally {
						onScheduledFlushComplete();
					}
				}
			}, 3, TimeUnit.SECONDS);
		} catch (RejectedExecutionException e) {
			scheduled = false;
			// Preserve the legacy visible scheduling failure, allowing a later retry.
			throw e;
		}
	}

	private void reportBackgroundFailure(Exception failure) {
		String message = null;
		synchronized (this) {
			if (!flushFailureReported) {
				flushFailureReported = true;
				message = "Background user cache flush failed for " + uuid
						+ (hasChangesToProcess() ? "; pending changes are retained for retry."
								: "; the committed batch will not be retried.");
			}
		}
		if (message != null) manager.getPlugin().getLogger().warning(message + " Enable debug for details.");
		manager.getPlugin().debug(failure);
	}

	private synchronized void onScheduledFlushComplete() {
		scheduled = false;
		if (cachedChanges != null && !cachedChanges.isEmpty()) {
			scheduleChanges();
		}
	}

	public synchronized void updateCache(HashMap<String, DataValue> tempCache) {
		if (cache == null || removing) return;
		HashMap<String, DataValue> replacement = tempCache == null ? new HashMap<>() : new HashMap<>(tempCache);
		preservePendingValues(replacement);
		cache = replacement;
		recordSnapshotReplacement();
	}

	private Set<String> pendingKeys() {
		Set<String> keys = new HashSet<>(inFlightValues.keySet());
		if (cachedChanges != null) for (UserDataChange change : cachedChanges) keys.add(change.getKey());
		return keys;
	}

	/** Monitor must be held; retain the latest visible value of each pending key. */
	private void preservePendingValues(HashMap<String, DataValue> replacement) {
		for (String key : pendingKeys()) {
			if (cache.containsKey(key)) replacement.put(key, cache.get(key));
		}
	}

	private void recordSnapshotReplacement() {
		replacementVersion = ++snapshotVersion;
		changedAt.keySet().retainAll(pendingKeys());
	}
}
