package com.bencodez.advancedcore.api.rewards;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.bencodez.advancedcore.AdvancedCoreConfigOptions;
import com.bencodez.advancedcore.api.permissions.PermissionHandler;
import com.bencodez.advancedcore.listeners.PlayerJoinEvent;
import com.bencodez.simpleapi.command.TabCompleteHandler;

class LegacyLoginSessionTest {
    @Test void oldDelayedLoginCannotClaimReconnectedPlayer() {
        fixture(f->{Player old=f.player(),current=f.player();when(Bukkit.getPlayer(f.id)).thenReturn(current);f.listener.onPlayerLogin(new org.bukkit.event.player.PlayerJoinEvent(old,"join"));f.listener.onPlayerQuit(new org.bukkit.event.player.PlayerQuitEvent(old,"quit"));f.listener.onPlayerLogin(new org.bukkit.event.player.PlayerJoinEvent(current,"join"));f.delayed.get(0).run();f.drain();verify(f.permissions,never()).login(old);f.delayed.get(1).run();f.drain();verify(f.permissions).login(current);assertEquals(1,f.events.size());});
    }
    @Test void quitBeforeOwnerAdmissionCancelsQueuedPermissionAndEventWork() {
        fixture(f->{Player player=f.player();when(Bukkit.getPlayer(f.id)).thenReturn(player);f.listener.onPlayerLogin(new org.bukkit.event.player.PlayerJoinEvent(player,"join"));f.delayed.get(0).run();f.listener.onPlayerQuit(new org.bukkit.event.player.PlayerQuitEvent(player,"quit"));when(Bukkit.getPlayer(f.id)).thenReturn(null);f.drain();verify(f.permissions,never()).login(player);assertTrue(f.events.isEmpty());});
    }
    @Test void currentLoginUsesOwnerForPermissionsAndWorkerForDeclaredAsyncEvent() {
        fixture(f->{Player player=f.player();when(Bukkit.getPlayer(f.id)).thenReturn(player);doAnswer(c->{assertTrue(f.dispatch.primary.get(),"Permission attachment requires owner thread");return null;}).when(f.permissions).login(player);f.listener.onPlayerLogin(new org.bukkit.event.player.PlayerJoinEvent(player,"join"));f.delayed.get(0).run();assertTrue(f.events.isEmpty());f.drain();verify(f.permissions).login(player);assertEquals(1,f.events.size());assertTrue(f.events.get(0).isAsynchronous());});
    }
    @Test void staleQuitCannotInvalidateNewerPendingLogin() {
        fixture(f->{Player old=f.player(),current=f.player();when(Bukkit.getPlayer(f.id)).thenReturn(current);f.listener.onPlayerLogin(new org.bukkit.event.player.PlayerJoinEvent(old,"join"));f.listener.onPlayerLogin(new org.bukkit.event.player.PlayerJoinEvent(current,"join"));f.listener.onPlayerQuit(new org.bukkit.event.player.PlayerQuitEvent(old,"quit"));f.delayed.get(1).run();f.drain();verify(f.permissions).login(current);assertEquals(1,f.events.size());});
    }
    @Test void authWaitAndVanishedPlayerKeepOptionalLoginSuppressed() {
        for(boolean auth:Arrays.asList(false,true))fixture(f->{Player player=f.player();when(Bukkit.getPlayer(f.id)).thenReturn(player);if(auth){when(f.dispatch.plugin.isAuthMeLoaded()).thenReturn(true);when(f.dispatch.plugin.getOptions().isWaitUntilLoggedIn()).thenReturn(true);}else{when(f.dispatch.plugin.getOptions().isTreatVanishAsOffline()).thenReturn(true);org.bukkit.metadata.MetadataValue vanished=mock(org.bukkit.metadata.MetadataValue.class);when(vanished.asBoolean()).thenReturn(true);when(player.getMetadata("vanished")).thenAnswer(c->{assertTrue(f.dispatch.primary.get());return Arrays.asList(vanished);});}f.listener.onPlayerLogin(new org.bukkit.event.player.PlayerJoinEvent(player,"join"));f.delayed.get(0).run();f.drain();verify(f.permissions,never()).login(player);assertTrue(f.events.isEmpty());});
    }
    @Test void vanishShowAlsoUsesOwnerPermissionAdmissionAndAsyncEvent() {
        fixture(f->{Player player=f.player();when(Bukkit.getPlayer(f.id)).thenReturn(player);doAnswer(c->{assertTrue(f.dispatch.primary.get());return null;}).when(f.permissions).login(player);com.bencodez.simpleapi.scheduler.BukkitScheduler scheduler=mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class);when(f.dispatch.plugin.getBukkitScheduler()).thenReturn(scheduler);List<Runnable> callbacks=new ArrayList<>();doAnswer(c->{callbacks.add(c.getArgument(1));return null;}).when(scheduler).runTaskLaterAsynchronously(eq(f.dispatch.plugin),any(Runnable.class),eq(2L));de.myzelyam.api.vanish.PlayerShowEvent event=mock(de.myzelyam.api.vanish.PlayerShowEvent.class);when(event.getPlayer()).thenReturn(player);new com.bencodez.advancedcore.listeners.PlayerShowListener(f.dispatch.plugin).onJoin(event);callbacks.get(0).run();assertTrue(f.events.isEmpty());f.drain();verify(f.permissions).login(player);assertEquals(1,f.events.size());assertTrue(f.events.get(0).isAsynchronous());});
    }
    @Test void staleVanishShowCannotAttachToReplacementSession() {
        fixture(f -> {
            Player old = f.player(), current = f.player();
            when(Bukkit.getPlayer(f.id)).thenReturn(current);
            com.bencodez.simpleapi.scheduler.BukkitScheduler scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class);
            when(f.dispatch.plugin.getBukkitScheduler()).thenReturn(scheduler);
            List<Runnable> callbacks = new ArrayList<>();
            doAnswer(c -> { callbacks.add(c.getArgument(1)); return null; })
                    .when(scheduler).runTaskLaterAsynchronously(eq(f.dispatch.plugin), any(Runnable.class), eq(2L));
            de.myzelyam.api.vanish.PlayerShowEvent event = mock(de.myzelyam.api.vanish.PlayerShowEvent.class);
            when(event.getPlayer()).thenReturn(old);
            new com.bencodez.advancedcore.listeners.PlayerShowListener(f.dispatch.plugin).onJoin(event);
            callbacks.get(0).run();
            f.drain();
            verify(f.permissions, never()).login(any(Player.class));
            assertTrue(f.events.isEmpty());
        });
    }
    private void fixture(Consumer<Fixture> body) {
        LegacyRewardDispatchTest.Fixture dispatch=new LegacyRewardDispatchTest.Fixture();when(dispatch.plugin.getRewardDispatch()).thenReturn(dispatch.owner);when(dispatch.plugin.isLoadUserData()).thenReturn(true);when(dispatch.plugin.getOptions()).thenReturn(mock(AdvancedCoreConfigOptions.class));when(dispatch.plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());ScheduledExecutorService timer=mock(ScheduledExecutorService.class);when(dispatch.plugin.getLoginTimer()).thenReturn(timer);PermissionHandler permissions=mock(PermissionHandler.class);when(dispatch.plugin.getPermissionHandler()).thenReturn(permissions);Fixture f=new Fixture(dispatch,permissions);when(timer.schedule(any(Runnable.class),anyLong(),eq(TimeUnit.MILLISECONDS))).thenAnswer(c->{f.delayed.add(c.getArgument(0));return mock(ScheduledFuture.class);});org.bukkit.plugin.PluginManager manager=mock(org.bukkit.plugin.PluginManager.class);
        try(MockedStatic<Bukkit> server=mockStatic(Bukkit.class);MockedStatic<TabCompleteHandler> tabs=mockStatic(TabCompleteHandler.class)) {
            server.when(Bukkit::isPrimaryThread).thenAnswer(c->dispatch.primary.get());server.when(Bukkit::getScheduler).thenReturn(dispatch.scheduler);server.when(Bukkit::getPluginManager).thenReturn(manager);tabs.when(TabCompleteHandler::getInstance).thenReturn(mock(TabCompleteHandler.class));doAnswer(c->{assertFalse(dispatch.primary.get(),"Login event retains declared asynchronous contract");f.events.add(c.getArgument(0));return null;}).when(manager).callEvent(any(Event.class));body.accept(f);
        }finally {dispatch.owner.close();}
    }
    private static class Fixture {
        final UUID id=UUID.randomUUID();final LegacyRewardDispatchTest.Fixture dispatch;final PermissionHandler permissions;final PlayerJoinEvent listener;final List<Runnable> delayed=new ArrayList<>();final List<Event> events=new ArrayList<>();
        Fixture(LegacyRewardDispatchTest.Fixture dispatch,PermissionHandler permissions){this.dispatch=dispatch;this.permissions=permissions;listener=new PlayerJoinEvent(dispatch.plugin);}
        Player player(){Player p=mock(Player.class);when(p.getUniqueId()).thenReturn(id);when(p.getName()).thenReturn("player");when(p.isOnline()).thenReturn(true);return p;}
        void drain(){int n=0;while(!dispatch.queued.isEmpty() || !dispatch.asyncQueued.isEmpty()){assertTrue(n++<30);if(!dispatch.queued.isEmpty())dispatch.runNext();else dispatch.runAsyncNext();}}
    }
}
