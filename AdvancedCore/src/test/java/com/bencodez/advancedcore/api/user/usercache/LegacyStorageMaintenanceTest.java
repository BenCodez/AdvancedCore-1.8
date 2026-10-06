package com.bencodez.advancedcore.api.user.usercache;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class LegacyStorageMaintenanceTest {
    @Test void synchronousMaintenancePermitsNestedWritesButNotForeignOrAsyncWork() throws Exception {
        UserStorageOwnership owner=new UserStorageOwnership();ExecutorService worker=Executors.newSingleThreadExecutor();
        AtomicInteger flush=new AtomicInteger(),work=new AtomicInteger();
        try {
            owner.maintain(0,TimeUnit.NANOSECONDS,flush::incrementAndGet,() -> {
                work.incrementAndGet();assertTrue(owner.isReplacingOnCurrentThread());
                try(UserStorageOwnership.Scope first=owner.admit();UserStorageOwnership.Scope second=owner.admit()) {}
                assertThrows(IllegalStateException.class,() -> owner.submit(Runnable::run,() -> fail("asynchronous maintenance")));
                assertThrows(IllegalStateException.class,() -> owner.replace(0,TimeUnit.NANOSECONDS,() -> fail("nested"),() -> fail("nested")));
                try {assertTrue(worker.submit(() -> {assertThrows(IllegalStateException.class,owner::admit);return true;}).get(2,TimeUnit.SECONDS));}
                catch(Exception failed){throw new AssertionError(failed);}
            });
            assertEquals(1,flush.get());assertEquals(1,work.get());assertFalse(owner.isReplacingOnCurrentThread());
            try(UserStorageOwnership.Scope normal=owner.admit()) {}
        }finally{worker.shutdownNow();assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void failedMaintenanceStaysSealedAndRemovesThreadPrivileges() {
        UserStorageOwnership owner=new UserStorageOwnership();IllegalStateException failure=new IllegalStateException("copy failed");
        assertSame(failure,assertThrows(IllegalStateException.class,() -> owner.maintain(0,TimeUnit.NANOSECONDS,() -> {},() -> {throw failure;})));
        assertFalse(owner.isReplacingOnCurrentThread());assertThrows(IllegalStateException.class,owner::admit);
        owner.maintain(0,TimeUnit.NANOSECONDS,() -> {},() -> {});try(UserStorageOwnership.Scope retry=owner.admit()) {}
    }
    @Test void queuedWorkMustSettleAndFinalRetirementCannotBeReopened() {
        UserStorageOwnership owner=new UserStorageOwnership();AtomicReference<Runnable> queued=new AtomicReference<>();owner.submit(queued::set,() -> {});
        assertThrows(IllegalStateException.class,() -> owner.maintain(0,TimeUnit.NANOSECONDS,() -> fail("pending"),() -> fail("pending")));
        queued.get().run();owner.maintain(0,TimeUnit.NANOSECONDS,() -> {},() -> {});
        owner.retire(0,TimeUnit.NANOSECONDS,() -> {},() -> {});
        assertThrows(IllegalStateException.class,() -> owner.maintain(0,TimeUnit.NANOSECONDS,() -> fail("final"),() -> fail("final")));
    }
    @Test void leakedSynchronousAdmissionCannotBeForgottenWhenMaintenanceReturns() {
        UserStorageOwnership owner=new UserStorageOwnership();AtomicReference<UserStorageOwnership.Scope> scope=new AtomicReference<>();
        assertThrows(IllegalStateException.class,() -> owner.maintain(0,TimeUnit.NANOSECONDS,() -> {},() -> scope.set(owner.admit())));
        scope.get().close();assertThrows(IllegalStateException.class,owner::admit);
        owner.maintain(0,TimeUnit.NANOSECONDS,() -> {},() -> {});
    }
}
