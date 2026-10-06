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
        RetainedNumericType numericTarget = RetainedNumericType.parse(target);
        if (!isTextDeclaration(target) && numericTarget == null) return;
        String stored = numericColumn(connection, table, name);
        if (stored == null) return;
        Attributes before = attributes(connection, table, stored);
        if (numericTarget != null && numericTarget.matches(before.type)) return;
        if (numericTarget != null && numericTarget.needsExactValueProof(before.type)) {
            if(numericTarget.exactDecimalCast(before.type)==null)
                throw new SQLException("Numeric declaration change requires exact-value migration validation");
            try(RetainedDecimalMigration fence=RetainedDecimalMigration.open(connection,table,discard)) {
                // A peer may have altered the source before the WRITE lock was granted.
                Attributes owned=attributes(connection,table,stored);
                owned.validate();
                if(numericTarget.matches(owned.type)) return;
                String cast=numericTarget.exactDecimalCast(owned.type);
                if(cast==null) throw new SQLException("Retained decimal source changed before migration ownership");
                exactDecimalValues(connection,table,stored,cast,owned.defaultValue);
                migrate(connection,table,stored,target,numericTarget,owned,discard,false);
            }
            return;
        }
        migrate(connection,table,stored,target,numericTarget,before,discard,true);
    }

    private static void exactDecimalValues(Connection connection,String table,String stored,String cast,String defaultValue) throws SQLException {
        String sql="SELECT 1 FROM "+quote(table)+" WHERE "+quote(stored)+" IS NOT NULL AND "
            +quote(stored)+" <> CAST("+quote(stored)+" AS "+cast+") LIMIT 1";
        try(PreparedStatement statement=connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            try(ResultSet rows=statement.executeQuery()) {
                if(rows==null)throw new SQLException("Retained decimal value evidence unavailable");
                if(rows.next())throw new SQLException("Retained decimal values would be rounded");
            }
        }
        if(defaultValue!=null) {
            try(PreparedStatement statement=connection.prepareStatement("SELECT CAST(? AS "+cast+")")) {
                statement.setQueryTimeout(5);statement.setString(1,defaultValue);
                try(ResultSet rows=statement.executeQuery()) {
                    if(rows==null || !rows.next())throw new SQLException("Retained decimal default evidence unavailable");
                    java.math.BigDecimal converted=rows.getBigDecimal(1);
                    java.math.BigDecimal original;
                    try {original=new java.math.BigDecimal(defaultValue);}
                    catch(NumberFormatException invalid){throw new SQLException("Retained decimal default is not numeric",invalid);}
                    if(converted==null || converted.compareTo(original)!=0 || rows.next())
                        throw new SQLException("Retained decimal default would be rounded");
                }
            }
        }
    }

    private static void migrate(Connection connection,String table,String stored,String target,
            RetainedNumericType numericTarget,Attributes before,DiscardConnection discard,boolean permitPeerCompletion) throws SQLException {
        before.validate();
        // Recheck numeric declarations too: a peer can complete INT-to-BIGINT
        // while the column remains numeric throughout both observations.
        String remainingNumeric = numericColumn(connection, table, stored);
        if (remainingNumeric == null || numericTarget != null) {
            Attributes after = attributes(connection, table, stored);
            if (!before.sameRetainedAttributes(after))
                throw new SQLException("Retained column attributes changed during migration inspection");
            if (targetMatches(target, numericTarget, after.type)) return;
            if (remainingNumeric == null)
                throw new SQLException("Retained column changed during migration inspection");
        }
        String sql = "ALTER TABLE " + quote(table) + " MODIFY COLUMN " + quote(stored) + " " + target
            + (before.nullable ? " NULL" : " NOT NULL")
            + (before.defaultValue == null ? "" : " DEFAULT '" + literal(before.defaultValue) + "'")
            + (before.comment.isEmpty() ? "" : " COMMENT '" + literal(before.comment) + "'") + ";";
        try (StrictSqlMode mode = StrictSqlMode.open(connection, discard)) {
            boolean executed = false;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(5);
                statement.executeUpdate();
                executed = true;
            } catch (SQLException failure) {
                // Resource-cleanup failures cannot be interpreted as successful DDL.
                if (!permitPeerCompletion || executed || failure.getSuppressed().length != 0) throw failure;
                try {
                    Attributes after = attributes(connection, table, stored);
                    if (!targetMatches(target, numericTarget, after.type) || !before.sameRetainedAttributes(after)) throw failure;
                } catch (SQLException inspection) {
                    if (inspection != failure) failure.addSuppressed(inspection);
                    throw failure;
                }
            }
        }
    }

    private static boolean targetMatches(String target, RetainedNumericType numeric, String actual) {
        return numeric == null ? target.equalsIgnoreCase(actual) : numeric.matches(actual);
    }

    private static String numericColumn(Connection connection, String table, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + quote(table) + " WHERE 1=0")) {
            statement.setQueryTimeout(5);
            try (ResultSet rows = statement.executeQuery()) {
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
    }

    private static Attributes attributes(Connection connection, String table, String name) throws SQLException {
        String sql = "SELECT IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT, COLUMN_TYPE "
            + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
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
            try (PreparedStatement statement = connection.prepareStatement("SELECT @@SESSION.sql_mode")) {
                statement.setQueryTimeout(5);
                try (ResultSet rows = statement.executeQuery()) {
                if (rows == null || !rows.next()) throw new SQLException("SQL session mode unavailable");
                original = rows.getString(1);
                if (original == null || original.length() > 4096
                        || !original.matches("[A-Za-z0-9_]*(?:,[A-Za-z0-9_]+)*") || rows.next())
                    throw new SQLException("SQL session mode evidence invalid");
                }
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
                statement.setQueryTimeout(5);statement.setString(1, mode); statement.executeUpdate();
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
        private static boolean sameDefault(String first, String second) {
            if (Objects.equals(first,second)) return true;
            if (first==null || second==null) return false;
            try { return new java.math.BigDecimal(first).compareTo(new java.math.BigDecimal(second))==0; }
            catch (NumberFormatException invalid) { return false; }
        }
        boolean sameRetainedAttributes(Attributes other) {
            return nullable == other.nullable && sameDefault(defaultValue, other.defaultValue)
                && extra.equals(other.extra) && comment.equals(other.comment);
        }
    }
}
