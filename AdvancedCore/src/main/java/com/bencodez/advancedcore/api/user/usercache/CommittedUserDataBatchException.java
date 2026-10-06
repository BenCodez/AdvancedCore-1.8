package com.bencodez.advancedcore.api.user.usercache;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import com.bencodez.simpleapi.sql.data.DataValue;

/** A bulk write committed, but notification of its older queued prefix failed. */
public final class CommittedUserDataBatchException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final Map<String, DataValue> committedValues;

    CommittedUserDataBatchException(Map<String, DataValue> values, Throwable failure) {
        super("User data batch committed; queued-prefix notification failed", failure);
        committedValues = Collections.unmodifiableMap(new HashMap<>(values));
    }

    public Map<String, DataValue> getCommittedValues() { return committedValues; }
}
