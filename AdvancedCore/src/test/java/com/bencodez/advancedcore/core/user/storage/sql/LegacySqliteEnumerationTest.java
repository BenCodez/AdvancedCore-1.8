package com.bencodez.advancedcore.core.user.storage.sql;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import com.bencodez.advancedcore.api.user.UserStorage;

@Timeout(30)
class LegacySqliteEnumerationTest {
    @TempDir Path directory;

    private SqliteUserBackend open(SqlBackendLogger logger) {
        return new SqliteUserBackend(directory, "Users", "Users", SqlUserSchema.builder().build(), logger);
    }

    @Test void malformedPageStillAdvancesCursorAndLogsOnlyOnce() throws Exception {
        SqlBackendLogger logger = mock(SqlBackendLogger.class);
        UUID first = UUID.fromString("ffffffff-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("ffffffff-0000-0000-0000-000000000002");
        try (SqliteUserBackend backend = open(logger)) {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + backend.databaseFile());
                    PreparedStatement insert = connection.prepareStatement("INSERT INTO Users(uuid) VALUES (?)")) {
                connection.setAutoCommit(false);
                for (int i = 0; i < 600; i++) {
                    insert.setString(1, "bad-" + i); insert.executeUpdate();
                }
                connection.commit();
            }
            backend.user(first).transaction(UserStorage.SQLITE, scope -> null);
            backend.user(second).transaction(UserStorage.SQLITE, scope -> null);
            List<UUID> streamed = new ArrayList<>(); backend.forEachUser(streamed::add);
            assertEquals(Arrays.asList(first, second), streamed);
            assertEquals(Arrays.asList(first, second), backend.enumerateUsers());
            verify(logger, times(1)).warn(anyString(), any(IllegalArgumentException.class));
        }
    }

    @Test void streamingExceedsMaterializedLimitWhileCompatibilityListRejectsOverflow() throws Exception {
        try (SqliteUserBackend backend = open(SqlBackendLogger.NO_OP)) {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + backend.databaseFile());
                    PreparedStatement insert = connection.prepareStatement("INSERT INTO Users(uuid) VALUES (?)")) {
                connection.setAutoCommit(false);
                for (int i = 0; i <= SqlUserBackend.MAX_MATERIALIZED_USERS; i++) {
                    insert.setString(1, new UUID(0, i).toString()); insert.executeUpdate();
                }
                connection.commit();
            }
            AtomicInteger streamed = new AtomicInteger(); backend.forEachUser(uuid -> streamed.incrementAndGet());
            assertEquals(SqlUserBackend.MAX_MATERIALIZED_USERS + 1, streamed.get());
            IllegalStateException overflow = assertThrows(IllegalStateException.class, backend::enumerateUsers);
            assertTrue(overflow.getMessage().contains("use forEachUser"));
            assertTrue(backend.isOpen());
        }
    }

    @Test void consumerCannotCloseItsOwnAdmittedStorageOperation() {
        try (SqliteUserBackend backend = open(SqlBackendLogger.NO_OP)) {
            UUID uuid = UUID.randomUUID(); backend.user(uuid).transaction(UserStorage.SQLITE, scope -> null);
            backend.forEachUser(found -> {
                assertEquals(uuid, found); assertThrows(IllegalStateException.class, backend::close);
                assertTrue(backend.isOpen());
            });
            assertTrue(backend.user(uuid).contains(UserStorage.SQLITE));
        }
    }
}
