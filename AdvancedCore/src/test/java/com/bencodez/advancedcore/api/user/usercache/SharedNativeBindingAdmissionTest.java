package com.bencodez.advancedcore.api.user.usercache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class SharedNativeBindingAdmissionTest {
    @Test void busyBindingFailsWithoutSealingExistingNativeAdmission() {
        UserStorageOwnership owner = new UserStorageOwnership();
        try (UserStorageOwnership.Scope work = owner.admit()) {
            assertThrows(IllegalStateException.class, owner::beginSharedBinding);
            try (UserStorageOwnership.Scope nested = owner.admit()) { }
        }
        try (UserStorageOwnership.Binding binding = owner.beginSharedBinding()) {
            assertThrows(IllegalStateException.class, owner::admit);
            assertThrows(IllegalStateException.class, owner::beginSharedBinding);
        }
        try (UserStorageOwnership.Scope work = owner.admit()) { }
    }

    @Test void bindingIsThreadOwnedAndReopensAdmissionOnlyWhenReleased() throws Exception {
        UserStorageOwnership owner = new UserStorageOwnership();
        UserStorageOwnership.Binding binding = owner.beginSharedBinding();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                assertThrows(IllegalStateException.class, binding::close);
                assertThrows(IllegalStateException.class, owner::admit);
                assertThrows(IllegalStateException.class,
                        () -> owner.retire(1, TimeUnit.SECONDS, () -> {}, () -> {}));
            }).get(5, TimeUnit.SECONDS);
            binding.close();
            binding.close();
            try (UserStorageOwnership.Scope work = owner.admit()) { }
        } finally {
            binding.close();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void bindingComposesProviderPublicationWithoutReopeningItsOuterRetirement() {
        UserStorageOwnership owner = new UserStorageOwnership();
        owner.replace(1, TimeUnit.SECONDS, () -> {}, () -> {
            try (UserStorageOwnership.Binding binding = owner.beginSharedBinding()) {
                assertThrows(IllegalStateException.class, owner::admit);
            }
            assertThrows(IllegalStateException.class, owner::admit);
        });
        try (UserStorageOwnership.Scope work = owner.admit()) { }
        owner.retire(1, TimeUnit.SECONDS, () -> {}, () -> {});
        assertThrows(IllegalStateException.class, owner::beginSharedBinding);
    }
}
