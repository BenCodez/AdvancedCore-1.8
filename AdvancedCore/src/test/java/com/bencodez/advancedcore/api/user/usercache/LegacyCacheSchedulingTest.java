package com.bencodez.advancedcore.api.user.usercache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.AdvancedCoreUser;
import com.bencodez.advancedcore.api.user.UserData;
import com.bencodez.advancedcore.api.user.UserManager;
import com.bencodez.advancedcore.api.user.usercache.change.UserDataChangeString;

class LegacyCacheSchedulingTest {
    @Test void failedChangeNotificationDoesNotDisableLaterFlushes() {
        Fixture f = new Fixture();
        doThrow(new IllegalStateException("notification failed"))
            .doNothing().when(f.users).onChange(eq(f.user), any(String[].class));
        f.cache.addChange(new UserDataChangeString("Points", "1"), true);
        f.tasks.get(0).run();
        assertFalse(f.cache.hasChangesToProcess());
        f.cache.addChange(new UserDataChangeString("Points", "2"), true);
        assertEquals(2, f.tasks.size());
        f.tasks.get(1).run();
        verify(f.data, times(2)).setValues(any());
        assertFalse(f.cache.hasChangesToProcess());
    }
    @Test void changeQueuedDuringStorageGetsItsOwnScheduledFlush() {
        Fixture f = new Fixture();
        doAnswer(call -> {
            f.cache.addChange(new UserDataChangeString("Points", "2"), true);
            return null;
        }).doNothing().when(f.data).setValues(any());
        f.cache.addChange(new UserDataChangeString("Points", "1"), true);
        f.tasks.get(0).run();
        assertEquals(2, f.tasks.size());
        assertTrue(f.cache.hasChangesToProcess());
        f.tasks.get(1).run();
        assertFalse(f.cache.hasChangesToProcess());
        verify(f.data, times(2)).setValues(any());
    }
    @Test void rejectedScheduleDoesNotPermanentlyMarkTheCacheScheduled() {
        Fixture f = new Fixture();
        doThrow(new RejectedExecutionException("timer stopped"))
            .doAnswer(call -> { f.tasks.add(call.getArgument(0)); return null; })
            .when(f.timer).schedule(any(Runnable.class), eq(3L), eq(TimeUnit.SECONDS));
        assertThrows(RejectedExecutionException.class,
            () -> f.cache.addChange(new UserDataChangeString("Points", "1"), true));
        assertTrue(f.cache.hasChangesToProcess());
        f.cache.addChange(new UserDataChangeString("Points", "2"), true);
        assertEquals(1, f.tasks.size());
        f.tasks.get(0).run();
        assertFalse(f.cache.hasChangesToProcess());
    }
    @Test void queuedMutationsCoalesceWithoutExtraTimers() {
        Fixture f = new Fixture();
        f.cache.addChange(new UserDataChangeString("Points", "1"), true);
        f.cache.addChange(new UserDataChangeString("Points", "2"), true);
        assertEquals(1, f.tasks.size());
        doAnswer(call -> {
            java.util.HashMap<String, com.bencodez.simpleapi.sql.data.DataValue> values = call.getArgument(0);
            assertEquals("2", values.get("Points").getString());
            return null;
        }).when(f.data).setValues(any());
        f.tasks.get(0).run();
        assertEquals(1, f.tasks.size());
        assertFalse(f.cache.hasChangesToProcess());
    }
    @Test void alreadyScheduledTaskCanFinishAfterCacheDump() {
        Fixture f = new Fixture();
        f.cache.addChange(new UserDataChangeString("Points", "1"), true);
        f.cache.dump();
        assertDoesNotThrow(() -> f.tasks.get(0).run());
        assertEquals(1, f.tasks.size());
        verify(f.data).setValues(any());
    }
    private static class Fixture {
        final ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
        final AdvancedCorePlugin plugin = mock(AdvancedCorePlugin.class);
        final UserDataManager manager = mock(UserDataManager.class);
        final UserManager users = mock(UserManager.class);
        final AdvancedCoreUser user = mock(AdvancedCoreUser.class);
        final UserData data = mock(UserData.class);
        final ArrayList<Runnable> tasks = new ArrayList<>();
        final UserDataCache cache;
        Fixture() {
            when(manager.getPlugin()).thenReturn(plugin);
            when(manager.getTimer()).thenReturn(timer);
            when(plugin.getUserManager()).thenReturn(users);
            when(user.getUserData()).thenReturn(data);
            cache = spy(new UserDataCache(manager, UUID.randomUUID()));
            doReturn(user).when(cache).getUser();
            doAnswer(call -> { tasks.add(call.getArgument(0)); return null; })
                .when(timer).schedule(any(Runnable.class), eq(3L), eq(TimeUnit.SECONDS));
        }
    }
}
