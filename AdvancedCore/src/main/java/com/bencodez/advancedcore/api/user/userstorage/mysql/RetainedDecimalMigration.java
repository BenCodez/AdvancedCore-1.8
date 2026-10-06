package com.bencodez.advancedcore.api.user.userstorage.mysql;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Exclusive, connection-owned scope for decimal value validation and one ALTER. */
final class RetainedDecimalMigration implements AutoCloseable {
    private final Connection connection;
    private final RetainedStringColumn.DiscardConnection discard;
    private boolean autoCommitChanged,lockAttempted;
    private RetainedDecimalMigration(Connection connection, RetainedStringColumn.DiscardConnection discard) {
        this.connection=connection; this.discard=discard;
    }

    static RetainedDecimalMigration open(Connection connection, String table,
            RetainedStringColumn.DiscardConnection discard) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("Decimal migration cannot own an existing transaction");
        // Connector/J automatic reconnect silently drops explicit table ownership.
        // Mutate only this borrowed physical connection and evict it on every exit;
        // pool configuration and other borrowers retain their original settings.
        Object driver;
        Class<?> contract;
        try {
            contract=Class.forName("com.mysql.jdbc.Connection", false, RetainedDecimalMigration.class.getClassLoader());
            driver=connection.unwrap(contract);
            if (driver==null || !contract.isInstance(driver)) throw new SQLException("Decimal migration driver ownership unavailable");
        } catch (ClassNotFoundException | RuntimeException failure) {
            throw new SQLException("Decimal migration requires a supported reconnect control",failure);
        }
        // Connector/J5 caches autoReconnect at initialization: its public setter
        // alone does not clear that cache. Reject an initially enabled mode;
        // reading only fixed boolean properties never exposes credentials.
        try {
            Object configured=contract.getMethod("getProperties").invoke(driver);
            if(!(configured instanceof java.util.Properties))
                throw new SQLException("Decimal migration reconnect evidence unavailable");
            java.util.Properties properties=(java.util.Properties)configured;
            for(String name:new String[]{"autoReconnect","autoReconnectForPools","reconnectAtTxEnd"}) {
                Object value=properties.get(name);
                if(value==null)value=properties.getProperty(name);
                if(value!=null && !(value instanceof String && "false".equalsIgnoreCase(((String)value).trim())))
                    throw new SQLException("Decimal migration requires automatic reconnect disabled at connection creation");
            }
        } catch(ReflectiveOperationException | RuntimeException failure) {
            throw new SQLException("Decimal migration reconnect evidence unavailable",failure);
        }
        RetainedDecimalMigration scope=new RetainedDecimalMigration(connection,discard);
        try {
            for(String option:new String[]{"setAutoReconnect","setAutoReconnectForPools","setReconnectAtTxEnd"})
                contract.getMethod(option,boolean.class).invoke(driver,false);
            checkServer(connection);
            execute(connection,"SET SESSION lock_wait_timeout=5");
            scope.autoCommitChanged=true;
            connection.setAutoCommit(false);
            scope.lockAttempted=true;
            execute(connection,"LOCK TABLES "+quote(table)+" WRITE");
            return scope;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            SQLException sql=new SQLException("Failed to fence decimal migration reconnects",
                failure instanceof InvocationTargetException ? ((InvocationTargetException)failure).getCause() : failure);
            try {scope.close();} catch(SQLException cleanup) {sql.addSuppressed(cleanup);}
            throw sql;
        } catch (SQLException failure) {
            try {scope.close();} catch(SQLException cleanup) {failure.addSuppressed(cleanup);}
            throw failure;
        }
    }

    private static void checkServer(Connection connection) throws SQLException {
        boolean innodb=false;
        try(PreparedStatement statement=connection.prepareStatement(
                "SHOW VARIABLES WHERE Variable_name IN ('innodb_table_locks','wsrep_on')")) {
            statement.setQueryTimeout(5);
            try(ResultSet rows=statement.executeQuery()) {
                if(rows==null) throw new SQLException("Decimal migration lock evidence unavailable");
                int count=0;
                while(rows.next()) {
                    if(++count>2) throw new SQLException("Decimal migration lock evidence ambiguous");
                    String name=rows.getString(1), value=rows.getString(2);
                    if("innodb_table_locks".equalsIgnoreCase(name)) {
                        if(innodb || !("ON".equalsIgnoreCase(value) || "1".equals(value)))
                            throw new SQLException("InnoDB table ownership is unavailable");
                        innodb=true;
                    } else if("wsrep_on".equalsIgnoreCase(name)) {
                        if(!("OFF".equalsIgnoreCase(value) || "0".equals(value)))
                            throw new SQLException("Decimal migration cannot use table locks on a Galera node");
                    } else throw new SQLException("Decimal migration lock evidence invalid");
                }
            }
        }
        if(!innodb) throw new SQLException("InnoDB table ownership evidence unavailable");
    }

    static void execute(Connection connection,String sql) throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5); statement.executeUpdate();
        }
    }
    private static String quote(String value) {return "`"+value.replace("`","``")+"`";}

    private static SQLException retain(SQLException failure,Exception cleanup) {
        SQLException sql=cleanup instanceof SQLException ? (SQLException)cleanup
            : new SQLException("Decimal migration cleanup failed",cleanup);
        if(failure==null)return sql;
        failure.addSuppressed(sql);return failure;
    }

    @Override public void close() throws SQLException {
        SQLException failure=null;
        try {if(autoCommitChanged)connection.rollback();}
        catch(SQLException | RuntimeException cleanup) {failure=retain(failure,cleanup);}
        try {if(lockAttempted)execute(connection,"UNLOCK TABLES");}
        catch(SQLException | RuntimeException cleanup) {failure=retain(failure,cleanup);}
        try {if(autoCommitChanged)connection.setAutoCommit(true);}
        catch(SQLException | RuntimeException cleanup) {failure=retain(failure,cleanup);}
        try {discard.discard(connection);}
        catch(SQLException | RuntimeException cleanup) {failure=retain(failure,cleanup);}
        if(failure!=null)throw failure;
    }
}
