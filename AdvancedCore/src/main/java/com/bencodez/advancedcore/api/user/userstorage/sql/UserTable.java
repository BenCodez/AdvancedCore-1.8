package com.bencodez.advancedcore.api.user.userstorage.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import com.bencodez.advancedcore.api.user.userstorage.SqlColumnNames;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.messages.PlaceholderUtils;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKey;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKeyInt;
import com.bencodez.simpleapi.sql.Column;
import com.bencodez.simpleapi.sql.DataType;
import com.bencodez.simpleapi.sql.data.DataValueBoolean;
import com.bencodez.simpleapi.sql.data.DataValueInt;
import com.bencodez.simpleapi.sql.data.DataValueString;
import com.bencodez.simpleapi.sql.sqlite.db.SQLite;

public class UserTable extends com.bencodez.simpleapi.sql.sqlite.Table {

	private List<Column> columns = new ArrayList<>();
	private String name;
	private Object object = new Object();

	private Column primaryKey;

	private SQLite sqLite;
	private AdvancedCorePlugin plugin;

	public UserTable(AdvancedCorePlugin plugin, String name, Collection<Column> columns) {
		this.name = name;
		this.columns.addAll(columns);
		primaryKey = this.columns.get(0);
		this.plugin = plugin;
	}

	public UserTable(AdvancedCorePlugin plugin, String name, Collection<Column> columns, Column primaryKey) {
		this.name = name;
		this.primaryKey = primaryKey;
		this.columns.addAll(columns);
		this.plugin = plugin;
	}

	public UserTable(AdvancedCorePlugin plugin, String name, Column... columns) {
		this.name = name;
		for (Column column : columns) {
			this.columns.add(column);
		}
		primaryKey = this.columns.get(0);
		this.plugin = plugin;
	}

	public UserTable(AdvancedCorePlugin plugin, String name, Column primaryKey, Column... columns) {
		this.name = name;
		this.primaryKey = primaryKey;
		for (Column column : columns) {
			this.columns.add(column);
		}
		this.plugin = plugin;
	}

	public void addColoumn(Column column) {
		if (hasColumn(column)) {
			return;
		}
		try {
			String query = "ALTER TABLE " + getName() + " ADD COLUMN `" + column.getName() + "` "
					+ column.getDataType().toString();
			try (PreparedStatement s = sqLite.getSQLConnection().prepareStatement(query)) {
				s.executeUpdate();
			}
			columns.add(column);
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	public void addColoumn(UserDataKey column) {
		if (getTableColumns().contains(column.getKey())) {
			return;
		}
		try {
			String query = "ALTER TABLE " + getName() + " ADD COLUMN `" + column.getKey() + "` " + column.getColumnType();
			try (PreparedStatement s = sqLite.getSQLConnection().prepareStatement(query)) {
				s.executeUpdate();
			}
			if (column instanceof UserDataKeyInt) {
				columns.add(new Column(column.getKey(), DataType.INTEGER));
			} else {
				columns.add(new Column(column.getKey(), DataType.STRING));
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	public void addCustomColumns() {
		// add custom column types
		for (UserDataKey key : plugin.getUserManager().getDataManager().getRegisteredKeysSnapshot()) {
			addColoumn(key);
		}
	}

	public void checkColumn(Column c) {
		if (!hasColumn(c)) {
			Column col = new Column(c.getName(), c.getDataType());
			addColoumn(col);
		}
	}

	public void checkColumns() {
		for (String col : getTableColumns()) {
			boolean has = false;
			for (Column column : columns) {
				if (col != null) {
					if (col.equals(column.getName())) {
						has = true;
					}
				}
			}
			if (!has) {
				columns.add(new Column(col, DataType.STRING));
			}
		}
	}

	public boolean containsKey(String index) {
		String query = "SELECT uuid FROM " + getName();

		try {
			try (PreparedStatement s = sqLite.getSQLConnection().prepareStatement(query);
				ResultSet rs = s.executeQuery()) {
			/*
			 * Query query = new Query(mysql, sql); ResultSet rs = query.executeQuery();
			 */
			while (rs.next()) {
				String str = rs.getString("uuid");
				if (str != null && str.equals(index)) {
					return true;
				}
			}
			}

		} catch (SQLException ex) {
			ex.printStackTrace();
		}
		return false;
	}

	public void copyColumnData(String columnFromName, String columnToName, DataType dataType) {
		checkColumn(new Column(columnToName, dataType));
		checkColumn(new Column(columnFromName, dataType));
		String sql = "UPDATE `" + getName() + "` SET `" + columnToName + "` = `" + columnFromName + "`;";
		try {
			try (PreparedStatement s = sqLite.getSQLConnection().prepareStatement(sql)) {
				s.executeUpdate();
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	public void delete(Column column) {
		if (column.getName().equalsIgnoreCase(primaryKey.getName())) {
			String query = "DELETE FROM " + getName() + " WHERE `" + column.getName() + "`=?";
			try {
				try (PreparedStatement s = sqLite.getSQLConnection().prepareStatement(query)) {
				if (column.getValue().isString()) {
					s.setString(1, column.getValue().getString());
				} else if (column.getValue().isInt()) {
					s.setInt(1, column.getValue().getInt());
				} else {
					s.setBoolean(1, column.getValue().getBoolean());
				}
				s.executeUpdate();
				}
			} catch (SQLException e) {
				e.printStackTrace();
			}
		} else {
			System.out.println("Primary key must be used!");
		}
	}

	/** Checked primary-key deletion; the shared connection remains owner-managed. */
	public void deleteStrict(Column primary) throws SQLException {
		if (!primary.getName().equalsIgnoreCase(primaryKey.getName())) throw new IllegalArgumentException("The configured primary identity must be used");
		java.util.Objects.requireNonNull(primary.getValue(), "value");
		synchronized (object) {
			if (sqLite == null) throw new SQLException("SQLite user storage is unavailable");
			Connection connection = sqLite.getSQLConnection();
			if (connection == null) throw new SQLException("SQLite connection is unavailable");
			if (!connection.getAutoCommit()) throw new SQLException("Checked user removal requires auto-commit");
			try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + getName() + " WHERE `" + primaryKey.getName() + "`=?")) {
				bindStrictValue(statement, 1, primary);
				statement.executeUpdate();
			}
		}
	}

	public void executeQuery(String str) {
		try {
			try (PreparedStatement s = sqLite.getSQLConnection()
					.prepareStatement(PlaceholderUtils.replacePlaceHolder(str, "tablename", getName()))) {
				s.executeUpdate();
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	public List<List<Column>> getAll() {
		List<List<Column>> results = new ArrayList<>();
		String query = "SELECT * FROM " + getName();
		try {
			try (PreparedStatement s = sqLite.getSQLConnection().prepareStatement(query);
				ResultSet rs = s.executeQuery()) {
			while (rs.next()) {
				List<Column> result = new ArrayList<>();
				for (int i = 0; i < getColumns().size(); i++) {
					Column rCol = new Column(getColumns().get(i).getName(), getColumns().get(i).getDataType(),
							getColumns().get(i).getLimit());
					if (getColumns().get(i).getValue().isString()) {
						s.setString(1, getColumns().get(i).getValue().getString());
					} else if (getColumns().get(i).getValue().isInt()) {
						s.setInt(1, getColumns().get(i).getValue().getInt());
					} else {
						s.setBoolean(1, getColumns().get(i).getValue().getBoolean());
					}
					result.add(rCol);
				}
				results.add(result);
			}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return results;
	}

	/** Complete conversion source without taking ownership of the shared connection. */
	public HashMap<UUID, ArrayList<Column>> getAllQueryStrict() throws SQLException {
		synchronized (object) {
			if (sqLite == null) throw new SQLException("SQLite user storage is unavailable");
			Connection connection = sqLite.getSQLConnection();
			if (connection == null || !connection.getAutoCommit()) throw new SQLException("Complete user source requires an available auto-commit connection");
			try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + getName());
					ResultSet rows = statement.executeQuery()) {
				return com.bencodez.advancedcore.api.user.userstorage.CompleteUserRows.read(rows, plugin.getUserManager().getDataManager());
			}
		}
	}

	public HashMap<UUID, ArrayList<Column>> getAllQuery() {
		HashMap<UUID, ArrayList<Column>> result = new HashMap<>();
		String query = "SELECT * FROM " + getName() + ";";

		try {
			try (PreparedStatement s = sqLite.getSQLConnection().prepareStatement(query);
				ResultSet rs = s.executeQuery()) {
				SqlColumnNames names = SqlColumnNames.capture(plugin.getUserManager().getDataManager());

			while (rs.next()) {
				ArrayList<Column> cols = new ArrayList<>();
				UUID uuid = null;
				for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
					String columnName = names.name(rs.getMetaData().getColumnLabel(i));
					Column rCol = null;

					if (plugin.getUserManager().getDataManager().isInt(columnName)) {
						try {
							rCol = new Column(columnName, DataType.INTEGER);
							rCol.setValue(new DataValueInt(rs.getInt(i)));
						} catch (Exception e) {
							rCol = new Column(columnName, DataType.INTEGER);
							String data = rs.getString(i);
							if (data != null) {
								try {
									rCol.setValue(new DataValueInt(Integer.parseInt(data)));
								} catch (NumberFormatException ex) {
									rCol.setValue(new DataValueInt(0));
								}
							} else {
								rCol.setValue(new DataValueInt(0));
							}
						}
					} else if (plugin.getUserManager().getDataManager().isBoolean(columnName)) {
						rCol = new Column(columnName, DataType.BOOLEAN);
						rCol.setValue(new DataValueBoolean(Boolean.valueOf(rs.getString(i))));
					} else {
						rCol = new Column(columnName, DataType.STRING);
						rCol.setValue(new DataValueString(rs.getString(i)));
						if (columnName.equals("uuid")) {
							uuid = UUID.fromString(rs.getString(i));
						}
					}
					cols.add(rCol);
				}
				result.put(uuid, cols);
			}
			return result;
			}
		} catch (SQLException e) {
			e.printStackTrace();
		} catch (ArrayIndexOutOfBoundsException e) {
			e.printStackTrace();
		}

		return result;
	}

	public List<Column> getColumns() {
		return columns;
	}

	public List<String> getColumnsString() {
		List<Column> column = getColumns();
		ArrayList<String> list = new ArrayList<>();
		for (Column col : column) {
			list.add(col.getName());
		}
		return list;
	}

	/** Checked read of the configured primary identity, retaining the shared connection owner. */
	public ArrayList<Column> getExactStrict(Column primary) throws SQLException {
		if (!primary.getName().equalsIgnoreCase(primaryKey.getName())) throw new IllegalArgumentException("The configured primary identity must be used");
		synchronized (object) {
			if (sqLite == null) throw new SQLException("SQLite user storage is unavailable");
			Connection connection = sqLite.getSQLConnection();
			if (connection == null) throw new SQLException("SQLite connection is unavailable");
			if (!connection.getAutoCommit()) throw new SQLException("Checked user snapshots require auto-commit");
			try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + getName() + " WHERE `" + primaryKey.getName() + "`=?")) {
				bindStrictValue(statement, 1, primary);
				try (ResultSet rows = statement.executeQuery()) {
					SqlColumnNames names = SqlColumnNames.capture(plugin.getUserManager().getDataManager());
					ArrayList<Column> result = new ArrayList<>();
					if (rows.next()) {
						for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
							String key = names.name(rows.getMetaData().getColumnLabel(i));
							if (plugin.getUserManager().getDataManager().isInt(key)) result.add(new Column(key, new DataValueInt(rows.getInt(i))));
							else if (plugin.getUserManager().getDataManager().isBoolean(key)) result.add(new Column(key, new DataValueBoolean(Boolean.valueOf(rows.getString(i)))));
							else result.add(new Column(key, new DataValueString(rows.getString(i))));
						}
					}
					return result;
				}
			}
		}
	}

	public ArrayList<Column> getExact(Column column) {
		ArrayList<Column> result = new ArrayList<>();

		String query = "SELECT * FROM " + getName() + " WHERE `" + column.getName() + "`=?";
		try {
			synchronized (object) {
				try (PreparedStatement s = sqLite.getSQLConnection().prepareStatement(query)) {
				if (column.getValue().isString()) {
					s.setString(1, column.getValue().getString());
				} else if (column.getValue().isInt()) {
					s.setInt(1, column.getValue().getInt());
				} else {
					s.setBoolean(1, column.getValue().getBoolean());
				}
				try (ResultSet rs = s.executeQuery()) {
				SqlColumnNames names = SqlColumnNames.capture(plugin.getUserManager().getDataManager());

				if (rs.next()) {
					for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
						String columnName = names.name(rs.getMetaData().getColumnLabel(i));
						Column rCol = null;
						if (plugin.getUserManager().getDataManager().isInt(columnName)) {
							rCol = new Column(columnName, DataType.INTEGER);
							rCol.setValue(new DataValueInt(rs.getInt(i)));
						} else if (plugin.getUserManager().getDataManager().isBoolean(columnName)) {
							rCol = new Column(columnName, DataType.BOOLEAN);
							rCol.setValue(new DataValueBoolean(Boolean.valueOf(rs.getString(i))));
						} else {
							rCol = new Column(columnName, DataType.STRING);
							rCol.setValue(new DataValueString(rs.getString(i)));
						}
						result.add(rCol);
					}
				}
				return result;
				}
			}
			}

		} catch (SQLException e) {
			e.printStackTrace();
		} catch (ArrayIndexOutOfBoundsException e) {
		}

		for (Column col : getColumns()) {
			result.add(new Column(col.getName(), col.getDataType()));
		}
		return result;
	}

	@Override
	public String getName() {
		return name;
	}

	public ArrayList<String> getNames() {
		ArrayList<String> names = new ArrayList<>();
		for (Column col : getRowsNames()) {
			if (col.getValue() != null && col.getValue().isString()) {
				names.add(col.getValue().getString());
			}
		}
		return names;
	}

	public ArrayList<Integer> getNumbersInColumn(String column) {
		ArrayList<Integer> result = new ArrayList<>();
		String sqlStr = "SELECT " + column + " FROM " + getName() + ";";

		try {
			try (PreparedStatement s = sqLite.getSQLConnection().prepareStatement(sqlStr);
				ResultSet rs = s.executeQuery()) {

			while (rs.next()) {
				result.add(rs.getInt(column));
			}

			}
		} catch (SQLException e) {
			e.printStackTrace();
		}

		return result;
	}

	public Column getPrimaryKey() {
		return primaryKey;
	}

	@Override
	public String getQuery() {
		String sql = "CREATE TABLE IF NOT EXISTS " + getName() + " (";
		sql += "uuid VARCHAR(37), ";
		// add custom column types
		for (UserDataKey key : AdvancedCorePlugin.getInstance().getUserManager().getDataManager().getRegisteredKeysSnapshot()) {
			sql += key.getKey() + " " + key.getColumnType() + ", ";
		}
		sql += "PRIMARY KEY ( uuid ));";
		return sql;
	}

	public List<Column> getRows() {
		List<Column> result = new ArrayList<>();
		String query = "SELECT uuid FROM " + getName();

		try {
			try (PreparedStatement s = sqLite.getSQLConnection().prepareStatement(query);
					ResultSet rs = s.executeQuery()) {
				try {
					while (rs.next()) {
						Column rCol = new Column("uuid", new DataValueString(rs.getString("uuid")));
						result.add(rCol);
					}
				} catch (SQLException e) {
					e.printStackTrace();
					return null;
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}

		return result;
	}

	public List<Column> getRowsNames() {
		checkColumn(new Column("PlayerName", DataType.STRING));
		List<Column> result = new ArrayList<>();
		String query = "SELECT PlayerName FROM " + getName();

		try {
			try (PreparedStatement s = sqLite.getSQLConnection().prepareStatement(query);
					ResultSet rs = s.executeQuery()) {
				try {
					while (rs.next()) {
						Column rCol = new Column("PlayerName", new DataValueString(rs.getString("PlayerName")));
						result.add(rCol);
					}
				} catch (SQLException e) {
					return null;
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}

		return result;
	}

	public ArrayList<String> getTableColumns() {
		ArrayList<String> columns = new ArrayList<>();
		String query = "SELECT * FROM " + getName();
		try {
			try (PreparedStatement s = sqLite.getSQLConnection().prepareStatement(query);
				ResultSet rs = s.executeQuery()) {
			ResultSetMetaData metadata = rs.getMetaData();
			int columnCount = metadata.getColumnCount();

			for (int i = 1; i <= columnCount; i++) {
				String columnName = metadata.getColumnName(i);

				columns.add(columnName);
			}

			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return columns;
	}

	public String getUUID(String playerName) {
		String query = "SELECT uuid FROM " + getName() + " WHERE PlayerName=?;";
		try (PreparedStatement sql = sqLite.getSQLConnection().prepareStatement(query)) {
			sql.setString(1, playerName);
			try (ResultSet rs = sql.executeQuery()) {
				if (rs.next()) {
					String uuid = rs.getString("uuid");
					if (uuid != null && !uuid.isEmpty()) return uuid;
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		} catch (ArrayIndexOutOfBoundsException e) {
		}
		return null;
	}

	public boolean hasColumn(Column column) {
		return getTableColumns().contains(column.getName());
	}

	public void insert(List<Column> columns) {
		for (Column c : columns) {
			checkColumn(c);
		}
		String query = "INSERT OR REPLACE INTO " + getName() + " (";
		for (Column column : columns) {
			if (columns.indexOf(column) < columns.size() - 1) {
				query += "`" + column.getName() + "`, ";
			} else {
				query += "`" + column.getName() + "`) ";
			}
		}
		query += "VALUES (";
		for (int i = 0; i < columns.size(); i++) {
			if (i < columns.size() - 1) {
				query += "?, ";
			} else {
				query += "?)";
			}
		}
		query += ";";
		try {
			try (PreparedStatement s = sqLite.getSQLConnection().prepareStatement(query)) {
			for (int i = 0; i < columns.size(); i++) {
				if (columns.get(i).getValue() != null) {
					if (columns.get(i).getValue().isString()) {
						s.setString(i + 1, columns.get(i).getValue().getString());
					} else if (columns.get(i).getValue().isInt()) {
						s.setInt(i + 1, columns.get(i).getValue().getInt());
					} else {
						s.setBoolean(i + 1, columns.get(i).getValue().getBoolean());
					}
				} else {
					s.setString(i + 1, "");
				}
			}
			s.executeUpdate();
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}

	}

	public List<List<Column>> search(Column column) {
		List<List<Column>> results = new ArrayList<>();
		if (column.getName().equalsIgnoreCase(primaryKey.getName())) {
			return null;
		}
		String query = "SELECT * FROM " + getName() + " WHERE `" + column.getName() + "`=?";
		try {
			try (PreparedStatement s = sqLite.getSQLConnection().prepareStatement(query)) {
			if (column.getValue().isString()) {
				s.setString(1, column.getValue().getString());
			} else if (column.getValue().isInt()) {
				s.setInt(1, column.getValue().getInt());
			} else {
				s.setBoolean(1, column.getValue().getBoolean());
			}

			try (ResultSet rs = s.executeQuery()) {
			while (rs.next()) {
				List<Column> result = new ArrayList<>();
				for (int i = 0; i < getColumns().size(); i++) {
					Column rCol = new Column(getColumns().get(i).getName(), getColumns().get(i).getDataType(),
							getColumns().get(i).getLimit());

					if (getColumns().get(i).getValue().isString()) {
						rCol.setValue(new DataValueString(rs.getString(i + 1)));
					} else if (getColumns().get(i).getValue().isInt()) {
						rCol.setValue(new DataValueInt(rs.getInt(i + 1)));
					} else if (getColumns().get(i).getValue().isBoolean()) {
						rCol.setValue(new DataValueBoolean(rs.getBoolean(i + 1)));
					}
					result.add(rCol);
				}
				results.add(result);
			}
			}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return results;
	}

	public void setColumns(List<Column> columns) {
		this.columns = columns;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setPrimaryKey(Column primaryKey) {
		this.primaryKey = primaryKey;
	}

	@Override
	public void setSqLite(SQLite sqLite) {
		this.sqLite = sqLite;
	}

	public void update(Column primaryKey, List<Column> columns) {
		for (Column c : columns) checkColumn(c);
		if (columns.isEmpty()) return;
		if (containsKey(primaryKey.getValue().toString())) {
			synchronized (object) {
				StringBuilder query = new StringBuilder("UPDATE ").append(getName()).append(" SET ");
				for (int i = 0; i < columns.size(); i++) {
					query.append("`").append(columns.get(i).getName()).append("`=?");
					if (i != columns.size() - 1) query.append(", ");
				}
				query.append(" WHERE `").append(primaryKey.getName()).append("`=?");
				try (PreparedStatement statement = sqLite.getSQLConnection().prepareStatement(query.toString())) {
					int parameter = 1;
					for (Column column : columns) bindValue(statement, parameter++, column);
					bindValue(statement, parameter, primaryKey);
					statement.executeUpdate();
				} catch (SQLException e) {
					e.printStackTrace();
				}
			}
		} else {
			boolean addPrimary = true;
			for (Column column : columns) {
				if (column.getName().equals("uuid")) addPrimary = false;
			}
			if (addPrimary) columns.add(primaryKey);
			insert(columns);
		}
	}

	/**
	 * Synchronously writes a batch without replacing unrelated row columns.
	 * Supports the legacy SQLite driver; no modern UPSERT syntax is required.
	 * SQL failures propagate. The shared SQLite connection remains owner-managed.
	 */
	public void updateStrict(Column primary, List<Column> values) throws SQLException {
		if (values.isEmpty()) return;
		if (!primary.getName().equalsIgnoreCase(primaryKey.getName())) {
			throw new IllegalArgumentException("The configured primary identity must be used");
		}
		for (Column column : values) {
			if (column.getName().equalsIgnoreCase(primary.getName())) {
				throw new IllegalArgumentException("The primary identity cannot be updated");
			}
		}
		synchronized (object) {
			Connection connection = sqLite.getSQLConnection();
			if (connection == null) throw new SQLException("SQLite connection is unavailable");
			if (!connection.getAutoCommit()) throw new SQLException("Checked user writes require auto-commit");
			for (Column column : values) checkColumn(column);
			StringBuilder update = new StringBuilder("UPDATE ").append(getName()).append(" SET ");
			for (int i = 0; i < values.size(); i++) {
				if (i > 0) update.append(", ");
				update.append("`").append(values.get(i).getName()).append("`=?");
			}
			update.append(" WHERE `").append(primary.getName()).append("`=?");
			if (executeStrictUpdate(connection, update.toString(), primary, values) > 0) return;
			StringBuilder insert = new StringBuilder("INSERT OR IGNORE INTO ").append(getName())
					.append(" (`").append(primary.getName()).append("`");
			for (Column column : values) insert.append(", `").append(column.getName()).append("`");
			insert.append(") VALUES (?");
			for (int i = 0; i < values.size(); i++) insert.append(", ?");
			insert.append(")");
			try (PreparedStatement statement = connection.prepareStatement(insert.toString())) {
				bindStrictValue(statement, 1, primary);
				for (int i = 0; i < values.size(); i++) bindStrictValue(statement, i + 2, values.get(i));
				if (statement.executeUpdate() > 0) return;
			}
			// Another writer may have inserted this identity after the first UPDATE.
			if (executeStrictUpdate(connection, update.toString(), primary, values) == 0) {
				throw new SQLException("User identity was not written");
			}
		}
	}

	private int executeStrictUpdate(Connection connection, String query, Column primary, List<Column> values) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(query)) {
			for (int i = 0; i < values.size(); i++) bindStrictValue(statement, i + 1, values.get(i));
			bindStrictValue(statement, values.size() + 1, primary);
			return statement.executeUpdate();
		}
	}

	private void bindStrictValue(PreparedStatement statement, int parameter, Column column) throws SQLException {
		// UserData boolean readers expect the legacy textual true/false format.
		if (column.getValue() != null && column.getValue().isBoolean()) {
			statement.setString(parameter, Boolean.toString(column.getValue().getBoolean()));
		} else {
			bindValue(statement, parameter, column);
		}
	}

	private void bindValue(PreparedStatement statement, int parameter, Column column) throws SQLException {
		if (column.getValue() == null) {
			statement.setObject(parameter, null);
		} else if (column.getValue().isString()) {
			statement.setString(parameter, column.getValue().getString());
		} else if (column.getValue().isBoolean()) {
			statement.setBoolean(parameter, column.getValue().getBoolean());
		} else if (column.getValue().isInt()) {
			statement.setInt(parameter, column.getValue().getInt());
		} else {
			statement.setObject(parameter, column.getValue().toString());
		}
	}

	public void wipeColumnData(String columnName, DataType dataType) {
		checkColumn(new Column(columnName, dataType));
		String sql = "UPDATE " + getName() + " SET " + columnName + " = " + dataType.getNoValue() + ";";
		try {
			try (PreparedStatement s = sqLite.getSQLConnection().prepareStatement(sql)) {
				s.executeUpdate();
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	@Override
	public SQLite getSqLite() {
		return sqLite;
	}

}
