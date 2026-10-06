package com.bencodez.advancedcore.api.permissions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class LegacyPermissionReuseTest {
    @Test void shutdownWaitsForDirectGrantAdmissionBeforeFencingAndSnapshot() throws Exception {
        com.bencodez.advancedcore.AdvancedCorePlugin plugin = mock(com.bencodez.advancedcore.AdvancedCorePlugin.class, RETURNS_DEEP_STUBS);
        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();
        when(plugin.getServerDataFile().getData()).thenReturn(config);
        PermissionHandler owner = new PermissionHandler(plugin);
        org.bukkit.permissions.PermissionAttachment attachment = mock(org.bukkit.permissions.PermissionAttachment.class);
        PlayerPermissionHandler state = new PlayerPermissionHandler(UUID.randomUUID(), attachment, owner);
        owner.getPerms().put(state.getUuid(), state);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failed = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(c -> { entered.countDown(); assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); return null; })
                .when(attachment).setPermission("grant", true);
        Thread grant = new Thread(() -> { try { state.addExpiration("grant", 60); } catch(Throwable t) { failed.set(t); } });
        Thread close = new Thread(() -> { try { owner.shutDown(); } catch(Throwable t) { failed.set(t); } });
        try {
            grant.start();
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            close.start();
            long bound = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while(close.getState() != Thread.State.BLOCKED && close.isAlive() && System.nanoTime() < bound) Thread.yield();
            assertEquals(Thread.State.BLOCKED, close.getState(), "Shutdown waits on admitted grant ownership");
            assertFalse(owner.getTimer().isShutdown(), "Fence cannot overtake admitted direct grant");
            verify(plugin.getServerDataFile(), never()).saveData();
            release.countDown();
            grant.join(2000); close.join(2000);
            assertFalse(grant.isAlive()); assertFalse(close.isAlive()); assertNull(failed.get());
            assertTrue(owner.getTimer().isTerminated());
            assertEquals(Collections.singletonList("grant%line%" + state.getTimedPermissions().get("grant")),
                    config.getStringList("TimedPermissions." + state.getUuid()));
        } finally { release.countDown(); grant.join(2000); if(close.getState() != Thread.State.NEW)close.join(2000); owner.shutDown(); }
    }
    @Test void failedQuitDetachmentRetainsActiveOwnershipForRetry() {
        com.bencodez.advancedcore.AdvancedCorePlugin plugin = mock(com.bencodez.advancedcore.AdvancedCorePlugin.class, RETURNS_DEEP_STUBS);
        when(plugin.getServerDataFile().getData()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
        PermissionHandler owner = new PermissionHandler(plugin);
        UUID id = UUID.randomUUID();
        org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class);
        when(player.getUniqueId()).thenReturn(id);
        org.bukkit.permissions.PermissionAttachment attachment = mock(org.bukkit.permissions.PermissionAttachment.class);
        when(attachment.getPermissible()).thenReturn(player);
        when(attachment.remove()).thenThrow(new IllegalStateException("detach failed")).thenReturn(true);
        PlayerPermissionHandler state = new PlayerPermissionHandler(id, attachment, owner).addPerm("retained");
        owner.getPerms().put(id, state);
        try {
            assertThrows(IllegalStateException.class, () -> owner.logout(player));
            assertSame(state, owner.getPerms().get(id));
            assertSame(attachment, state.getAttachment());
            assertFalse(owner.getPermsToAdd().containsKey(id));
            owner.logout(player);
            assertFalse(owner.getPerms().containsKey(id));
            assertSame(state, owner.getPermsToAdd().get(id));
            assertNull(state.getAttachment());
        } finally { owner.shutDown(); }
    }
    @Test void staleQuitCannotDetachReplacementPlayersHandler() {
        com.bencodez.advancedcore.AdvancedCorePlugin plugin = mock(com.bencodez.advancedcore.AdvancedCorePlugin.class, RETURNS_DEEP_STUBS);
        when(plugin.getServerDataFile().getData()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
        PermissionHandler owner = new PermissionHandler(plugin);
        UUID id = UUID.randomUUID();
        org.bukkit.entity.Player old = mock(org.bukkit.entity.Player.class), current = mock(org.bukkit.entity.Player.class);
        when(old.getUniqueId()).thenReturn(id);
        org.bukkit.permissions.PermissionAttachment attachment = mock(org.bukkit.permissions.PermissionAttachment.class);
        when(attachment.getPermissible()).thenReturn(current);
        PlayerPermissionHandler state = new PlayerPermissionHandler(id, attachment, owner).addPerm("retained");
        owner.getPerms().put(id, state);
        clearInvocations(attachment);
        try {
            owner.logout(old);
            assertSame(state, owner.getPerms().get(id));
            assertSame(attachment, state.getAttachment());
            assertFalse(owner.getPermsToAdd().containsKey(id));
            verify(attachment, never()).remove();
        } finally { owner.shutDown(); }
    }
    @Test void retainedHandlerCannotAdmitGrantsAfterManagerShutdown() {
        com.bencodez.advancedcore.AdvancedCorePlugin plugin = mock(com.bencodez.advancedcore.AdvancedCorePlugin.class, RETURNS_DEEP_STUBS);
        when(plugin.getServerDataFile().getData()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
        PermissionHandler owner = new PermissionHandler(plugin);
        org.bukkit.permissions.PermissionAttachment attachment = mock(org.bukkit.permissions.PermissionAttachment.class);
        PlayerPermissionHandler state = new PlayerPermissionHandler(UUID.randomUUID(), attachment, owner);
        state.addExpiration("original", 60);
        owner.getPerms().put(state.getUuid(), state);
        owner.shutDown();
        long deadline = state.getTimedPermissions().get("original");
        clearInvocations(attachment);
        assertThrows(IllegalStateException.class, () -> state.addExpiration("original", 120));
        assertThrows(IllegalStateException.class, () -> state.addPerm("late.permanent"));
        assertThrows(IllegalStateException.class, () -> state.addOfflinePerm("late.offline", 60));
        assertThrows(IllegalStateException.class, () -> state.onLogin(mock(org.bukkit.entity.Player.class)));
        assertThrows(IllegalStateException.class, () -> state.setAttachment(mock(org.bukkit.permissions.PermissionAttachment.class)));
        assertEquals(deadline, state.getTimedPermissions().get("original"));
        assertEquals(Collections.singletonMap("original", deadline), state.getTimedPermissions());
        assertSame(attachment, state.getAttachment());
        verifyNoInteractions(attachment);
        state.removePermission("original");
        assertTrue(state.getTimedPermissions().isEmpty());
        verify(attachment).setPermission("original", false);
    }
    @Test void quitListenerPreservesTimedAndPermanentStateInOfflineHandler() {
        com.bencodez.advancedcore.AdvancedCorePlugin plugin=mock(com.bencodez.advancedcore.AdvancedCorePlugin.class,RETURNS_DEEP_STUBS);when(plugin.getServerDataFile().getData()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());when(plugin.isEnabled()).thenReturn(true);PermissionHandler owner=new PermissionHandler(plugin);when(plugin.getPermissionHandler()).thenReturn(owner);
        UUID id=UUID.randomUUID();org.bukkit.entity.Player live=mock(org.bukkit.entity.Player.class);when(live.getUniqueId()).thenReturn(id);when(live.getName()).thenReturn("player");org.bukkit.permissions.PermissionAttachment attachment=mock(org.bukkit.permissions.PermissionAttachment.class);when(attachment.getPermissible()).thenReturn(live);PlayerPermissionHandler state=new PlayerPermissionHandler(id,attachment,owner).addPerm("permanent").addExpiration("timed",60);long deadline=state.getTimedPermissions().get("timed");owner.getPerms().put(id,state);
        try(MockedStatic<Bukkit> server=mockStatic(Bukkit.class)) {
            new com.bencodez.advancedcore.listeners.PlayerJoinEvent(plugin).onPlayerQuit(new org.bukkit.event.player.PlayerQuitEvent(live,"quit"));assertSame(state,owner.getPermsToAdd().get(id));assertFalse(owner.getPerms().containsKey(id));assertNull(state.getAttachment());assertEquals(deadline,state.getTimedPermissions().get("timed"));owner.addPermission(id,"fresh",60);assertFalse(state.getTimedPermissions().containsKey("fresh"),"Fresh offline duration must wait for login");
        } finally {owner.shutDown();}
    }
    @Test void malformedRestorationRetiresActualOwnedExecutorWithoutClearingEvidence() {
        java.util.concurrent.ScheduledThreadPoolExecutor timer=new java.util.concurrent.ScheduledThreadPoolExecutor(1);com.bencodez.advancedcore.AdvancedCorePlugin plugin=mock(com.bencodez.advancedcore.AdvancedCorePlugin.class,RETURNS_DEEP_STUBS);org.bukkit.configuration.file.YamlConfiguration config=new org.bukkit.configuration.file.YamlConfiguration();UUID id=UUID.randomUUID();config.set("TimedPermissions."+id,Arrays.asList("valid%line%"+(System.currentTimeMillis()+60000),"bad%line%invalid"));when(plugin.getServerDataFile().getData()).thenReturn(config);
        try(MockedStatic<java.util.concurrent.Executors> executors=mockStatic(java.util.concurrent.Executors.class)) {
            executors.when(()->java.util.concurrent.Executors.newScheduledThreadPool(1)).thenReturn(timer);assertThrows(NumberFormatException.class,()->new PermissionHandler(plugin));assertTrue(timer.isTerminated());assertEquals(2,config.getStringList("TimedPermissions."+id).size());
        } finally {timer.shutdownNow();}
    }
    @Test void failedOnlineGrantRetainsPendingHandlerAndReusesItsPreparedAttachment() throws Exception {
        com.bencodez.advancedcore.AdvancedCorePlugin plugin=mock(com.bencodez.advancedcore.AdvancedCorePlugin.class,RETURNS_DEEP_STUBS);when(plugin.getServerDataFile().getData()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());PermissionHandler owner=spy(new PermissionHandler(plugin));java.util.concurrent.ScheduledExecutorService timer=mock(java.util.concurrent.ScheduledExecutorService.class);doReturn(timer).when(owner).getTimer();when(timer.awaitTermination(anyLong(),any())).thenReturn(true);when(timer.schedule(any(Runnable.class),anyLong(),any(java.util.concurrent.TimeUnit.class))).thenThrow(new java.util.concurrent.RejectedExecutionException("first rejected")).thenReturn(mock(java.util.concurrent.ScheduledFuture.class));
        UUID id=UUID.randomUUID();org.bukkit.entity.Player live=mock(org.bukkit.entity.Player.class);org.bukkit.permissions.PermissionAttachment attachment=mock(org.bukkit.permissions.PermissionAttachment.class);when(attachment.getPermissible()).thenReturn(live);when(live.addAttachment(plugin)).thenReturn(attachment);
        try(MockedStatic<Bukkit> server=mockStatic(Bukkit.class)) {
            server.when(()->Bukkit.getPlayer(id)).thenReturn(live);assertThrows(java.util.concurrent.RejectedExecutionException.class,()->owner.addPermission(id,"grant",60));PlayerPermissionHandler retained=owner.getPermsToAdd().get(id);assertNotNull(retained);assertFalse(owner.getPerms().containsKey(id));verify(attachment,never()).setPermission(anyString(),anyBoolean());owner.addPermission(id,"grant",60);assertSame(retained,owner.getPerms().get(id));verify(live,times(1)).addAttachment(plugin);verify(attachment).setPermission("grant",true);
        } finally {owner.shutDown();}
    }
    @Test void rejectedRenewalDoesNotInvalidateExistingDeadlineOrApplyPermission() {
        PermissionHandler owner=mock(PermissionHandler.class);org.bukkit.permissions.PermissionAttachment attachment=mock(org.bukkit.permissions.PermissionAttachment.class);PlayerPermissionHandler player=new PlayerPermissionHandler(UUID.randomUUID(),attachment,owner);long old=System.currentTimeMillis()+1000;player.getTimedPermissions().put("grant",old);
        doThrow(new java.util.concurrent.RejectedExecutionException("expiry rejected")).when(owner).scheduleExpiration(any(),anyString(),anyLong(),anyLong());assertThrows(java.util.concurrent.RejectedExecutionException.class,()->player.addExpiration("grant",60));assertEquals(old,player.getTimedPermissions().get("grant"));verifyNoInteractions(attachment);
    }
    @Test void failedOfflineHandoffDoesNotRegrantCompletedPrefixOnRetry() {
        PermissionHandler owner=mock(PermissionHandler.class);List<String> scheduled=new ArrayList<>();doAnswer(c->{scheduled.add(c.getArgument(1));if(scheduled.size()==2)throw new java.util.concurrent.RejectedExecutionException("second failed");return null;}).when(owner).scheduleExpiration(any(),anyString(),anyLong(),anyLong());
        org.bukkit.permissions.PermissionAttachment attachment=mock(org.bukkit.permissions.PermissionAttachment.class);PlayerPermissionHandler player=new PlayerPermissionHandler(UUID.randomUUID(),attachment,owner);player.addOfflinePerm("first",60);player.addOfflinePerm("second",120);org.bukkit.entity.Player live=mock(org.bukkit.entity.Player.class);
        assertThrows(java.util.concurrent.RejectedExecutionException.class,()->player.onLogin(live));String completed=scheduled.get(0);player.onLogin(live);assertEquals(1,Collections.frequency(scheduled,completed));assertEquals(3,scheduled.size());
    }
    @Test void failedReplacementAttachmentCreationKeepsPreviousAttachmentForRetry() {
        com.bencodez.advancedcore.AdvancedCorePlugin plugin=mock(com.bencodez.advancedcore.AdvancedCorePlugin.class,RETURNS_DEEP_STUBS);when(plugin.getServerDataFile().getData()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());PermissionHandler owner=new PermissionHandler(plugin);UUID id=UUID.randomUUID();org.bukkit.entity.Player oldPlayer=mock(org.bukkit.entity.Player.class),live=mock(org.bukkit.entity.Player.class);when(live.getUniqueId()).thenReturn(id);
        org.bukkit.permissions.PermissionAttachment old=mock(org.bukkit.permissions.PermissionAttachment.class),replacement=mock(org.bukkit.permissions.PermissionAttachment.class);when(old.getPermissible()).thenReturn(oldPlayer);when(replacement.getPermissible()).thenReturn(live);when(old.remove()).thenReturn(true,false);when(live.addAttachment(plugin)).thenThrow(new IllegalStateException("create failed")).thenReturn(replacement);
        PlayerPermissionHandler state=new PlayerPermissionHandler(id,old,owner).addPerm("grant");owner.getPerms().put(id,state);
        try {assertThrows(IllegalStateException.class,()->owner.login(live));verify(old,never()).remove();owner.login(live);assertSame(replacement,state.getAttachment());verify(old,times(1)).remove();verify(replacement).setPermission("grant",true);}
        finally {owner.shutDown();}
    }
    @Test void repeatedLoginHookForSamePlayerDoesNotCreateDuplicateAttachments() {
        com.bencodez.advancedcore.AdvancedCorePlugin plugin=mock(com.bencodez.advancedcore.AdvancedCorePlugin.class,RETURNS_DEEP_STUBS);when(plugin.getServerDataFile().getData()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());PermissionHandler owner=new PermissionHandler(plugin);UUID id=UUID.randomUUID();org.bukkit.entity.Player live=mock(org.bukkit.entity.Player.class);when(live.getUniqueId()).thenReturn(id);org.bukkit.permissions.PermissionAttachment attachment=mock(org.bukkit.permissions.PermissionAttachment.class);when(attachment.getPermissible()).thenReturn(live);when(live.addAttachment(plugin)).thenReturn(attachment);owner.getPermsToAdd().put(id,new PlayerPermissionHandler(id,null,owner).addOfflinePerm("grant",-1));
        try {owner.login(live);owner.login(live);verify(live,times(1)).addAttachment(plugin);assertSame(attachment,owner.getPerms().get(id).getAttachment());}
        finally {owner.shutDown();}
    }
    @Test void failedShutdownPublicationRemainsRetryable() {
        com.bencodez.advancedcore.AdvancedCorePlugin plugin=mock(com.bencodez.advancedcore.AdvancedCorePlugin.class,RETURNS_DEEP_STUBS);when(plugin.getServerDataFile().getData()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
        com.bencodez.advancedcore.data.ServerData store=plugin.getServerDataFile();doThrow(new IllegalStateException("publication failed")).doNothing().when(store).saveData();PermissionHandler owner=new PermissionHandler(plugin);
        try {assertThrows(IllegalStateException.class,owner::shutDown);owner.shutDown();verify(plugin.getServerDataFile(),times(2)).saveData();assertTrue(owner.getTimer().isTerminated());}
        finally {owner.getTimer().shutdownNow();}
    }
    @Test void onlineGrantBeforeLoginPreservesPendingHandlerAndItsPermissions() {
        for(boolean timed:Arrays.asList(false,true)) {
            com.bencodez.advancedcore.AdvancedCorePlugin plugin=mock(com.bencodez.advancedcore.AdvancedCorePlugin.class,RETURNS_DEEP_STUBS);when(plugin.getServerDataFile().getData()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());PermissionHandler owner=new PermissionHandler(plugin);
            UUID id=UUID.randomUUID();PlayerPermissionHandler pending=new PlayerPermissionHandler(id,null,owner).addOfflinePerm("first",-1);owner.getPermsToAdd().put(id,pending);org.bukkit.entity.Player live=mock(org.bukkit.entity.Player.class);org.bukkit.permissions.PermissionAttachment attachment=mock(org.bukkit.permissions.PermissionAttachment.class);when(live.addAttachment(plugin)).thenReturn(attachment);
            try(MockedStatic<Bukkit> server=mockStatic(Bukkit.class)) {
                server.when(()->Bukkit.getPlayer(id)).thenReturn(live);if(timed)owner.addPermission(id,"second",60);else owner.addPermission(id,"second");assertSame(pending,owner.getPerms().get(id));assertFalse(owner.getPermsToAdd().containsKey(id));verify(attachment).setPermission("first",true);verify(attachment).setPermission("second",true);
            } finally {owner.shutDown();}
        }
    }
    @Test void multiPermissionRemovalRetainsHandlerAcrossRegistryRemovalAndSupportsPendingState() {
        for(boolean offline:Arrays.asList(false,true)) {
            PermissionHandler owner=mock(PermissionHandler.class,CALLS_REAL_METHODS);UUID id=UUID.randomUUID();PlayerPermissionHandler player=mock(PlayerPermissionHandler.class);
            ConcurrentHashMap<UUID,PlayerPermissionHandler> active=new ConcurrentHashMap<>();HashMap<UUID,PlayerPermissionHandler> pending=new HashMap<>();when(owner.getPerms()).thenReturn(active);when(owner.getPermsToAdd()).thenReturn(pending);if(offline)pending.put(id,player);else active.put(id,player);
            doAnswer(c->{active.remove(id);pending.remove(id);return null;}).when(player).removePermission("first");owner.removePermission(id,"player","first|second");verify(player).removePermission("first");verify(player).removePermission("second");
        }
    }
    @Test void restoredAbsoluteDeadlineIsNotExtendedByLogin() {
        PermissionHandler owner=mock(PermissionHandler.class);org.bukkit.permissions.PermissionAttachment attachment=mock(org.bukkit.permissions.PermissionAttachment.class);
        PlayerPermissionHandler player=new PlayerPermissionHandler(UUID.randomUUID(),null,owner);long deadline=System.currentTimeMillis()+60000L;
        player.restoreExpiration("restored",deadline);player.setAttachment(attachment);player.onLogin(mock(org.bukkit.entity.Player.class));assertEquals(deadline,player.getTimedPermissions().get("restored"));verify(attachment).setPermission("restored",true);
    }
    @Test void staleExpirationCannotRemoveRenewedOrPermanentGrant() {
        for(boolean permanent:Arrays.asList(false,true)) {
            PermissionHandler owner=mock(PermissionHandler.class);org.bukkit.permissions.PermissionAttachment attachment=mock(org.bukkit.permissions.PermissionAttachment.class);
            PlayerPermissionHandler player=new PlayerPermissionHandler(UUID.randomUUID(),attachment,owner);player.addExpiration("grant",1L);long previous=player.getTimedPermissions().get("grant");
            if(permanent)player.addPerm("grant");else player.addExpiration("grant",60L);
            player.expirePermission("grant",previous,true);verify(attachment,never()).unsetPermission(anyString());verify(owner,never()).removePermissionIfEmpty(any(),any());
            if(!permanent)assertTrue(player.getTimedPermissions().get("grant")>previous);else assertFalse(player.getTimedPermissions().containsKey("grant"));
        }
    }
    @Test void expiredRestoredGrantIsNotAppliedOnLogin() {
        PermissionHandler owner=mock(PermissionHandler.class);org.bukkit.permissions.PermissionAttachment attachment=mock(org.bukkit.permissions.PermissionAttachment.class);
        PlayerPermissionHandler player=new PlayerPermissionHandler(UUID.randomUUID(),attachment,owner);player.restoreExpiration("expired",System.currentTimeMillis()-1);player.onLogin(mock(org.bukkit.entity.Player.class));verifyNoInteractions(attachment);assertTrue(player.getTimedPermissions().isEmpty());
    }
    @Test void timerOnlyRequestsOwnerHandoffAndShutdownFencesQueuedExpiry() throws Exception {
        for(boolean closeBeforeOwner:Arrays.asList(false,true)) {
            com.bencodez.advancedcore.AdvancedCorePlugin plugin=mock(com.bencodez.advancedcore.AdvancedCorePlugin.class,RETURNS_DEEP_STUBS);org.bukkit.configuration.file.YamlConfiguration config=new org.bukkit.configuration.file.YamlConfiguration();when(plugin.getServerDataFile().getData()).thenReturn(config);
            PermissionHandler owner=spy(new PermissionHandler(plugin));java.util.concurrent.ScheduledExecutorService timer=mock(java.util.concurrent.ScheduledExecutorService.class);doReturn(timer).when(owner).getTimer();when(timer.awaitTermination(anyLong(),any())).thenReturn(true);
            List<Runnable> scheduled=new ArrayList<>(),handoffs=new ArrayList<>();when(timer.schedule(any(Runnable.class),anyLong(),any(java.util.concurrent.TimeUnit.class))).thenAnswer(c->{scheduled.add(c.getArgument(0));return mock(java.util.concurrent.ScheduledFuture.class);});
            org.bukkit.scheduler.BukkitScheduler scheduler=mock(org.bukkit.scheduler.BukkitScheduler.class);when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(c->{handoffs.add(c.getArgument(1));return mock(org.bukkit.scheduler.BukkitTask.class);});
            org.bukkit.entity.Player live=mock(org.bukkit.entity.Player.class);UUID id=UUID.randomUUID();org.bukkit.permissions.PermissionAttachment attachment=mock(org.bukkit.permissions.PermissionAttachment.class);when(attachment.getPermissible()).thenReturn(live);
            PlayerPermissionHandler player=new PlayerPermissionHandler(id,attachment,owner);owner.getPerms().put(id,player);long expired=System.currentTimeMillis()-1;player.getTimedPermissions().put("grant",expired);
            try(MockedStatic<Bukkit> server=mockStatic(Bukkit.class)) {
                server.when(Bukkit::getScheduler).thenReturn(scheduler);server.when(()->Bukkit.getPlayer(id)).thenReturn(live);owner.scheduleExpiration(player,"grant",expired,0);scheduled.get(0).run();assertEquals(1,handoffs.size());verify(attachment,never()).unsetPermission(anyString());
                if(closeBeforeOwner)owner.shutDown();handoffs.get(0).run();if(closeBeforeOwner)verify(attachment,never()).unsetPermission(anyString());else verify(attachment).unsetPermission("grant");
            } finally {owner.shutDown();}
        }
    }
    @Test void shutdownHandlesPermanentAndPendingRestoredStateAndTerminatesTimer() {
        com.bencodez.advancedcore.AdvancedCorePlugin plugin=mock(com.bencodez.advancedcore.AdvancedCorePlugin.class,RETURNS_DEEP_STUBS);org.bukkit.configuration.file.YamlConfiguration config=new org.bukkit.configuration.file.YamlConfiguration();when(plugin.getServerDataFile().getData()).thenReturn(config);
        PermissionHandler owner=new PermissionHandler(plugin);UUID permanent=UUID.randomUUID(),pending=UUID.randomUUID();long deadline=System.currentTimeMillis()+60000;
        owner.getPerms().put(permanent,new PlayerPermissionHandler(permanent,null,owner).addPerm("permanent"));owner.getPermsToAdd().put(pending,new PlayerPermissionHandler(pending,null,owner).restoreExpiration("restored",deadline));owner.shutDown();assertTrue(owner.getTimer().isTerminated());assertEquals(Arrays.asList("restored%line%"+deadline),config.getStringList("TimedPermissions."+pending));assertFalse(config.contains("TimedPermissions."+permanent));
    }
    @Test void offlineSecondsAreNotDividedAgainAtLogin() {
        PermissionHandler owner=mock(PermissionHandler.class);com.bencodez.advancedcore.AdvancedCorePlugin plugin=mock(com.bencodez.advancedcore.AdvancedCorePlugin.class);when(owner.getPlugin()).thenReturn(plugin);
        java.util.concurrent.ScheduledExecutorService timer=mock(java.util.concurrent.ScheduledExecutorService.class);when(owner.getTimer()).thenReturn(timer);
        org.bukkit.permissions.PermissionAttachment attachment=mock(org.bukkit.permissions.PermissionAttachment.class);
        PlayerPermissionHandler player=new PlayerPermissionHandler(UUID.randomUUID(),attachment,owner);player.addOfflinePerm("permission.timed",60L);long before=System.currentTimeMillis();player.onLogin(mock(org.bukkit.entity.Player.class));
        assertTrue(player.getTimedPermissions().get("permission.timed")>=before+59000L,"60 seconds must remain approximately one minute");
    }
    @Test void permanentPermissionSurvivesAttachmentReplacementAtLogin() {
        PermissionHandler owner=mock(PermissionHandler.class);when(owner.getPlugin()).thenReturn(mock(com.bencodez.advancedcore.AdvancedCorePlugin.class));
        org.bukkit.permissions.PermissionAttachment first=mock(org.bukkit.permissions.PermissionAttachment.class),second=mock(org.bukkit.permissions.PermissionAttachment.class);
        PlayerPermissionHandler player=new PlayerPermissionHandler(UUID.randomUUID(),first,owner);player.addPerm("permission.permanent");player.setAttachment(second);player.onLogin(mock(org.bukkit.entity.Player.class));verify(second).setPermission("permission.permanent",true);
    }
    @Test void consecutiveOfflineGrantsPreserveExistingPendingPermissions() {
        for(boolean timed:Arrays.asList(false,true)) {
            PermissionHandler owner=mock(PermissionHandler.class,CALLS_REAL_METHODS);UUID id=UUID.randomUUID();
            HashMap<UUID,PlayerPermissionHandler> pending=new HashMap<>();when(owner.getPerms()).thenReturn(new ConcurrentHashMap<>());when(owner.getPermsToAdd()).thenReturn(pending);
            try(MockedStatic<Bukkit> server=mockStatic(Bukkit.class)) {
                owner.addPermission(id,"permission.first");PlayerPermissionHandler first=spy(pending.get(id));pending.put(id,first);
                if(timed)owner.addPermission(id,"permission.second",60L);else owner.addPermission(id,"permission.second");
                assertSame(first,pending.get(id));verify(first).addOfflinePerm("permission.second",timed?60L:-1L);
            }
        }
    }
    @Test void permanentGrantReusesExistingPlayerHandler() {
        PermissionHandler owner=mock(PermissionHandler.class,CALLS_REAL_METHODS);
        UUID id=UUID.randomUUID();PlayerPermissionHandler existing=mock(PlayerPermissionHandler.class);
        ConcurrentHashMap<UUID,PlayerPermissionHandler> active=new ConcurrentHashMap<>();active.put(id,existing);
        HashMap<UUID,PlayerPermissionHandler> pending=new HashMap<>();when(owner.getPerms()).thenReturn(active);when(owner.getPermsToAdd()).thenReturn(pending);
        try(MockedStatic<Bukkit> server=mockStatic(Bukkit.class)) {
            owner.addPermission(id,"permission.second");
            verify(existing).addPerm("permission.second");assertSame(existing,active.get(id));assertTrue(pending.isEmpty());server.verifyNoInteractions();
        }
    }
    @Test void timedGrantReusesExistingHandlerAndRetainsExpiration() {
        PermissionHandler owner=mock(PermissionHandler.class,CALLS_REAL_METHODS);
        UUID id=UUID.randomUUID();PlayerPermissionHandler existing=mock(PlayerPermissionHandler.class);
        ConcurrentHashMap<UUID,PlayerPermissionHandler> active=new ConcurrentHashMap<>();active.put(id,existing);
        HashMap<UUID,PlayerPermissionHandler> pending=new HashMap<>();when(owner.getPerms()).thenReturn(active);when(owner.getPermsToAdd()).thenReturn(pending);
        try(MockedStatic<Bukkit> server=mockStatic(Bukkit.class)) {
            owner.addPermission(id,"permission.timed",60L);
            verify(existing).addExpiration("permission.timed",60L);verify(existing,never()).addPerm(anyString());assertSame(existing,active.get(id));assertTrue(pending.isEmpty());server.verifyNoInteractions();
        }
    }
}
