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

    /**
     * Checked adapter for a legacy SimpleAPI MySQL/MariaDB pool. A closed pool
     * is unavailable; this path never uses getConnection()'s reopen/null fallback.
     * The platform still owns lifecycle admission, schema and native cache fencing.
     */
    public static SqlUserStorage existingMysqlUser(UUID uuid, String tableName, SqlUserSchema schema,
            com.bencodez.simpleapi.sql.mysql.MySQL mysql, DatabaseType databaseType, SqlBackendLogger logger) {
        Objects.requireNonNull(mysql, "mysql");
        Objects.requireNonNull(databaseType, "databaseType");
        if (databaseType == DatabaseType.POSTGRESQL) {
            throw new IllegalArgumentException("Legacy MySQL provider cannot select PostgreSQL; supply an explicit connection opener");
        }
        return existingUser(UserStorage.MYSQL, uuid, tableName, schema,
                () -> openLegacyMysqlConnection(mysql), databaseType, logger);
    }

    private static Connection openLegacyMysqlConnection(com.bencodez.simpleapi.sql.mysql.MySQL mysql)
            throws SQLException {
        com.bencodez.simpleapi.sql.mysql.ConnectionManager manager = mysql.getConnectionManager();
        if (manager == null) throw new SQLException("MySQL connection manager is unavailable");
        if (manager.isClosed()) throw new SQLException("MySQL connection pool is unavailable");
        // The legacy method's concrete return type is Java 11 in the compile
        // dependency. Packaging replaces it with the verified Java 8 pool.
        // Reflect only this fixed public getter; use the standard JDBC contract.
        Object value;
        try {
            value = com.bencodez.simpleapi.sql.mysql.ConnectionManager.class
                    .getMethod("getDataSource").invoke(manager);
        } catch (ReflectiveOperationException accessFailure) {
            throw new SQLException("Cannot access legacy MySQL connection pool", accessFailure);
        }
        if (!(value instanceof javax.sql.DataSource)) throw new SQLException("MySQL connection pool is unavailable");
        Connection connection = ((javax.sql.DataSource) value).getConnection();
        if (connection == null) throw new SQLException("MySQL connection is unavailable");
        return connection;
    }

}
