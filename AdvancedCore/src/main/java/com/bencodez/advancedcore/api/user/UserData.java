package com.bencodez.advancedcore.api.user;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

import org.bukkit.configuration.file.FileConfiguration;

import com.bencodez.advancedcore.api.user.usercache.UserDataCache;
import com.bencodez.advancedcore.api.user.usercache.change.UserDataChangeInt;
import com.bencodez.advancedcore.api.user.usercache.change.UserDataChangeString;
import com.bencodez.advancedcore.thread.FileThread;
import com.bencodez.simpleapi.array.ArrayUtils;
import com.bencodez.simpleapi.sql.Column;
import com.bencodez.simpleapi.sql.data.DataValue;
import com.bencodez.simpleapi.sql.data.DataValueInt;
import com.bencodez.simpleapi.sql.data.DataValueString;

import lombok.Getter;
import lombok.Setter;

public class UserData {
	@Getter
	@Setter
	private HashMap<String, DataValue> tempCache;

	private AdvancedCoreUser user;

	public UserData(AdvancedCoreUser user) {
		this.user = user;
	}

	public void clearTempCache() {
		tempCache.clear();
		tempCache = null;
	}

	public HashMap<String, DataValue> convert(List<Column> cols) {
		return com.bencodez.advancedcore.core.user.storage.SqlUserDataAccess.convert(cols);
	}

	public boolean getBoolean(String key) {
		return Boolean.valueOf(getString(key));
	}

	public boolean getBoolean(String key, boolean useCache, boolean waitForCache) {
		return Boolean.valueOf(getString(key, useCache, waitForCache));
	}

	@Deprecated
	public FileConfiguration getData(String uuid) {
		return FileThread.getInstance().getThread().getData(this, uuid);
	}

	public DataValue getDataValue(String key) {
		boolean isInt = user.getPlugin().getUserManager().getDataManager().isInt(key);
		if (isInt) {
			return new DataValueInt(getInt(key));
		}
		return new DataValueString(getString(key));
	}

	@Deprecated
	public int getInt(String key) {
		return getInt(key, 0, true, true);
	}

	public int getInt(String key, boolean waitForCache) {
		return getInt(key, 0, true, waitForCache);
	}

	public int getInt(String key, boolean useCache, boolean waitForCache) {
		return getInt(key, 0, useCache, waitForCache);
	}

	public int getInt(String key, int def, boolean waitForCache) {
		return getInt(user.getPlugin().getStorageType(), key, def, true, waitForCache);
	}

	public int getInt(String key, int def, boolean useCache, boolean waitForCache) {
		return getInt(user.getPlugin().getStorageType(), key, def, useCache, waitForCache);
	}

	@SuppressWarnings("deprecation")
	public int getInt(UserStorage storage, String key, int def, boolean useCache, boolean waitForCache) {
		if (!key.equals("")) {
			if (user.isTempCache() && tempCache != null) {
				if (tempCache.get(key) == null) {
					return def;
				}
				if (tempCache.get(key).isInt()) {
					return tempCache.get(key).getInt();
				}
			}
			// user.getPlugin().debug("Pulling data: " + key + " " + useCache + " " +
			// waitForCache);
			if (useCache) {
				UserDataCache cache = user.getCache();
				if (cache != null) {
					user.cacheIfNeeded();
					DataValue cached = cache.getCachedValue(key);
					if (cached != null) {
						if (cached.isInt()) return cached.getInt();
						String str = cached.getString();
						if (str != null && !str.equals("null")) {
							try {
								return Integer.parseInt(str);
							} catch (Exception e) {
							}
						}
					}
				} else {
					user.cache();
				}
			}
			if (storage.equals(UserStorage.SQLITE)) {
				List<Column> row = getSQLiteRow();
				if (row != null) {
					for (Column element : row) {
						if (element.getName().equals(key)) {
							DataValue value = element.getValue();
							if (value.isInt()) {
								return value.getInt();
							}
							if (value.isString()) {
								String str = value.getString();
								if (str != null) {
									try {
										return Integer.parseInt(str);
									} catch (Exception e) {
									}
								}
								return def;
							}
						}
					}

				}

			} else if (storage.equals(UserStorage.MYSQL)) {
				List<Column> row = getMySqlRow();
				if (row != null) {
					for (Column element : row) {
						if (element.getName().equals(key)) {
							DataValue value = element.getValue();
							if (value.isInt()) {
								return value.getInt();
							}
							if (value.isString()) {
								String str = value.getString();
								if (str != null) {
									try {
										return Integer.parseInt(str);
									} catch (Exception e) {
									}
								}
								return def;
							}
						}
					}
				}
			}
		} else if (storage.equals(UserStorage.FLAT)) {
			try {
				return getData(user.getUUID()).getInt(key, def);
			} catch (Exception e) {

			}

		}

		// user.getPlugin()
		// .extraDebug("Failed to get int from '" + key + "' for '" +
		// user.getPlayerName() + "'");

		return def;

	}

	public ArrayList<String> getKeys() {
		return getKeys(true);
	}

	@SuppressWarnings("deprecation")
	public ArrayList<String> getKeys(boolean waitForCache) {
		ArrayList<String> keys = new ArrayList<>();
		if (user.getPlugin().getStorageType().equals(UserStorage.FLAT)) {
			keys = new ArrayList<>(getData(user.getUUID()).getConfigurationSection("").getKeys(false));
		} else if (user.getPlugin().getStorageType().equals(UserStorage.MYSQL)) {
			List<Column> col = getMySqlRow();
			if (col != null && !col.isEmpty()) {
				for (Column c : col) {
					keys.add(c.getName());
				}
			}
		} else if (user.getPlugin().getStorageType().equals(UserStorage.SQLITE)) {
			List<Column> col = getSQLiteRow();
			if (col != null && !col.isEmpty()) {
				for (Column c : col) {
					keys.add(c.getName());
				}
			}
		}

		return keys;
	}

	@SuppressWarnings("deprecation")
	public ArrayList<String> getKeys(UserStorage storage, boolean waitForCache) {
		ArrayList<String> keys = new ArrayList<>();
		if (storage.equals(UserStorage.FLAT)) {
			keys = new ArrayList<>(getData(user.getUUID()).getConfigurationSection("").getKeys(false));
		} else if (storage.equals(UserStorage.MYSQL)) {
			List<Column> col = getMySqlRow();
			if (col != null && !col.isEmpty()) {
				for (Column c : col) {
					keys.add(c.getName());
				}
			}
		} else if (storage.equals(UserStorage.SQLITE)) {
			List<Column> col = getSQLiteRow();
			if (col != null && !col.isEmpty()) {
				for (Column c : col) {
					keys.add(c.getName());
				}
			}
		}

		return keys;
	}

	public List<Column> getMySqlRow() {
		com.bencodez.advancedcore.api.user.usercache.UserDataManager manager = sharedDataManager();
		if (manager != null && manager.hasSharedSqlBackend()) return sharedRow(manager, UserStorage.MYSQL);
		return user.getPlugin().getMysql().getExact(user.getUUID());
	}

	public List<Column> getSQLiteRow() {
		com.bencodez.advancedcore.api.user.usercache.UserDataManager manager = sharedDataManager();
		if (manager != null && manager.hasSharedSqlBackend()) return sharedRow(manager, UserStorage.SQLITE);
		return user.getPlugin().getSQLiteUserTable().getExact(new Column("uuid", new DataValueString(user.getUUID())));
	}

	@Deprecated
	public String getString(String key) {
		return getString(key, true, true);
	}

	public String getString(String key, boolean waitForCache) {
		return getString(user.getPlugin().getStorageType(), key, true, waitForCache);
	}

	public String getString(String key, boolean useCache, boolean waitForCache) {
		return getString(user.getPlugin().getStorageType(), key, useCache, waitForCache);
	}

	@SuppressWarnings("deprecation")
	public String getString(UserStorage storage, String key, boolean useCache, boolean waitForCache) {
		if (!key.equals("")) {
			if (user.isTempCache() && tempCache != null) {
				if (tempCache.get(key) == null) {
					return "";
				}
				if (tempCache.get(key).isString() || tempCache.get(key).isBoolean()) {
					String str = tempCache.get(key).getString();
					if (str != null) {
						return str;
					}
					return "";
				}
			}
			if (useCache) {
				UserDataCache cache = user.getCache();
				if (cache != null) {
					DataValue cached = cache.getCachedValue(key);
					if (cached != null) {
						String str = cached.getString();
						if (str != null) {
							return str;
						}
						return "";
					}
				} else {
					user.cache();
				}
			}

			if (storage.equals(UserStorage.SQLITE)) {
				List<Column> row = getSQLiteRow();
				if (row != null) {
					for (Column element : row) {
						if (element.getName().equals(key)
								&& (element.getValue().isString() || element.getValue().isBoolean())) {
							String st = element.getValue().getString();
							if (st != null && !st.equals("null")) {
								return st;
							}
							return "";
						}
					}
				}

			} else if (storage.equals(UserStorage.MYSQL)) {
				List<Column> row = getMySqlRow();
				if (row != null) {
					for (Column element : row) {
						if (element.getName().equals(key)
								&& (element.getValue().isString() || element.getValue().isBoolean())) {
							String st = element.getValue().getString();
							if (st != null && !st.equals("null")) {
								return st;
							}
							return "";
						}
					}
				}
			} else if (storage.equals(UserStorage.FLAT)) {
				try {
					return getData(user.getUUID()).getString(key, "");
				} catch (Exception e) {

				}
			}
		}
		/*
		 * if (user.getPlugin().isExtraDebug()) { user.getPlugin()
		 * .debug("Extra: Failed to get string from: '" + key + "' for '" +
		 * user.getPlayerName() + "'"); }
		 */
		return "";

	}

	public ArrayList<String> getStringList(String key) {
		return getStringList(key, true, true);
	}

	public ArrayList<String> getStringList(String key, boolean cache, boolean waitForCache) {
		String str = getString(key, cache, waitForCache);
		if (str == null || str.equals("")) {
			return new ArrayList<>();
		}
		String[] list = str.split("%line%");
		return ArrayUtils.convert(list);
	}

	public String getValue(String key) {
		boolean isInt = user.getPlugin().getUserManager().getDataManager().isInt(key);
		if (isInt) {
			return "" + getInt(key);
		}
		return getString(key);
	}

	/** One checked storage snapshot; absent identities are empty, failures propagate. */
	public HashMap<String, DataValue> getValuesStrict() throws SQLException, IOException {
		com.bencodez.advancedcore.api.user.usercache.UserDataManager manager = sharedDataManager();
		if (manager != null && manager.hasSharedSqlBackend()) {
			try { return convert(sharedRow(manager, manager.effectiveStorageType(user.getPlugin().getStorageType()))); }
			catch (IllegalStateException failure) {
				if (failure.getCause() instanceof SQLException) throw (SQLException) failure.getCause();
				throw failure;
			}
		}
		try (com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership.Scope admission = user.getPlugin().getUserStorageOwnership().admit()) {
		com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership.Slot owner = storageOwner();
		owner.getLock().lock();
		try {
			if (owner.isWriting()) throw new IllegalStateException("Cannot read user snapshot from its in-flight storage write");
			return readValuesStrictOwned();
		}
		finally { owner.getLock().unlock(); }
			}
	}

	private com.bencodez.advancedcore.api.user.usercache.UserDataManager sharedDataManager() {
		UserManager users = user.getPlugin().getUserManager();
		return users == null ? null : users.getDataManager();
	}

	private List<Column> sharedRow(com.bencodez.advancedcore.api.user.usercache.UserDataManager manager,
			UserStorage requested) {
		return manager.withSharedSqlBackend(java.util.UUID.fromString(user.getUUID()), (actual, storage) -> {
			if (actual != requested) throw new IllegalStateException("Requested user store differs from the active shared owner");
			List<Column> row = storage.readRow(actual);
			if (row == null) throw new IllegalStateException("Shared user storage omitted its checked snapshot");
			return row;
		});
	}

	private com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership.Slot storageOwner() {
		return user.getPlugin().getUserStorageOwnership().owner(java.util.UUID.fromString(user.getUUID()));
	}

	private HashMap<String, DataValue> readValuesStrictOwned() throws SQLException, IOException {
		UserStorage storage = user.getPlugin().getStorageType();
		if (storage == null) throw new IllegalStateException("User storage is not initialized");
		if (storage == UserStorage.FLAT) return FileThread.getInstance().getValuesStrict(user.getUUID());
		List<Column> columns;
		if (storage == UserStorage.MYSQL) {
			if (user.getPlugin().getMysql() == null) throw new SQLException("MySQL user storage is unavailable");
			columns = user.getPlugin().getMysql().getExactStrict(user.getUUID());
		} else if (storage == UserStorage.SQLITE) {
			if (user.getPlugin().getSQLiteUserTable() == null) throw new SQLException("SQLite user storage is unavailable");
			columns = user.getPlugin().getSQLiteUserTable().getExactStrict(new Column("uuid", new DataValueString(user.getUUID())));
		} else throw new IllegalStateException("Unsupported user storage");
		if (columns == null) throw new IllegalStateException("User storage omitted its checked snapshot");
		return convert(columns);
	}

	public HashMap<String, DataValue> getValues() {
		return getValues(user.getPlugin().getStorageType());
	}

	@SuppressWarnings("deprecation")
	public HashMap<String, DataValue> getValues(UserStorage storage) {
		if (storage.equals(UserStorage.MYSQL)) {
			return convert(getMySqlRow());
		}
		if (storage.equals(UserStorage.SQLITE)) {
			return convert(getSQLiteRow());
		} else if (storage.equals(UserStorage.FLAT)) {
			HashMap<String, DataValue> list = new HashMap<>();
			FileConfiguration data = getData(user.getUUID());
			for (String str : data.getKeys(false)) {
				if (data.isInt(str)) {
					list.put(str, new DataValueInt(data.getInt(str)));
				} else {
					list.put(str, new DataValueString(data.getString(str, "")));
				}
			}
			return list;
		}
		return null;
	}

	@SuppressWarnings("deprecation")
	public boolean hasData() {
        com.bencodez.advancedcore.api.user.usercache.UserDataManager manager = sharedDataManager();
        if (manager != null && manager.hasSharedSqlBackend()) {
            return manager.withSharedSqlBackend(java.util.UUID.fromString(user.getUUID()),
                    (type, storage) -> storage.contains(type));
        }
		if (user.getPlugin().getStorageType().equals(UserStorage.MYSQL)) {
			return user.getPlugin().getMysql().containsKey(user.getUUID());
		}
		if (user.getPlugin().getStorageType().equals(UserStorage.SQLITE)) {
			return user.getPlugin().getSQLiteUserTable().containsKey(user.getUUID());
		} else if (user.getPlugin().getStorageType().equals(UserStorage.FLAT)) {
			return FileThread.getInstance().getThread().hasPlayerFile(user.getUUID());
		}
		return false;
	}

	public void remove() {
        com.bencodez.advancedcore.api.user.usercache.UserDataManager manager = user.getPlugin().getUserManager().getDataManager();
        manager.removeFromStorage(user, () -> {
            if (manager.hasSharedSqlBackend()) {
                manager.withSharedSqlBackend(java.util.UUID.fromString(user.getUUID()), (type, storage) -> {
                    storage.delete(type); return null;
                });
                return;
            }
			com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership.Slot owner = storageOwner();
			owner.getLock().lock();
			try {
				owner.beginWrite();
				try {
					UserStorage storage = user.getPlugin().getStorageType();
					if (storage == UserStorage.MYSQL) {
						if (user.getPlugin().getMysql() == null) throw new SQLException("MySQL user storage is unavailable");
						user.getPlugin().getMysql().deletePlayerStrict(user.getUUID());
					} else if (storage == UserStorage.SQLITE) {
						if (user.getPlugin().getSQLiteUserTable() == null) throw new SQLException("SQLite user storage is unavailable");
						user.getPlugin().getSQLiteUserTable().deleteStrict(new Column("uuid", new DataValueString(user.getUUID())));
					} else if (storage == UserStorage.FLAT) FileThread.getInstance().deletePlayerFileStrict(user.getUUID());
					else throw new IllegalStateException("User storage is not initialized");
				} finally { owner.endWrite(); }
			} catch (SQLException | IOException failure) {
				throw new IllegalStateException("User removal was not acknowledged", failure);
			} finally { owner.getLock().unlock(); }
		});
	}

	public void setBoolean(String key, boolean value) {
		setString(key, "" + value);
	}

	public void setBoolean(String key, boolean value, boolean queue) {
		setString(key, "" + value, queue);
	}

	@Deprecated
	private void setData(final String uuid, final String path, final Object value) {
		FileThread.getInstance().getThread().setData(this, uuid, path, value);
	}

	public void setInt(final String key, final int value) {
		setInt(key, value, true);
	}

	public void setInt(final String key, final int value, boolean queue) {
		setInt(user.getPlugin().getStorageType(), key, value, queue);
	}

	public void setInt(final String key, final int value, boolean queue, boolean async) {
		setInt(user.getPlugin().getStorageType(), key, value, queue, async);
	}

	public void setInt(UserStorage storage, final String key, final int value, boolean queue) {
		setInt(storage, key, value, queue, false);
	}

	@SuppressWarnings("deprecation")
	public void setInt(final UserStorage storage, final String key, final int value, boolean queue, boolean async) {
		if (key.equals("")) {
			user.getPlugin().debug("No key: " + key);
			return;
		}
		if (key.contains(" ")) user.getPlugin().getLogger().severe("Keys cannot contain spaces " + key);
		writeTypedValue(storage, key, new DataValueInt(value), queue, async);
	}

	public void setString(final String key, final String value) {
		setString(key, value, true);
	}

	public void setString(final String key, final String value, boolean queue) {
		setString(user.getPlugin().getStorageType(), key, value, queue);
	}

	public void setString(final String key, final String value, boolean queue, boolean async) {
		setString(user.getPlugin().getStorageType(), key, value, queue, async);
	}

	public void setString(UserStorage storage, final String key, final String value, boolean queue) {
		setString(storage, key, value, queue, false);
	}

	@SuppressWarnings("deprecation")
	public void setString(final UserStorage storage, final String key, final String value, boolean queue,
			boolean async) {
		if (key.equals("") && value != null) {
			user.getPlugin().debug("No key: " + key);
			return;
		}
		if (key.contains(" ")) user.getPlugin().getLogger().severe("Keys cannot contain spaces " + key);
		writeTypedValue(storage, key, new DataValueString(value), queue, async);
	}

	private void writeTypedValue(UserStorage storage, String key, DataValue value, boolean queue, boolean async) {
		try (com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership.Scope admission = user.getPlugin().getUserStorageOwnership().admit()) {
		UserDataCache cache = user.isCached() ? user.getCache() : null;
		if (queue && cache != null) {
			if (value instanceof DataValueInt) cache.addChange(new UserDataChangeInt(key, value.getInt()), true);
			else cache.addChange(new UserDataChangeString(key, value.getString()), true);
			user.getPlugin().getUserManager().onChange(user, key);
			return;
		}
		Runnable write = () -> {
			Runnable storageWrite = () -> {
				try { setValuesStrict(storage, java.util.Collections.singletonMap(key, value)); }
				catch (SQLException | IOException failure) { throw new IllegalStateException("Direct user-data write was not acknowledged", failure); }
			};
			user.getPlugin().getUserManager().getDataManager().writeDirect(user, key, value, storageWrite);
		};
		if (async) user.getPlugin().getUserStorageOwnership().submit(user.getPlugin().getTimer(), write);
		else write.run();
			}
	}

    /**
     * Physically commit a queue edit against the current authoritative predecessor.
     * The transform must be side-effect-free. A checked absent key is empty;
     * unreadable storage is a failure. Post-commit notification failures carry
     * the committed value and must never cause the edit to be replayed.
     */
    public ArrayList<String> mutateStringListStrict(String key,
            java.util.function.UnaryOperator<ArrayList<String>> transform) {
        java.util.Objects.requireNonNull(key,"key");java.util.Objects.requireNonNull(transform,"transform");
        if(key.isEmpty() || key.contains(" "))throw new IllegalArgumentException("Invalid queue key");
        DataValue committed=user.getPlugin().getUserManager().getDataManager().mutateDirect(user,key,()->{
            try {
                DataValue stored=getValuesStrict().get(key);
                return stored==null?new DataValueString(""):stored;
            }catch(SQLException | IOException failure){throw new IllegalStateException("Queue predecessor could not be read",failure);}
        },before->{
            if(before==null)throw new IllegalStateException("Queue predecessor unavailable");
            ArrayList<String> updated=java.util.Objects.requireNonNull(transform.apply(decodeStringList(before)),"transformed queue");
            for(String entry:updated)java.util.Objects.requireNonNull(entry,"queue entry");
            String serialized=String.join("%line%",updated);
            if(serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65535)
                throw new IllegalStateException("Queue edit exceeds the legacy storage bound");
            return new DataValueString(serialized);
        },value->{
            try {setValuesStrict(java.util.Collections.singletonMap(key,value));}
            catch(SQLException | IOException failure){throw new IllegalStateException("Queue edit was not acknowledged",failure);}
        });
        return decodeStringList(committed);
    }

    /** Checked observational queue snapshot; admission still performs an atomic mutation. */
    public ArrayList<String> getStringListStrict(String key) {
        java.util.Objects.requireNonNull(key,"key");
        if(key.isEmpty() || key.contains(" "))throw new IllegalArgumentException("Invalid queue key");
        com.bencodez.advancedcore.api.user.usercache.UserDataManager manager = sharedDataManager();
        if (manager != null && manager.hasSharedSqlBackend()) {
            java.util.UUID identity = java.util.UUID.fromString(user.getUUID());
            return manager.withSharedSqlBackend(identity, (type, storage) -> {
                UserDataCache cache = manager.getUserDataCache().get(identity);
                DataValue value = cache == null || cache.getUuid() == null ? null : cache.getCachedValue(key);
                if (value == null) {
                    List<Column> row = storage.readRow(type);
                    if (row == null) throw new IllegalStateException("Shared user storage omitted its checked snapshot");
                    value = convert(row).get(key);
                }
                return value == null ? new ArrayList<>() : decodeStringList(value);
            });
        }
        try(com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership.Scope admission=user.getPlugin().getUserStorageOwnership().admit()) {
            com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership.Slot owner=storageOwner();
            owner.getLock().lock();
            try {
                if(owner.isWriting())throw new IllegalStateException("Queue snapshot requested during a storage write");
                UserDataCache cache=user.getPlugin().getUserManager().getDataManager().getUserDataCache().get(java.util.UUID.fromString(user.getUUID()));
                DataValue value=cache==null || cache.getUuid()==null?null:cache.getCachedValue(key);
                if(value==null)value=readValuesStrictOwned().get(key);
                return value==null?new ArrayList<>():decodeStringList(value);
            }catch(SQLException | IOException failure){throw new IllegalStateException("Queue snapshot could not be read",failure);}
            finally {owner.getLock().unlock();}
        }
    }

    private static ArrayList<String> decodeStringList(DataValue value) {
        if(!value.isString())throw new IllegalStateException("Queue predecessor is not a string");
        String stored=value.getString();
        return stored==null || stored.isEmpty()?new ArrayList<>():new ArrayList<>(java.util.Arrays.asList(stored.split("%line%")));
    }

	public void setStringList(final String key, final ArrayList<String> value) {
		setStringList(key, value, true);
	}

	public void setStringList(final String key, final ArrayList<String> value, boolean queue) {
		// user.getPlugin().debug("Setting " + key + " to " +
		// value);
		String str = "";
		for (int i = 0; i < value.size(); i++) {
			if (i != 0) {
				str += "%line%";
			}
			str += value.get(i);
		}
		setString(key, str, queue);
	}

	public void setValues(HashMap<String, DataValue> values) {
		setValues(user.getPlugin().getStorageType(), values);
	}

	public void setValues(String key, DataValue value) {
		HashMap<String, DataValue> values = new HashMap<>();
		values.put(key, value);
		setValues(user.getPlugin().getStorageType(), values);
	}

	public void setValues(UserStorage storage, HashMap<String, DataValue> values) {
		HashMap<String, DataValue> candidate = new HashMap<>(values);
		// SQL identity is never part of the update, as in the legacy bulk API.
		if (storage == UserStorage.MYSQL || storage == UserStorage.SQLITE) candidate.remove("uuid");
		if (candidate.isEmpty()) return;
		for (Entry<String, DataValue> entry : candidate.entrySet()) {
			java.util.Objects.requireNonNull(entry.getKey(), "key");
			java.util.Objects.requireNonNull(entry.getValue(), "value");
		}
		Runnable storageWrite = () -> {
			try { setValuesStrict(storage, candidate); }
			catch (SQLException | IOException failure) { throw new IllegalStateException("Bulk user-data write was not acknowledged", failure); }
		};
		user.getPlugin().getUserManager().getDataManager().writeBatch(user, candidate, storageWrite,
				storage == user.getPlugin().getStorageType());
	}

	/** Writes one synchronous checked batch; callers retain pending changes on failure. */
	public void setValuesStrict(Map<String, DataValue> values) throws SQLException, IOException {
		setValuesStrict(user.getPlugin().getStorageType(), values);
	}

	/** Checked explicit-storage overload for compatibility setters and converters. */
	public void setValuesStrict(UserStorage storage, Map<String, DataValue> values) throws SQLException, IOException {
		if (values.isEmpty()) return;
		com.bencodez.advancedcore.api.user.usercache.UserDataManager manager = sharedDataManager();
		if (manager != null && manager.hasSharedSqlBackend()) {
			HashMap<String, DataValue> selected = new HashMap<>(values);
			selected.remove("uuid");
			if (selected.isEmpty()) return;
			for (Entry<String, DataValue> entry : selected.entrySet()) {
				if (entry.getKey() == null || entry.getValue() == null) throw new IllegalArgumentException("Invalid user-data batch value");
			}
			try {
				manager.withSharedSqlBackend(java.util.UUID.fromString(user.getUUID()), (actual, target) -> {
					if (actual != storage) throw new IllegalStateException("Requested user store differs from the active shared owner");
					target.writeValues(actual, selected);
					return null;
				});
			} catch (IllegalStateException failure) {
				if (failure.getCause() instanceof SQLException) throw (SQLException) failure.getCause();
				throw failure;
			}
			return;
		}
		try (com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership.Scope admission = user.getPlugin().getUserStorageOwnership().admit()) {
		com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership.Slot owner = storageOwner();
		owner.getLock().lock();
		try {
			owner.beginWrite();
			try {
				writeValuesStrictOwned(storage, values);
			} finally { owner.endWrite(); }
		} finally { owner.getLock().unlock(); }
			}
	}

	private void writeValuesStrictOwned(UserStorage storage, Map<String, DataValue> values) throws SQLException, IOException {
		if (values.isEmpty()) return;
		if (storage == null) throw new IllegalStateException("User storage is not initialized");
		if (storage == UserStorage.FLAT) {
			FileThread.getInstance().setValuesStrict(user.getUUID(), values);
			return;
		}
		ArrayList<Column> columns = new ArrayList<>();
		for (Entry<String, DataValue> entry : values.entrySet()) {
			if (!"uuid".equals(entry.getKey())) {
				if (entry.getKey() == null || entry.getValue() == null) {
					throw new IllegalArgumentException("Invalid user-data batch value");
				}
				columns.add(new Column(entry.getKey(), entry.getValue()));
			}
		}
		if (columns.isEmpty()) return;
		if (storage == UserStorage.MYSQL) {
			if (user.getPlugin().getMysql() == null) throw new SQLException("MySQL user storage is unavailable");
			user.getPlugin().getMysql().updateStrict(user.getUUID(), columns);
		} else if (storage == UserStorage.SQLITE) {
			if (user.getPlugin().getSQLiteUserTable() == null) throw new SQLException("SQLite user storage is unavailable");
			user.getPlugin().getSQLiteUserTable().updateStrict(new Column("uuid", new DataValueString(user.getUUID())), columns);
		} else {
			throw new IllegalStateException("Unsupported user storage");
		}
	}

	public void tempCache() {
		tempCache = getValues();
	}

	public void updateCacheWithTemp() {
		if (user.isCached()) {
			user.getCache().updateCache(tempCache);
		}
	}

	public void updateTempCacheWithColumns(ArrayList<Column> cols) {
		tempCache = convert(cols);
	}
}
