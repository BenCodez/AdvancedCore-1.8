package com.bencodez.advancedcore.api.user.usercache;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.misc.PlayerManager;
import com.bencodez.advancedcore.api.user.UserStorage;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKey;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKeyBoolean;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKeyInt;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKeyString;

import lombok.Getter;

public class UserDataManager {
	@Getter
	private ArrayList<UserDataKey> keys;

	@Getter
	private ArrayList<String> intColumns;

	@Getter
	private ArrayList<String> booleanColumns;

	@Getter
	private AdvancedCorePlugin plugin;

	@Getter
	private ScheduledExecutorService timer;

	@Getter
	private ConcurrentHashMap<UUID, UserDataCache> userDataCache;

    private final ConcurrentHashMap<UUID,OnlineSessionState> onlineUserSessions=new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong onlineSessionGeneration=new java.util.concurrent.atomic.AtomicLong();
    private final Object[] onlineSessionLocks=createOnlineSessionLocks();
    @Getter private volatile Throwable lastDeferredStorageFailure;

    private static final class OnlineSessionState {
        final boolean online;final long generation;
        OnlineSessionState(boolean online,long generation){this.online=online;this.generation=generation;}
    }
    private static Object[] createOnlineSessionLocks() {
        Object[] locks=new Object[64];java.util.Arrays.setAll(locks,ignored->new Object());return locks;
    }
    private Object onlineSessionLock(UUID uuid){return onlineSessionLocks[(uuid.hashCode() & Integer.MAX_VALUE)%onlineSessionLocks.length];}

	private volatile SharedSqlNotificationRoute sharedSqlNotificationRoute;
	private final java.util.concurrent.atomic.AtomicLong sharedNotificationGeneration = new java.util.concurrent.atomic.AtomicLong();
	private volatile boolean sharedNotificationsClosed;

	private static final class SharedSqlNotificationRoute {
		final com.bencodez.advancedcore.core.user.storage.sql.SqlUserBackend backend;
		final java.util.function.Consumer<Runnable> lifecycleGate;
		SharedSqlNotificationRoute(com.bencodez.advancedcore.core.user.storage.sql.SqlUserBackend backend,
				java.util.function.Consumer<Runnable> lifecycleGate) {
			this.backend = java.util.Objects.requireNonNull(backend, "backend");
			this.lifecycleGate = java.util.Objects.requireNonNull(lifecycleGate, "lifecycleGate");
		}
	}

	/** Bind the producing owner's lifetime; per-user storage routing is integrated separately. */
	public final synchronized void bindSharedUserDataNotificationLifecycle(
			com.bencodez.advancedcore.core.user.storage.sql.SqlUserBackend backend,
			java.util.function.Consumer<Runnable> lifecycleGate) {
		if (sharedNotificationsClosed) throw new IllegalStateException("Shared user notifications are closed");
		SharedSqlNotificationRoute replacement = new SharedSqlNotificationRoute(backend, lifecycleGate);
		SharedSqlNotificationRoute previous = sharedSqlNotificationRoute;
		if (previous != null && previous.backend != backend) sharedNotificationGeneration.incrementAndGet();
		sharedSqlNotificationRoute = replacement;
	}

	/** Capture the producing generation and owner before storage admission is released. */
	public final Runnable captureSharedUserDataNotification(Runnable notification) {
		java.util.Objects.requireNonNull(notification, "notification");
		long generation = sharedNotificationGeneration.get();
		SharedSqlNotificationRoute route = sharedSqlNotificationRoute;
		return () -> {
			if (sharedNotificationsClosed || generation != sharedNotificationGeneration.get()) return;
			Runnable admitted = () -> {
				if (!sharedNotificationsClosed && generation == sharedNotificationGeneration.get()) notification.run();
			};
			if (route == null) admitted.run();
			else try { route.lifecycleGate.accept(admitted); }
			catch (RuntimeException | Error failure) {
				if (!sharedNotificationsClosed && generation == sharedNotificationGeneration.get()) throw failure;
			}
		};
	}

	/** Queue captured notifications on the existing storage worker, outside per-user admission. */
	public final void dispatchSharedUserDataNotification(Runnable notification) {
		java.util.Objects.requireNonNull(notification, "notification");
		if (sharedNotificationsClosed) return;
		Runnable captured = captureSharedUserDataNotification(notification);
		try {
			timer.execute(() -> {
				try { captured.run(); }
				catch (RuntimeException | Error failure) { reportDeferredStorageFailure(failure); }
			});
		} catch (java.util.concurrent.RejectedExecutionException failure) {
			reportDeferredStorageFailure(failure);
			throw failure;
		}
	}

	public final void advanceSharedUserDataNotificationGeneration() {
		sharedNotificationGeneration.incrementAndGet();
	}

	public final void closeSharedUserDataNotifications() {
		sharedNotificationsClosed = true;
		sharedNotificationGeneration.incrementAndGet();
	}

	public final void recordSharedStorageFailure(Throwable failure) {
		reportDeferredStorageFailure(java.util.Objects.requireNonNull(failure, "failure"));
	}

	public UserDataManager(AdvancedCorePlugin plugin) {
		this.plugin = plugin;
		userDataCache = new ConcurrentHashMap<>();
		keys = new ArrayList<>();
		intColumns = new ArrayList<>();
		booleanColumns = new ArrayList<>();
		timer = Executors.newScheduledThreadPool(1);
		loadKeys();

		// run every hour to clear some cache
		timer.scheduleAtFixedRate(new Runnable() {

			@Override
			public void run() {
				if (plugin != null && plugin.isEnabled()) {
					clearNonNeededCachedUsers();
				}
			}
		}, 60 * 3, 60 * 60, TimeUnit.SECONDS);
	}

	public synchronized void addKey(UserDataKey userDataKey) {
		keys.add(userDataKey);
		if (userDataKey instanceof UserDataKeyInt) {
			intColumns.add(userDataKey.getKey());
		} else if (userDataKey instanceof UserDataKeyBoolean) {
			booleanColumns.add(userDataKey.getKey());
		}

	}

	/**
	 * Capture registered schema membership without holding the registration monitor
	 * while callers perform SQL work. Key objects retain their legacy mutability;
	 * the public getKeys() collection remains available for compatibility.
	 */
	public synchronized ArrayList<UserDataKey> getRegisteredKeysSnapshot() {
		return new ArrayList<>(keys);
	}

	@Deprecated
	public void cacheUser(UUID uuid) {
		plugin.devDebug("Caching " + uuid.toString());
		UserDataCache data = userDataCache.get(uuid);
		if (data != null && !data.isRetired()) {
			data.clearChanges();
			data.cache();
		} else getOrPopulate(uuid);
	}

	public void cacheUser(UUID uuid, String playerName) {
		if (playerName != null && !playerName.isEmpty() && !plugin.getOptions().isOnlineMode()) {
			uuid = UUID.fromString(PlayerManager.getInstance().getUUID(playerName));
		}
		cacheUser(uuid);
	}

	private UserDataCache getOrPopulate(UUID uuid) {
		try (UserStorageOwnership.Scope admission = getPlugin().getUserStorageOwnership().admit()) {
		UserDataCache current = userDataCache.get(uuid);
		if (current != null && !current.isRetired()) return current;
		UserStorageOwnership.Slot owner = plugin.getUserStorageOwnership().owner(uuid);
		long readRevision = owner.getRevision();
		// Private reads remain outside registry locks. A published live successor
		// wins; intervening physical writes require a fresh candidate.
		UserDataCache candidate = new UserDataCache(this, uuid).cache();
		owner.getLock().lock();
		try {
			current = userDataCache.get(uuid);
			if (current != null && !current.isRetired()) return current;
			if (owner.getRevision() != readRevision) candidate = new UserDataCache(this, uuid).cache();
			if (!candidate.hasCache()) return null;
			final UserDataCache prepared = candidate;
			return userDataCache.compute(uuid, (key, registered) ->
					registered == null || registered.isRetired() ? prepared : registered);
		} finally { owner.getLock().unlock(); }
			}
	}

	public void cacheUserIfNeeded(UUID uuid) {
		getOrPopulate(uuid);
	}

	public void clearCache() {
		plugin.debug("Clearing cache: " + userDataCache.keySet().size());
		// Clear only generations captured here, never a replacement installed by
		// concurrent work or a post-commit callback.
		for (java.util.Map.Entry<UUID, UserDataCache> entry : new ArrayList<>(userDataCache.entrySet())) {
			retire(entry.getKey(), entry.getValue());
		}
	}

	/** Final shutdown cannot deliver change notifications that admit new work. */
	public void clearCacheForShutdown() {
		getPlugin().getUserStorageOwnership().requireFinalFlush();
		for (java.util.Map.Entry<UUID, UserDataCache> entry : new ArrayList<>(getUserDataCache().entrySet())) {
			retire(entry.getKey(), entry.getValue(), false);
		}
	}

	private void retire(UUID uuid, UserDataCache cache) {
		retire(uuid, cache, true);
	}

    private void retire(UUID uuid,UserDataCache cache,boolean notify) {
        retire(uuid,cache,notify,false);
    }

    private boolean retire(UUID uuid,UserDataCache cache,boolean notify,boolean onlyOffline) {
        final long expectedVersion=onlyOffline ? cache.cleanupSnapshotVersion() : 0L;
        try(UserStorageOwnership.Scope admission=getPlugin().getUserStorageOwnership().admit()) {
            UserStorageOwnership.Slot owner=plugin.getUserStorageOwnership().owner(uuid);
            Runnable notification;boolean countRetirement;
            owner.getLock().lock();
            try {
                if(onlyOffline && (userDataCache.get(uuid)!=cache || cache.cleanupSnapshotVersion()!=expectedVersion || isUserOnline(uuid)))return false;
                // Join markers must not wait for physical storage. Once flush starts,
                // retire that flushed generation even if a concurrent join arrives.
                notification=cache.retireForManager();
                userDataCache.remove(uuid,cache);
                countRetirement=!onlyOffline || !isUserOnline(uuid);
            }finally {owner.getLock().unlock();}
            if(notify && notification!=null)notification.run();
            return countRetirement;
        }
    }

	/** Resolve cached/uncached ownership at execution, not asynchronous admission. */
	public void writeDirect(com.bencodez.advancedcore.api.user.AdvancedCoreUser user, String key,
			com.bencodez.simpleapi.sql.data.DataValue value, Runnable storageWrite) {
		try (UserStorageOwnership.Scope admission = getPlugin().getUserStorageOwnership().admit()) {
		UUID identity = UUID.fromString(user.getUUID());
		UserStorageOwnership.Slot owner = getPlugin().getUserStorageOwnership().owner(identity);
		UserDataCache current;
		owner.getLock().lock();
		try {
			current = getUserDataCache().get(identity);
			if (current != null && current.isRetired()) current = null;
			if (current == null) storageWrite.run();
		} finally { owner.getLock().unlock(); }
		if (current == null) getPlugin().getUserManager().onChange(user, key);
		// Releasing before this call keeps notifications outside ownership.
		// If retirement wins this gap, the retired handle rejects visibly.
		else current.writeDirect(key, value, storageWrite);
			}
	}

	/** Checked read/modify/write using the current cache generation or the shared uncached owner. */
	public com.bencodez.simpleapi.sql.data.DataValue mutateDirect(
			com.bencodez.advancedcore.api.user.AdvancedCoreUser user, String key,
			java.util.function.Supplier<com.bencodez.simpleapi.sql.data.DataValue> storageRead,
			java.util.function.Function<com.bencodez.simpleapi.sql.data.DataValue, com.bencodez.simpleapi.sql.data.DataValue> transform,
			java.util.function.Consumer<com.bencodez.simpleapi.sql.data.DataValue> storageWrite) {
		java.util.Objects.requireNonNull(storageRead, "storageRead");
		java.util.Objects.requireNonNull(transform, "transform");
		java.util.Objects.requireNonNull(storageWrite, "storageWrite");
		try (UserStorageOwnership.Scope admission = getPlugin().getUserStorageOwnership().admit()) {
			UUID identity = UUID.fromString(user.getUUID());
			UserStorageOwnership.Slot owner = getPlugin().getUserStorageOwnership().owner(identity);
			UserDataCache current;
			com.bencodez.simpleapi.sql.data.DataValue committed = null;
			owner.getLock().lock();
			try {
				if (owner.isWriting()) throw new IllegalStateException("Recursive user mutation");
				current = getUserDataCache().get(identity);
				if (current != null && current.isRetired()) current = null;
				if (current == null) {
					committed = java.util.Objects.requireNonNull(transform.apply(storageRead.get()), "transformed value");
					storageWrite.accept(committed);
				}
			} finally { owner.getLock().unlock(); }
			if (current != null) {
				// Retirement in this gap rejects visibly before mutation, never silently
				// switching to a successor after an earlier read.
				return current.mutateDirect(key, value -> transform.apply(value == null ? storageRead.get() : value), storageWrite);
			}
			try { getPlugin().getUserManager().onChange(user, key); }
			catch (RuntimeException | Error failure) { throw new CommittedUserDataMutationException(committed, failure); }
			return committed;
		}
	}

	/** Bulk writes preserve the legacy absence of their own change notification. */
	public void writeBatch(com.bencodez.advancedcore.api.user.AdvancedCoreUser user,
			java.util.Map<String, com.bencodez.simpleapi.sql.data.DataValue> values,
			Runnable storageWrite, boolean publishActiveCache) {
		try (UserStorageOwnership.Scope admission = getPlugin().getUserStorageOwnership().admit()) {
		if (values.isEmpty()) return;
		java.util.Objects.requireNonNull(storageWrite, "storageWrite");
		UUID identity = UUID.fromString(user.getUUID());
		UserStorageOwnership.Slot owner = getPlugin().getUserStorageOwnership().owner(identity);
		UserDataCache current;
		owner.getLock().lock();
		try {
			current = publishActiveCache ? getUserDataCache().get(identity) : null;
			if (current != null && current.isRetired()) current = null;
			if (current == null) storageWrite.run();
		} finally { owner.getLock().unlock(); }
		if (current != null) current.writeDirectBatch(values, storageWrite);
			}
	}

	/** Flush and delete one identity before retiring its active cache generation. */
	public void removeFromStorage(com.bencodez.advancedcore.api.user.AdvancedCoreUser user, Runnable storageDelete) {
		try (UserStorageOwnership.Scope admission = getPlugin().getUserStorageOwnership().admit()) {
		java.util.Objects.requireNonNull(storageDelete, "storageDelete");
		UUID identity = UUID.fromString(user.getUUID());
		UserStorageOwnership.Slot owner = getPlugin().getUserStorageOwnership().owner(identity);
		Runnable[] notification = new Runnable[1];
		Throwable failure = null;
		boolean committed = false;
		owner.getLock().lock();
		try {
			if (owner.isWriting()) throw new IllegalStateException("Recursive user removal");
			UserDataCache current = getUserDataCache().get(identity);
			if (current != null && !current.isRetired()) {
				current.deleteForManager(storageDelete, notification);
				getUserDataCache().remove(identity, current);
			} else storageDelete.run();
			committed = true;
		} catch (RuntimeException | Error rejected) { failure = rejected; throw rejected; }
		finally {
			owner.getLock().unlock();
			if (notification[0] != null) try { notification[0].run(); }
			catch (RuntimeException | Error rejected) {
				if (failure != null) { if (failure != rejected) failure.addSuppressed(rejected); }
				else if (committed) throw new CommittedUserDataRemovalException(rejected);
				else throw rejected;
			}
		}
			}
	}

	public void clearCacheBasic() {
		if (plugin.getStorageType().equals(UserStorage.MYSQL)) {
			plugin.getMysql().clearCacheBasic();
		}
	}

    /** Capture Bukkit state on its owner; flush and retire only on the storage worker. */
    public void clearNonNeededCachedUsers() {
        final com.bencodez.advancedcore.api.rewards.ServerThreadRewardDispatch owner=plugin.getRewardDispatch();
        owner.dispatch(()->{
            java.util.HashSet<UUID> platformOnline=new java.util.HashSet<>();
            if(Bukkit.getServer()!=null)for(Player player:Bukkit.getOnlinePlayers())platformOnline.add(onlineStorageUuid(player));
            final long generation=onlineSessionGeneration.get();
            timer.execute(()->{
                try {clearNonNeededCachedUsers(reconcileOnlineSnapshot(platformOnline,generation));}
                catch(RuntimeException | Error failure){reportDeferredStorageFailure(failure);throw failure;}
            });
            return java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
        },30000).whenComplete((unused,failure)->{if(failure!=null)reportDeferredStorageFailure(failure);});
    }

    private void reportDeferredStorageFailure(Throwable failure) {
        lastDeferredStorageFailure=failure;
        plugin.getLogger().log(java.util.logging.Level.SEVERE,"Deferred user-cache cleanup failed",failure);
    }

    private java.util.Set<UUID> reconcileOnlineSnapshot(java.util.Set<UUID> platformOnline,long generation) {
        java.util.Set<UUID> online=new java.util.HashSet<>();
        for(UUID uuid:platformOnline)synchronized(onlineSessionLock(uuid)) {
            OnlineSessionState state=onlineUserSessions.computeIfAbsent(uuid,ignored->new OnlineSessionState(true,generation));
            if(state.online)online.add(uuid);
        }
        for(UUID uuid:new ArrayList<>(onlineUserSessions.keySet()))synchronized(onlineSessionLock(uuid)) {
            OnlineSessionState state=onlineUserSessions.get(uuid);
            if(state!=null && !state.online && state.generation<=generation && !platformOnline.contains(uuid))onlineUserSessions.remove(uuid,state);
        }
        return online;
    }

    private UUID onlineStorageUuid(Player player) {
        if(plugin.getOptions().isOnlineMode())return player.getUniqueId();
        return UUID.fromString(PlayerManager.getInstance().getUUID(player.getName()));
    }
    public void markUserOnline(UUID uuid) {
        if(uuid!=null)synchronized(onlineSessionLock(uuid)){onlineUserSessions.put(uuid,new OnlineSessionState(true,onlineSessionGeneration.incrementAndGet()));}
    }
    /** Invoke from the native owner-thread join event, even when delayed user loading is disabled. */
    public void markUserOnline(Player player){if(player!=null)markUserOnline(onlineStorageUuid(player));}
    public void markUserOffline(UUID uuid) {
        if(uuid!=null)synchronized(onlineSessionLock(uuid)){onlineUserSessions.put(uuid,new OnlineSessionState(false,onlineSessionGeneration.incrementAndGet()));}
    }
    public void markUserOffline(Player player){if(player!=null)markUserOffline(onlineStorageUuid(player));}
    private boolean isUserOnline(UUID uuid){OnlineSessionState state=onlineUserSessions.get(uuid);return state!=null && state.online;}

    private void clearNonNeededCachedUsers(java.util.Set<UUID> onlineSnapshot) {
        plugin.devDebug("Clearing cache for non online players (if any)");
        int removed=0;
        for(java.util.Map.Entry<UUID,UserDataCache> entry:new ArrayList<>(userDataCache.entrySet())) {
            if(onlineSnapshot.contains(entry.getKey()) || isUserOnline(entry.getKey()))continue;
            if(retire(entry.getKey(),entry.getValue(),true,true))removed++;
        }
        if(removed>0)plugin.devDebug("Removed "+removed+" cached users who are no longer online");
    }

	public boolean containsKey(UUID fromString) {
		UserDataCache cache = userDataCache.get(fromString);
		return cache != null && !cache.isRetired();
	}

	public UserDataCache getCache(UUID uuid) {
		return getOrPopulate(uuid);
	}

	public synchronized boolean isBoolean(String str) {
		return booleanColumns.contains(str);
	}

	public boolean isCached(UUID uuid) {
		UserDataCache cache = userDataCache.get(uuid);
		return cache != null && cache.hasCache();
	}

	public synchronized boolean isInt(String str) {
		return intColumns.contains(str);
	}

	private void loadKeys() {
		addKey(new UserDataKeyString("PlayerName").setColumnType("VARCHAR(30)"));
		addKey(new UserDataKeyString("OfflineRewards").setColumnType("MEDIUMTEXT"));
		addKey(new UserDataKeyString("UnClaimedChoices"));
		addKey(new UserDataKeyString("TimedRewards"));
		addKey(new UserDataKeyString("LastOnline").setColumnType("VARCHAR(20)"));
		addKey(new UserDataKeyString("InputMethod"));
		addKey(new UserDataKeyString("ChoicePreference"));
		addKey(new UserDataKeyBoolean("CheckWorld"));
	}

	public void removeCache(UUID uuid, String playerName) {
		if (playerName != null && !playerName.isEmpty()) {
			if (!plugin.getOptions().isOnlineMode()) {
				uuid = UUID.fromString(PlayerManager.getInstance().getUUID(playerName));
			}
		}
		UserDataCache cache = userDataCache.get(uuid);
		if (cache != null) retire(uuid, cache);
	}

	public void updateCacheOnline() {
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (isCached(p.getUniqueId())) {
				cacheUser(p.getUniqueId());
			}
		}
	}
}
