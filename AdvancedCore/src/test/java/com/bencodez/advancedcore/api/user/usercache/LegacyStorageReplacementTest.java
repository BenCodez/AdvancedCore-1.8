package com.bencodez.advancedcore.api.user.usercache;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class LegacyStorageReplacementTest {
    @Test void publicationPermissionIsThreadLocalAndUnavailableDuringFlushOrAfterFailure() throws Exception {
        UserStorageOwnership owner=new UserStorageOwnership();ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            IllegalStateException failure=new IllegalStateException("publish failed");
            assertSame(failure,assertThrows(IllegalStateException.class,() -> owner.replace(0,TimeUnit.NANOSECONDS,
                () -> assertFalse(owner.isReplacingOnCurrentThread()),() -> {
                    assertTrue(owner.isReplacingOnCurrentThread());
                    try {assertFalse(worker.submit(owner::isReplacingOnCurrentThread).get(2,TimeUnit.SECONDS));}catch(Exception unexpected){throw new AssertionError(unexpected);}
                    throw failure;
                })));
            assertFalse(owner.isReplacingOnCurrentThread());
            owner.replace(0,TimeUnit.NANOSECONDS,() -> {},() -> assertTrue(owner.isReplacingOnCurrentThread()));
            assertFalse(owner.isReplacingOnCurrentThread());
        }finally{worker.shutdownNow();assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void successfulReplacementReopensTheExistingOwnerWithoutResettingUuidRevisions() {
        UserStorageOwnership owner=new UserStorageOwnership();java.util.UUID id=java.util.UUID.randomUUID();
        UserStorageOwnership.Slot slot=owner.owner(id);slot.getLock().lock();try {slot.beginWrite();slot.endWrite();}finally {slot.getLock().unlock();}
        owner.replace(0,TimeUnit.NANOSECONDS,() -> {try(UserStorageOwnership.Scope flush=owner.admit()){}},() -> assertThrows(IllegalStateException.class,owner::admit));
        assertSame(slot,owner.owner(id));assertEquals(1,slot.getRevision());try(UserStorageOwnership.Scope next=owner.admit()){}
    }
    @Test void failedFlushAndFailedPublicationRemainSealedUntilExplicitSuccessfulRetry() {
        for(boolean flushFailure:new boolean[]{false,true}) {
            UserStorageOwnership owner=new UserStorageOwnership();IllegalStateException failure=new IllegalStateException("fixture");AtomicInteger published=new AtomicInteger();
            assertSame(failure,assertThrows(IllegalStateException.class,() -> owner.replace(0,TimeUnit.NANOSECONDS,
                () -> {if(flushFailure)throw failure;},() -> {published.incrementAndGet();throw failure;})));
            assertEquals(flushFailure?0:1,published.get());assertThrows(IllegalStateException.class,owner::admit);
            owner.replace(0,TimeUnit.NANOSECONDS,() -> {},published::incrementAndGet);try(UserStorageOwnership.Scope next=owner.admit()){}
        }
    }
    @Test void acceptedQueuedWorkCanContinueAfterSealButCannotBeForgottenByReplacement() {
        UserStorageOwnership owner=new UserStorageOwnership();AtomicReference<Runnable> queued=new AtomicReference<>();AtomicBoolean written=new AtomicBoolean();
        owner.submit(queued::set,() -> {try(UserStorageOwnership.Scope nested=owner.admit()){written.set(true);}});
        assertThrows(IllegalStateException.class,() -> owner.replace(0,TimeUnit.NANOSECONDS,() -> fail("pending"),() -> fail("pending")));
        assertThrows(IllegalStateException.class,owner::admit);queued.get().run();assertTrue(written.get());
        owner.replace(0,TimeUnit.NANOSECONDS,() -> {},() -> {});try(UserStorageOwnership.Scope next=owner.admit()){}
    }
    @Test void finalRetirementCannotBeReopenedEvenIfItsDrainFailed() {
        for(boolean pending:new boolean[]{false,true}) {
            UserStorageOwnership owner=new UserStorageOwnership();AtomicReference<Runnable> queued=new AtomicReference<>();
            if(pending){owner.submit(queued::set,() -> {});assertThrows(IllegalStateException.class,() -> owner.retire(0,TimeUnit.NANOSECONDS,() -> {},() -> {}));}
            else owner.retire(0,TimeUnit.NANOSECONDS,() -> {},() -> {});
            assertThrows(IllegalStateException.class,() -> owner.replace(0,TimeUnit.NANOSECONDS,() -> fail("final"),() -> fail("final")));
            if(pending){queued.get().run();owner.retire(0,TimeUnit.NANOSECONDS,() -> {},() -> {});}
            assertThrows(IllegalStateException.class,owner::admit);
        }
    }
    @Test void selfAndCompetingTransitionsAreRejectedBeforeTheirCallbacksRun() {
        UserStorageOwnership owner=new UserStorageOwnership();
        try(UserStorageOwnership.Scope admitted=owner.admit()){assertThrows(IllegalStateException.class,() -> owner.replace(0,TimeUnit.NANOSECONDS,() -> fail("self"),() -> fail("self")));}
        owner.replace(0,TimeUnit.NANOSECONDS,() -> {
            assertThrows(IllegalStateException.class,() -> owner.replace(0,TimeUnit.NANOSECONDS,() -> fail("competing"),() -> fail("competing")));
            assertThrows(IllegalStateException.class,() -> owner.retire(0,TimeUnit.NANOSECONDS,() -> fail("competing"),() -> fail("competing")));
            assertThrows(IllegalStateException.class,() -> owner.submit(Runnable::run,() -> fail("async flush")));
        },() -> {});
        try(UserStorageOwnership.Scope next=owner.admit()){}
    }
    @Test void interruptedReplacementKeepsAcceptedWorkAndPreservesInterruptForRetry() {
        UserStorageOwnership owner=new UserStorageOwnership();AtomicReference<Runnable> queued=new AtomicReference<>();owner.submit(queued::set,() -> {});
        Thread.currentThread().interrupt();try {
            IllegalStateException failure=assertThrows(IllegalStateException.class,() -> owner.replace(1,TimeUnit.SECONDS,() -> fail("pending"),() -> fail("pending")));
            assertTrue(failure.getCause() instanceof InterruptedException);assertTrue(Thread.currentThread().isInterrupted());
        }finally{Thread.interrupted();}
        queued.get().run();owner.replace(0,TimeUnit.NANOSECONDS,() -> {},() -> {});try(UserStorageOwnership.Scope next=owner.admit()){}
    }
}
