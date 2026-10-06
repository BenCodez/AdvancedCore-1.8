package com.bencodez.advancedcore.thread;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.misc.files.FilesManager;
import com.bencodez.advancedcore.api.user.UserData;
import com.bencodez.simpleapi.sql.data.DataValue;
import com.bencodez.simpleapi.sql.data.DataValueInt;
import com.bencodez.simpleapi.sql.data.DataValueString;

/**
 * The Class Thread.
 */
public class FileThread {

	/**
	 * The Class ReadThread.
	 */
	public class ReadThread extends java.lang.Thread {

		@Deprecated
		public void deletePlayerFile(String uuid) {
			synchronized (FileThread.getInstance()) {
				try {
					File dFile = new File(AdvancedCorePlugin.getInstance().getDataFolder() + File.separator + "Data",
							uuid + ".yml");
					if (dFile.exists()) {
						dFile.delete();
					}

				} catch (Exception e) {
					AdvancedCorePlugin.getInstance().debug(e);
				}
			}
		}

		@Deprecated
		public FileConfiguration getData(UserData userData, String uuid) {
			synchronized (FileThread.getInstance()) {
				try {
					File dFile = getPlayerFile(uuid);
					if (dFile != null) {
						FileConfiguration data = YamlConfiguration.loadConfiguration(dFile);
						return data;
					}
				} catch (Exception e) {
					AdvancedCorePlugin.getInstance().debug(e);
				}
				AdvancedCorePlugin.getInstance().getLogger()
						.warning("Filed to load " + uuid + ".yml, turn debug on to see full stacktraces");
				return null;
			}

		}

		@Deprecated
		public File getPlayerFile(String uuid) {
			synchronized (FileThread.getInstance()) {
				try {
					File dFile = new File(AdvancedCorePlugin.getInstance().getDataFolder() + File.separator + "Data",
							uuid + ".yml");
					FileConfiguration data = YamlConfiguration.loadConfiguration(dFile);
					if (!dFile.exists()) {
						FilesManager.getInstance().editFile(dFile, data);
					}
					return dFile;
				} catch (Exception e) {
					AdvancedCorePlugin.getInstance().debug(e);
				}
				AdvancedCorePlugin.getInstance().getLogger()
						.warning("Failed to load " + uuid + ".yml, turn debug on to see full stacktraces");
				return null;
			}
		}

		@Deprecated
		public boolean hasPlayerFile(String uuid) {
			synchronized (FileThread.getInstance()) {
				try {
					File dFile = new File(AdvancedCorePlugin.getInstance().getDataFolder() + File.separator + "Data",
							uuid + ".yml");
					return dFile.exists();

				} catch (Exception e) {
					AdvancedCorePlugin.getInstance().debug(e);
				}
				return false;
			}
		}

		@Override
		public void run() {
			while (!thread.isInterrupted()) {
				try {
					sleep(50);
				} catch (InterruptedException e) {
					e.printStackTrace();
					System.exit(0);
				}
			}
		}

		/**
		 * Run.
		 *
		 * @param run the run
		 */
		public void run(Runnable run) {
			synchronized (FileThread.getInstance()) {
				run.run();
			}

		}

		@Deprecated
		public void setData(UserData userData, final String uuid, final String path, final Object value) {
			synchronized (FileThread.getInstance()) {
				try {
					File dFile = getPlayerFile(uuid);
					FileConfiguration data = getData(userData, uuid);
					data.set(path, value);
					data.save(dFile);
				} catch (Exception e) {
					AdvancedCorePlugin.getInstance().getLogger().warning(
							"Failed to set a value for " + uuid + ".yml, turn debug on to see full stacktraces");
					AdvancedCorePlugin.getInstance().debug(e);
				}
			}

		}
	}

	/** The instance. */
	static FileThread instance = new FileThread();

	/**
	 * Gets the single instance of Thread.
	 *
	 * @return single instance of Thread
	 */
	public static FileThread getInstance() {
		return instance;
	}

	/** The plugin. */
	AdvancedCorePlugin plugin = AdvancedCorePlugin.getInstance();

	/** The thread. */
	private ReadThread thread;

	/**
	 * Instantiates a new thread.
	 */
	private FileThread() {
	}

	/** Checked configuration replacement under the same owner as legacy FilesManager writes. */
	public void saveConfigurationStrict(File file, FileConfiguration data) throws IOException {
		java.util.Objects.requireNonNull(file, "file");
		java.util.Objects.requireNonNull(data, "data");
		synchronized (FileThread.getInstance()) {
			Path target = file.getCanonicalFile().toPath();
			PosixFileAttributes attributes = null;
			if (!Files.notExists(target)) {
				if (!Files.isRegularFile(target)) throw new IOException("Configuration target is not a regular file");
				try { new YamlConfiguration().load(target.toFile()); }
				catch (InvalidConfigurationException invalid) { throw new IOException("Existing configuration is malformed", invalid); }
				if (Files.getFileAttributeView(target, PosixFileAttributeView.class) != null) {
					attributes = Files.readAttributes(target, PosixFileAttributes.class);
				}
			}
			Files.createDirectories(target.getParent());
			Path staged = Files.createTempFile(target.getParent(), ".configuration-", ".tmp");
			Throwable failure = null;
			try {
				data.save(staged.toFile());
				if (attributes != null) {
					PosixFileAttributeView view = Files.getFileAttributeView(staged, PosixFileAttributeView.class);
					view.setPermissions(attributes.permissions());
					view.setOwner(attributes.owner());
					view.setGroup(attributes.group());
				}
				publishStrict(staged, target);
				staged = null;
			} catch (IOException | RuntimeException | Error problem) {
				failure = problem;
				throw problem;
			} finally {
				if (staged != null) {
					try { Files.deleteIfExists(staged); }
					catch (IOException cleanup) {
						if (failure != null) failure.addSuppressed(cleanup); else throw cleanup;
					}
				}
			}
		}
	}

	/**
	 * Publishes one checked legacy user-data batch under the existing file owner.
	 * Does not start the deprecated polling thread. Malformed/unreadable input is
	 * a failed write, never an empty document to overwrite.
	 */
	public void setValuesStrict(String uuid, Map<String, DataValue> values) throws IOException {
		if (values.isEmpty()) return;
		UUID.fromString(uuid); // Keep the supplied filename spelling; reject path-like identities.
		for (Map.Entry<String, DataValue> entry : values.entrySet()) {
			DataValue value = entry.getValue();
			if (entry.getKey() == null || entry.getKey().isEmpty() || value == null
					|| (!value.isString() && !value.isInt() && !value.isBoolean())) {
				throw new IllegalArgumentException("Unsupported user-data batch value");
			}
		}
		synchronized (FileThread.getInstance()) {
			if (plugin == null) throw new IOException("User file owner is not initialized");
			Path target = new File(new File(plugin.getDataFolder(), "Data"), uuid + ".yml")
					.getCanonicalFile().toPath();
			YamlConfiguration data = new YamlConfiguration();
			PosixFileAttributes attributes = null;
			if (!Files.notExists(target)) {
				if (!Files.isRegularFile(target)) throw new IOException("User data is not a readable regular file");
				try {
					data.load(target.toFile());
				} catch (InvalidConfigurationException invalid) {
					throw new IOException("Existing user data is malformed", invalid);
				}
				if (Files.getFileAttributeView(target, PosixFileAttributeView.class) != null) {
					attributes = Files.readAttributes(target, PosixFileAttributes.class);
				}
			}
			for (Map.Entry<String, DataValue> entry : values.entrySet()) {
				DataValue value = entry.getValue();
				if (value.isInt()) data.set(entry.getKey(), value.getInt());
				else if (value.isBoolean()) data.set(entry.getKey(), Boolean.toString(value.getBoolean()));
				else data.set(entry.getKey(), value.getString());
			}
			Files.createDirectories(target.getParent());
			Path staged = Files.createTempFile(target.getParent(), ".user-data-", ".tmp");
			Throwable failure = null;
			try {
				data.save(staged.toFile());
				if (attributes != null) {
					PosixFileAttributeView view = Files.getFileAttributeView(staged, PosixFileAttributeView.class);
					view.setPermissions(attributes.permissions());
					view.setOwner(attributes.owner());
					view.setGroup(attributes.group());
				}
				publishStrict(staged, target);
				staged = null; // Publication succeeded; cleanup cannot turn it into a failed write.
			} catch (IOException | RuntimeException | Error problem) {
				failure = problem;
				throw problem;
			} finally {
				if (staged != null) {
					try { Files.deleteIfExists(staged); }
					catch (IOException cleanup) {
						if (failure != null) failure.addSuppressed(cleanup);
						else throw cleanup;
					}
				}
			}
		}
	}

	/** Checked identity-file deletion, preserving legacy symlink deletion semantics. */
	public void deletePlayerFileStrict(String uuid) throws IOException {
		UUID.fromString(uuid);
		synchronized (FileThread.getInstance()) {
			if (plugin == null) throw new IOException("User file owner is not initialized");
			Path target = new File(new File(plugin.getDataFolder(), "Data"), uuid + ".yml").toPath();
			if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)
					&& !Files.isRegularFile(target) && !Files.isSymbolicLink(target)) throw new IOException("User data is not a regular file");
			Files.deleteIfExists(target);
		}
	}

	/** Read-only checked snapshot under the same file owner as checked writes. */
	public HashMap<String, DataValue> getValuesStrict(String uuid) throws IOException {
		UUID.fromString(uuid);
		synchronized (FileThread.getInstance()) {
			if (plugin == null) throw new IOException("User file owner is not initialized");
			Path target = new File(new File(plugin.getDataFolder(), "Data"), uuid + ".yml").getCanonicalFile().toPath();
			HashMap<String, DataValue> values = new HashMap<>();
			if (Files.notExists(target)) return values;
			if (!Files.isRegularFile(target)) throw new IOException("User data is not a readable regular file");
			YamlConfiguration data = new YamlConfiguration();
			try { data.load(target.toFile()); }
			catch (InvalidConfigurationException invalid) { throw new IOException("Existing user data is malformed", invalid); }
			for (String key : data.getKeys(false)) {
				if (data.isInt(key)) values.put(key, new DataValueInt(data.getInt(key)));
				else values.put(key, new DataValueString(data.getString(key, "")));
			}
			return values;
		}
	}

	/** Read the explicit FLAT conversion source under the existing file owner. */
	public HashMap<UUID, HashMap<String, DataValue>> getAllValuesStrict() throws IOException {
		synchronized (FileThread.getInstance()) {
			if (plugin == null) throw new IOException("User file owner is not initialized");
			Path directory = new File(plugin.getDataFolder(), "Data").toPath();
			HashMap<UUID, HashMap<String, DataValue>> result = new HashMap<>();
			if (Files.notExists(directory)) return result;
			if (!Files.isDirectory(directory)) throw new IOException("User data directory is unavailable");
			try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.yml")) {
				for (Path file : files) {
					String name = file.getFileName().toString();String text = name.substring(0, name.length() - 4);UUID identity;
					try { identity = UUID.fromString(text);if (!identity.toString().equalsIgnoreCase(text)) throw new IllegalArgumentException(); }
					catch (IllegalArgumentException invalid) { throw new IOException("Invalid source user file identity", invalid); }
					if (!Files.isRegularFile(file)) throw new IOException("User source is not a readable regular file");
					YamlConfiguration data = new YamlConfiguration();
					try { data.load(file.toFile()); }
					catch (InvalidConfigurationException invalid) { throw new IOException("Existing user source is malformed", invalid); }
					HashMap<String, DataValue> values = new HashMap<>();
					for (String key : data.getKeys(false)) {
						Object value = data.get(key);
						if (value instanceof Integer) values.put(key, new DataValueInt((Integer) value));
						else if (value instanceof String || value instanceof Boolean || value instanceof Number)
							values.put(key, new DataValueString(String.valueOf(value)));
						else throw new IOException("Unsupported structured user source value");
					}
					if (result.put(identity, values) != null) throw new IOException("Duplicate source user file identity");
				}
			}
			return result;
		}
	}

	void publishStrict(Path staged, Path target) throws IOException {
		Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * @return the thread
	 */
	public ReadThread getThread() {
		if (thread == null || !thread.isAlive()) {
			plugin.debug("Loading thread");
			loadThread();
		}
		return thread;
	}

	/**
	 * Load thread.
	 */
	public void loadThread() {
		thread = new ReadThread();
		thread.start();
	}

	/**
	 * Run.
	 *
	 * @param run the run
	 */
	public void run(Runnable run) {
		getThread().run(run);
	}
}