package com.bencodez.advancedcore.tests.item;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.bencodez.advancedcore.AdvancedCoreConfigOptions;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.item.FullInventoryHandler;
import com.bencodez.advancedcore.api.user.AdvancedCoreUser;
import com.bencodez.advancedcore.data.ServerData;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;

public class LegacyReplayItemDeliveryTest {
	private final List<FullInventoryHandler> handlers = new ArrayList<>();
	private final List<ServerData> fixtureStores = new ArrayList<>();

	@AfterEach
	public void shutdownHandlers() {
		// End fault injection before fixture retirement; shutdown failures remain explicit in production.
		for (ServerData store : fixtureStores) org.mockito.Mockito.doNothing().when(store).saveData();
		for (FullInventoryHandler handler : handlers) {
			handler.shutdown();
			try { assertTrue(handler.getTimer().awaitTermination(2, TimeUnit.SECONDS)); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
		}
	}











	@Test
	public void giveItemAsyncCompletesOnlyAfterOwnedInventoryDelivery() {
		Fixture fixture = createFixture();
		UUID uuid = UUID.randomUUID();
		Player player = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		ItemStack item = snapshotItem();
		when(player.getUniqueId()).thenReturn(uuid);
		when(player.isOnline()).thenReturn(true);
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.addItem(item)).thenReturn(new HashMap<>());

		CompletionStage<Void> delivery = fixture.handler.giveItemAsync(player, item);
		assertFalse(delivery.toCompletableFuture().isDone());
		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(fixture.bukkitScheduler).runTask(eq(fixture.plugin), task.capture(), eq(player));

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
			task.getValue().run();
		}

		assertTrue(delivery.toCompletableFuture().isDone());
		verify(inventory).addItem(item);
	}

	@Test
	public void noOverflowCompletionPrecedesShutdownSnapshot() throws Exception {
		Fixture fixture = createFixture();
		UUID uuid = UUID.randomUUID();
		Player player = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		ItemStack item = snapshotItem();
		when(player.getUniqueId()).thenReturn(uuid);
		when(player.isOnline()).thenReturn(true);
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.addItem(item)).thenReturn(new HashMap<>());
		CountDownLatch saveStarted = new CountDownLatch(1);
		doAnswer(invocation -> {
			saveStarted.countDown();
			return null;
		}).when(fixture.serverData).saveData();

		CompletionStage<Void> delivery = fixture.handler.giveItemAsync(player, item);
		AtomicReference<Thread> shutdownThread = new AtomicReference<>();
		CompletionStage<Void> completionObserved = delivery.thenRun(() -> {
			Thread shutdown = new Thread(fixture.handler::shutdown, "full-inventory-shutdown-race");
			shutdownThread.set(shutdown);
			shutdown.start();
			try {
				assertFalse(saveStarted.await(100, TimeUnit.MILLISECONDS),
						"shutdown snapshot overtook the successful delivery completion");
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new AssertionError(interrupted);
			}
		});
		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(fixture.bukkitScheduler).runTask(eq(fixture.plugin), task.capture(), eq(player));
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
			task.getValue().run();
		}

		completionObserved.toCompletableFuture().get(2, TimeUnit.SECONDS);
		Thread shutdown = shutdownThread.get();
		assertNotNull(shutdown);
		shutdown.join(TimeUnit.SECONDS.toMillis(2));
		assertFalse(shutdown.isAlive());
		assertEquals(0, saveStarted.getCount());
		verify(inventory).addItem(item);
	}

	@Test
	public void giveItemAsyncWaitsForOverflowToBePersisted() throws Exception {
		Fixture fixture = createFixture();
		UUID uuid = UUID.randomUUID();
		Player player = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		ItemStack item = snapshotItem();
		ItemStack excess = snapshotItem();
		when(player.getUniqueId()).thenReturn(uuid);
		when(player.isOnline()).thenReturn(true);
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.addItem(item)).thenReturn(new HashMap<>(java.util.Collections.singletonMap(0, excess)));
		fixture.handler.getLastMessageTime().put(uuid, System.currentTimeMillis());
		CountDownLatch saveStarted = new CountDownLatch(1);
		CountDownLatch releaseSave = new CountDownLatch(1);
		doAnswer(invocation -> {
			saveStarted.countDown();
			assertTrue(releaseSave.await(2, TimeUnit.SECONDS));
			return null;
		}).when(fixture.serverData).saveData();

		CompletionStage<Void> delivery = fixture.handler.giveItemAsync(player, item);
		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(fixture.bukkitScheduler).runTask(eq(fixture.plugin), task.capture(), eq(player));
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
			task.getValue().run();
		}

		assertTrue(saveStarted.await(2, TimeUnit.SECONDS));
		assertFalse(delivery.toCompletableFuture().isDone());
		releaseSave.countDown();
		delivery.toCompletableFuture().get(2, TimeUnit.SECONDS);
		assertEquals(java.util.Arrays.asList(excess), fixture.handler.getItems().get(uuid));
	}

	@Test
	public void replayCompletionIsRegisteredBeforeOverflowReservationCanRaceShutdown() throws Exception {
		Fixture fixture = createFixture();
		UUID uuid = UUID.randomUUID();
		Player player = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		ItemStack item = snapshotItem();
		ItemStack excess = snapshotItem();
		when(player.getUniqueId()).thenReturn(uuid);
		when(player.isOnline()).thenReturn(true);
		when(player.getInventory()).thenReturn(inventory);
		fixture.handler.getLastMessageTime().put(uuid, System.currentTimeMillis());
		doAnswer(invocation -> {
			java.lang.reflect.Field completionsField = FullInventoryHandler.class
					.getDeclaredField("replayOverflowCompletions");
			completionsField.setAccessible(true);
			@SuppressWarnings("unchecked")
			ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Void>> completions =
					(ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Void>>) completionsField
							.get(fixture.handler);
			assertEquals(1, completions.size(),
					"shutdown must be able to find the completion before it can snapshot a reservation");
			return new HashMap<>(java.util.Collections.singletonMap(0, excess));
		}).when(inventory).addItem(item);

		CompletionStage<Void> delivery = fixture.handler.giveItemAsync(player, item);
		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(fixture.bukkitScheduler).runTask(eq(fixture.plugin), task.capture(), eq(player));
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
			task.getValue().run();
		}

		delivery.toCompletableFuture().get(2, TimeUnit.SECONDS);
	}

	@Test
	public void replayDropFailurePersistsOnlyTheUndroppedOverflow() throws Exception {
		Fixture fixture = createFixture();
		UUID uuid = UUID.randomUUID();
		Player player = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		ItemStack item = snapshotItem();
		ItemStack excess = snapshotItem();
		World world = mock(World.class);
		Location location = mock(Location.class);
		when(fixture.plugin.getOptions().isDropOnFullInv()).thenReturn(true);
		when(player.getUniqueId()).thenReturn(uuid);
		when(player.isOnline()).thenReturn(true);
		when(player.getInventory()).thenReturn(inventory);
		when(player.getWorld()).thenReturn(world);
		when(player.getLocation()).thenReturn(location);
		when(inventory.addItem(item)).thenReturn(new HashMap<>(java.util.Collections.singletonMap(0, excess)));
		doAnswer(invocation -> {
			throw new IllegalStateException("world unavailable");
		}).when(world).dropItem(location, excess);
		fixture.handler.getLastMessageTime().put(uuid, System.currentTimeMillis());
		CountDownLatch saveStarted = new CountDownLatch(1);
		CountDownLatch releaseSave = new CountDownLatch(1);
		doAnswer(invocation -> {
			saveStarted.countDown();
			assertTrue(releaseSave.await(2, TimeUnit.SECONDS));
			return null;
		}).when(fixture.serverData).saveData();

		CompletionStage<Void> delivery = fixture.handler.giveItemAsync(player, item);
		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(fixture.bukkitScheduler).runTask(eq(fixture.plugin), task.capture(), eq(player));
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
			task.getValue().run();
		}

		assertTrue(saveStarted.await(2, TimeUnit.SECONDS));
		assertFalse(delivery.toCompletableFuture().isDone());
		releaseSave.countDown();
		delivery.toCompletableFuture().get(2, TimeUnit.SECONDS);
		verify(inventory, times(1)).addItem(item);
		verify(world).dropItem(location, excess);
		assertEquals(java.util.Arrays.asList(excess), fixture.handler.getItems().get(uuid));
		assertEquals(excess, fixture.data.getItemStack("FullInventory." + uuid + ".Items.0"));
	}

	@Test
	public void replayOverflowSaveFailureDropsOnlyReservedOverflowWithoutReplayingPartialInsert() throws Exception {
		Fixture fixture = createFixture();
		UUID uuid = UUID.randomUUID();
		Player player = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		ItemStack item = snapshotItem();
		ItemStack excess = snapshotItem();
		World world = mock(World.class);
		Location location = mock(Location.class);
		when(fixture.plugin.getOptions().isDropOnFullInv()).thenReturn(false);
		when(player.getUniqueId()).thenReturn(uuid);
		when(player.isOnline()).thenReturn(true);
		when(player.getInventory()).thenReturn(inventory);
		when(player.getWorld()).thenReturn(world);
		when(player.getLocation()).thenReturn(location);
		when(inventory.addItem(item)).thenReturn(new HashMap<>(java.util.Collections.singletonMap(0, excess)));
		when(world.dropItem(location, excess)).thenReturn(mock(org.bukkit.entity.Item.class));
		fixture.handler.getLastMessageTime().put(uuid, System.currentTimeMillis());
		doAnswer(invocation -> {
			throw new IllegalStateException("disk unavailable");
		}).when(fixture.serverData).saveData();

		AtomicInteger scheduled = new AtomicInteger();
		AtomicReference<Runnable> initialDelivery = new AtomicReference<>();
		AtomicReference<Runnable> fallback = new AtomicReference<>();
		CountDownLatch fallbackScheduled = new CountDownLatch(1);
		doAnswer(invocation -> {
			Runnable task = invocation.getArgument(1);
			if (scheduled.incrementAndGet() == 1) initialDelivery.set(task);
			else {
				fallback.set(task);
				fallbackScheduled.countDown();
			}
			return null;
		}).when(fixture.bukkitScheduler).runTask(eq(fixture.plugin), any(Runnable.class), eq(player));

		CompletionStage<Void> delivery = fixture.handler.giveItemAsync(player, item);
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
			bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
			initialDelivery.get().run();
			assertTrue(fallbackScheduled.await(2, TimeUnit.SECONDS));
			assertFalse(delivery.toCompletableFuture().isDone());

			// The overflow remains reserved while its persistence is failing, so an
			// ordinary sweep cannot consume it before the fallback owns it.
			fixture.handler.check();
			assertEquals(2, scheduled.get());
			fallback.get().run();
		}

		delivery.toCompletableFuture().get(2, TimeUnit.SECONDS);
		verify(inventory, times(1)).addItem(item);
		verify(world).dropItem(location, excess);
		assertFalse(fixture.handler.getItems().containsKey(uuid));
		assertFalse(fixture.data.contains("FullInventory"));
	}

	@Test
	public void replayOverflowDoesNotAcknowledgeAnUnavailableFallback() throws Exception {
		Fixture fixture = createFixture();
		UUID uuid = UUID.randomUUID();
		Player player = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		ItemStack item = snapshotItem();
		ItemStack excess = snapshotItem();
		when(fixture.plugin.getOptions().isDropOnFullInv()).thenReturn(false);
		when(player.getUniqueId()).thenReturn(uuid);
		when(player.isOnline()).thenReturn(true);
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.addItem(item)).thenReturn(new HashMap<>(java.util.Collections.singletonMap(0, excess)));
		fixture.handler.getLastMessageTime().put(uuid, System.currentTimeMillis());
		doAnswer(invocation -> {
			throw new IllegalStateException("disk unavailable");
		}).when(fixture.serverData).saveData();

		AtomicReference<Runnable> initialDelivery = new AtomicReference<>();
		AtomicReference<Runnable> fallback = new AtomicReference<>();
		CountDownLatch fallbackScheduled = new CountDownLatch(1);
		doAnswer(invocation -> {
			Runnable task = invocation.getArgument(1);
			if (initialDelivery.get() == null) initialDelivery.set(task);
			else {
				fallback.set(task);
				fallbackScheduled.countDown();
			}
			return null;
		}).when(fixture.bukkitScheduler).runTask(eq(fixture.plugin), any(Runnable.class), eq(player));

		CompletionStage<Void> delivery = fixture.handler.giveItemAsync(player, item);
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
			initialDelivery.get().run();
			assertTrue(fallbackScheduled.await(2, TimeUnit.SECONDS));
			bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(null);
			fallback.get().run();
		}

		assertFalse(delivery.toCompletableFuture().isDone());
		verify(inventory, times(1)).addItem(item);
		assertFalse(fixture.handler.getItems().containsKey(uuid));
		assertFalse(fixture.data.contains("FullInventory"));
	}

	@Test
	public void shutdownPersistsAcceptedOverflowBeforeDiscardingQueuedPersistence() throws Exception {
		Fixture fixture = createFixture();
		UUID uuid = UUID.randomUUID();
		Player player = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		ItemStack item = snapshotItem();
		ItemStack excess = snapshotItem();
		when(fixture.plugin.getOptions().isDropOnFullInv()).thenReturn(false);
		when(player.getUniqueId()).thenReturn(uuid);
		when(player.isOnline()).thenReturn(true);
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.addItem(item)).thenReturn(new HashMap<>(java.util.Collections.singletonMap(0, excess)));
		fixture.handler.getLastMessageTime().put(uuid, System.currentTimeMillis());
		CountDownLatch executorBlocked = new CountDownLatch(1);
		CountDownLatch releaseExecutor = new CountDownLatch(1);
		fixture.handler.getTimer().execute(() -> {
			executorBlocked.countDown();
			try {
				releaseExecutor.await(2, TimeUnit.SECONDS);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		});
		assertTrue(executorBlocked.await(2, TimeUnit.SECONDS));

		CompletionStage<Void> delivery = fixture.handler.giveItemAsync(player, item);
		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(fixture.bukkitScheduler).runTask(eq(fixture.plugin), task.capture(), eq(player));
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
			task.getValue().run();
		}
		assertFalse(delivery.toCompletableFuture().isDone());

		fixture.handler.shutdown();
		delivery.toCompletableFuture().get(2, TimeUnit.SECONDS);
		releaseExecutor.countDown();
		assertEquals(excess, fixture.data.getItemStack("FullInventory." + uuid + ".Items.0"));
		verify(inventory, times(1)).addItem(item);
	}

	@Test
	public void queuedReplayDeliveryDoesNotStartAfterShutdownSnapshot() throws Exception {
		Fixture fixture = createFixture();
		UUID uuid = UUID.randomUUID();
		Player player = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		ItemStack item = snapshotItem();
		when(player.getUniqueId()).thenReturn(uuid);
		when(player.isOnline()).thenReturn(true);
		when(player.getInventory()).thenReturn(inventory);

		CompletionStage<Void> delivery = fixture.handler.giveItemAsync(player, item);
		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(fixture.bukkitScheduler).runTask(eq(fixture.plugin), task.capture(), eq(player));
		fixture.handler.shutdown();
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
			task.getValue().run();
		}

		java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
				() -> delivery.toCompletableFuture().get(2, TimeUnit.SECONDS));
		assertTrue(AdvancedCoreUser.isReplayActionNotStarted(failure));
		verify(inventory, never()).addItem(item);
	}

	@Test
	public void queuedPendingCheckCannotMutateItemsAfterShutdownSnapshot() {
		Fixture fixture = createFixture();
		UUID uuid = UUID.randomUUID();
		Player player = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		ItemStack item = snapshotItem();
		when(player.getUniqueId()).thenReturn(uuid);
		when(player.getInventory()).thenReturn(inventory);
		fixture.handler.add(uuid, item);

		fixture.handler.check(player);
		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(fixture.bukkitScheduler).runTask(eq(fixture.plugin), task.capture(), eq(player));
		fixture.handler.shutdown();
		assertEquals(item, fixture.data.getItemStack("FullInventory." + uuid + ".Items.0"));

		task.getValue().run();

		verify(inventory, never()).addItem(any(ItemStack[].class));
		assertEquals(java.util.Arrays.asList(item), fixture.handler.getItems().get(uuid));
	}

	@Test
	public void giveItemAsyncFailsWithoutMutationWhenPlayerDisconnectsAfterEnqueue() throws Exception {
		Fixture fixture = createFixture();
		UUID uuid = UUID.randomUUID();
		Player player = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		ItemStack item = snapshotItem();
		when(player.getUniqueId()).thenReturn(uuid);
		when(player.getInventory()).thenReturn(inventory);

		CompletionStage<Void> delivery = fixture.handler.giveItemAsync(player, item);
		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(fixture.bukkitScheduler).runTask(eq(fixture.plugin), task.capture(), eq(player));

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(null);
			task.getValue().run();
		}

		java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
				() -> delivery.toCompletableFuture().get(2, TimeUnit.SECONDS));
		assertTrue(AdvancedCoreUser.isReplayActionNotStarted(failure));
		verify(inventory, never()).addItem(item);
	}

    @Test public void rejectedOverflowRetryRemainsPendingButReportsFailureAndCanBeExplicitlySaved() throws Exception {
        Fixture f=createFixture();java.util.logging.Logger logger=mock(java.util.logging.Logger.class);
        when(f.plugin.getLogger()).thenReturn(logger);
        Player player=mock(Player.class);UUID id=UUID.randomUUID();PlayerInventory inventory=mock(PlayerInventory.class);
        ItemStack item=snapshotItem(),extra=snapshotItem();when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);when(player.getInventory()).thenReturn(inventory);
        when(inventory.addItem(item)).thenReturn(new HashMap<>(java.util.Collections.singletonMap(0,extra)));
        f.handler.getLastMessageTime().put(id,System.currentTimeMillis());
        AtomicReference<Runnable> nativeDelivery=new AtomicReference<>();AtomicInteger admissions=new AtomicInteger();
        doAnswer(call->{
            if(admissions.incrementAndGet()==1){nativeDelivery.set(call.getArgument(1));return null;}
            throw new java.util.concurrent.RejectedExecutionException("owner unavailable");
        }).when(f.bukkitScheduler).runTask(eq(f.plugin),any(Runnable.class),eq(player));
        CompletionStage<Void> result=f.handler.giveItemAsync(player,item);
        // Retire the private persistence executor only after native admission. The
        // insertion has started, so neither success nor a replayable failure is safe.
        f.handler.getTimer().shutdownNow();assertTrue(f.handler.getTimer().awaitTermination(2,TimeUnit.SECONDS));
        try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(()->Bukkit.getPlayer(id)).thenReturn(player);nativeDelivery.get().run();
        }
        assertFalse(result.toCompletableFuture().isDone());verify(inventory,times(1)).addItem(item);
        verify(logger).log(eq(java.util.logging.Level.WARNING),
                eq("Unable to schedule full-inventory overflow persistence retry; delivery remains pending until saved"),
                any(java.util.concurrent.RejectedExecutionException.class));
        f.handler.save();result.toCompletableFuture().get(2,TimeUnit.SECONDS);
        assertEquals(extra,f.data.getItemStack("FullInventory."+id+".Items.0"));
        verify(inventory,times(1)).addItem(item);
    }

	@Test
	public void rejectedItemSchedulerSignalsThatDeliveryNeverStarted() throws Exception {
		Fixture fixture = createFixture();
		Player player = mock(Player.class);
		ItemStack item = snapshotItem();
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());
		org.mockito.Mockito.doThrow(new IllegalStateException("scheduler stopped"))
				.when(fixture.bukkitScheduler).runTask(eq(fixture.plugin), any(Runnable.class), eq(player));

		CompletionStage<Void> delivery = fixture.handler.giveItemAsync(player, item);
		java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
				() -> delivery.toCompletableFuture().get(2, TimeUnit.SECONDS));

		assertTrue(AdvancedCoreUser.isReplayActionNotStarted(failure));
	}

	@Test
	public void timedOutGiveItemAsyncSuppressesTheQueuedDeliveryToPreventReplayDuplicates() throws Exception {
		Fixture fixture = createFixture(0L);
		UUID uuid = UUID.randomUUID();
		Player player = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		ItemStack item = snapshotItem();
		when(player.getUniqueId()).thenReturn(uuid);
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.addItem(item)).thenReturn(new HashMap<>());

		CompletionStage<Void> delivery = fixture.handler.giveItemAsync(player, item);
		java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
				() -> delivery.toCompletableFuture().get(2, TimeUnit.SECONDS));
		assertTrue(AdvancedCoreUser.isReplayActionNotStarted(failure));
		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(fixture.bukkitScheduler).runTask(eq(fixture.plugin), task.capture(), eq(player));

		task.getValue().run();
		task.getValue().run();

		verify(inventory, never()).addItem(item);
	}







	@Test
	public void saveWaitsForInflightDeliveryBeforeSnapshotting() throws Exception {
		Fixture fixture = createFixture();
		UUID uuid = UUID.randomUUID();
		Player player = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		ItemStack pending = snapshotItem();
		ItemStack excessItem = snapshotItem();
		CountDownLatch deliveryStarted = new CountDownLatch(1);
		CountDownLatch releaseDelivery = new CountDownLatch(1);
		CountDownLatch saveFinished = new CountDownLatch(1);

		when(player.getUniqueId()).thenReturn(uuid);
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.addItem(pending)).thenAnswer(invocation -> {
			deliveryStarted.countDown();
			assertTrue(releaseDelivery.await(2, TimeUnit.SECONDS));
			HashMap<Integer, ItemStack> excess = new HashMap<>();
			excess.put(0, excessItem);
			return excess;
		});
		fixture.handler.add(uuid, pending);

		fixture.handler.check(player);
		ArgumentCaptor<Runnable> deliveryTask = ArgumentCaptor.forClass(Runnable.class);
		verify(fixture.bukkitScheduler).runTask(eq(fixture.plugin), deliveryTask.capture(), eq(player));

		Thread deliveryThread = new Thread(deliveryTask.getValue());
		deliveryThread.start();
		assertTrue(deliveryStarted.await(2, TimeUnit.SECONDS));

		Thread saveThread = new Thread(() -> {
			fixture.handler.save();
			saveFinished.countDown();
		});
		saveThread.start();
		assertFalse(saveFinished.await(100, TimeUnit.MILLISECONDS));

		releaseDelivery.countDown();
		assertTrue(saveFinished.await(2, TimeUnit.SECONDS));
		deliveryThread.join(2000L);
		saveThread.join(2000L);

		ConfigurationSection root = fixture.data.getConfigurationSection("FullInventory");
		assertNotNull(root);
		assertEquals(excessItem, root.getItemStack(uuid + ".Items.0"));
	}


    @Test public void shutdownSettlesQueuedReceiptBeforeCancellingItsDeadline() {
        Fixture f=createFixture();Player player=mock(Player.class);UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);
        CompletionStage<Void> result=f.handler.giveItemAsync(player,snapshotItem());
        assertFalse(result.toCompletableFuture().isDone());f.handler.shutdown();
        assertTrue(result.toCompletableFuture().isCompletedExceptionally());
        java.util.concurrent.CompletionException failure=assertThrows(java.util.concurrent.CompletionException.class,()->result.toCompletableFuture().join());
        assertTrue(AdvancedCoreUser.isReplayActionNotStarted(failure));verify(player,never()).getInventory();
    }
    @Test public void externallyStoppedTimerRejectsQueuedReceiptWithoutThrowingOrMutating() {
        Fixture f=createFixture();Player player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());f.handler.getTimer().shutdownNow();
        CompletionStage<Void> result=assertDoesNotThrow(()->f.handler.giveItemAsync(player,snapshotItem()));
        assertTrue(result.toCompletableFuture().isCompletedExceptionally());
        java.util.concurrent.CompletionException failure=assertThrows(java.util.concurrent.CompletionException.class,()->result.toCompletableFuture().join());
        assertTrue(AdvancedCoreUser.isReplayActionNotStarted(failure));verify(player,never()).getInventory();
    }
    @Test public void observerCancellationCannotTurnAcceptedDeliveryIntoAReplayReceipt() {
        Fixture f=createFixture();Player player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        CompletionStage<Void> result=f.handler.giveItemAsync(player,snapshotItem());
        assertFalse(result.toCompletableFuture().cancel(true));assertFalse(result.toCompletableFuture().isDone());
    }

	@Test public void absentServerDataCannotBeAcknowledgedAsDurable() {
		Fixture f=createFixture();when(f.plugin.getServerDataFile()).thenReturn(null);
		assertFalse(f.handler.saveDurably());
	}


    @Test public void durableCompletionCallbackRunsOutsideThePublicationMonitor() throws Exception {
        Fixture f=createFixture();Player player=mock(Player.class);UUID id=UUID.randomUUID();PlayerInventory inventory=mock(PlayerInventory.class);
        ItemStack item=snapshotItem(),extra=snapshotItem();when(player.getUniqueId()).thenReturn(id);when(player.isOnline()).thenReturn(true);when(player.getInventory()).thenReturn(inventory);
        when(inventory.addItem(item)).thenReturn(new HashMap<>(java.util.Collections.singletonMap(0,extra)));f.handler.getLastMessageTime().put(id,System.currentTimeMillis());
        CompletionStage<Void> result=f.handler.giveItemAsync(player,item);java.util.concurrent.ExecutorService writer=java.util.concurrent.Executors.newSingleThreadExecutor();
        CompletionStage<Void> callback=result.thenRun(()->{try{writer.submit(f.handler::save).get(1,TimeUnit.SECONDS);}catch(Exception failure){throw new AssertionError("Publication lock must be released before callbacks",failure);}});
        ArgumentCaptor<Runnable> task=ArgumentCaptor.forClass(Runnable.class);verify(f.bukkitScheduler).runTask(eq(f.plugin),task.capture(),eq(player));
        try {
            try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)){bukkit.when(()->Bukkit.getPlayer(id)).thenReturn(player);task.getValue().run();}
            callback.toCompletableFuture().get(2,TimeUnit.SECONDS);
        }finally{writer.shutdownNow();assertTrue(writer.awaitTermination(2,TimeUnit.SECONDS));}
    }

	private static ItemStack snapshotItem() {
		ItemStack item = mock(ItemStack.class);
		when(item.clone()).thenReturn(item);
		return item;
	}

	private Fixture createFixture() {
		return createFixture(TimeUnit.SECONDS.toMillis(30));
	}

	private Fixture createFixture(long itemDeliveryTimeoutMillis) {
		AdvancedCorePlugin plugin = mock(AdvancedCorePlugin.class);
		AdvancedCoreConfigOptions options = mock(AdvancedCoreConfigOptions.class);
		ScheduledExecutorService sharedInventoryTimer = mock(ScheduledExecutorService.class);
		BukkitScheduler bukkitScheduler = mock(BukkitScheduler.class);
		ServerData serverData = mock(ServerData.class);
		YamlConfiguration data = new YamlConfiguration();

		when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("LegacyReplayItemDeliveryTest"));
		when(plugin.getInventoryTimer()).thenReturn(sharedInventoryTimer);
		when(plugin.getOptions()).thenReturn(options);
		when(plugin.isEnabled()).thenReturn(true);
		when(plugin.getBukkitScheduler()).thenReturn(bukkitScheduler);
		when(plugin.getServerDataFile()).thenReturn(serverData);
		when(serverData.getData()).thenReturn(data);

		FullInventoryHandler handler = new FullInventoryHandler(plugin) {
			@Override
			protected long getItemDeliveryTimeoutMillis() {
				return itemDeliveryTimeoutMillis;
			}
		};
		handlers.add(handler);
		fixtureStores.add(serverData);
		return new Fixture(plugin, sharedInventoryTimer, bukkitScheduler, serverData, data, handler);
	}

	private static final class Fixture {
		private final AdvancedCorePlugin plugin;
		private final ScheduledExecutorService sharedInventoryTimer;
		private final BukkitScheduler bukkitScheduler;
		private final ServerData serverData;
		private final YamlConfiguration data;
		private final FullInventoryHandler handler;

		private Fixture(AdvancedCorePlugin plugin, ScheduledExecutorService sharedInventoryTimer,
				BukkitScheduler bukkitScheduler, ServerData serverData, YamlConfiguration data,
				FullInventoryHandler handler) {
			this.plugin = plugin;
			this.sharedInventoryTimer = sharedInventoryTimer;
			this.bukkitScheduler = bukkitScheduler;
			this.serverData = serverData;
			this.data = data;
			this.handler = handler;
		}
	}
}
