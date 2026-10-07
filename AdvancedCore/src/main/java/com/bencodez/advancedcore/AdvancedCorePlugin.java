package com.bencodez.advancedcore;

import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.net.URL;
import java.security.CodeSource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import com.bencodez.advancedcore.api.backup.BackupHandle;
import com.bencodez.advancedcore.api.cmi.CMIHandler;
import com.bencodez.advancedcore.api.geyser.GeyserHandle;
import com.bencodez.advancedcore.api.inventory.BInventoryListener;
import com.bencodez.advancedcore.api.item.FullInventoryHandler;
import com.bencodez.advancedcore.api.javascript.JavascriptPlaceholderRequest;
import com.bencodez.advancedcore.api.misc.effects.FireworkHandler;
import com.bencodez.advancedcore.api.permissions.LuckPermsHandle;
import com.bencodez.advancedcore.api.permissions.PermissionHandler;
import com.bencodez.advancedcore.api.rewards.Reward;
import com.bencodez.advancedcore.api.rewards.RewardHandler;
import com.bencodez.advancedcore.api.time.TimeChecker;
import com.bencodez.advancedcore.api.time.TimeType;
import com.bencodez.advancedcore.api.updater.UpdateDownloader;
import com.bencodez.advancedcore.api.user.AdvancedCoreUser;
import com.bencodez.advancedcore.api.user.UserManager;
import com.bencodez.advancedcore.api.user.UserStartup;
import com.bencodez.advancedcore.api.user.UserStorage;
import com.bencodez.advancedcore.api.user.userstorage.mysql.MySQL;
import com.bencodez.advancedcore.api.user.userstorage.sql.UserTable;
import com.bencodez.advancedcore.api.valuerequest.InputMethod;
import com.bencodez.advancedcore.api.valuerequest.sign.SignMenu;
import com.bencodez.advancedcore.command.CommandLoader;
import com.bencodez.advancedcore.command.executor.ValueRequestInputCommand;
import com.bencodez.advancedcore.data.ServerData;
import com.bencodez.advancedcore.listeners.AuthMeLogin;
import com.bencodez.advancedcore.listeners.LoginSecurityLogin;
import com.bencodez.advancedcore.listeners.NLoginAuthenticate;
import com.bencodez.advancedcore.listeners.PlayerJoinEvent;
import com.bencodez.advancedcore.listeners.PlayerShowListener;
import com.bencodez.advancedcore.listeners.PluginUpdateVersionEvent;
import com.bencodez.advancedcore.listeners.WorldChangeEvent;
import com.bencodez.advancedcore.logger.Logger;
import com.bencodez.simpleapi.command.TabCompleteHandle;
import com.bencodez.simpleapi.command.TabCompleteHandler;
import com.bencodez.simpleapi.debug.DebugLevel;
import com.bencodez.simpleapi.file.YMLConfig;
import com.bencodez.simpleapi.messages.MessageAPI;
import com.bencodez.simpleapi.nms.NMSManager;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;
import com.bencodez.simpleapi.servercomm.pluginmessage.PluginMessage;
import com.bencodez.simpleapi.skull.SkullCacheHandler;
import com.bencodez.simpleapi.sql.Column;
import com.bencodez.simpleapi.sql.DataType;
import com.bencodez.simpleapi.sql.sqlite.Database;
import com.bencodez.simpleapi.sql.sqlite.Table;
import com.bencodez.simpleapi.utils.PluginUtils;

import lombok.Getter;
import lombok.Setter;

public abstract class AdvancedCorePlugin extends JavaPlugin {

	private static AdvancedCorePlugin javaPlugin;

	public static AdvancedCorePlugin getInstance() {
		return javaPlugin;
	}

	public static void setInstance(AdvancedCorePlugin plugin) {
		javaPlugin = plugin;
	}

	@Getter
	public VaultHandler vaultHandler;

	@Getter
	private CommandLoader advancedCoreCommandLoader;

	@Getter
	private SkullCacheHandler skullCacheHandler;

	@Getter
	private boolean authMeLoaded = false;

	@Getter
	private boolean nLoginLoaded = false;

	@Getter
	private boolean loginSecurityLoaded = false;

	@Getter
	private ArrayList<String> bannedPlayers = new ArrayList<>();

	@Getter
	private String buildTime = "";

	@Getter
	@Setter
	private String bungeeChannel;

	@Getter
	private CMIHandler cmiHandle;

	private volatile Database database;
	private final Object sqliteInitialization = new Object();
	private final ThreadLocal<Boolean> sqliteBootstrap = ThreadLocal.withInitial(() -> false);

	@Getter
	private FullInventoryHandler fullInventoryHandler;

	@Getter
	@Setter
	private HashMap<String, Object> javascriptEngine = new HashMap<>();
	@Getter
	@Setter
	private ArrayList<JavascriptPlaceholderRequest> javascriptEngineRequests = new ArrayList<>();

	@Getter
	@Setter
	private String jenkinsSite = "";

	@Getter
	@Setter
	private boolean loadRewards = true;

	@Getter
	@Setter
	private boolean loadServerData = true;

	@Getter
	@Setter
	private boolean loadUserData = true;
	@Getter
	private volatile MySQL mysql;
	@Getter
	private AdvancedCoreConfigOptions options = new AdvancedCoreConfigOptions();

	@Getter
	private boolean placeHolderAPIEnabled;

	@Getter
	private Logger pluginLogger;

	@Getter
	private PluginMessage pluginMessaging;

	@Getter
	private ServerData serverDataFile;

	@Getter
	private SignMenu signMenu;

	@Getter
	private TimeChecker timeChecker;

	@Getter
	private volatile com.bencodez.advancedcore.api.rewards.ServerThreadRewardDispatch rewardDispatch =
			new com.bencodez.advancedcore.api.rewards.ServerThreadRewardDispatch(this);

	@Getter
	private ScheduledExecutorService timer;

	@Getter
	private ScheduledExecutorService loginTimer;

	@Getter
	private ScheduledExecutorService inventoryTimer;

	@Setter
	private UserManager userManager;

	@Getter
	private final com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership userStorageOwnership =
			new com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership();

	private ArrayList<UserStartup> userStartup = new ArrayList<>();

	@Getter
	private ConcurrentHashMap<String, String> uuidNameCache;

	@Getter
	private String version = "";

	@Getter
	private String advancedCoreBuildNumber = "NOTSET";

	@Getter
	private PermissionHandler permissionHandler;

	@Getter
	private RewardHandler rewardHandler;

	@Getter
	private LuckPermsHandle luckPermsHandle;

	@Getter
	private BukkitScheduler bukkitScheduler;

	@Getter
	@Setter
	private boolean loadGeyserAPI = true;

	@Getter
	@Setter
	private boolean loadLuckPerms = true;

	@Getter
	private GeyserHandle geyserHandle;

	@Getter
	@Setter
	private boolean loadSkullHandler = true;

	@Getter
	@Setter
	private boolean loadVault = true;

	public void addUserStartup(UserStartup start) {
		userStartup.add(start);
	}

	public void allowDownloadingFromSpigot(int resourceId) {
		getOptions().setResourceId(resourceId);
	}

	private void checkAutoUpdate() {
		getBukkitScheduler().runTaskAsynchronously(this, new Runnable() {

			@Override
			public void run() {
				if (getOptions().isAutoDownload() && getOptions().getResourceId() != 0) {
					UpdateDownloader.getInstance().checkAutoDownload(javaPlugin, getOptions().getResourceId());
				}
			}
		});

	}

	private void checkCMI() {
		getBukkitScheduler().runTaskAsynchronously(javaPlugin, new Runnable() {

			@Override
			public void run() {
				if (Bukkit.getPluginManager().getPlugin("CMI") != null) {
					getLogger().info("CMI found, loading hook");
					cmiHandle = new CMIHandler();
				}
			}
		});
	}

	private void checkPlaceHolderAPI() {
		getBukkitScheduler().runTaskAsynchronously(this, new Runnable() {

			@Override
			public void run() {
				if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
					placeHolderAPIEnabled = true;
					debug("PlaceholderAPI found, will attempt to parse placeholders");
				} else {
					placeHolderAPIEnabled = false;
					debug("PlaceholderAPI not found, PlaceholderAPI placeholders will not work");
				}
			}
		});

	}

	public void checkPluginUpdate() {
		if (!loadServerData) {
			return;
		}
		getBukkitScheduler().runTaskAsynchronously(this, new Runnable() {

			@Override
			public void run() {
				String version = getServerDataFile().getPluginVersion(javaPlugin);
				if (!version.equals(javaPlugin.getDescription().getVersion())) {
					PluginUpdateVersionEvent event = new PluginUpdateVersionEvent(javaPlugin, version);
					Bukkit.getServer().getPluginManager().callEvent(event);
				}
				getServerDataFile().setPluginVersion(javaPlugin);
			}
		});

	}

	/** Worker callers retain synchronous completion; server callers dispatch off-owner. */
	public void convertDataStorage(UserStorage from, UserStorage to) {
		if (from == null || to == null) throw new RuntimeException("Invalid Storage Method");
		if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
			convertDataStorageAsync(from, to).whenComplete((ignored, failure) -> {
				if (failure != null) getLogger().severe("User storage conversion failed (" + failure.getClass().getSimpleName() + ")");
			});
			return;
		}
		getUserStorageOwnership().maintain(5, TimeUnit.SECONDS, this::flushStorageForReplacement,
				() -> convertDataStorageOwned(from, to));
	}

	/** Completion observes the synchronous conversion body, not scheduler admission. */
	public java.util.concurrent.CompletionStage<Void> convertDataStorageAsync(UserStorage from, UserStorage to) {
		if (from == null || to == null) {
			java.util.concurrent.CompletableFuture<Void> failed = new java.util.concurrent.CompletableFuture<>();
			failed.completeExceptionally(new RuntimeException("Invalid Storage Method"));
			return failed;
		}
		return getRewardDispatch().dispatchOffPrimary(() -> {
			if (Bukkit.isPrimaryThread()) throw new IllegalStateException("Storage conversion requires a worker thread");
			convertDataStorage(from, to);
			return java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
		}, TimeUnit.SECONDS.toMillis(30));
	}

	private void convertDataStorageOwned(UserStorage from, UserStorage to) {
		debug("Starting convert process");
		if (!hasStorageProvider(from)) loadUserAPI(from);

		if (getMysql() != null) {
			getMysql().clearCacheBasic();
		}

		HashMap<UUID, ArrayList<Column>> cols;
		try { cols = getUserManager().getAllKeysStrict(from); }
		catch (java.sql.SQLException | java.io.IOException failure) { throw new IllegalStateException("Conversion source was not completely read", failure); }
		if (!hasStorageProvider(to)) loadUserAPI(to);
		Queue<Entry<UUID, ArrayList<Column>>> players = new LinkedList<>(cols.entrySet());

		while (players.size() > 0) {
			Entry<UUID, ArrayList<Column>> entry = players.poll();
			AdvancedCoreUser user = getUserManager().getUser(entry.getKey(), false);
			user.dontCache();

			user.getData().setValues(to, user.getData().convert(entry.getValue()));
			debug("Finished convert for " + user.getUUID() + ", " + players.size() + " more left to go!");

			if (players.size() % 50 == 0) {
				getLogger().info("Working on converting data, about " + players.size() + " left to go!");
			}
		}
		debug("Convert finished!");

	}

	private boolean hasStorageProvider(UserStorage storage) {
		if (storage == UserStorage.MYSQL) return getMysql() != null;
		if (storage == UserStorage.SQLITE) return database != null;
		return storage == UserStorage.FLAT;
	}

	public void debug(DebugLevel debugLevel, String debug) {
		if (debugLevel.equals(DebugLevel.EXTRA)) {
			debug = "ExtraDebug: " + debug;
		} else if (debugLevel.equals(DebugLevel.INFO)) {
			debug = "Debug: " + debug;
		} else if (debugLevel.equals(DebugLevel.DEV)) {
			debug = "Developer Debug: " + debug;
		}

		if (getOptions().getDebug().isDebug(debugLevel)) {
			getLogger().info(debug);
		}
		if (getOptions().isDebugIngame()) {
			for (Player player : Bukkit.getOnlinePlayers()) {
				if (player.hasPermission(this.getName() + ".Debug")) {
					player.sendMessage(MessageAPI.colorize("&c" + getName() + " Debug: " + debug));
				}
			}
		}
		if (getOptions().isLogDebugToFile()) {
			if (pluginLogger == null) {
				loadLogger();
			}
			String str = new SimpleDateFormat("EEE, d MMM yyyy HH:mm").format(Calendar.getInstance().getTime());
			pluginLogger.logToFile(str + ":" + debug);
		}
	}

	/**
	 * Show exception in console if debug is on
	 *
	 * @param e Exception
	 */
	public void debug(Exception e) {
		if (getOptions().getDebug().isDebug()) {
			e.printStackTrace();
		}
		if (getOptions().isLogDebugToFile()) {
			if (pluginLogger != null) {
				String str = new SimpleDateFormat("EEE, d MMM yyyy HH:mm").format(Calendar.getInstance().getTime());
				pluginLogger.logToFile(str + " [" + this.getName() + "] ExceptionDebug: " + e.getMessage());
			} else {
				loadLogger();
			}
		}
	}

	public void debug(String debug) {
		debug(DebugLevel.INFO, debug);
	}

	public void devDebug(String debug) {
		debug(DebugLevel.DEV, debug);
	}

	public void extraDebug(String debug) {
		debug(DebugLevel.EXTRA, debug);
	}

	public UserTable getSQLiteUserTable() {
		if (!loadUserData) return null;
		if (database == null) {
			if (getUserStorageOwnership().isReplacingOnCurrentThread()) {
				initializeSQLiteIfMissing();
			} else {
				try (com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership.Scope admission = getUserStorageOwnership().admit()) {
					initializeSQLiteIfMissing();
				}
			}
		}
		Database current = database;
		if (current == null) throw new IllegalStateException("SQLite user storage is unavailable");
		for (Table table : current.getTables()) {
			if (table instanceof UserTable) return (UserTable) table;
		}
		return null;
	}

	private void initializeSQLiteIfMissing() {
		synchronized (sqliteInitialization) {
			if (database != null) return;
			if (sqliteBootstrap.get()) throw new IllegalStateException("SQLite initialization is already in progress on this thread");
			sqliteBootstrap.set(true);
			try { loadUserAPI(getStorageType()); }
			finally { sqliteBootstrap.remove(); }
		}
	}

	public UserStorage getStorageType() {
		return getOptions().getStorageType();
	}

	public UserManager getUserManager() {
		if (userManager == null) {
			userManager = new UserManager(this);
		}
		return userManager;
	}

	private YamlConfiguration getVersionFile() {
		try {
			CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
			if (src != null) {
				URL jar = src.getLocation();
				ZipInputStream zip = null;
				zip = new ZipInputStream(jar.openStream());
				while (true) {
					ZipEntry e = zip.getNextEntry();
					if (e != null) {
						String name = e.getName();
						if (name.equals("advancedcoreversion.yml")) {
							Reader defConfigStream = new InputStreamReader(zip);
							if (defConfigStream != null) {
								YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(defConfigStream);
								defConfigStream.close();
								return defConfig;
							}
						}
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	public boolean isMySQLOkay() {
		if (getStorageType().equals(UserStorage.MYSQL)) {
			return mysql != null;
		}
		return true;
	}

	public void loadAdvancedCoreEvents() {
		if (loadUserData) {
			Bukkit.getPluginManager().registerEvents(new PlayerJoinEvent(this), this);
			Bukkit.getPluginManager().registerEvents(new WorldChangeEvent(this), this);
		}

		Bukkit.getPluginManager().registerEvents(FireworkHandler.getInstance(), this);
		Bukkit.getPluginManager().registerEvents(new BInventoryListener(this), this);
	}

	public void loadAutoUpdateCheck() {
		long delay = 60 * 60;
		timer.scheduleWithFixedDelay(new Runnable() {

			@Override
			public void run() {
				checkAutoUpdate();
			}
		}, delay, delay, TimeUnit.SECONDS);
	}

	private void loadConfig(boolean userStorage) {
		if (loadUserData && userStorage) {
			getUserStorageOwnership().replace(5, TimeUnit.SECONDS, this::flushStorageForReplacement, () -> {
				// Pending batches must use the old type/config, before Options changes.
				getOptions().load(this);
				loadUserAPI(getOptions().getStorageType());
			});
		} else getOptions().load(this);
	}

	private void loadHandle() {

		if (Bukkit.getOnlineMode()) {
			debug("Server in online mode");
		} else {
			debug("Server in offline mode");
		}
	}

	/**
	 * Load AdvancedCore hook
	 */
	@SuppressWarnings("deprecation")
	public void loadHook() {
		serverDataFile = new ServerData(this);

		if (loadLuckPerms) {
			if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
				luckPermsHandle = new LuckPermsHandle();
				luckPermsHandle.load(this);
			}
		}

		loadSignAPI();
		loadUUIDs();
		getOptions().setPermPrefix(this.getName());
		checkPlaceHolderAPI();
		checkCMI();
		loadHandle();
		loadVault();
		loadAdvancedCoreEvents();
		timeChecker = new TimeChecker(this);
		if (loadServerData) {
			serverDataFile.setup();
			timeChecker.loadTimer();
		}

		// load usermanager
		getUserManager();
		permissionHandler = new PermissionHandler(this);

		loadConfig(true);

		skullCacheHandler = new SkullCacheHandler(getOptions().getSkullLoadDelay()) {

			@Override
			public void debugException(Exception e) {
				debug(e);
			}

			@Override
			public void debugLog(String debug) {
				extraDebug(debug);
			}

			@Override
			public void log(String log) {
				getLogger().info(log);
			}
		};
		if (!getOptions().getSkullProfileAPIURL().isEmpty()) {
			debug("Setting API profile URL to " + getOptions().getSkullProfileAPIURL());
			skullCacheHandler.changeApiProfileURL(getOptions().getSkullProfileAPIURL());
		}
		skullCacheHandler.setBedrockPrefix(getOptions().getBedrockPlayerPrefix());
		skullCacheHandler.startTimer();

		if (loadGeyserAPI) {
			geyserHandle = new GeyserHandle();
			geyserHandle.load();
		}

		rewardHandler = RewardHandler.getInstance();
		rewardHandler.loadInjectedRewards();
		rewardHandler.loadInjectedRequirements();
		if (loadRewards) {
			File rewardsFolder = new File(this.getDataFolder(), "Rewards");
			rewardHandler.addRewardFolder(rewardsFolder, false, true);
			File file = new File(rewardsFolder.getAbsolutePath() + File.separator + "DirectlyDefined");
			rewardHandler.addRewardFolder(file, false, false);
			rewardHandler.loadRewards();
		}

		loadValueRequestInputCommands();
		checkPluginUpdate();
		loadAutoUpdateCheck();
		loadVersionFile();

		getUserManager().purgeOldPlayersStartup();

		userStartup();
		loadTabComplete();

		fullInventoryHandler = new FullInventoryHandler(this);

		for (OfflinePlayer p : Bukkit.getBannedPlayers()) {
			bannedPlayers.add(p.getUniqueId().toString());
		}

		Bukkit.getPluginManager().registerEvents(BackupHandle.getInstance(), this);

		if (Bukkit.getPluginManager().getPlugin("authme") != null) {
			authMeLoaded = true;
			Bukkit.getPluginManager().registerEvents(new AuthMeLogin(this), this);
		}

		if (Bukkit.getPluginManager().getPlugin("nLogin") != null) {
			nLoginLoaded = true;
			Bukkit.getPluginManager().registerEvents(new NLoginAuthenticate(this), this);
		}

		if (Bukkit.getPluginManager().getPlugin("LoginSecurity") != null) {
			loginSecurityLoaded = true;
			Bukkit.getPluginManager().registerEvents(new LoginSecurityLogin(this), this);
		}

		try {
			Class.forName("de.myzelyam.api.vanish.PostPlayerShowEvent");
			registerEvents(new PlayerShowListener(this));
			debug("Loaded PostPlayerShowEvent");
		} catch (ClassNotFoundException e) {
			debug("Not loading PostPlayerShowEvent");
		}

		String buildNumberMsg = "";
		if (!advancedCoreBuildNumber.equals("NOTSET")) {
			buildNumberMsg = ", build number: " + advancedCoreBuildNumber + ", ";
		}

		debug("Using AdvancedCore '" + getVersion() + "' built on '" + getBuildTime() + "' " + buildNumberMsg
				+ " Spigot Version: " + Bukkit.getVersion() + " Total RAM: " + PluginUtils.getMemory() + " Free RAM: "
				+ PluginUtils.getFreeMemory());

		debug(DebugLevel.INFO, "Debug Level: " + getOptions().getDebug().toString());
	}

	/**
	 * Load logger
	 */
	public void loadLogger() {
		if (getOptions().isLogDebugToFile() && pluginLogger == null) {
			pluginLogger = new Logger(this, new File(this.getDataFolder(), "Log" + File.separator + "Log.txt"));
		}
	}

	private void loadSignAPI() {
		if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null
				&& !NMSManager.getInstance().isVersion("1.8", "1.9", "1.10", "1.11")) {
			if (Bukkit.getPluginManager().getPlugin("ProtocolLib").isEnabled()) {
				try {
					this.signMenu = new SignMenu(this);
				} catch (Exception e) {
					getLogger().warning("ProtocolLib may not be up to date? Failed to load SignMenu");
					debug(e);
				}
			}
		}
	}

	public void loadTabComplete() {
		TabCompleteHandler.getInstance().addTabCompleteOption(new TabCompleteHandle("(AllPlayer)", new ArrayList<>()) {

			@Override
			public void reload() {
				ArrayList<String> players = new ArrayList<>();
				for (String name : getUuidNameCache().values()) {
					if (!players.contains(name)) {
						players.add(name);
					}
				}
				setReplace(players);
			}

			@Override
			public void updateReplacements() {
				for (Player player : Bukkit.getOnlinePlayers()) {
					if (!getReplace().contains(player.getName())) {
						getReplace().add(player.getName());
					}
				}

			}
		});

		TabCompleteHandler.getInstance().addTabCompleteOption(new TabCompleteHandle("(Player)", new ArrayList<>()) {

			@Override
			public void reload() {
				ArrayList<String> list = new ArrayList<>();
				for (Player player : Bukkit.getOnlinePlayers()) {
					list.add(player.getName());
				}
				setReplace(list);
			}

			@Override
			public void updateReplacements() {
				ArrayList<String> list = new ArrayList<>();
				for (Player player : Bukkit.getOnlinePlayers()) {
					list.add(player.getName());
				}
				setReplace(list);
			}
		}.updateOnLoginLogout());

		TabCompleteHandler.getInstance()
				.addTabCompleteOption(new TabCompleteHandle("(PlayerExact)", new ArrayList<>()) {

					@Override
					public void reload() {
						ArrayList<String> list = new ArrayList<>();
						for (Player player : Bukkit.getOnlinePlayers()) {
							list.add(player.getName());
						}
						setReplace(list);
					}

					@Override
					public void updateReplacements() {
						ArrayList<String> list = new ArrayList<>();
						for (Player player : Bukkit.getOnlinePlayers()) {
							list.add(player.getName());
						}
						setReplace(list);
					}
				}.updateOnLoginLogout());

		TabCompleteHandler.getInstance().addTabCompleteOption(new TabCompleteHandle("(uuid)", new ArrayList<>()) {

			@Override
			public void reload() {
				ArrayList<String> uuids = new ArrayList<>();
				for (String name : getUuidNameCache().keySet()) {
					if (!uuids.contains(name)) {
						uuids.add(name);
					}
				}
				setReplace(uuids);
			}

			@Override
			public void updateReplacements() {
				for (Player player : Bukkit.getOnlinePlayers()) {
					if (!getReplace().contains(player.getUniqueId().toString())) {
						getReplace().add(player.getUniqueId().toString());
					}
				}
			}
		}.updateEveryXMinutes(getTimer(), 30));

		ArrayList<String> options = new ArrayList<>();
		options.add("True");
		options.add("False");
		TabCompleteHandler.getInstance().addTabCompleteOption("(Boolean)", options);
		options = new ArrayList<>();
		TabCompleteHandler.getInstance().addTabCompleteOption("(List)", options);
		TabCompleteHandler.getInstance().addTabCompleteOption("(String)", options);
		TabCompleteHandler.getInstance().addTabCompleteOption("(Text)", options);
		TabCompleteHandler.getInstance().addTabCompleteOption("(Number)", options);
		TabCompleteHandler.getInstance().addTabCompleteOption(new TabCompleteHandle("(Reward)", options) {

			@Override
			public void reload() {
				ArrayList<String> rewards = new ArrayList<>();
				for (Reward reward : rewardHandler.getRewards()) {
					if (!reward.getConfig().isDirectlyDefinedReward()) {
						rewards.add(reward.getRewardName());
					}
				}
				setReplace(rewards);
			}

			@Override
			public void updateReplacements() {

			}
		});

		TabCompleteHandler.getInstance().addTabCompleteOption(new TabCompleteHandle("(ChoiceReward)", options) {

			@Override
			public void reload() {
				ArrayList<String> rewards = new ArrayList<>();
				for (Reward reward : rewardHandler.getRewards()) {
					if (reward.getConfig().getEnableChoices()) {
						rewards.add(reward.getRewardName());
					}
				}
				setReplace(rewards);
			}

			@Override
			public void updateReplacements() {

			}
		});

		ArrayList<String> method = new ArrayList<>();
		for (InputMethod me : InputMethod.values()) {
			method.add(me.toString());
		}
		TabCompleteHandler.getInstance().addTabCompleteOption("(RequestMethod)", method);

		ArrayList<String> userStorage = new ArrayList<>();
		for (UserStorage storage : UserStorage.values()) {
			userStorage.add(storage.toString());
		}
		TabCompleteHandler.getInstance().addTabCompleteOption("(UserStorage)", userStorage);

		ArrayList<String> times = new ArrayList<>();
		for (TimeType ty : TimeType.values()) {
			times.add(ty.toString());
		}
		TabCompleteHandler.getInstance().addTabCompleteOption("(TimeType)", times);
	}

	public void loadUserAPI(UserStorage storageType) {
		java.util.Objects.requireNonNull(storageType, "storageType");
		if (getUserStorageOwnership().isReplacingOnCurrentThread()
				|| (storageType == UserStorage.SQLITE && database == null && sqliteBootstrap.get())) {
			loadUserAPIOwned(storageType);
		} else {
			getUserStorageOwnership().replace(5, TimeUnit.SECONDS, this::flushStorageForReplacement, () -> loadUserAPIOwned(storageType));
		}
	}

	private void loadUserAPIOwned(UserStorage storageType) {
		if (storageType == UserStorage.SQLITE) {
			synchronized (sqliteInitialization) {
				Database candidate = java.util.Objects.requireNonNull(createSQLiteProvider(), "SQLite provider");
				try {
					if (candidate.getDB() == null) throw new IllegalStateException("SQLite provider did not initialize");
					java.sql.Connection connection = candidate.getDB().getConnection();
					if (connection == null || connection.isClosed()) throw new IllegalStateException("SQLite provider did not initialize");
					if (candidate == database) return;
					closeSQLiteProvider(database);
					database = candidate;
				} catch (java.sql.SQLException failure) {
					IllegalStateException reported = new IllegalStateException("SQLite initialization was not acknowledged", failure);
					cleanupSQLiteCandidate(candidate, reported);
					throw reported;
				} catch (RuntimeException | Error failure) {
					cleanupSQLiteCandidate(candidate, failure);
					throw failure;
				}
			}
		} else if (storageType == UserStorage.MYSQL) {
			MySQL candidate = java.util.Objects.requireNonNull(createMySQLProvider(), "MySQL provider");
			try { setMysql(candidate); }
			catch (RuntimeException | Error failure) {
				if (mysql != candidate) {
					try { candidate.close(); }
					catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
				}
				throw failure;
			}
		} else if (storageType == UserStorage.FLAT) {
			getLogger().severe("Detected using FLAT storage, this will be removed in the future!");
		}
	}

	Database createSQLiteProvider() {
		ArrayList<Column> columns = new ArrayList<>();
		Column key = new Column("uuid", DataType.STRING);
		columns.add(key);
		UserTable table = new UserTable(this, "Users", columns, key);
		Database candidate = new Database(this, "Users", table);
		try { table.addCustomColumns(); return candidate; }
		catch (RuntimeException | Error failure) { cleanupSQLiteCandidate(candidate, failure); throw failure; }
	}

	@SuppressWarnings("deprecation")
	MySQL createMySQLProvider() {
		org.bukkit.configuration.ConfigurationSection root = getOptions().getYmlConfig().getData();
		// Modern AdvancedCore calls this section Database; retain MySQL precedence
		// when both are present so existing installations remain authoritative.
		org.bukkit.configuration.ConfigurationSection section = root.getConfigurationSection("MySQL");
		if (section == null) {
            section = root.getConfigurationSection("Database");
            if (section != null) {
                String type = section.getString("DbType", "MYSQL");
                if (!"MYSQL".equalsIgnoreCase(type) && !"MARIADB".equalsIgnoreCase(type)) {
                    throw new IllegalArgumentException("Java 8 native MYSQL storage supports Database.DbType MYSQL or MARIADB only");
                }
            }
        }
		return new MySQL(javaPlugin, javaPlugin.getName() + "_Users", section);
	}

	private void cleanupSQLiteCandidate(Database candidate, Throwable failure) {
		if (candidate == database) return;
		try { closeSQLiteProvider(candidate); }
		catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
	}

	private void closeSQLiteProvider(Database provider) {
		if (provider == null || provider.getDB() == null) return;
		// getSQLConnection/Database.getConnection can reopen a retired connection.
		java.sql.Connection connection = provider.getDB().getConnection();
		if (connection != null) try { connection.close(); }
		catch (java.sql.SQLException failure) { throw new IllegalStateException("SQLite provider close was not acknowledged", failure); }
	}

	private void loadUUIDs() {

		uuidNameCache = new ConcurrentHashMap<>();

		addUserStartup(new UserStartup() {

			@Override
			public void onFinish() {
				TabCompleteHandler.getInstance().reload();
				debug("Finished loading uuids");
			}

			@Override
			public void onStart() {
				debug("Starting background uuid/name task");
				if (!getOptions().isOnlineMode()) {
					setProcess(false);
				}
			}

			@Override
			public void onStartUp(AdvancedCoreUser user) {
				String uuid = user.getUUID();
				String name = user.getData().getString("PlayerName", false, true);
				boolean add = true;
				if (uuidNameCache.containsKey(uuid)) {
					debug("Duplicate uuid? " + uuid + "/" + name);
				}

				if (name == null || name.equals("") || name.equals("Error getting name") || name.equals("null")) {
					// extraDebug("Invalid player name: " + uuid);
					add = false;
				} else {
					if (uuidNameCache.containsValue(name)) {
						debug("Duplicate player name?" + uuid + "/" + name);
					}
				}
				if (uuid == null || uuid.equals("")) {
					debug("Invalid uuid: " + uuid);
					add = false;
				}

				if (add) {
					uuidNameCache.put(uuid, name);
				}
			}
		});

		TabCompleteHandler.getInstance().reload();
		TabCompleteHandler.getInstance().loadTabCompleteOptions();
		TabCompleteHandler.getInstance().loadTimer(getTimer());
	}

	public void loadValueRequestInputCommands() {
		CommandLoader.getInstance().loadValueRequestCommands();
		try {
			final Field bukkitCommandMap = Bukkit.getServer().getClass().getDeclaredField("commandMap");

			bukkitCommandMap.setAccessible(true);
			CommandMap commandMap = (CommandMap) bukkitCommandMap.get(Bukkit.getServer());
			commandMap.register(this.getName() + "valuerequestinput",
					new ValueRequestInputCommand(this, this.getName() + "valuerequestinput"));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void loadVault() {
		vaultHandler = new VaultHandler();
		if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
			debug("Attempting to hook into vault");
			vaultHandler.loadVault(this);
		}
	}

	private void loadVersionFile() {
		YamlConfiguration conf = getVersionFile();
		version = conf.getString("version", "Unknown");
		buildTime = conf.getString("time", "Unknown");
		advancedCoreBuildNumber = conf.getString("buildnumber", "NOTSET");
	}

	/** Stop subclass producers while the shared storage executor is still available. */
	public void onPreUnLoad() {
	}

	@Override
	public void onDisable() {
		if (rewardDispatch != null) rewardDispatch.close();
		if (rewardHandler != null) rewardHandler.stopSubmittingDelayedRewards();
		onPreUnLoad();
		if (rewardHandler != null) rewardHandler.shutdown();
		if (serverDataFile != null) serverDataFile.setLastUpdated();
		ScheduledExecutorService timeTimer = timeChecker == null ? null : timeChecker.getTimer();
		ScheduledExecutorService cacheTimer = userManager == null || userManager.getDataManager() == null
				? null : userManager.getDataManager().getTimer();
		shutdownProducer(loginTimer);
		shutdownProducer(timeTimer);
		shutdownProducer(inventoryTimer);
		shutdownProducer(cacheTimer);
		long started = System.nanoTime();
		long grace = TimeUnit.SECONDS.toNanos(5);
		getLogger().info("Allowing accepted background work to finish before storage retirement");
		// Producer tasks may submit storage work; keep the shared timer open until they settle.
		awaitProducer(loginTimer, started, grace, "login");
		awaitProducer(timeTimer, started, grace, "time checker");
		awaitProducer(inventoryTimer, started, grace, "inventory");
		awaitProducer(cacheTimer, started, grace, "user cache");
		shutdownProducer(timer);
		awaitProducer(timer, started, grace, "shared storage");
		long remaining = Math.max(0L, grace - (System.nanoTime() - started));
		userStorageOwnership.retire(remaining, TimeUnit.NANOSECONDS, () -> {
			onUnLoad();
			if (userManager != null && userManager.getDataManager() != null) userManager.getDataManager().clearCacheForShutdown();
		}, () -> {
			if (mysql != null) mysql.close();
			closeSQLiteProvider(database);
		});
		if (skullCacheHandler != null) skullCacheHandler.close();
		if (fullInventoryHandler != null) fullInventoryHandler.shutdown();
		unRegisterValueRequest();
		if (permissionHandler != null) permissionHandler.shutDown();
		javaPlugin = null;
	}

	private static void shutdownProducer(ScheduledExecutorService executor) {
		if (executor != null) executor.shutdown();
	}

	private static void awaitProducer(ScheduledExecutorService executor, long started, long grace, String name) {
		if (executor == null || executor.isTerminated()) return;
		long remaining = Math.max(0L, grace - (System.nanoTime() - started));
		try {
			if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
				throw new IllegalStateException("The " + name + " executor has not settled; storage provider remains open");
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Shutdown interrupted; storage provider remains open", interrupted);
		}
	}

	@Override
	public void onEnable() {
		rewardDispatch = new com.bencodez.advancedcore.api.rewards.ServerThreadRewardDispatch(this);
		javaPlugin = this;
		bukkitScheduler = new BukkitScheduler(this);
		timer = Executors.newSingleThreadScheduledExecutor();
		loginTimer = Executors.newSingleThreadScheduledExecutor();
		advancedCoreCommandLoader = CommandLoader.getInstance();
		inventoryTimer = Executors.newSingleThreadScheduledExecutor();

		onPreLoad();
		loadHook();
		onPostLoad();
		getRewardHandler().checkSubRewards();
	}

	public abstract void onPostLoad();

	public abstract void onPreLoad();

	public abstract void onUnLoad();

	public void registerBungeeChannels(String name) {
		this.bungeeChannel = name;
		getServer().getMessenger().registerOutgoingPluginChannel(this, name);
		pluginMessaging = new PluginMessage(this, name);
		getServer().getMessenger().registerIncomingPluginChannel(this, name, pluginMessaging);
		getLogger().info("Loaded plugin message channels: " + name);
	}

	public void registerEvents(Listener listener) {
		Bukkit.getPluginManager().registerEvents(listener, this);
	}

	public abstract void reload();

	@Deprecated
	public void reloadAdvancedCore() {
		reloadAdvancedCore(false);
	}

	public void reloadAdvancedCore(boolean userStorage) {
		getServerDataFile().reloadData();
		rewardHandler.loadRewards();
		loadConfig(userStorage);

		if (userStorage) {
			if (!loadUserData) getUserManager().getDataManager().clearCache();
			if (getStorageType().equals(UserStorage.MYSQL) && getMysql() != null) {
				getMysql().clearCacheBasic();
			}
		}
		timeChecker.update();
		TabCompleteHandler.getInstance().reload();
		TabCompleteHandler.getInstance().loadTabCompleteOptions();
		getRewardHandler().checkSubRewards();

		if (skullCacheHandler != null) {
			if (!getOptions().getSkullProfileAPIURL().isEmpty()) {
				debug("Setting API profile URL to " + getOptions().getSkullProfileAPIURL());
				skullCacheHandler.changeApiProfileURL(getOptions().getSkullProfileAPIURL());
			}
			getSkullCacheHandler().setBedrockPrefix(getOptions().getBedrockPlayerPrefix());
		}
	}

	/**
	 * @param configData the configData to set
	 */
	@Deprecated
	public void setConfigData(ConfigurationSection configData) {
		getOptions().setYmlConfig(new YMLConfig(this, configData) {

			@Override
			public void createSection(String key) {

			}

			@Override
			public void saveData() {

			}

			@Override
			public void setValue(String path, Object value) {

			}
		});
	}

	public void setConfigData(YMLConfig ymlConfig) {
		getOptions().setYmlConfig(ymlConfig);
	}

	/**
	 * @param mysql the mysql to set
	 */
	public void setMysql(MySQL mysql) {
		if (this.mysql == mysql) return;
		if (getUserStorageOwnership().isReplacingOnCurrentThread()) {
			setMysqlOwned(mysql);
		} else {
			getUserStorageOwnership().replace(5, TimeUnit.SECONDS, this::flushStorageForReplacement, () -> setMysqlOwned(mysql));
		}
	}

	private void flushStorageForReplacement() {
		// Do not create a user manager merely to replace an unused provider.
		if (userManager != null && userManager.getDataManager() != null) {
			userManager.getDataManager().clearCacheForShutdown();
		}
	}

	private void setMysqlOwned(MySQL mysql) {
		if (this.mysql != null) this.mysql.close();
		this.mysql = mysql;
	}

	public void unRegisterValueRequest() {
		try {
			final Field bukkitCommandMap = Bukkit.getServer().getClass().getDeclaredField("commandMap");

			bukkitCommandMap.setAccessible(true);
			CommandMap commandMap = (CommandMap) bukkitCommandMap.get(Bukkit.getServer());

			commandMap.getCommand(this.getName() + "valuerequestinput").unregister(commandMap);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void userStartup() {
		if (!loadUserData) {
			debug("Not loading userdata");
			return;
		}
		rewardHandler.startup();
		getBukkitScheduler().runTaskLaterAsynchronously(this, new Runnable() {

			@Override
			public void run() {
				debug("User Startup starting");
				for (UserStartup start : userStartup) {
					start.onStart();
				}
				boolean onlineMode = getOptions().isOnlineMode();
				int offlineAmount = 0;
				HashMap<UUID, ArrayList<Column>> cols = getUserManager().getAllKeys();
				for (Entry<UUID, ArrayList<Column>> playerData : cols.entrySet()) {
					String uuid = playerData.getKey().toString();
					if (onlineMode) {
						if (uuid.charAt(14) == '3') {
							offlineAmount++;
						}
					}
					if (javaPlugin != null) {
						if (uuid != null) {
							AdvancedCoreUser user = getUserManager().getUser(UUID.fromString(uuid), false);
							if (user != null) {
								user.dontCache();
								user.updateTempCacheWithColumns(playerData.getValue());
								for (UserStartup start : userStartup) {
									if (start.isProcess()) {
										start.onStartUp(user);
									}
								}
								user.clearTempCache();
								cols.put(playerData.getKey(), null);
								user = null;
							}
						}
					}
				}
				cols.clear();
				cols = null;
				for (UserStartup start : userStartup) {
					start.onFinish();
				}
				if (offlineAmount > 0 && onlineMode) {
					debug("Detected offline uuids in a online server, this could mean an error for your server setup: "
							+ offlineAmount);
				}
				debug("User Startup finished");
			}
		}, 5);
	}
}
