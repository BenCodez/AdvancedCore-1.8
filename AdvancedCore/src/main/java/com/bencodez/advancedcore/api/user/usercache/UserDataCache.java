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
	private final UserStorageOwnership.Slot storageOwner;
	private final ReentrantLock batchOwner;
	private boolean removing;
	private boolean inFlight;
	private boolean flushFailureReported;
	private long snapshotVersion;
	private long replacementVersion;
	private final HashMap<String, Long> changedAt = new HashMap<>();
	private final HashMap<String, DataValue> inFlightValues = new HashMap<>();
	private volatile java.util.function.Consumer<HashMap<String, DataValue>> sharedStorageWriter;
	private volatile java.util.function.Consumer<Runnable> sharedFlushGate;
	private volatile java.util.function.Consumer<Runnable> sharedExclusiveFlushGate;
	private int exclusiveFlushesPending;
	private final Queue<UserDataChange> changesAfterExclusiveFlush = new ConcurrentLinkedQueue<>();
	private final Queue<Runnable> notificationsAfterExclusiveFlush = new ConcurrentLinkedQueue<>();
	private boolean flushChangesAfterExclusive;
	private final java.util.concurrent.locks.ReentrantReadWriteLock legacyMutationOrder =
			new java.util.concurrent.locks.ReentrantReadWriteLock(true);
	private boolean scheduled = false;
	@Getter
	private volatile UUID uuid;

	public UserDataCache(UserDataManager manager, UUID uuid) {
		this.uuid = uuid;
		this.manager = manager;
		storageOwner = manager.getPlugin().getUserStorageOwnership().owner(uuid);
		batchOwner = storageOwner.getLock();
		cachedChanges = new ConcurrentLinkedQueue<>();
		cache = new HashMap<>();
	}

	public void addChange(UserDataChange change, boolean queue) {
		java.util.function.Consumer<Runnable> gate = sharedFlushGate;
		if (gate != null) gate.accept(() -> addChangeInternal(change, queue));
		else {
			legacyMutationOrder.readLock().lock();
			try { addChangeInternal(change, queue); }
			finally { legacyMutationOrder.readLock().unlock(); }
		}
	}

	public boolean tryAddChangeBeforeDeferredSharedFlush(UserDataChange change) {
		return tryAddChangeBeforeDeferredSharedFlush(change, null, false);
	}

	public boolean tryAddChangeBeforeDeferredSharedFlush(UserDataChange change, Runnable notification) {
		return tryAddChangeBeforeDeferredSharedFlush(change, notification, false);
	}

	public boolean tryAddChangeBeforeDeferredSharedFlush(UserDataChange change, Runnable notification,
			boolean flushImmediately) {
		return tryAddChangesBeforeDeferredSharedFlush(java.util.Collections.singletonList(change), notification,
				flushImmediately);
	}

	public boolean tryAddChangesBeforeDeferredSharedFlush(Iterable<UserDataChange> changes) {
		return tryAddChangesBeforeDeferredSharedFlush(changes, null, false);
	}

	public boolean tryAddChangesBeforeDeferredSharedFlush(Iterable<UserDataChange> changes, boolean flushImmediately) {
		return tryAddChangesBeforeDeferredSharedFlush(changes, null, flushImmediately);
	}

	private boolean tryAddChangesBeforeDeferredSharedFlush(Iterable<UserDataChange> changes,
			Runnable notification, boolean flushImmediately) {
		Objects.requireNonNull(changes, "changes");
		boolean staged;
		synchronized (this) {
			if (sharedFlushGate == null || removing || uuid == null || cache == null || cachedChanges == null) return false;
			staged = exclusiveFlushesPending != 0;
			for (UserDataChange change : changes) {
				Objects.requireNonNull(change, "change");
				if (staged) {
					cache.put(change.getKey(), change.toUserDataValue());
					changedAt.put(change.getKey(), ++snapshotVersion);
					changesAfterExclusiveFlush.add(change);
				} else addChangeInternal(change, true);
			}
			if (staged) {
				if (notification != null) notificationsAfterExclusiveFlush.add(captureNotification(notification));
				flushChangesAfterExclusive |= flushImmediately;
			}
		}
		if (!staged) {
			if (notification != null) deliverNotification(captureNotification(notification));
			if (flushImmediately) processChangesAsync();
		}
		return true;
	}

	/** Flush the admitted prefix, run its checkpoint, then release later staged mutations. */
	public void flushChangesAndRun(Runnable action) {
		if (action == null) return;
		java.util.function.Consumer<Runnable> gate = sharedExclusiveFlushGate;
		ArrayList<Runnable> notifications = new ArrayList<>();
		java.util.concurrent.atomic.AtomicBoolean flushLater = new java.util.concurrent.atomic.AtomicBoolean();
		Throwable primaryFailure = null;
		try {
			Runnable checkpoint = () -> {
				try (UserStorageOwnership.Scope admission = manager.getPlugin().getUserStorageOwnership().admit()) {
					boolean marked = false;
					boolean locked = false;
					try {
						synchronized (this) {
							if (removing || uuid == null || cache == null || cachedChanges == null) {
								throw new IllegalStateException("User cache is retiring or retired");
							}
							if (gate != null) { exclusiveFlushesPending++; marked = true; }
						}
						batchOwner.lock();
						locked = true;
						Runnable notification;
						while ((notification = flushClaimedChanges()) != null) notifications.add(notification);
						action.run();
					} finally {
						try { if (marked) synchronized (this) {
							if (--exclusiveFlushesPending == 0) {
								drainStagedChanges(notifications);
								flushLater.set(flushChangesAfterExclusive);
								flushChangesAfterExclusive = false;
								if (!flushLater.get() && !cachedChanges.isEmpty() && !scheduled) scheduleChanges();
							}
						}
						} finally { if (locked) batchOwner.unlock(); }
					}
				}
			};
			if (gate != null) gate.accept(checkpoint);
			else {
				legacyMutationOrder.writeLock().lock();
				try { checkpoint.run(); }
				finally { legacyMutationOrder.writeLock().unlock(); }
			}
		} catch (RuntimeException | Error failure) { primaryFailure = failure; throw failure; }
		finally {
			Throwable notificationFailure = null;
			try {
				for (Runnable notification : notifications) {
					try { deliverNotification(notification); }
					catch (RuntimeException | Error failure) {
						if (notificationFailure == null) notificationFailure = failure;
						else if (notificationFailure != failure) notificationFailure.addSuppressed(failure);
					}
				}
			} finally {
				if (flushLater.get()) try { processChangesAsync(); }
				catch (RuntimeException | Error failure) {
					if (notificationFailure == null) notificationFailure = failure;
					else if (notificationFailure != failure) notificationFailure.addSuppressed(failure);
				}
			}
			if (notificationFailure != null) {
				if (primaryFailure != null) { if (primaryFailure != notificationFailure) primaryFailure.addSuppressed(notificationFailure); }
				else if (notificationFailure instanceof RuntimeException) throw (RuntimeException) notificationFailure;
				else throw (Error) notificationFailure;
			}
		}
	}

	/** Preserve the accepted staged payload if its conversion cannot yet complete. */
	private synchronized void drainStagedChanges(ArrayList<Runnable> notifications) {
		UserDataChange change;
		while ((change = changesAfterExclusiveFlush.peek()) != null) {
			DataValue value = change.toUserDataValue();
			cache.put(change.getKey(), value);
			changedAt.put(change.getKey(), ++snapshotVersion);
			cachedChanges.add(change);
			changesAfterExclusiveFlush.remove();
		}
		Runnable notification;
		while ((notification = notificationsAfterExclusiveFlush.poll()) != null) notifications.add(notification);
	}

	private synchronized void addChangeInternal(UserDataChange change, boolean queue) {
		try (UserStorageOwnership.Scope admission = manager.getPlugin().getUserStorageOwnership().admit()) {
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
	}

	public UserDataCache cache() {
		try (UserStorageOwnership.Scope admission = manager.getPlugin().getUserStorageOwnership().admit()) {
		final UUID currentUuid;
		final long expectedVersion;
		final HashMap<String, DataValue> before;
		synchronized (this) {
			if (uuid == null || cache == null || removing) return this;
			currentUuid = uuid;
			expectedVersion = snapshotVersion;
			before = new HashMap<>(cache);
		}
		final long readRevision = storageOwner.getRevision();
		AdvancedCoreUser user = manager.getPlugin().getUserManager().getUser(currentUuid, false);
		HashMap<String, DataValue> refreshed;
		try { refreshed = new HashMap<>(user.getUserData().getValuesStrict()); }
		catch (SQLException | IOException failure) { throw new IllegalStateException("User cache snapshot was not read", failure); }
		ArrayList<String> keys = additionalKeysAndDefaults(refreshed);
		final HashMap<String, DataValue> published;
		batchOwner.lock();
		try {
			synchronized (this) {
				if (uuid == null || cache == null || removing || !uuid.equals(currentUuid)
						|| replacementVersion > expectedVersion) return this;
			}
			// A raw checked write may not update this cache's local version. Fence
			// publication with the plugin-local storage revision as well.
			if (storageOwner.getRevision() != readRevision) {
				try { refreshed = new HashMap<>(user.getUserData().getValuesStrict()); }
				catch (SQLException | IOException failure) { throw new IllegalStateException("User cache snapshot was not re-read", failure); }
				keys = additionalKeysAndDefaults(refreshed);
			}
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
		} finally { batchOwner.unlock(); }
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
	}

	private ArrayList<String> additionalKeysAndDefaults(HashMap<String, DataValue> refreshed) {
		ArrayList<String> keys = new ArrayList<>(refreshed.keySet());
		for (UserDataKey dataKey : manager.getRegisteredKeysSnapshot()) {
			keys.remove(dataKey.getKey());
			if (!refreshed.containsKey(dataKey.getKey())) refreshed.put(dataKey.getKey(), dataKey.getDefault());
		}
		return keys;
	}

	public void clearCache() {
		try (UserStorageOwnership.Scope admission = manager.getPlugin().getUserStorageOwnership().admit()) {
			Runnable notification = finishCache(false);
			if (notification != null) notification.run();
		}
	}

	private Runnable finishCache(boolean retire) {
		try (UserStorageOwnership.Scope admission = manager.getPlugin().getUserStorageOwnership().admit()) {
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
	}

	/** Manager holds canonical ownership; return older-prefix notification through its holder. */
	void deleteForManager(Runnable storageDelete, Runnable[] notification) {
		if (!batchOwner.isHeldByCurrentThread()) throw new IllegalStateException("User removal is not owned");
		synchronized (this) {
			if (uuid == null || cache == null || removing || inFlight) throw new IllegalStateException("User cache cannot accept removal");
			removing = true;
		}
		try {
			notification[0] = flushClaimedChanges();
			storageDelete.run();
			synchronized (this) {
				cache = null;
				cachedChanges = null;
				uuid = null;
				scheduled = false;
				changedAt.clear();
				inFlightValues.clear();
				replacementVersion = ++snapshotVersion;
			}
		} finally { synchronized (this) { removing = false; } }
	}

    /** Version selected by periodic cleanup before waiting on canonical storage ownership. */
    synchronized long cleanupSnapshotVersion() { return snapshotVersion; }

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
		try (UserStorageOwnership.Scope admission = manager.getPlugin().getUserStorageOwnership().admit()) {
			Runnable notification = finishCache(true);
			if (notification != null) notification.run();
		}
	}

	public AdvancedCoreUser getUser() {
		return manager.getPlugin().getUserManager().getUser(uuid, false);
	}

	public synchronized boolean hasCache() {
		return cache != null && !cache.isEmpty();
	}

	public synchronized boolean hasChangesToProcess() {
		return inFlight || !changesAfterExclusiveFlush.isEmpty() || (cachedChanges != null && !cachedChanges.isEmpty());
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
		// Legacy callbacks remain part of the admitted public operation. The shared
		// manager separately retains producing-runtime admission for queued callbacks.
		try (UserStorageOwnership.Scope admission = manager.getPlugin().getUserStorageOwnership().admit()) {
			Runnable notification = processChangesForSharedRuntime();
			if (notification != null) deliverNotification(notification);
		}
	}

	/** Persist one batch and return its notification after releasing both owners. */
	public Runnable processChangesForSharedRuntime() {
		java.util.function.Consumer<Runnable> gate = sharedFlushGate;
		java.util.concurrent.atomic.AtomicReference<Runnable> notification = new java.util.concurrent.atomic.AtomicReference<>();
		ArrayList<Runnable> stagedNotifications = new ArrayList<>();
		Runnable flush = () -> {
			try (UserStorageOwnership.Scope admission = manager.getPlugin().getUserStorageOwnership().admit()) {
				batchOwner.lock();
				try {
					synchronized (this) {
						if (exclusiveFlushesPending == 0 && (!changesAfterExclusiveFlush.isEmpty()
								|| !notificationsAfterExclusiveFlush.isEmpty())) {
							drainStagedChanges(stagedNotifications);
							flushChangesAfterExclusive = false;
						}
					}
					notification.set(flushClaimedChanges());
				} catch (RuntimeException | Error failure) {
					// The payload was requeued by the checked writer. Keep its staged
					// notification with it until the retry can return a completion receipt.
					synchronized (this) {
						ArrayList<Runnable> later = new ArrayList<>(notificationsAfterExclusiveFlush);
						notificationsAfterExclusiveFlush.clear();
						notificationsAfterExclusiveFlush.addAll(stagedNotifications);
						notificationsAfterExclusiveFlush.addAll(later);
						stagedNotifications.clear();
					}
					throw failure;
				} finally { batchOwner.unlock(); }
			}
		};
		if (gate == null) flush.run(); else gate.accept(flush);
		if (stagedNotifications.isEmpty()) return notification.get();
		Runnable committedNotification = notification.get();
		return () -> {
			try { for (Runnable staged : stagedNotifications) staged.run(); }
			finally { if (committedNotification != null) committedNotification.run(); }
		};
	}

	/** Serialize a direct checked write with queued batches and retirement. */
	public void writeDirect(String key, DataValue value, Runnable storageWrite) {
		mutateDirectInternal(key, ignored -> value, ignored -> storageWrite.run(), false);
	}

	/**
	 * Read, transform and physically write under the existing batch owner.
	 * The transform must be side-effect-free; a missing cached value is passed as
	 * null, not interpreted as an empty stored queue. Storage exceptions propagate.
	 * Notifications run after ownership is released, as for direct replacement.
	 * A post-commit notification failure carries the acknowledged value in
	 * CommittedUserDataMutationException; callers must not retry the mutation.
	 */
	public DataValue mutateDirect(String key, java.util.function.Function<DataValue, DataValue> transform,
			java.util.function.Consumer<DataValue> storageWrite) {
		Objects.requireNonNull(transform, "transform");
		Objects.requireNonNull(storageWrite, "storageWrite");
		return mutateDirectInternal(key, transform, storageWrite, true);
	}

	private DataValue mutateDirectInternal(String key, java.util.function.Function<DataValue, DataValue> transform,
			java.util.function.Consumer<DataValue> storageWrite, boolean requireValue) {
		try (UserStorageOwnership.Scope admission = manager.getPlugin().getUserStorageOwnership().admit()) {
		DataValue committedValue = null;
		Runnable pendingNotification = null;
		Runnable directNotification = null;
		Throwable failure = null;
		batchOwner.lock();
		try {
			synchronized (this) {
				if (uuid == null || removing || inFlight) throw new IllegalStateException("User cache cannot accept a direct write");
			}
			pendingNotification = flushClaimedChanges();
			final AdvancedCoreUser user = getUser();
			final Long expectedVersion;
			final DataValue current;
			synchronized (this) {
				expectedVersion = changedAt.get(key);
				current = cache.get(key);
				inFlight = true;
			}
			try {
				DataValue value = transform.apply(current);
				if (requireValue) Objects.requireNonNull(value, "transformed value");
				storageWrite.accept(value);
				committedValue = value;
				synchronized (this) {
					// Later queued changes remain optimistic and must not be overwritten.
					if (Objects.equals(expectedVersion, changedAt.get(key)) && !pendingKeys().contains(key)) {
						cache.put(key, value);
						changedAt.put(key, ++snapshotVersion);
					}
				}
				directNotification = () -> manager.getPlugin().getUserManager().onChange(user, key);
			} finally { synchronized (this) { inFlight = false; } }
		} catch (RuntimeException | Error rejected) { failure = rejected; throw rejected; }
		finally {
			batchOwner.unlock();
			// Notify every committed write, even when another callback or later write fails.
			Throwable notificationFailure = null;
			for (Runnable notification : new Runnable[] {pendingNotification, directNotification}) {
				if (notification != null) try { notification.run(); }
				catch (RuntimeException | Error rejected) {
					if (notificationFailure == null) notificationFailure = rejected;
					else if (notificationFailure != rejected) notificationFailure.addSuppressed(rejected);
				}
			}
			if (notificationFailure != null) {
				if (failure != null) { if (failure != notificationFailure) failure.addSuppressed(notificationFailure); }
				else if (requireValue && committedValue != null) {
					throw new CommittedUserDataMutationException(committedValue, notificationFailure);
				}
				else if (notificationFailure instanceof RuntimeException) throw (RuntimeException) notificationFailure;
				else throw (Error) notificationFailure;
			}
		}
		return committedValue;
			}
	}

	/** Publish a checked bulk replacement without adding legacy bulk change callbacks. */
	public void writeDirectBatch(java.util.Map<String, DataValue> values, Runnable storageWrite) {
		try (UserStorageOwnership.Scope admission = manager.getPlugin().getUserStorageOwnership().admit()) {
		HashMap<String, DataValue> candidate = new HashMap<>(values);
		if (candidate.isEmpty()) return;
		Objects.requireNonNull(storageWrite, "storageWrite");
		for (Entry<String, DataValue> entry : candidate.entrySet()) {
			Objects.requireNonNull(entry.getKey(), "key");
			Objects.requireNonNull(entry.getValue(), "value");
		}
		Runnable pendingNotification = null;
		Throwable failure = null;
		boolean committed = false;
		batchOwner.lock();
		try {
			synchronized (this) {
				if (uuid == null || removing || inFlight) throw new IllegalStateException("User cache cannot accept a bulk write");
			}
			pendingNotification = flushClaimedChanges();
			HashMap<String, Long> expectedVersions = new HashMap<>();
			synchronized (this) {
				for (String key : candidate.keySet()) expectedVersions.put(key, changedAt.get(key));
				inFlight = true;
			}
			try {
				storageWrite.run();
				committed = true;
				synchronized (this) {
					Set<String> pending = pendingKeys();
					for (Entry<String, DataValue> entry : candidate.entrySet()) {
						String key = entry.getKey();
						if (Objects.equals(expectedVersions.get(key), changedAt.get(key)) && !pending.contains(key)) {
							cache.put(key, entry.getValue());
							changedAt.put(key, ++snapshotVersion);
						}
					}
				}
			} finally { synchronized (this) { inFlight = false; } }
		} catch (RuntimeException | Error rejected) { failure = rejected; throw rejected; }
		finally {
			batchOwner.unlock();
			if (pendingNotification != null) try { pendingNotification.run(); }
			catch (RuntimeException | Error rejected) {
				if (failure != null) { if (failure != rejected) failure.addSuppressed(rejected); }
				else if (committed) throw new CommittedUserDataBatchException(candidate, rejected);
				else throw rejected;
			}
		}
			}
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
			java.util.function.Consumer<HashMap<String, DataValue>> writer = sharedStorageWriter;
			if (writer == null) user.getUserData().setValuesStrict(values);
			else writer.accept(values);
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
		return captureNotification(() -> {
			try { manager.getPlugin().getUserManager().onChange(user, ArrayUtils.convert(keys)); }
			finally { for (UserDataChange change : changes) change.dump(); }
		});
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
		// A synchronous unload mutation is included in the final owned flush, not a stopped timer.
		if (manager.getPlugin().getUserStorageOwnership().isFinalFlush()) return;
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

	private Runnable captureNotification(Runnable notification) {
		if (sharedStorageWriter == null) return notification;
		Runnable captured = manager.captureSharedUserDataNotification(notification);
		return captured == null ? notification : captured;
	}

	private void deliverNotification(Runnable notification) {
		if (sharedStorageWriter == null) notification.run();
		else manager.dispatchSharedUserDataNotification(notification);
	}

	/** Refuse a partial binding while a native provider batch is active. */
	public synchronized void ensureNoLegacyBatchForSharedBinding() {
		if (sharedStorageWriter == null && inFlight) throw new IllegalStateException("Cannot attach shared storage during an active legacy batch");
	}

	public void configureSharedStorage(java.util.function.Consumer<HashMap<String, DataValue>> writer,
			java.util.function.Consumer<Runnable> gate) {
		configureSharedStorage(writer, gate, gate);
	}

	public synchronized void configureSharedStorage(java.util.function.Consumer<HashMap<String, DataValue>> writer,
			java.util.function.Consumer<Runnable> gate, java.util.function.Consumer<Runnable> exclusiveGate) {
		Objects.requireNonNull(writer, "writer");
		Objects.requireNonNull(gate, "gate");
		Objects.requireNonNull(exclusiveGate, "exclusiveGate");
		if (sharedFlushGate != null && sharedFlushGate != gate) throw new IllegalStateException("Shared user cache already belongs to another runtime");
		if (sharedExclusiveFlushGate != null && sharedExclusiveFlushGate != exclusiveGate) throw new IllegalStateException("Shared user cache already belongs to another runtime");
		ensureNoLegacyBatchForSharedBinding();
		setSharedStorageWriter(writer);
		sharedFlushGate = gate;
		sharedExclusiveFlushGate = exclusiveGate;
	}

	public synchronized void setSharedStorageWriter(java.util.function.Consumer<HashMap<String, DataValue>> writer) {
		if (uuid == null || cachedChanges == null) throw new IllegalStateException("Shared user cache is retired");
		ensureNoLegacyBatchForSharedBinding();
		sharedStorageWriter = Objects.requireNonNull(writer, "writer");
	}

	public synchronized void retireAfterSharedFlush() {
		if (exclusiveFlushesPending != 0 || hasChangesToProcess()) throw new IllegalStateException("Shared user cache has unflushed work");
		cache = null;
		cachedChanges = null;
		uuid = null;
		scheduled = false;
		changedAt.clear();
		inFlightValues.clear();
		replacementVersion = ++snapshotVersion;
	}

	public synchronized void beginRemoval() { removing = true; }

	public synchronized void cancelRemoval() {
		if (cache != null && cachedChanges != null) removing = false;
	}

	public synchronized long getSharedSnapshotVersion() { return snapshotVersion; }

	public synchronized HashMap<String, DataValue> updateSharedSnapshot(HashMap<String, DataValue> values,
			long expectedVersion) {
		if (cache == null || uuid == null) throw new IllegalStateException("Shared user cache changed while loading");
		if (expectedVersion < 0 || expectedVersion > snapshotVersion) throw new IllegalArgumentException("Invalid cache snapshot version");
		if (replacementVersion > expectedVersion) return new HashMap<>(cache);
		HashMap<String, DataValue> merged = values == null ? new HashMap<>() : new HashMap<>(values);
		changedAt.forEach((key, version) -> {
			if (version >= expectedVersion && cache.containsKey(key)) merged.put(key, cache.get(key));
		});
		preservePendingValues(merged);
		cache = merged;
		recordSnapshotReplacement();
		return new HashMap<>(cache);
	}

	public synchronized void updateCachePreservingPending(HashMap<String, DataValue> values) {
		updateCache(values);
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
		for (UserDataChange change : changesAfterExclusiveFlush) keys.add(change.getKey());
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
