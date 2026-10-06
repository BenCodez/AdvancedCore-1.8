package com.bencodez.advancedcore.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import com.bencodez.advancedcore.AdvancedCorePlugin;

import de.myzelyam.api.vanish.PlayerShowEvent;

// TODO: Auto-generated Javadoc
/**
 * The Class PlayerJoinEvent.
 */
public class PlayerShowListener implements Listener {

	/** The plugin. */
	private AdvancedCorePlugin plugin;

	/**
	 * Instantiates a new player join event.
	 *
	 * @param plugin the plugin
	 */
	public PlayerShowListener(AdvancedCorePlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(PlayerShowEvent event) {
		if (plugin != null && plugin.isEnabled()) {
            final com.bencodez.advancedcore.api.rewards.ServerThreadRewardDispatch owner=plugin.getRewardDispatch();
			plugin.getBukkitScheduler().runTaskLaterAsynchronously(plugin, new Runnable() {

				@Override
				public void run() {
					if (plugin != null && plugin.isEnabled()) {
						if ((plugin.isAuthMeLoaded() || plugin.isNLoginLoaded()) && plugin.getOptions().isWaitUntilLoggedIn()) {
							return;
						}

						Player player = event.getPlayer();

						if (player != null) {
							plugin.debug("Vanish Login: " + event.getPlayer().getName() + " ("
									+ event.getPlayer().getUniqueId() + ")");
                            owner.dispatch(()->{
                                if(!plugin.isEnabled() || !player.isOnline() || Bukkit.getPlayer(player.getUniqueId())!=player)return java.util.concurrent.CompletableFuture.completedFuture(false);
                                if(plugin.getPermissionHandler()!=null)plugin.getPermissionHandler().login(player);
                                return java.util.concurrent.CompletableFuture.completedFuture(true);
                            },30000).thenCompose(accepted->{
                                if(!accepted)return java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
                                return owner.dispatchOffPrimary(()->{
                                    if(plugin.isEnabled())Bukkit.getPluginManager().callEvent(new AdvancedCoreLoginEvent(player));
                                    return java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
                                },30000);
                            }).whenComplete((unused,failure)->{
                                if(failure!=null)plugin.getLogger().log(java.util.logging.Level.SEVERE,"Vanish login failed",failure);
                            });
						}

					}

				}
			}, 2);
		}
	}

}