package com.bencodez.advancedcore.api.user.usercache;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded plugin-local ownership shared by cached and uncached identities. */
public final class UserStorageOwnership {
    private final Slot[] slots = new Slot[64];

    public UserStorageOwnership() {
        for (int i = 0; i < slots.length; i++) slots[i] = new Slot();
    }

    public Slot owner(UUID identity) {
        return slots[Objects.requireNonNull(identity, "identity").hashCode() & (slots.length - 1)];
    }

    public static final class Slot {
        private final ReentrantLock lock = new ReentrantLock(true);
        private final AtomicLong revision = new AtomicLong();
        private boolean writing;

        private Slot() {}
        public ReentrantLock getLock() { return lock; }
        public long getRevision() { return revision.get(); }
        public boolean isWriting() {
            if (!lock.isHeldByCurrentThread()) throw new IllegalStateException("Storage read is not owned");
            return writing;
        }
        public void beginWrite() {
            if (!lock.isHeldByCurrentThread() || writing) throw new IllegalStateException("Recursive storage write");
            writing = true;
            // Even a failed attempt can have an uncertain committed outcome.
            revision.incrementAndGet();
        }
        public void endWrite() {
            if (!lock.isHeldByCurrentThread() || !writing) throw new IllegalStateException("Storage write is not owned");
            writing = false;
        }

    }
}
