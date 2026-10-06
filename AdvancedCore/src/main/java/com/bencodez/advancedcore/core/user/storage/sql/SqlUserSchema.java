package com.bencodez.advancedcore.core.user.storage.sql;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKey;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKeyBoolean;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKeyInt;
import com.bencodez.simpleapi.sql.DataType;

public final class SqlUserSchema {
    public static final String UUID_COLUMN = "uuid";

    public static final class ColumnDefinition {
        private final String name;
        private final String sqlType;
        private final DataType dataType;

        public ColumnDefinition(String name, String sqlType, DataType dataType) {
            this.name = Objects.requireNonNull(name, "name");
            this.sqlType = Objects.requireNonNull(sqlType, "sqlType");
            this.dataType = Objects.requireNonNull(dataType, "dataType");
            if (blank(name)) throw new IllegalArgumentException("Column name cannot be blank");
            if (blank(sqlType)) throw new IllegalArgumentException("SQL type cannot be blank");
        }

        public String name() { return name; }
        public String sqlType() { return sqlType; }
        public DataType dataType() { return dataType; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ColumnDefinition)) return false;
            ColumnDefinition column = (ColumnDefinition) other;
            return name.equals(column.name) && sqlType.equals(column.sqlType) && dataType == column.dataType;
        }

        @Override public int hashCode() {
            return 31 * (31 * name.hashCode() + sqlType.hashCode()) + dataType.hashCode();
        }

        @Override public String toString() {
            return "ColumnDefinition[name=" + name + ", sqlType=" + sqlType + ", dataType=" + dataType + "]";
        }

        private static boolean blank(String value) {
            for (int offset = 0; offset < value.length();) {
                int point = value.codePointAt(offset);
                if (!Character.isWhitespace(point)) return false;
                offset += Character.charCount(point);
            }
            return true;
        }
    }

    private final Map<String, ColumnDefinition> columnsByLowerName;

    private SqlUserSchema(Map<String, ColumnDefinition> columnsByLowerName) {
        this.columnsByLowerName = Collections.unmodifiableMap(new LinkedHashMap<>(columnsByLowerName));
    }

    public static Builder builder() { return new Builder(); }

    public static SqlUserSchema fromKeys(Collection<? extends UserDataKey> keys) {
        Builder builder = builder();
        // Detach membership before extension metadata callbacks can register another key.
        for (UserDataKey key : new ArrayList<UserDataKey>(Objects.requireNonNull(keys, "keys"))) {
            DataType type = DataType.STRING;
            if (key instanceof UserDataKeyInt) type = DataType.INTEGER;
            else if (key instanceof UserDataKeyBoolean) type = DataType.BOOLEAN;
            builder.column(key.getKey(), key.getColumnType(), type);
        }
        return builder.build();
    }

    public List<ColumnDefinition> columns() { return new ArrayList<>(columnsByLowerName.values()); }

    public ColumnDefinition column(String name) {
        if (name == null) return null;
        return columnsByLowerName.get(name.toLowerCase(Locale.ROOT));
    }

    public boolean contains(String name) { return column(name) != null; }

    public static final class Builder {
        private final Map<String, ColumnDefinition> columns = new LinkedHashMap<>();

        private Builder() {
            columns.put(UUID_COLUMN, new ColumnDefinition(UUID_COLUMN, "VARCHAR(37)", DataType.STRING));
        }

        public Builder column(String name, String sqlType, DataType dataType) {
            Objects.requireNonNull(name, "name");
            if (UUID_COLUMN.equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("Column name 'uuid' is reserved for user identity");
            }
            String canonical = name.toLowerCase(Locale.ROOT);
            ColumnDefinition existing = columns.get(canonical);
            if (existing != null) {
                throw new IllegalArgumentException("Duplicate SQL column name: " + name + " conflicts with " + existing.name());
            }
            columns.put(canonical, new ColumnDefinition(name, sqlType, dataType));
            return this;
        }

        public SqlUserSchema build() { return new SqlUserSchema(columns); }
    }
}
