package com.bencodez.advancedcore.core.user.storage.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

import com.bencodez.advancedcore.api.user.UserStorage;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKey;
import com.bencodez.advancedcore.core.user.storage.SqlUserStorage;

/**
 * Explicit platform-neutral SQL construction. No native configuration, cache,
 * executor or connection pool is selected or replaced by this factory.
 */
public final class SqlUserBackendFactory {
    private SqlUserBackendFactory() {
    }

    /** Java 8 boundary independent of the DbType API absent from legacy SimpleAPI. */
    public enum DatabaseType { MYSQL, MARIADB, POSTGRESQL }

    public static SqliteUserBackend sqlite(Path dataDirectory, String databaseName, String tableName,
            Collection<? extends UserDataKey> keys, SqlBackendLogger logger) {
        Objects.requireNonNull(keys, "keys");
        return new SqliteUserBackend(dataDirectory, databaseName, tableName, SqlUserSchema.fromKeys(keys), logger);
    }

    @FunctionalInterface
    public interface ConnectionOpener { Connection open() throws SQLException; }

    /**
     * Adapt a platform-owned provider. Each call borrows a fresh usable connection;
     * the caller retains pool and per-user cache ownership. This does not reconcile
     * schema or flush native cached writes. Database type must be explicit for MYSQL.
     */
    public static SqlUserStorage existingUser(UserStorage storage, UUID uuid, String tableName,
            SqlUserSchema schema, ConnectionOpener connections, DatabaseType databaseType, SqlBackendLogger logger) {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(connections, "connections");
        JdbcSqlUserStorage.Dialect dialect;
        if (storage == UserStorage.SQLITE) {
            dialect = JdbcSqlUserStorage.Dialect.SQLITE;
        } else if (storage == UserStorage.MYSQL) {
            switch (Objects.requireNonNull(databaseType, "databaseType")) {
                case MYSQL:
                case MARIADB:
                    dialect = JdbcSqlUserStorage.Dialect.MYSQL;
                    break;
                case POSTGRESQL:
                    dialect = JdbcSqlUserStorage.Dialect.POSTGRESQL;
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported database type: " + databaseType);
            }
        } else {
            throw new IllegalArgumentException("SQL user factory requires MYSQL or SQLITE storage");
        }
        return new JdbcSqlUserStorage(storage, uuid, tableName, schema, connections::open, dialect, logger);
    }
}
