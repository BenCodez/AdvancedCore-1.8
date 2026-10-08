package com.bencodez.advancedcore.api.user.usercache;

/** Removal committed; failure of an older-prefix notification must not retry it. */
public final class CommittedUserDataRemovalException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    CommittedUserDataRemovalException(Throwable failure) {
        super("User removal committed; queued-prefix notification failed", failure);
    }
}
