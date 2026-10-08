package com.bencodez.advancedcore.api.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership;
import com.bencodez.advancedcore.thread.FileThread;
import com.bencodez.simpleapi.sql.data.*;

class LegacyExplicitSourceReadTest {
    @TempDir Path folder;
    final String id="8d17c3ab-1085-4e22-95ad-8c975f3e4c24";
    @Test void flatGatewayDoesNotReadTheCurrentlySelectedSqlProvider() throws Exception {
        AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);when(plugin.getUserStorageOwnership()).thenReturn(new UserStorageOwnership());when(plugin.getStorageType()).thenReturn(UserStorage.MYSQL);
        UserManager users=mock(UserManager.class,CALLS_REAL_METHODS);field(UserManager.class,users,"plugin",plugin);FileThread files=mock(FileThread.class);
        HashMap<UUID,HashMap<String,DataValue>> source=new HashMap<>();HashMap<String,DataValue> data=new HashMap<>();data.put("Points",new DataValueInt(7));source.put(UUID.fromString(id),data);when(files.getAllValuesStrict()).thenReturn(source);
        try(MockedStatic<FileThread> global=mockStatic(FileThread.class)) {
            global.when(FileThread::getInstance).thenReturn(files);assertEquals(7,users.getAllKeysStrict(UserStorage.FLAT).get(UUID.fromString(id)).get(0).getValue().getInt());
            IOException failure=new IOException("fixture failed");when(files.getAllValuesStrict()).thenThrow(failure);assertSame(failure,assertThrows(IOException.class,() -> users.getAllKeysStrict(UserStorage.FLAT)));
        }
        verify(plugin,never()).getStorageType();verify(plugin,never()).getMysql();verify(files,never()).getThread();
    }
    @Test void realFlatSourcePreservesScalarValuesWithoutCreatingOrEditingFiles() throws Exception {
        Files.createDirectories(folder.resolve("Data"));Path file=folder.resolve("Data").resolve(id+".yml");String original="Points: 7\nEnabled: true\nMessage: O'Brien\nTimestamp: 5000000000\n";Files.write(file,original.getBytes("UTF-8"));
        withOwner(files -> {Map<String,DataValue> data=files.getAllValuesStrict().get(UUID.fromString(id));assertEquals(7,data.get("Points").getInt());assertEquals("true",data.get("Enabled").getString());assertEquals("5000000000",data.get("Timestamp").getString());assertEquals("O'Brien",data.get("Message").getString());});
        assertEquals(original,new String(Files.readAllBytes(file),"UTF-8"));
    }
    @Test void malformedStructuredAndDuplicateFlatSourcesFailWithoutMutation() throws Exception {
        Path data=Files.createDirectories(folder.resolve("Data")),file=data.resolve(id+".yml");
        for(String invalid:new String[]{"Points: [unterminated","Nested:\n  Value: lost"}) {Files.write(file,invalid.getBytes("UTF-8"));withOwner(files -> assertThrows(IOException.class,files::getAllValuesStrict));assertEquals(invalid,new String(Files.readAllBytes(file),"UTF-8"));}
        Files.write(file,"Points: 1\n".getBytes("UTF-8"));Files.write(data.resolve(id.toUpperCase(Locale.ROOT)+".yml"),"Points: 2\n".getBytes("UTF-8"));withOwner(files -> assertThrows(IOException.class,files::getAllValuesStrict));
    }
    @Test void missingFlatDirectoryIsEmptyButUnreadableShapeIsNot() throws Exception {
        withOwner(files -> assertTrue(files.getAllValuesStrict().isEmpty()));Files.write(folder.resolve("Data"),new byte[0]);withOwner(files -> assertThrows(IOException.class,files::getAllValuesStrict));
    }
    @Test void duplicateYamlKeysCannotSilentlyChooseOneValueForConversion() throws Exception {
        Path data=Files.createDirectories(folder.resolve("Data")),file=data.resolve(id+".yml");String original="Points: 7\nPoints: 8\n";Files.write(file,original.getBytes("UTF-8"));
        withOwner(files -> assertThrows(IOException.class,files::getAllValuesStrict));assertEquals(original,new String(Files.readAllBytes(file),"UTF-8"));
    }
    @Test void quotedDuplicateKeysFailAndScalarAliasesRemainCompatible() throws Exception {
        Path data=Files.createDirectories(folder.resolve("Data")),file=data.resolve(id+".yml");
        for(String source:new String[]{"Points: 7\n'Points': 8\n", "Points: 7\n\"Poin\\u0074s\": 8\n", "Defaults: &d {Points: 1, Points: 2}\n<<: *d\n", "&cycle {Recursive: *cycle}", "Recursive: &cycle [*cycle]"}) {
            Files.write(file,source.getBytes("UTF-8"));withOwner(files -> assertThrows(IOException.class,files::getAllValuesStrict));assertEquals(source,new String(Files.readAllBytes(file),"UTF-8"));
        }
        String valid="Message: &m 'O’Brien'\nOtherMessage: *m\nPoints: 7\n";Files.write(file,valid.getBytes("UTF-8"));
        withOwner(files -> {Map<String,DataValue> values=files.getAllValuesStrict().get(UUID.fromString(id));assertEquals("O’Brien",values.get("Message").getString());assertEquals("O’Brien",values.get("OtherMessage").getString());});
    }
    interface CheckedWork {void run(FileThread owner)throws Exception;}
    void withOwner(CheckedWork work)throws Exception {
        AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);when(plugin.getDataFolder()).thenReturn(folder.toFile());FileThread owner=FileThread.getInstance();java.lang.reflect.Field f=FileThread.class.getDeclaredField("plugin");f.setAccessible(true);Object before=f.get(owner);
        try {f.set(owner,plugin);work.run(owner);}finally {f.set(owner,before);}
    }
    static void field(Class<?> type,Object target,String name,Object value)throws Exception {java.lang.reflect.Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
}
