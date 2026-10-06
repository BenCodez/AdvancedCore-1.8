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
            ItemStack original=mock(ItemStack.class), newer=mock(ItemStack.class), excess=mock(ItemStack.class);
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
            ItemStack item=mock(ItemStack.class);f.handler.add(f.id,item);f.handler.check(f.player);
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
            ItemStack item=mock(ItemStack.class);f.handler.add(f.id,item);f.handler.check(f.player);
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            when(f.inventory.addItem(item)).thenAnswer(call->{entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));HashMap<Integer,ItemStack> rest=new HashMap<>();rest.put(0,item);return rest;});
            ExecutorService workers=Executors.newFixedThreadPool(2);
            try {
                Future<?> check=workers.submit(f.owner.remove(0));assertTrue(entered.await(2,TimeUnit.SECONDS));
                Future<?> save=workers.submit(f.handler::save);
                java.lang.reflect.Field field=FullInventoryHandler.class.getDeclaredField("deliveryLock");field.setAccessible(true);ReentrantReadWriteLock lock=(ReentrantReadWriteLock)field.get(f.handler);
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(!lock.hasQueuedThreads()&&System.nanoTime()<end)Thread.yield();
                assertTrue(lock.hasQueuedThreads());assertFalse(save.isDone());
                release.countDown();check.get(2,TimeUnit.SECONDS);save.get(2,TimeUnit.SECONDS);
                assertEquals(item,f.data.getItemStack("FullInventory."+f.id+".Items.0"));
            } finally {release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(2,TimeUnit.SECONDS));}
        }
    }
    @Test void failedSaveRestoresThePreviousSectionAndRetainsAcceptedItemsForExplicitRetry() {
        try(Fixture f=new Fixture()) {
            f.data.set("FullInventory.previous.Time",123L);f.data.set("Unrelated",456);
            ItemStack item=mock(ItemStack.class);f.handler.add(f.id,item);
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
            ItemStack original=mock(ItemStack.class),replacement=mock(ItemStack.class);ItemStack[] supplied={original};
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
    private static final class Fixture implements AutoCloseable {
        final UUID id=UUID.randomUUID();final AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);
        final BukkitScheduler scheduler=mock(BukkitScheduler.class);final ServerData serverData=mock(ServerData.class);
        final YamlConfiguration data=new YamlConfiguration();final Player player=mock(Player.class);final PlayerInventory inventory=mock(PlayerInventory.class);
        final List<Runnable> owner=new ArrayList<>();final FullInventoryHandler handler;
        Fixture(){when(plugin.getBukkitScheduler()).thenReturn(scheduler);when(plugin.getServerDataFile()).thenReturn(serverData);when(serverData.getData()).thenReturn(data);doAnswer(call->{data.set(call.getArgument(0),call.getArgument(1));return null;}).when(serverData).setData(anyString(),any());when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());when(plugin.getOptions()).thenReturn(mock(AdvancedCoreConfigOptions.class));when(player.getUniqueId()).thenReturn(id);when(player.getInventory()).thenReturn(inventory);doAnswer(call->{owner.add(call.getArgument(1));return null;}).when(scheduler).runTask(eq(plugin),any(Runnable.class),eq(player));handler=new FullInventoryHandler(plugin);}
        public void close(){handler.shutdown();try{assertTrue(handler.getTimer().awaitTermination(2,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    }
}
