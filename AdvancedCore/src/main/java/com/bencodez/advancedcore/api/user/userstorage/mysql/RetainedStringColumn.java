package com.bencodez.advancedcore.api.user.userstorage.mysql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Locale;
import java.util.Objects;

/** Checked legacy numeric-to-string reconciliation on the caller's borrowed connection. */
final class RetainedStringColumn {
    private RetainedStringColumn() {}

    static void reconcile(Connection connection, String table, String name, String declaration, DiscardConnection discard) throws SQLException {
        String target = physicalType(declaration);
        // String APIs can intentionally declare numeric SQL storage. Do not turn
        // those declarations into TEXT solely because their Java values are strings.
        if (!isTextDeclaration(target)) return;
        String stored = numericColumn(connection, table, name);
        if (stored == null) return;
        Attributes before = attributes(connection, table, stored);
        before.validate();
        // The peer may have completed its migration between the two observations.
        if (numericColumn(connection, table, stored) == null) {
            Attributes after = attributes(connection, table, stored);
            if (!after.type.equalsIgnoreCase(target) || !before.sameRetainedAttributes(after))
                throw new SQLException("Retained column changed during migration inspection");
            return;
        }
        String sql = "ALTER TABLE " + quote(table) + " MODIFY COLUMN " + quote(stored) + " " + target
            + (before.nullable ? " NULL" : " NOT NULL")
            + (before.defaultValue == null ? "" : " DEFAULT '" + literal(before.defaultValue) + "'")
            + (before.comment.isEmpty() ? "" : " COMMENT '" + literal(before.comment) + "'") + ";";
        try (StrictSqlMode mode = StrictSqlMode.open(connection, discard)) {
            boolean executed = false;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.executeUpdate();
                executed = true;
            } catch (SQLException failure) {
                // Resource-cleanup failures cannot be interpreted as successful DDL.
                if (executed || failure.getSuppressed().length != 0) throw failure;
                try {
                    Attributes after = attributes(connection, table, stored);
                    if (!after.type.equalsIgnoreCase(target) || !before.sameRetainedAttributes(after)) throw failure;
                } catch (SQLException inspection) {
                    if (inspection != failure) failure.addSuppressed(inspection);
                    throw failure;
                }
            }
        }
    }

    private static String numericColumn(Connection connection, String table, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + quote(table) + " WHERE 1=0");
                ResultSet rows = statement.executeQuery()) {
            if (rows == null) throw new SQLException("Retained SQL type result unavailable");
            ResultSetMetaData metadata = rows.getMetaData();
            if (metadata == null) throw new SQLException("Retained SQL type metadata unavailable");
            int count = metadata.getColumnCount();
            if (count <= 0) throw new SQLException("Retained SQL type metadata unavailable");
            for (int i = 1; i <= count; i++) {
                String stored = metadata.getColumnName(i);
                if (stored == null || stored.isEmpty()) throw new SQLException("Retained SQL column identity unavailable");
                if (stored.equalsIgnoreCase(name)) {
                    int type = metadata.getColumnType(i);
                    if (type == Types.NULL || type == 0) throw new SQLException("Retained SQL column type unavailable");
                    return numeric(type) ? stored : null;
                }
            }
            throw new SQLException("Registered column disappeared during type inspection");
        }
    }

    private static Attributes attributes(Connection connection, String table, String name) throws SQLException {
        String sql = "SELECT IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT, COLUMN_TYPE "
            + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table); statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows == null || !rows.next()) throw new SQLException("Retained SQL column attributes unavailable");
                String nullable = rows.getString(1), value = rows.getString(2), extra = rows.getString(3),
                    comment = rows.getString(4), type = rows.getString(5);
                if (rows.next() || (!"YES".equalsIgnoreCase(nullable) && !"NO".equalsIgnoreCase(nullable))
                        || extra == null || comment == null || type == null || type.isEmpty())
                    throw new SQLException("Retained SQL column attributes ambiguous or incomplete");
                return new Attributes("YES".equalsIgnoreCase(nullable), normalizeDefault(value), extra, comment, type);
            }
        }
    }

    private static String normalizeDefault(String value) {
        if (value == null || value.trim().equalsIgnoreCase("NULL")) return null;
        value = value.trim();
        if (value.length() >= 2 && value.charAt(0) == '\'' && value.charAt(value.length()-1) == '\'')
            value = value.substring(1, value.length()-1).replace("''", "'");
        return value;
    }

    private static String physicalType(String declaration) {
        return declaration.trim().replaceFirst(
            "(?i)\\s+(?=DEFAULT\\b|NOT\\s+NULL\\b|NULL\\b|PRIMARY\\s+KEY\\b|UNIQUE\\b|COMMENT\\b|REFERENCES\\b|CHECK\\b).*$", "");
    }
    private static boolean isTextDeclaration(String type) {
        return type.toUpperCase(Locale.ROOT).matches("(?:TINYTEXT|TEXT|MEDIUMTEXT|LONGTEXT|(?:VAR)?CHAR\\s*\\(\\s*\\d+\\s*\\))");
    }
    private static boolean numeric(int type) {
        return type == Types.TINYINT || type == Types.SMALLINT || type == Types.INTEGER || type == Types.BIGINT
            || type == Types.REAL || type == Types.FLOAT || type == Types.DOUBLE || type == Types.NUMERIC
            || type == Types.DECIMAL || type == Types.BOOLEAN || type == Types.BIT;
    }
    private static String quote(String value) { return "`" + value.replace("`", "``") + "`"; }
    private static String literal(String value) { return value.replace("'", "''"); }

    interface DiscardConnection { void discard(Connection connection) throws SQLException; }

    /** Temporary connection-local mode; restore before the borrowed connection is returned. */
    private static final class StrictSqlMode implements AutoCloseable {
        private final Connection connection;
        private final String original;
        private final DiscardConnection discard;
        private StrictSqlMode(Connection connection, String original, DiscardConnection discard) {
            this.connection = connection; this.original = original; this.discard = discard;
        }
        static StrictSqlMode open(Connection connection, DiscardConnection discard) throws SQLException {
            String original;
            try (PreparedStatement statement = connection.prepareStatement("SELECT @@SESSION.sql_mode");
                    ResultSet rows = statement.executeQuery()) {
                if (rows == null || !rows.next()) throw new SQLException("SQL session mode unavailable");
                original = rows.getString(1);
                if (original == null || original.length() > 4096
                        || !original.matches("[A-Za-z0-9_]*(?:,[A-Za-z0-9_]+)*") || rows.next())
                    throw new SQLException("SQL session mode evidence invalid");
            }
            StrictSqlMode scope = new StrictSqlMode(connection, original, discard);
            try {
                scope.set(original.isEmpty() ? "STRICT_ALL_TABLES" : original + ",STRICT_ALL_TABLES");
                return scope;
            } catch (SQLException failure) {
                try { scope.close(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        private void set(String mode) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("SET SESSION sql_mode=?")) {
                statement.setString(1, mode); statement.executeUpdate();
            }
        }
        @Override public void close() throws SQLException {
            try { set(original); }
            catch (SQLException failure) {
                // Hikari does not reset SQL session variables on pool return.
                // Discard this physical connection, never the borrowed pool.
                try {
                    discard.discard(connection);
                } catch (SQLException | RuntimeException invalidation) { failure.addSuppressed(invalidation); }
                throw failure;
            }
        }
    }

    private static final class Attributes {
        final boolean nullable;
        final String defaultValue, extra, comment, type;
        Attributes(boolean nullable, String defaultValue, String extra, String comment, String type) {
            this.nullable=nullable; this.defaultValue=defaultValue; this.extra=extra; this.comment=comment; this.type=type;
        }
        void validate() throws SQLException {
            if (!extra.trim().isEmpty() || comment.indexOf('\\') >= 0 ||
                    defaultValue != null && !defaultValue.matches("(?i)(?:true|false|[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:e[-+]?\\d+)?)"))
                throw new SQLException("Cannot safely preserve retained SQL column attributes");
        }
        boolean sameRetainedAttributes(Attributes other) {
            return nullable == other.nullable && Objects.equals(defaultValue, other.defaultValue)
                && extra.equals(other.extra) && comment.equals(other.comment);
        }
    }
}
