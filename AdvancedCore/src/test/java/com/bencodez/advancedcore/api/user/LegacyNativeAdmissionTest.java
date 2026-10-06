package com.bencodez.advancedcore.api.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership;
import com.bencodez.advancedcore.api.user.usercache.change.UserDataChangeInt;

class LegacyNativeAdmissionTest {
    @Test void retiredCheckedNativeAndCachedEntriesRejectBeforeMutationOrStorage() throws Exception {
        LegacyDirectUserDataTest.Fixture f = new LegacyDirectUserDataTest.Fixture(); UserStorageOwnership owner = f.plugin.getUserStorageOwnership();
        owner.retire(0, TimeUnit.NANOSECONDS, () -> {}, () -> {});
        assertThrows(IllegalStateException.class, f.data::getValuesStrict);
        assertThrows(IllegalStateException.class, () -> f.data.setInt("Points", 9, false));
        assertThrows(IllegalStateException.class, () -> f.data.setInt("Points", 9, false, true));
        assertThrows(IllegalStateException.class, () -> f.cache.addChange(new UserDataChangeInt("Points", 9), true));
        assertThrows(IllegalStateException.class, f.data::remove);
        assertEquals(1, f.cache.getCachedValue("Points").getInt()); assertFalse(f.cache.hasChangesToProcess());
        verify(f.mysql, never()).updateStrict(anyString(), anyList()); verify(f.mysql, never()).deletePlayerStrict(anyString());
        verify(f.plugin.getTimer(), never()).execute(any());
    }
    @Test void finalCacheFlushAcknowledgesAndRetiresWithoutDeliveringChangeCallbacks() throws Exception {
        LegacyDirectUserDataTest.Fixture f = new LegacyDirectUserDataTest.Fixture(); UserStorageOwnership owner = f.plugin.getUserStorageOwnership();
        doCallRealMethod().when(f.manager).clearCacheForShutdown(); f.cache.addChange(new UserDataChangeInt("Points", 9), true);
        assertThrows(IllegalStateException.class, f.manager::clearCacheForShutdown);
        owner.retire(0, TimeUnit.NANOSECONDS, f.manager::clearCacheForShutdown, () -> {
            assertTrue(f.manager.getUserDataCache().isEmpty()); assertNull(f.cache.getUuid());
        });
        verify(f.mysql).updateStrict(anyString(), anyList()); verify(f.users, never()).onChange(any(), any());
        assertFalse(f.cache.hasChangesToProcess());
    }
    @Test void failedFinalCacheFlushKeepsPendingGenerationAndDoesNotCloseOrNotify() throws Exception {
        LegacyDirectUserDataTest.Fixture f = new LegacyDirectUserDataTest.Fixture(); UserStorageOwnership owner = f.plugin.getUserStorageOwnership();
        doCallRealMethod().when(f.manager).clearCacheForShutdown(); f.cache.addChange(new UserDataChangeInt("Points", 9), true);
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        doThrow(new java.sql.SQLException("offline")).when(f.mysql).updateStrict(anyString(), anyList());
        assertThrows(IllegalStateException.class, () -> owner.retire(0, TimeUnit.NANOSECONDS, f.manager::clearCacheForShutdown, () -> closed.set(true)));
        assertFalse(closed.get()); assertTrue(f.cache.hasChangesToProcess()); assertNotNull(f.cache.getUuid());
        assertSame(f.cache, f.manager.getUserDataCache().get(f.cache.getUuid())); verify(f.users, never()).onChange(any(), any());
        doNothing().when(f.mysql).updateStrict(anyString(), anyList());
        owner.retire(0, TimeUnit.NANOSECONDS, f.manager::clearCacheForShutdown, () -> closed.set(true));
        assertTrue(closed.get()); verify(f.users, never()).onChange(any(), any());
    }
    @Test void cacheRetirementNotificationsRemainInsideAdmissionUntilTheirCallbacksSettle() throws Exception {
        for (int entry=0;entry<3;entry++) {
            LegacyDirectUserDataTest.Fixture f=new LegacyDirectUserDataTest.Fixture();
            UserStorageOwnership owner=f.plugin.getUserStorageOwnership();
            f.cache.addChange(new UserDataChangeInt("Points",9),true);
            java.util.concurrent.atomic.AtomicBoolean notified=new java.util.concurrent.atomic.AtomicBoolean();
            doAnswer(call -> {
                notified.set(true);
                assertThrows(IllegalStateException.class, () -> owner.retire(0,TimeUnit.NANOSECONDS,() -> {},() -> fail("Callback still owns admission")));
                return null;
            }).when(f.users).onChange(any(),any());
            if(entry==0) f.cache.clearCache();
            else if(entry==1) f.cache.dump();
            else {doCallRealMethod().when(f.manager).removeCache(any(),any());f.manager.removeCache(f.cache.getUuid(),null);}
            assertTrue(notified.get());
            owner.retire(0,TimeUnit.NANOSECONDS,() -> {},() -> {});
        }
    }
    @Test void acceptedAsyncTypedSetterRetainsAdmissionUntilItsActualCheckedWriteCompletes() throws Exception {
        LegacyDirectUserDataTest.Fixture f = new LegacyDirectUserDataTest.Fixture(); UserStorageOwnership owner = f.plugin.getUserStorageOwnership(); AtomicReference<Runnable> accepted = new AtomicReference<>();
        ScheduledExecutorService timer = f.plugin.getTimer();
        doAnswer(call -> { accepted.set(call.getArgument(0)); return null; }).when(timer).execute(any());
        f.data.setInt("Points", 9, false, true);
        assertThrows(IllegalStateException.class, () -> owner.retire(0, TimeUnit.NANOSECONDS, () -> fail("Pending write"), () -> fail("Pending write")));
        verify(f.mysql, never()).updateStrict(anyString(), anyList()); assertEquals(1, f.cache.getCachedValue("Points").getInt());
        accepted.get().run(); verify(f.mysql).updateStrict(anyString(), anyList()); assertEquals(9, f.cache.getCachedValue("Points").getInt());
        owner.retire(0, TimeUnit.NANOSECONDS, () -> {}, () -> {});
    }
}
