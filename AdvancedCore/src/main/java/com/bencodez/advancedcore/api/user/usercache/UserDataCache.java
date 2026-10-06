package com.bencodez.advancedcore.api.user.usercache;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
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
	private boolean scheduled = false;
	@Getter
	private UUID uuid;

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
		if (queue) {
			cachedChanges.add(change);
			if (!scheduled) {
				scheduleChanges();
			}
		}

	}

	public UserDataCache cache() {
		if (uuid != null) {
			AdvancedCoreUser user = getUser();
			ArrayList<String> keys = user.getUserData().getKeys();
			HashMap<String, DataValue> data = user.getUserData().getValues();
			ArrayList<String> changedKeys = new ArrayList<>();
			for (UserDataKey dataKey : manager.getKeys()) {
				String key = dataKey.getKey();
				keys.remove(key);
				if (data.containsKey(key)) {
					DataValue dataValue = data.get(key);
					manager.getPlugin().devDebug("Caching " + dataValue.getTypeName() + " " + key + " for "
							+ uuid.toString() + ", value: " + dataValue.toString());
					// temp try/catch to prevent plugin failures
					try {
						if (cache.containsKey(key)) {
							if (!cache.get(key).toString().equals(dataValue.toString())) {
								changedKeys.add(key);
							}
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
					cache.put(key, dataValue);
				} else {
					manager.getPlugin().devDebug("Loading default cache value for " + key + " for " + uuid.toString());
					cache.put(key, dataKey.getDefault());
				}

			}
			if (!changedKeys.isEmpty()) {
				manager.getPlugin().getUserManager().onChange(user, ArrayUtils.convert(changedKeys));
			}
			if (keys.size() > 0) {
				manager.getPlugin().devDebug("Keys not cached: " + ArrayUtils.makeStringList(keys));
			}
		}
		return this;
	}

	public void clearCache() {
		finishCache(false);
	}

	private void finishCache(boolean retire) {
		Runnable notification = null;
		boolean markedRemoval = false;
		batchOwner.lock();
		try {
			synchronized (this) {
				if (inFlight) throw new IllegalStateException("Cannot retire a cache from its own storage write");
				if (cache == null) return;
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
			}
		} finally {
			if (markedRemoval) synchronized (this) { removing = false; }
			batchOwner.unlock();
		}
		if (notification != null) notification.run();
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
		finishCache(true);
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
		synchronized (this) {
			if (inFlight) throw new IllegalStateException("Cannot recursively flush a user cache storage write");
			if (uuid == null || cachedChanges == null || cachedChanges.isEmpty()) return null;
			changes = new ArrayList<>(cachedChanges);
			// Preparation failure leaves the original queue intact.
			for (UserDataChange change : changes) {
				values.put(change.getKey(), change.toUserDataValue());
				keys.add(change.getKey());
			}
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
				}
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

	public void updateCache(HashMap<String, DataValue> tempCache) {
		cache = tempCache;
	}
}
