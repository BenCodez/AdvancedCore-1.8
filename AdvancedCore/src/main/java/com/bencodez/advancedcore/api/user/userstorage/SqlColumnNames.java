package com.bencodez.advancedcore.api.user.userstorage;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import com.bencodez.advancedcore.api.user.usercache.UserDataManager;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKey;

/** Registered SQL names captured once per read; unknown dynamic names remain unchanged. */
public final class SqlColumnNames {
    private final Map<String,String> registered;
    private SqlColumnNames(Map<String,String> registered) { this.registered=registered; }
    public static SqlColumnNames capture(UserDataManager manager) throws SQLException {
        Map<String,String> names=new HashMap<>();names.put("uuid","uuid");
        for(UserDataKey key:manager.getRegisteredKeysSnapshot()) {
            String name=key.getKey();
            if(name==null||name.isEmpty())throw new SQLException("Invalid registered SQL column identity");
            String folded=name.toLowerCase(Locale.ROOT),prior=names.get(folded);
            // Identical legacy registrations remain supported. Distinct aliases
            // cannot safely select one cache key for the same physical column.
            if(prior!=null&&!prior.equals(name))throw new SQLException("Ambiguous registered SQL column identity");
            names.put(folded,name);
        }
        return new SqlColumnNames(names);
    }
    public String name(String observed) throws SQLException {
        if(observed==null||observed.isEmpty())throw new SQLException("Invalid SQL column identity");
        String canonical=registered.get(observed.toLowerCase(Locale.ROOT));
        return canonical==null?observed:canonical;
    }
}
