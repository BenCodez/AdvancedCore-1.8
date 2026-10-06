import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;
import com.bencodez.votingplugin.advancedcore.api.user.UserStorage;
import com.bencodez.votingplugin.advancedcore.core.user.storage.SqlUserStorage;
import com.bencodez.votingplugin.advancedcore.core.user.storage.SqlUserDataAccess;
import com.bencodez.votingplugin.advancedcore.core.user.storage.sql.*;
import com.bencodez.votingplugin.simpleapi.sql.data.*;

/** Manual acceptance fixture. Database work runs off the Bukkit owner thread. */
public final class SharedSqliteAcceptance extends JavaPlugin {
    public void onEnable() { getLogger().info("shared-sqlite-probe-ready"); }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) return false;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try { probe(); }
            catch (Throwable failure) { getLogger().log(Level.SEVERE, "SHARED SQLITE PROBE FAILED", failure); }
        });
        return true;
    }

    private void check(boolean valid, String name) {
        if (!valid) throw new AssertionError(name);
        getLogger().info("shared-sqlite-pass:" + name);
    }

    private SqliteUserBackend openBackend(Path directory) {
        return SqlUserBackendFactory.sqlite(directory, "Probe", "Users", Arrays.asList(
                new com.bencodez.votingplugin.advancedcore.api.user.usercache.keys.UserDataKeyString("PlayerName"),
                new com.bencodez.votingplugin.advancedcore.api.user.usercache.keys.UserDataKeyInt("Points"),
                new com.bencodez.votingplugin.advancedcore.api.user.usercache.keys.UserDataKeyString("OfflineRewards")), SqlBackendLogger.NO_OP);
    }

    private void probe() throws Exception {
        Path directory = getDataFolder().toPath();
        UserStorage storage = UserStorage.SQLITE;
        UUID uuid = UUID.fromString("c17d7784-00ce-421f-a38b-a30ed419e1a4");
        SqlUserStorage retained;
        try (SqliteUserBackend backend = openBackend(directory)) {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + backend.databaseFile());
                    Statement statement = connection.createStatement()) {
                try (ResultSet version = statement.executeQuery("SELECT sqlite_version()")) {
                    check(version.next() && "3.7.2".equals(version.getString(1)), "actual-spigot-sqlite-3.7.2");
                }
                statement.executeUpdate("CREATE TABLE Receipts(id TEXT PRIMARY KEY)");
            }
            retained = backend.user(uuid);
            HashMap<String, DataValue> seed = new HashMap<>();
            seed.put("PlayerName", new DataValueString("LegacyPlayer"));
            seed.put("Points", new DataValueInt(17));
            seed.put("OfflineRewards", new DataValueString("RewardA;;RewardB"));
            AtomicReference<SqlUserStorage.TransactionScope> escaped = new AtomicReference<>();
            retained.transaction(storage, seed, scope -> {
                check(scope.createdUserRow(), "transaction-created-user"); escaped.set(scope);
                try (PreparedStatement statement = scope.connection().prepareStatement("INSERT INTO Receipts(id) VALUES (?)")) {
                    statement.setString(1, "occurrence-a"); statement.executeUpdate();
                }
                return null;
            });
            boolean expired = false;
            try { escaped.get().connection(); } catch (IllegalStateException expected) { expired = true; }
            check(expired, "scope-expires-after-commit");
            boolean rolledBack = false;
            try {
                retained.transaction(storage, scope -> {
                    scope.writeValues(Collections.<String, DataValue>singletonMap("Points", new DataValueInt(99)));
                    try (Statement statement = scope.connection().createStatement()) {
                        statement.executeUpdate("INSERT INTO Receipts(id) VALUES ('failed-occurrence')");
                    }
                    throw new SQLException("deliberate acceptance rollback");
                });
            } catch (IllegalStateException expected) {
                rolledBack = expected.getCause() instanceof SQLException
                        && "deliberate acceptance rollback".equals(expected.getCause().getMessage());
            }
            check(rolledBack && new SqlUserDataAccess(retained).getInt(storage, "Points", 0) == 17, "rollback-preserves-committed-user");
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + backend.databaseFile());
                    Statement statement = connection.createStatement(); ResultSet receipts = statement.executeQuery("SELECT COUNT(*) FROM Receipts")) {
                check(receipts.next() && receipts.getInt(1) == 1, "receipt-and-user-rollback-together");
            }
        }
        boolean staleRejected = false;
        try { retained.readRow(storage); } catch (IllegalStateException expected) { staleRejected = true; }
        check(staleRejected, "closed-generation-rejects-retained-user");
        try (SqliteUserBackend reopened = openBackend(directory)) {
            SqlUserDataAccess data = new SqlUserDataAccess(reopened.user(uuid));
            check(data.getInt(storage, "Points", 0) == 17 && "RewardA;;RewardB".equals(data.getString(storage, "OfflineRewards")), "reopen-preserves-values-and-format");
            check(reopened.enumerateUsers().equals(Collections.singletonList(uuid)), "reopen-enumeration");
        }
        getLogger().info("shared-sqlite-probe-complete");
    }
}
