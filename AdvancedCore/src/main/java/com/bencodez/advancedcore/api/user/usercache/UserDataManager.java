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

	public void addKey(UserDataKey userDataKey) {
		keys.add(userDataKey);
		if (userDataKey instanceof UserDataKeyInt) {
			intColumns.add(userDataKey.getKey());
		} else if (userDataKey instanceof UserDataKeyBoolean) {
			booleanColumns.add(userDataKey.getKey());
		}

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

	private void retire(UUID uuid, UserDataCache cache) {
		UserStorageOwnership.Slot owner = plugin.getUserStorageOwnership().owner(uuid);
		Runnable notification;
		owner.getLock().lock();
		try {
			notification = cache.retireForManager();
			userDataCache.remove(uuid, cache);
		} finally { owner.getLock().unlock(); }
		if (notification != null) notification.run();
	}

	/** Resolve cached/uncached ownership at execution, not asynchronous admission. */
	public void writeDirect(com.bencodez.advancedcore.api.user.AdvancedCoreUser user, String key,
			com.bencodez.simpleapi.sql.data.DataValue value, Runnable storageWrite) {
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

	/** Bulk writes preserve the legacy absence of their own change notification. */
	public void writeBatch(com.bencodez.advancedcore.api.user.AdvancedCoreUser user,
			java.util.Map<String, com.bencodez.simpleapi.sql.data.DataValue> values,
			Runnable storageWrite, boolean publishActiveCache) {
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

	/** Flush and delete one identity before retiring its active cache generation. */
	public void removeFromStorage(com.bencodez.advancedcore.api.user.AdvancedCoreUser user, Runnable storageDelete) {
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

	public void clearCacheBasic() {
		if (plugin.getStorageType().equals(UserStorage.MYSQL)) {
			plugin.getMysql().clearCacheBasic();
		}
	}

	public void clearNonNeededCachedUsers() {
		plugin.devDebug("Clearing cache for non online players (if any)");
		ArrayList<UUID> onlineUUIDS = new ArrayList<>();
		for (Player p : Bukkit.getOnlinePlayers()) {
			onlineUUIDS.add(p.getUniqueId());
		}
		int removed = 0;
		for (UUID uuid : userDataCache.keySet()) {
			if (!onlineUUIDS.contains(uuid)) {
				removeCache(uuid, null);
				removed++;
			}
		}
		if (removed > 0) {
			plugin.devDebug("Removed " + removed + " cached users who are no longer online");
		}
	}

	public boolean containsKey(UUID fromString) {
		UserDataCache cache = userDataCache.get(fromString);
		return cache != null && !cache.isRetired();
	}

	public UserDataCache getCache(UUID uuid) {
		return getOrPopulate(uuid);
	}

	public boolean isBoolean(String str) {
		return booleanColumns.contains(str);
	}

	public boolean isCached(UUID uuid) {
		UserDataCache cache = userDataCache.get(uuid);
		return cache != null && cache.hasCache();
	}

	public boolean isInt(String str) {
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
