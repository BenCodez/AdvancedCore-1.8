package com.bencodez.advancedcore.api.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import com.bencodez.advancedcore.api.user.usercache.CommittedUserDataMutationException;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.api.user.usercache.change.UserDataChangeString;
import com.bencodez.simpleapi.sql.data.*;

class LegacyCheckedQueueMutationTest {
    private LegacyDirectUserDataTest.Fixture fixture() {
        LegacyDirectUserDataTest.Fixture f = new LegacyDirectUserDataTest.Fixture();
        HashMap<String, DataValue> values = new HashMap<>();
        values.put("OfflineRewards", new DataValueString("retained"));
        f.cache.updateCache(values);
        return f;
    }

    @Test void concurrentAppendsReadTheCommittedPredecessorUnderOneOwner() throws Exception {
        LegacyDirectUserDataTest.Fixture f = fixture();
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1), attempting = new CountDownLatch(1);
        AtomicBoolean secondTransformed = new AtomicBoolean();
        List<String> writes = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<DataValue> first = executor.submit(() -> f.cache.mutateDirect("OfflineRewards",
                before -> new DataValueString(before.getString() + "%line%first"), value -> {
                    writing.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                    writes.add(value.getString());
                }));
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            Future<DataValue> second = executor.submit(() -> {
                attempting.countDown();
                return f.cache.mutateDirect("OfflineRewards", before -> {
                    secondTransformed.set(true);
                    return new DataValueString(before.getString() + "%line%second");
                }, value -> writes.add(value.getString()));
            });
            assertTrue(attempting.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
            assertFalse(secondTransformed.get());
            assertEquals("retained", f.cache.getCachedValue("OfflineRewards").getString());
            release.countDown();
            assertEquals("retained%line%first", first.get(5, TimeUnit.SECONDS).getString());
            assertEquals("retained%line%first%line%second", second.get(5, TimeUnit.SECONDS).getString());
            assertEquals(Arrays.asList("retained%line%first", "retained%line%first%line%second"), writes);
            assertEquals(writes.get(1), f.cache.getCachedValue("OfflineRewards").getString());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void queuedPredecessorIsFlushedBeforeTransformAndLaterQueuedValueRemainsVisible() throws Exception {
        LegacyDirectUserDataTest.Fixture f = fixture();
        f.cache.addChange(new UserDataChangeString("OfflineRewards", "queued"), true);
        DataValue committed = f.cache.mutateDirect("OfflineRewards", before -> {
            try { verify(f.mysql).updateStrict(anyString(), anyList()); }
            catch (java.sql.SQLException impossible) { throw new AssertionError(impossible); }
            assertEquals("queued", before.getString());
            return new DataValueString(before.getString() + "%line%new");
        }, value -> f.cache.addChange(new UserDataChangeString("OfflineRewards", "later replacement"), true));
        assertEquals("queued%line%new", committed.getString());
        assertEquals("later replacement", f.cache.getCachedValue("OfflineRewards").getString());
        assertTrue(f.cache.hasChangesToProcess());
    }

    @Test void failedWriteOrUnknownValueDoesNotPublishAnEmptyQueue() {
        LegacyDirectUserDataTest.Fixture f = fixture();
        assertThrows(IllegalStateException.class, () -> f.cache.mutateDirect("OfflineRewards",
            before -> new DataValueString(before.getString() + "%line%new"), value -> { throw new IllegalStateException("offline"); }));
        assertEquals("retained", f.cache.getCachedValue("OfflineRewards").getString());
        AtomicBoolean written = new AtomicBoolean();
        assertThrows(IllegalStateException.class, () -> f.cache.mutateDirect("Unavailable", before -> {
            if (before == null) throw new IllegalStateException("queue evidence unavailable");
            return before;
        }, value -> written.set(true)));
        assertFalse(written.get());
        assertNull(f.cache.getCachedValue("Unavailable"));
        verify(f.users, never()).onChange(any(), any());
    }

    @Test void legacyDirectReplacementStillAllowsNullButCheckedMutationRejectsIt() {
        LegacyDirectUserDataTest.Fixture f = fixture();
        AtomicBoolean written = new AtomicBoolean();
        assertThrows(NullPointerException.class, () -> f.cache.mutateDirect("OfflineRewards", before -> null, value -> written.set(true)));
        assertFalse(written.get());
        assertEquals("retained", f.cache.getCachedValue("OfflineRewards").getString());
        f.cache.writeDirect("OfflineRewards", null, () -> written.set(true));
        assertTrue(written.get());
        assertTrue(f.cache.isCached("OfflineRewards"));
        assertNull(f.cache.getCachedValue("OfflineRewards"));
    }

    @Test void notificationFailureCarriesTheCommittedValueWithoutRetryingTheWrite() {
        LegacyDirectUserDataTest.Fixture f = fixture();
        IllegalStateException callbackFailure = new IllegalStateException("listener failed");
        doThrow(callbackFailure).when(f.users).onChange(eq(f.user), any(String[].class));
        AtomicInteger writes = new AtomicInteger();
        CommittedUserDataMutationException result = assertThrows(CommittedUserDataMutationException.class,
            () -> f.cache.mutateDirect("OfflineRewards", before -> new DataValueString(before.getString() + "%line%new"),
                value -> writes.incrementAndGet()));
        assertEquals("retained%line%new", result.getCommittedValue().getString());
        assertSame(callbackFailure, result.getCause());
        assertEquals(1, writes.get());
        assertEquals(result.getCommittedValue().getString(), f.cache.getCachedValue("OfflineRewards").getString());
        assertFalse(f.cache.hasChangesToProcess());
    }

    @Test void transformCannotRetireOrRecursivelyMutateItsOwnCache() {
        LegacyDirectUserDataTest.Fixture f = fixture();
        f.cache.mutateDirect("OfflineRewards", before -> {
            assertThrows(IllegalStateException.class, f.cache::dump);
            assertThrows(IllegalStateException.class, () -> f.cache.mutateDirect("OfflineRewards", value -> value, value -> fail("recursive write")));
            return before;
        }, value -> {});
        assertEquals("retained", f.cache.getCachedValue("OfflineRewards").getString());
    }
}
