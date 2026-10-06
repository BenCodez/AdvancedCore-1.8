package com.bencodez.advancedcore.api.rewards;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.bencodez.advancedcore.AdvancedCoreConfigOptions;
import com.bencodez.advancedcore.api.user.*;
import com.bencodez.advancedcore.api.user.usercache.*;
import com.bencodez.advancedcore.api.user.usercache.change.UserDataChangeString;
import com.bencodez.simpleapi.sql.data.*;

class LegacyCacheCleanupThreadingTest {
    @Test void mutationWhileCleanupWaitsForOwnershipPreservesNewerSnapshot() throws Exception {
        fixture(f -> {
            UserDataCache cache=f.cached();f.manager.clearNonNeededCachedUsers();f.dispatch.runNext();
            java.util.concurrent.locks.ReentrantLock lock=f.dispatch.plugin.getUserStorageOwnership().owner(f.id).getLock();
            Runnable cleanup=f.storage.remove(0);
            java.util.concurrent.atomic.AtomicReference<Throwable> failed=new java.util.concurrent.atomic.AtomicReference<>();
            Thread worker=new Thread(()->{try {cleanup.run();}catch(Throwable failure){failed.set(failure);}},"cleanup-version-waiter");
            lock.lock();
            try {
                worker.start();long bound=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(!lock.hasQueuedThread(worker) && worker.isAlive() && System.nanoTime()<bound)Thread.yield();
                assertTrue(lock.hasQueuedThread(worker),"Cleanup reached canonical ownership wait");
                cache.addChange(new UserDataChangeString("Points","newer"),true);
            }finally {
                lock.unlock();
                try {worker.join(2000);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}
            }
            assertFalse(worker.isAlive());assertNull(failed.get());
            assertSame(cache,f.manager.getUserDataCache().get(f.id),"Earlier cleanup cannot retire an in-place newer snapshot");
            assertEquals("newer",cache.getCachedValue("Points").getString());assertTrue(cache.hasChangesToProcess());verifyNoInteractions(f.data);
            f.manager.clearNonNeededCachedUsers();f.dispatch.runNext();f.runStorage();assertFalse(f.manager.getUserDataCache().containsKey(f.id));
            try {verify(f.data).setValuesStrict(any());}catch(Exception failure){throw new AssertionError(failure);}
        });
    }
    @Test void cleanupCapturesBukkitOnOwnerAndFlushesOnStorageWorker() throws Exception {
        fixture(f -> {
            UserDataCache cache=f.cached();cache.addChange(new UserDataChangeString("Points","pending"),true);
            f.manager.clearNonNeededCachedUsers();
            assertSame(cache,f.manager.getUserDataCache().get(f.id));
            assertTrue(f.storage.isEmpty(),"Storage is not admitted before owner capture");
            f.dispatch.runNext();assertSame(cache,f.manager.getUserDataCache().get(f.id));
            assertEquals(1,f.storage.size());f.runStorage();
            assertFalse(f.manager.getUserDataCache().containsKey(f.id));
            try {verify(f.data).setValuesStrict(any());}catch(Exception failure){throw new AssertionError(failure);}
            assertNull(cache.getUuid());
        });
    }
    @Test void capturedOnlinePlayerRemainsCachedWithoutStorageWork() throws Exception {
        fixture(f -> {
            UserDataCache cache=f.cached();f.online.add(f.player());
            f.manager.clearNonNeededCachedUsers();f.dispatch.runNext();f.runStorage();
            assertSame(cache,f.manager.getUserDataCache().get(f.id));verifyNoInteractions(f.data);
        });
    }
    @Test void nativeJoinAfterSnapshotProtectsCacheEvenWhenUserLoadingDisabled() throws Exception {
        fixture(f -> {
            UserDataCache cache=f.cached();f.manager.clearNonNeededCachedUsers();f.dispatch.runNext();
            assertFalse(f.dispatch.plugin.isLoadUserData());
            f.dispatch.primary.set(true);
            try {new com.bencodez.advancedcore.listeners.PlayerJoinEvent(f.dispatch.plugin).onPlayerLogin(new org.bukkit.event.player.PlayerJoinEvent(f.player(),"join"));}
            finally {f.dispatch.primary.set(false);}
            f.runStorage();assertSame(cache,f.manager.getUserDataCache().get(f.id));verifyNoInteractions(f.data);
        });
    }
    @Test void queuedSnapshotsCannotEraseNewerQuitOrResurrectIt() throws Exception {
        fixture(f -> {
            f.cached();f.manager.clearNonNeededCachedUsers();f.dispatch.runNext();
            f.online.add(f.player());f.manager.clearNonNeededCachedUsers();f.dispatch.runNext();
            f.manager.markUserOffline(f.id);
            f.runStorage();assertFalse(f.manager.getUserDataCache().containsKey(f.id));
            f.cached();f.runStorage();assertFalse(f.manager.getUserDataCache().containsKey(f.id),"Older online snapshot cannot resurrect newer quit");
        });
    }
    @Test void failedRetirementKeepsMappedPendingStateAndLaterCleanupRetries() throws Exception {
        fixture(f -> {
            UserDataCache cache=f.cached();cache.addChange(new UserDataChangeString("Points","pending"),true);
            try {doThrow(new java.sql.SQLException("fixture unavailable")).doNothing().when(f.data).setValuesStrict(any());}
            catch(Exception failure){throw new AssertionError(failure);}
            f.manager.clearNonNeededCachedUsers();f.dispatch.runNext();assertThrows(IllegalStateException.class,f::runStorage);
            assertSame(cache,f.manager.getUserDataCache().get(f.id));assertTrue(cache.hasChangesToProcess());assertNotNull(f.manager.getLastDeferredStorageFailure());
            f.manager.clearNonNeededCachedUsers();f.dispatch.runNext();f.runStorage();assertFalse(f.manager.getUserDataCache().containsKey(f.id));
        });
    }
    @Test void joinDuringPhysicalFlushStillInvalidatesFlushedGeneration() throws Exception {
        fixture(f -> {
            UserDataCache cache=f.cached();cache.addChange(new UserDataChangeString("Points","pending"),true);
            try {doAnswer(c->{assertFalse(f.dispatch.primary.get());f.manager.markUserOnline(f.id);return null;}).when(f.data).setValuesStrict(any());}
            catch(Exception failure){throw new AssertionError(failure);}
            f.manager.clearNonNeededCachedUsers();f.dispatch.runNext();f.runStorage();
            assertNull(cache.getUuid());assertFalse(f.manager.getUserDataCache().containsKey(f.id),"A join cannot retain a generation already cleared by accepted storage work");
            verify(f.dispatch.plugin,never()).devDebug(startsWith("Removed "));
            UserDataCache replacement=f.cached();f.manager.clearNonNeededCachedUsers();f.dispatch.runNext();f.runStorage();assertSame(replacement,f.manager.getUserDataCache().get(f.id));
        });
    }
    @Test void quitCapturesPlayerIdentityBeforeWorkerCleanup() throws Exception {
        fixture(f -> {
            UserDataCache cache=f.cached();cache.addChange(new UserDataChangeString("Points","pending"),true);
            when(f.dispatch.plugin.getLoginTimer()).thenReturn(f.worker);
            try(MockedStatic<com.bencodez.simpleapi.command.TabCompleteHandler> tabs=mockStatic(com.bencodez.simpleapi.command.TabCompleteHandler.class)) {
                tabs.when(com.bencodez.simpleapi.command.TabCompleteHandler::getInstance).thenReturn(mock(com.bencodez.simpleapi.command.TabCompleteHandler.class));
                f.dispatch.primary.set(true);
                try {new com.bencodez.advancedcore.listeners.PlayerJoinEvent(f.dispatch.plugin).onPlayerQuit(new org.bukkit.event.player.PlayerQuitEvent(f.player(),"quit"));}
                finally {f.dispatch.primary.set(false);}
                assertSame(cache,f.manager.getUserDataCache().get(f.id));f.runStorage();assertFalse(f.manager.getUserDataCache().containsKey(f.id));
            }
        });
    }
    @Test void rejectedStorageAdmissionIsVisibleAndDoesNotStopNextCleanup() throws Exception {
        fixture(f -> {
            doThrow(new RejectedExecutionException("fixture rejected")).doAnswer(c->{f.storage.add(c.getArgument(0));return null;}).when(f.worker).execute(any(Runnable.class));
            UserDataCache cache=f.cached();f.manager.clearNonNeededCachedUsers();f.dispatch.runNext();
            assertNotNull(f.manager.getLastDeferredStorageFailure());assertSame(cache,f.manager.getUserDataCache().get(f.id));assertTrue(f.storage.isEmpty());
            f.manager.clearNonNeededCachedUsers();f.dispatch.runNext();f.runStorage();assertFalse(f.manager.getUserDataCache().containsKey(f.id));
        });
    }
    @Test void retiredCapturedOwnerCannotPublishStorageCleanup() throws Exception {
        fixture(f -> {
            UserDataCache cache=f.cached();f.manager.clearNonNeededCachedUsers();f.dispatch.owner.close();f.dispatch.runNext();
            assertTrue(f.storage.isEmpty());assertSame(cache,f.manager.getUserDataCache().get(f.id));verifyNoInteractions(f.data);
        });
    }
    @Test void headlessCaptureDoesNotEnumerateBukkitPlayers() throws Exception {
        fixture(f -> {
            f.bukkit.when(Bukkit::getServer).thenReturn(null);
            f.bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(c->{throw new AssertionError("No Bukkit server available");});
            f.manager.clearNonNeededCachedUsers();f.dispatch.runNext();f.runStorage();
        });
    }
    private void fixture(Consumer<Fixture> action) throws Exception {
        Fixture f=new Fixture();
        try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)) {
            f.bukkit=bukkit;
            bukkit.when(Bukkit::getServer).thenReturn(mock(org.bukkit.Server.class));
            bukkit.when(Bukkit::getScheduler).thenReturn(f.dispatch.scheduler);
            bukkit.when(Bukkit::isPrimaryThread).thenAnswer(c->f.dispatch.primary.get());
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(c->{assertTrue(f.dispatch.primary.get(),"Bukkit snapshot requires owner thread");return new ArrayList<>(f.online);});
            action.accept(f);
        }finally {f.dispatch.owner.close();}
    }
    private static class Fixture {
        MockedStatic<Bukkit> bukkit;
        final UUID id=UUID.randomUUID();
        final LegacyRewardDispatchTest.Fixture dispatch=new LegacyRewardDispatchTest.Fixture();
        final UserManager users=mock(UserManager.class);final AdvancedCoreUser user=mock(AdvancedCoreUser.class);final UserData data=mock(UserData.class);
        final ScheduledExecutorService worker=mock(ScheduledExecutorService.class);
        final List<Runnable> storage=new ArrayList<>();final List<Player> online=new ArrayList<>();final UserDataManager manager;
        Fixture() throws Exception {
            when(dispatch.plugin.getRewardDispatch()).thenReturn(dispatch.owner);
            when(dispatch.plugin.getUserStorageOwnership()).thenReturn(new UserStorageOwnership());
            AdvancedCoreConfigOptions options=mock(AdvancedCoreConfigOptions.class);when(options.isOnlineMode()).thenReturn(true);when(dispatch.plugin.getOptions()).thenReturn(options);
            when(dispatch.plugin.getUserManager()).thenReturn(users);when(users.getUser(any(UUID.class),eq(false))).thenReturn(user);when(user.getUserData()).thenReturn(data);
            when(dispatch.plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            manager=new UserDataManager(dispatch.plugin);ScheduledExecutorService old=manager.getTimer();old.shutdownNow();assertTrue(old.awaitTermination(2,TimeUnit.SECONDS));
            Field timer=UserDataManager.class.getDeclaredField("timer");timer.setAccessible(true);timer.set(manager,worker);
            when(users.getDataManager()).thenReturn(manager);
            doAnswer(c->{storage.add(c.getArgument(0));return null;}).when(worker).execute(any(Runnable.class));
            doAnswer(c->{assertFalse(dispatch.primary.get(),"Physical storage must stay off owner");return null;}).when(data).setValuesStrict(any());
        }
        UserDataCache cached(){UserDataCache cache=new UserDataCache(manager,id);HashMap<String,DataValue> values=new HashMap<>();values.put("Points",new DataValueString("stored"));cache.updateCache(values);manager.getUserDataCache().put(id,cache);return cache;}
        Player player(){Player player=mock(Player.class);when(player.getUniqueId()).thenAnswer(c->{assertTrue(dispatch.primary.get());return id;});when(player.getName()).thenAnswer(c->{assertTrue(dispatch.primary.get());return "player";});return player;}
        void runStorage(){assertFalse(dispatch.primary.get());storage.remove(0).run();}
    }
}
