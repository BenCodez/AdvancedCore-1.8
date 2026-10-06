package com.bencodez.advancedcore.api.user.userstorage;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;
import com.bencodez.advancedcore.api.user.usercache.UserDataManager;
import com.bencodez.simpleapi.sql.Column;
import com.bencodez.simpleapi.sql.data.*;

/** Strict conversion materialization: a failed or ambiguous source is never a prefix. */
public final class CompleteUserRows {
    private CompleteUserRows() {}
    public static HashMap<UUID,ArrayList<Column>> read(ResultSet rows,UserDataManager types) throws SQLException {
        HashMap<UUID,ArrayList<Column>> result=new HashMap<>();
        SqlColumnNames registered=SqlColumnNames.capture(types);
        ResultSetMetaData metadata=rows.getMetaData();int count=metadata.getColumnCount();
        Set<String> labels=new HashSet<>();String[] names=new String[count];
        for(int i=0;i<count;i++) {
            String name=registered.name(metadata.getColumnLabel(i+1));
            if(name==null||name.isEmpty()||!labels.add(name))throw new SQLException("Invalid source column identities");
            names[i]=name;
        }
        if(!labels.contains("uuid"))throw new SQLException("Source has no user identity");
        while(rows.next()) {
            UUID identity=null;ArrayList<Column> columns=new ArrayList<>();
            for(int i=0;i<count;i++) {
                String name=names[i],raw=rows.getString(i+1);DataValue value;
                if(name.equals("uuid")) {
                    try {identity=UUID.fromString(raw);if(!identity.toString().equalsIgnoreCase(raw))throw new IllegalArgumentException();}
                    catch(IllegalArgumentException|NullPointerException malformed){throw new SQLException("Invalid source user identity",malformed);}
                    value=new DataValueString(raw);
                } else if(types.isInt(name)) {
                    try {value=new DataValueInt(raw==null?0:Integer.parseInt(raw));}
                    catch(NumberFormatException malformed){throw new SQLException("Invalid source integer value",malformed);}
                } else if(types.isBoolean(name)) {
                    if(raw==null||raw.equalsIgnoreCase("false")||raw.equals("0"))value=new DataValueBoolean(false);
                    else if(raw.equalsIgnoreCase("true")||raw.equals("1"))value=new DataValueBoolean(true);
                    else throw new SQLException("Invalid source boolean value");
                } else value=new DataValueString(raw);
                columns.add(new Column(name,value));
            }
            if(result.put(identity,columns)!=null)throw new SQLException("Duplicate source user identity");
        }
        return result;
    }
}
