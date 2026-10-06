package com.bencodez.advancedcore.api.permissions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;

import com.bencodez.advancedcore.AdvancedCorePlugin;

import lombok.Getter;

public class PermissionHandler {
	private volatile boolean closed;
    private volatile boolean shutdownPersisted;
	@Getter
	private AdvancedCorePlugin plugin;

	@Getter
	private ConcurrentHashMap<UUID, PlayerPermissionHandler> perms = new ConcurrentHashMap<>();

	@Getter
	private ScheduledExecutorService timer = Executors.newScheduledThreadPool(1);

	@Getter
	private HashMap<UUID, PlayerPermissionHandler> permsToAdd;

	public PermissionHandler(AdvancedCorePlugin plugin) {
		this.plugin = plugin;
		permsToAdd = new HashMap<>();
		try {
		if (plugin.getServerDataFile().getData() != null) {
			if (plugin.getServerDataFile().getData().isConfigurationSection("TimedPermissions")) {
				for (String string : plugin.getServerDataFile().getData().getConfigurationSection("TimedPermissions")
						.getKeys(false)) {
					UUID uuid = UUID.fromString(string);
					List<String> list = plugin.getServerDataFile().getData()
							.getStringList("TimedPermissions." + string);
					for (String str : list) {
						String[] data = str.split(Pattern.quote("%line%"));
						if (data.length > 1) {
							String perm = data[0];
							String longStr = data[1];
							long delay = Long.valueOf(longStr).longValue() - System.currentTimeMillis();
							if (delay > 0) {
								plugin.debug("Adding permission " + perm + " to " + string);
								getPermsToAdd().computeIfAbsent(uuid, key -> new PlayerPermissionHandler(key, null, this))
										.restoreExpiration(perm, Long.parseLong(longStr));

							}
						}
					}
				}
				plugin.getServerDataFile().getData().set("TimedPermissions", null);
			}
		}
        } catch(RuntimeException | Error failure) {
            closed=true;timer.shutdownNow();
            try {awaitTimerTermination();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
	}

	public void addPermission(Player player, String permission) {
		addPermission(player.getUniqueId(), permission);
	}

	public void addPermission(Player player, String permission, long expiration) {
		addPermission(player.getUniqueId(), permission, expiration);
	}

	public synchronized void addPermission(UUID uuid, String permission) {
		if (closed) throw new IllegalStateException("Permission manager is closed");
		if (permission == null || permission.isEmpty()) {
			plugin.debug("Permission is empty");
			return;
		}
		for (String perm : permission.split(Pattern.quote("|"))) {
			PlayerPermissionHandler existing = getPerms().get(uuid);
			if (existing != null) {
				existing.addPerm(perm);
			} else {
				Player p = Bukkit.getPlayer(uuid);
				if (p != null) {
                    PlayerPermissionHandler handle=getPermsToAdd().computeIfAbsent(uuid,key -> new PlayerPermissionHandler(key,null,this));
                    prepareAttachment(handle,p);handle.onLogin(p);
                    getPerms().put(uuid,handle.addPerm(perm));getPermsToAdd().remove(uuid,handle);
				} else {
					getPermsToAdd().computeIfAbsent(uuid, key -> new PlayerPermissionHandler(key, null, this))
							.addOfflinePerm(perm, -1);
				}
			}
		}
	}

	public synchronized void addPermission(UUID uuid, String permission, long delay) {
		if (closed) throw new IllegalStateException("Permission manager is closed");
		if (permission == null || permission.isEmpty()) {
			plugin.debug("Permission is empty");
			return;
		}
		for (String perm : permission.split(Pattern.quote("|"))) {
			PlayerPermissionHandler existing = getPerms().get(uuid);
			if (existing != null) {
				existing.addExpiration(perm, delay);
			} else {
				Player p = Bukkit.getPlayer(uuid);
				if (p != null) {
                    PlayerPermissionHandler handle=getPermsToAdd().computeIfAbsent(uuid,key -> new PlayerPermissionHandler(key,null,this));
                    prepareAttachment(handle,p);handle.onLogin(p);
                    getPerms().put(uuid,handle.addExpiration(perm,delay));getPermsToAdd().remove(uuid,handle);
				} else {
					getPermsToAdd().computeIfAbsent(uuid, key -> new PlayerPermissionHandler(key, null, this))
							.addOfflinePerm(perm, delay);
				}
			}

		}
	}

	public synchronized void login(Player player) {
        if(closed)return;
        UUID uuid=player.getUniqueId();PlayerPermissionHandler handle=getPerms().get(uuid);
        if(handle==null)handle=getPermsToAdd().get(uuid);
        if(handle==null)return;
        prepareAttachment(handle,player);
        handle.onLogin(player);
        getPerms().put(uuid,handle);getPermsToAdd().remove(uuid);
    }

    /** Preserve tracked grants while detaching only the session that is quitting. */
    public synchronized void logout(Player player) {
        if(closed)return;
        UUID uuid=player.getUniqueId();PlayerPermissionHandler handle=getPerms().get(uuid);
        if(handle==null)return;
        PermissionAttachment attachment=handle.getAttachment();
        if(attachment!=null && attachment.getPermissible()!=player)return;
        if(attachment!=null)attachment.remove();
        handle.setAttachment(null);
        PlayerPermissionHandler pending=getPermsToAdd().get(uuid);
        if(pending!=null && pending!=handle)handle.mergeOfflinePermissions(pending.offlinePermissionSnapshot());
        getPerms().remove(uuid,handle);getPermsToAdd().put(uuid,handle);
    }

    /** Prepare the successor before retiring the previous session attachment. */
    private void prepareAttachment(PlayerPermissionHandler handle,Player player) {
        PermissionAttachment previous=handle.getAttachment();
        if(previous!=null && previous.getPermissible()==player)return;
        PermissionAttachment replacement=player.addAttachment(plugin);
        try {if(previous!=null)previous.remove();}
        catch(RuntimeException | Error failure) {
            try {replacement.remove();}catch(RuntimeException | Error cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
        handle.setAttachment(replacement);
    }

    /** Caller holds the manager monitor before entering player state. */
    void requireOpen() {
        if(closed)throw new IllegalStateException("Permission manager is closed");
    }

    void scheduleExpiration(PlayerPermissionHandler handle,String perm,long expected,long delayMillis) {
        if(closed)return;
        getTimer().schedule(()->dispatchExpiration(handle,perm,expected),Math.max(0L,delayMillis),java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void dispatchExpiration(PlayerPermissionHandler handle,String perm,long expected) {
        if(closed || !handle.isExpirationCurrent(perm,expected))return;
        try {
            Bukkit.getScheduler().runTask(plugin,()->{
                synchronized(PermissionHandler.this) {
                    if(closed || !handle.isExpirationCurrent(perm,expected))return;
                    if(getPerms().get(handle.getUuid())!=handle && getPermsToAdd().get(handle.getUuid())!=handle)return;
                    Player player=Bukkit.getPlayer(handle.getUuid());
                    boolean live=player!=null && handle.getAttachment()!=null && handle.getAttachment().getPermissible()==player;
                    handle.expirePermission(perm,expected,live);
                }
            });
        } catch(RuntimeException failure) {
            plugin.debug(failure);
            if(!closed)scheduleExpiration(handle,perm,expected,1000L);
        }
    }

    synchronized void removePermissionIfEmpty(UUID uuid,PlayerPermissionHandler expected) {
        if(!expected.isEmpty())return;
        getPerms().remove(uuid,expected);getPermsToAdd().remove(uuid,expected);
    }

	public synchronized void removePermission(UUID uuid) {
		getPerms().remove(uuid);getPermsToAdd().remove(uuid);
	}

    public synchronized void removePermission(UUID uuid,String playerName,String permission) {
        if(permission==null || permission.isEmpty())return;
        PlayerPermissionHandler handle=getPerms().get(uuid);if(handle==null)handle=getPermsToAdd().get(uuid);
        if(handle==null)return;
        for(String perm:permission.split(Pattern.quote("|")))handle.removePermission(perm);
    }

    public void shutDown() {
        synchronized(this) {
            if(shutdownPersisted)return;
            closed=true;getTimer().shutdownNow();
            HashMap<UUID,PlayerPermissionHandler> all=new HashMap<>(getPermsToAdd());all.putAll(getPerms());
            plugin.getServerDataFile().getData().set("TimedPermissions",null);
            for(Entry<UUID,PlayerPermissionHandler> entry:all.entrySet()) {
                ArrayList<String> list=new ArrayList<>();
                for(Entry<String,Long> permission:entry.getValue().timedPermissionSnapshot().entrySet()) {
                    if(permission.getValue()>System.currentTimeMillis())list.add(permission.getKey()+"%line%"+permission.getValue());
                }
                if(!list.isEmpty())plugin.getServerDataFile().getData().set("TimedPermissions."+entry.getKey(),list);
            }
            plugin.getServerDataFile().saveData();
        }
        awaitTimerTermination();shutdownPersisted=true;
    }

    private void awaitTimerTermination() {
        try {
            if(!getTimer().awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Permission expiry executor did not terminate");
        } catch(InterruptedException failure) {
            Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted retiring permission expiry executor",failure);
        }
    }
}
