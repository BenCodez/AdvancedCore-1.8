package com.bencodez.advancedcore.api.user.usercache;

import com.bencodez.simpleapi.sql.data.DataValue;

/** A checked mutation committed physically, but a post-commit notification failed. */
public final class CommittedUserDataMutationException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final DataValue committedValue;

    CommittedUserDataMutationException(DataValue committedValue, Throwable failure) {
        super("User data mutation committed; post-commit notification failed", failure);
        this.committedValue = committedValue;
    }

    /** The acknowledged value; retry only notification work, never this write. */
    public DataValue getCommittedValue() {
        return committedValue;
    }
}
