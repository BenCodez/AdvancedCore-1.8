package com.bencodez.advancedcore.listeners;

import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.MetadataValue;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.misc.PlayerManager;
import com.bencodez.advancedcore.api.user.AdvancedCoreUser;
import com.bencodez.simpleapi.command.TabCompleteHandler;

// TODO: Auto-generated Javadoc
/**
 * The Class PlayerJoinEvent.
 */
public class PlayerJoinEvent implements Listener {

	/** The plugin. */
	private AdvancedCorePlugin plugin;
    private final java.util.concurrent.ConcurrentHashMap<java.util.UUID,Player> pendingLoginSessions=new java.util.concurrent.ConcurrentHashMap<>();
    private final Object[] loginSessionLocks=java.util.stream.IntStream.range(0,64).mapToObj(ignored->new Object()).toArray();
    private Object loginSessionLock(java.util.UUID uuid){return loginSessionLocks[(uuid.hashCode() & Integer.MAX_VALUE)%loginSessionLocks.length];}


	/**
	 * Instantiates a new player join event.
	 *
	 * @param plugin the plugin
	 */
	public PlayerJoinEvent(AdvancedCorePlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onJoin(AdvancedCoreLoginEvent event) {
		if (event.isUserInStorage() && plugin.isLoadUserData()) {
			Player player = event.getPlayer();
			boolean userExist = plugin.getUserManager().userExist(event.getPlayer().getUniqueId());
			if (player.getName().startsWith(plugin.getOptions().getBedrockPlayerPrefix())
					|| plugin.getGeyserHandle().isGeyserPlayer(player.getUniqueId())) {
				userExist = true;

				if (plugin.getOptions().isOnlineMode()) {
					plugin.getUuidNameCache().put(player.getUniqueId().toString(), player.getName());
				} else {
					plugin.getUuidNameCache().put(PlayerManager.getInstance().getUUID(player.getName()),
							player.getName());
				}
				plugin.extraDebug("Detected Geyser Player, Forcing player data to load");
			}

			plugin.getUserManager().getDataManager().cacheUser(player.getUniqueId(), player.getName());

			if (userExist) {
				AdvancedCoreUser user = plugin.getUserManager().getUser(player);

				user.checkOfflineRewards();
				user.setLastOnline(System.currentTimeMillis());
				user.updateName(false);
			}
			if (plugin.getOptions().isOnlineMode()) {
				plugin.getUuidNameCache().put(player.getUniqueId().toString(), player.getName());
			} else {
				plugin.getUuidNameCache().put(PlayerManager.getInstance().getUUID(player.getName()),
						player.getName());
			}

		}

	}

	/**
	 * On player login.
	 *
	 * @param event the event
	 */
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerLogin(final org.bukkit.event.player.PlayerJoinEvent event) {
        if(plugin==null || !plugin.isEnabled())return;
        plugin.getUserManager().getDataManager().markUserOnline(event.getPlayer());
        if(!plugin.isLoadUserData())return;
        final Player player=event.getPlayer();final java.util.UUID uuid=player.getUniqueId();
        final com.bencodez.advancedcore.api.rewards.ServerThreadRewardDispatch owner=plugin.getRewardDispatch();
        plugin.getLogger().info("Login: "+player.getName()+" ("+uuid+")");
        synchronized(loginSessionLock(uuid)){pendingLoginSessions.put(uuid,player);}
        try {
            plugin.getLoginTimer().schedule(()->{
                if(!plugin.isEnabled() || pendingLoginSessions.get(uuid)!=player){pendingLoginSessions.remove(uuid,player);return;}
                try {
                TabCompleteHandler.getInstance().onLogin();
                owner.dispatch(()->{
                    synchronized(loginSessionLock(uuid)) {
                        if(!plugin.isEnabled() || pendingLoginSessions.get(uuid)!=player || !player.isOnline() || Bukkit.getPlayer(uuid)!=player)return java.util.concurrent.CompletableFuture.completedFuture(false);
                        if((plugin.isAuthMeLoaded() || plugin.isLoginSecurityLoaded() || plugin.isNLoginLoaded()) && plugin.getOptions().isWaitUntilLoggedIn())return java.util.concurrent.CompletableFuture.completedFuture(false);
                        for(MetadataValue meta:player.getMetadata("vanished"))if(meta.asBoolean() && plugin.getOptions().isTreatVanishAsOffline())return java.util.concurrent.CompletableFuture.completedFuture(false);
                        if(plugin.getCmiHandle()!=null && plugin.getCmiHandle().isVanished(player) && plugin.getOptions().isTreatVanishAsOffline())return java.util.concurrent.CompletableFuture.completedFuture(false);
                        if(plugin.getPermissionHandler()!=null)plugin.getPermissionHandler().login(player);
                        return java.util.concurrent.CompletableFuture.completedFuture(true);
                    }
                },30000).thenCompose(accepted->{
                    if(!accepted)return java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
                    return owner.dispatchOffPrimary(()->{
                        if(plugin.isEnabled() && pendingLoginSessions.get(uuid)==player)Bukkit.getPluginManager().callEvent(new AdvancedCoreLoginEvent(player));
                        return java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
                    },30000);
                }).whenComplete((unused,failure)->{
                    pendingLoginSessions.remove(uuid,player);
                    if(failure!=null)plugin.getLogger().log(java.util.logging.Level.SEVERE,"Delayed login failed",failure);
                });
                }catch(RuntimeException | Error failure){pendingLoginSessions.remove(uuid,player);plugin.getLogger().log(java.util.logging.Level.SEVERE,"Delayed login preparation failed",failure);}
            },1500+plugin.getOptions().getDelayLoginEvent(),TimeUnit.MILLISECONDS);
        }catch(RuntimeException failure){pendingLoginSessions.remove(uuid,player);throw failure;}
    }

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPlayerQuit(PlayerQuitEvent event) {
		if (plugin != null && plugin.isEnabled()) {
			Player player = event.getPlayer();
            final java.util.UUID uuid=player.getUniqueId();
            final String playerName=player.getName();
            plugin.getUserManager().getDataManager().markUserOffline(player);
            synchronized(loginSessionLock(uuid)) {
                pendingLoginSessions.remove(uuid,player);
                if(plugin.getPermissionHandler()!=null)plugin.getPermissionHandler().logout(player);
            }
			plugin.debug("Logout: " + event.getPlayer().getName() + " (" + player.getUniqueId() + ")");

			plugin.getLoginTimer().execute(new Runnable() {

				@Override
				public void run() {
					if (plugin != null && plugin.isEnabled()) {
						TabCompleteHandler.getInstance().onLogin();
						
						plugin.getUserManager().getDataManager().removeCache(uuid, playerName);

					}
				}
			});
		}

	}

}