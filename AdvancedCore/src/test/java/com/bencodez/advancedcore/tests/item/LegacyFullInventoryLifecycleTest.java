package com.bencodez.advancedcore.tests.item;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.nio.file.Path;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import com.bencodez.advancedcore.AdvancedCoreConfigOptions;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.item.FullInventoryHandler;
import com.bencodez.advancedcore.data.ServerData;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;

class LegacyFullInventoryLifecycleTest {
    @Test void deliveryDefersNativeAccessAndKeepsAnOverflowAddedDuringTheCheck() {
        try (Fixture f = new Fixture()) {
            ItemStack original=snapshotItem(), newer=snapshotItem(), excess=snapshotItem();
            f.handler.add(f.id, original);
            when(f.inventory.addItem(original)).thenAnswer(call -> {
                f.handler.add(f.id, newer); HashMap<Integer,ItemStack> remaining=new HashMap<>();remaining.put(0,excess);return remaining;
            });
            f.handler.check(f.player);
            verify(f.player,never()).getInventory();
            f.owner.remove(0).run();
            assertEquals(Arrays.asList(newer,excess),f.handler.getItems().get(f.id));
        }
    }
    @Test void shutdownFencesAnAlreadyQueuedOrdinaryCheckBeforeItsSnapshot() {
        try (Fixture f = new Fixture()) {
            ItemStack item=snapshotItem();f.handler.add(f.id,item);f.handler.check(f.player);
            f.handler.shutdown();f.owner.remove(0).run();
            verify(f.inventory,never()).addItem(any(ItemStack[].class));
            assertEquals(item,f.data.getItemStack("FullInventory."+f.id+".Items.0"));
            assertEquals(Arrays.asList(item),f.handler.getItems().get(f.id));
            f.handler.check(f.player);assertTrue(f.owner.isEmpty());
        }
    }
    @Test void timerAdmissionIsIdempotentAndExternalShutdownCanRecoverItsPrivateTimer() {
        try(Fixture f=new Fixture()) {
            ScheduledExecutorService first=f.handler.getTimer();f.handler.loadTimer();assertSame(first,f.handler.getTimer());
            first.shutdownNow();f.handler.loadTimer();assertNotSame(first,f.handler.getTimer());assertFalse(f.handler.getTimer().isShutdown());
        }
    }
    @Test void saveWaitsForTheRemoveAndOverflowRequeuePhase() throws Exception {
        try(Fixture f=new Fixture()) {
            ItemStack item=snapshotItem();f.handler.add(f.id,item);f.handler.check(f.player);
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            when(f.inventory.addItem(item)).thenAnswer(call->{entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));HashMap<Integer,ItemStack> rest=new HashMap<>();rest.put(0,item);return rest;});
            ExecutorService workers=Executors.newFixedThreadPool(2);
            try {
                Future<?> check=workers.submit(f.owner.remove(0));assertTrue(entered.await(2,TimeUnit.SECONDS));
                java.util.concurrent.atomic.AtomicReference<Thread> savingThread=new java.util.concurrent.atomic.AtomicReference<>();
                Future<?> save=workers.submit(()->{savingThread.set(Thread.currentThread());f.handler.save();});
                java.lang.reflect.Field field=FullInventoryHandler.class.getDeclaredField("deliveryLock");field.setAccessible(true);ReentrantReadWriteLock lock=(ReentrantReadWriteLock)field.get(f.handler);
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while((savingThread.get()==null || !lock.hasQueuedThread(savingThread.get()))&&System.nanoTime()<end)Thread.yield();
                assertNotNull(savingThread.get());assertTrue(lock.hasQueuedThread(savingThread.get()));assertFalse(save.isDone());
                release.countDown();check.get(2,TimeUnit.SECONDS);save.get(2,TimeUnit.SECONDS);
                assertEquals(item,f.data.getItemStack("FullInventory."+f.id+".Items.0"));
            } finally {release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(2,TimeUnit.SECONDS));}
        }
    }
    @Test void failedSaveRestoresThePreviousSectionAndRetainsAcceptedItemsForExplicitRetry() {
        try(Fixture f=new Fixture()) {
            f.data.set("FullInventory.previous.Time",123L);f.data.set("Unrelated",456);
            ItemStack item=snapshotItem();f.handler.add(f.id,item);
            doThrow(new IllegalStateException("Fixture disk failure")).when(f.serverData).saveData();
            assertThrows(IllegalStateException.class,f.handler::save);
            assertEquals(123,f.data.getLong("FullInventory.previous.Time"));assertEquals(456,f.data.getInt("Unrelated"));assertEquals(Arrays.asList(item),f.handler.getItems().get(f.id));
            doNothing().when(f.serverData).saveData();f.handler.save();assertEquals(item,f.data.getItemStack("FullInventory."+f.id+".Items.0"));
        }
    }
    @Test void missingAndMalformedLegacyRecoveryEntriesDoNotDiscardValidRecentItems() {
        try(Fixture f=new Fixture()) {
            UUID recent=UUID.randomUUID(),expired=UUID.randomUUID(),empty=UUID.randomUUID();
            ItemStack item=new ItemStack(Material.DIAMOND);
            f.data.set("FullInventory.not-a-uuid.Time",System.currentTimeMillis());
            f.data.set("FullInventory."+recent+".Time",System.currentTimeMillis());f.data.set("FullInventory."+recent+".Items.0",item);
            f.data.set("FullInventory."+expired+".Time",System.currentTimeMillis()-TimeUnit.DAYS.toMillis(2));f.data.set("FullInventory."+expired+".Items.0",item);
            f.data.set("FullInventory."+empty+".Time",System.currentTimeMillis());
            f.handler.startup();assertEquals(Arrays.asList(item),f.handler.getItems().get(recent));assertFalse(f.handler.getItems().containsKey(expired));assertFalse(f.handler.getItems().containsKey(empty));assertFalse(f.data.contains("FullInventory"));
        }
    }
    @Test void giveItemCopiesTheInputArrayAndDeliversOnOwner() {
        try(Fixture f=new Fixture()) {
            ItemStack original=snapshotItem(),replacement=snapshotItem();ItemStack[] supplied={original};
            when(f.inventory.addItem(original)).thenReturn(new HashMap<>());
            f.handler.giveItem(f.player,supplied);supplied[0]=replacement;verify(f.player,never()).getInventory();
            f.owner.remove(0).run();verify(f.inventory).addItem(original);verify(f.inventory,never()).addItem(replacement);verify(f.player).updateInventory();
        }
    }
    @Test void checkedServerDataSaveRetainsTheVoidApiButReportsActualIoFailure(@TempDir Path temporary) throws Exception {
        ServerData data=mock(ServerData.class,CALLS_REAL_METHODS);YamlConfiguration config=new YamlConfiguration();config.set("Unrelated",123);
        when(data.getData()).thenReturn(config);when(data.getdFile()).thenReturn(temporary.toFile());
        IllegalStateException failure=assertThrows(IllegalStateException.class,data::saveData);assertInstanceOf(java.io.IOException.class,failure.getCause());
        Path file=temporary.resolve("ServerData.yml");when(data.getdFile()).thenReturn(file.toFile());data.saveData();
        YamlConfiguration reloaded=new YamlConfiguration();reloaded.load(file.toFile());assertEquals(123,reloaded.getInt("Unrelated"));
    }
    @Test void diskPublicationDoesNotHoldTheNativeDeliveryLock() throws Exception {
        try(Fixture f=new Fixture()) {
            ItemStack item=snapshotItem();f.handler.add(f.id,item);
            CountDownLatch writing=new CountDownLatch(1),releaseWrite=new CountDownLatch(1),delivered=new CountDownLatch(1);
            doAnswer(call->{writing.countDown();assertTrue(releaseWrite.await(3,TimeUnit.SECONDS));return null;}).when(f.serverData).saveData();
            when(f.inventory.addItem(item)).thenAnswer(call->{delivered.countDown();return new HashMap<Integer,ItemStack>();});
            ExecutorService workers=Executors.newFixedThreadPool(2);
            try {
                Future<?> save=workers.submit(f.handler::save);assertTrue(writing.await(2,TimeUnit.SECONDS));
                f.handler.check(f.player);Future<?> delivery=workers.submit(f.owner.remove(0));
                assertTrue(delivered.await(1,TimeUnit.SECONDS),"Disk IO must not stall native delivery");
                assertFalse(save.isDone(),"Fixture disk write must still be physically active");
                assertEquals(item,f.data.getItemStack("FullInventory."+f.id+".Items.0"),"Capture remains the complete point-in-time snapshot");
                releaseWrite.countDown();delivery.get(2,TimeUnit.SECONDS);save.get(2,TimeUnit.SECONDS);
                assertFalse(f.handler.getItems().containsKey(f.id));
            } finally {releaseWrite.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(2,TimeUnit.SECONDS));doNothing().when(f.serverData).saveData();}
        }
    }

    @Test void capturedNativeItemCannotChangeWhileDiskPublicationIsPending() throws Exception {
        try(Fixture f=new Fixture()) {
            ItemStack original=new ItemStack(Material.DIAMOND,3);f.handler.add(f.id,original);
            CountDownLatch writing=new CountDownLatch(1),release=new CountDownLatch(1);
            doAnswer(call->{writing.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));return null;}).when(f.serverData).saveData();
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                Future<?> save=worker.submit(f.handler::save);assertTrue(writing.await(2,TimeUnit.SECONDS));
                ItemStack captured=f.data.getItemStack("FullInventory."+f.id+".Items.0");
                assertNotSame(original,captured);original.setAmount(1);
                assertEquals(3,captured.getAmount());assertEquals(Material.DIAMOND,captured.getType());
                release.countDown();save.get(2,TimeUnit.SECONDS);
            }finally{release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));doNothing().when(f.serverData).saveData();}
        }
    }

    @Test void competingSavesCannotReplaceAnInFlightSnapshotBeforeItsWriteFinishes() throws Exception {
        try(Fixture f=new Fixture()) {
            f.handler.add(f.id,snapshotItem());
            CountDownLatch writing=new CountDownLatch(1),release=new CountDownLatch(1),secondAdmitted=new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger writes=new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<Thread> secondThread=new java.util.concurrent.atomic.AtomicReference<>();
            List<Integer> sizes=java.util.Collections.synchronizedList(new ArrayList<>());
            doAnswer(call->{sizes.add(f.data.getConfigurationSection("FullInventory."+f.id+".Items").getKeys(false).size());if(writes.incrementAndGet()==1){writing.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));}return null;}).when(f.serverData).saveData();
            ExecutorService workers=Executors.newFixedThreadPool(2);
            try {
                Future<?> first=workers.submit(f.handler::save);assertTrue(writing.await(2,TimeUnit.SECONDS));
                f.handler.add(f.id,snapshotItem());
                Future<?> second=workers.submit(()->{secondThread.set(Thread.currentThread());secondAdmitted.countDown();f.handler.save();});
                assertTrue(secondAdmitted.await(2,TimeUnit.SECONDS));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
                while(secondThread.get().getState()!=Thread.State.BLOCKED&&System.nanoTime()<end)Thread.yield();
                assertEquals(Thread.State.BLOCKED,secondThread.get().getState());assertFalse(second.isDone());assertEquals(Arrays.asList(1),sizes);
                release.countDown();first.get(2,TimeUnit.SECONDS);second.get(2,TimeUnit.SECONDS);assertEquals(Arrays.asList(1,2),sizes);
            }finally{release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(2,TimeUnit.SECONDS));doNothing().when(f.serverData).saveData();}
        }
    }

    private static ItemStack snapshotItem() {
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item); // Stable mock; native clone ownership has separate coverage.
        return item;
    }
    private static final class Fixture implements AutoCloseable {
        final UUID id=UUID.randomUUID();final AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);
        final BukkitScheduler scheduler=mock(BukkitScheduler.class);final ServerData serverData=mock(ServerData.class);
        final YamlConfiguration data=new YamlConfiguration();final Player player=mock(Player.class);final PlayerInventory inventory=mock(PlayerInventory.class);
        final List<Runnable> owner=new ArrayList<>();final FullInventoryHandler handler;
        Fixture(){when(plugin.getBukkitScheduler()).thenReturn(scheduler);when(plugin.getServerDataFile()).thenReturn(serverData);when(serverData.getData()).thenReturn(data);doAnswer(call->{data.set(call.getArgument(0),call.getArgument(1));return null;}).when(serverData).setData(anyString(),any());when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());when(plugin.getOptions()).thenReturn(mock(AdvancedCoreConfigOptions.class));when(player.getUniqueId()).thenReturn(id);when(player.getInventory()).thenReturn(inventory);doAnswer(call->{owner.add(call.getArgument(1));return null;}).when(scheduler).runTask(eq(plugin),any(Runnable.class),eq(player));handler=new FullInventoryHandler(plugin);}
        public void close(){handler.shutdown();try{assertTrue(handler.getTimer().awaitTermination(2,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    }
}
