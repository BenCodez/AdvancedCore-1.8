package com.bencodez.advancedcore.api.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.bencodez.simpleapi.sql.Column;
import com.bencodez.simpleapi.sql.data.*;
import com.bencodez.advancedcore.api.user.usercache.*;

class LegacyQueueMutationBridgeTest {
    private LegacyDirectUserDataTest.Fixture fixture() {
        LegacyDirectUserDataTest.Fixture f=new LegacyDirectUserDataTest.Fixture();
        doCallRealMethod().when(f.manager).mutateDirect(any(),anyString(),any(),any(),any());
        HashMap<String,DataValue> values=new HashMap<>();values.put("OfflineRewards",new DataValueString("old"));f.cache.updateCache(values);return f;
    }
    @Test void usesActualCurrentCacheRatherThanTheUsersStaleHandle() throws Exception {
        LegacyDirectUserDataTest.Fixture f=fixture();UserDataCache replacement=spy(new UserDataCache(f.manager,f.cache.getUuid()));doReturn(f.user).when(replacement).getUser();
        HashMap<String,DataValue> values=new HashMap<>();values.put("OfflineRewards",new DataValueString("replacement"));replacement.updateCache(values);
        f.manager.getUserDataCache().put(replacement.getUuid(),replacement);
        assertEquals(Arrays.asList("replacement","new"),f.data.mutateStringListStrict("OfflineRewards",list->{list.add("new");return list;}));
        assertEquals("old",f.cache.getCachedValue("OfflineRewards").getString());assertEquals("replacement%line%new",replacement.getCachedValue("OfflineRewards").getString());
        verify(f.mysql,never()).getExactStrict(anyString());
    }
    @Test void unknownCachedValueRequiresCheckedStorageRead() throws Exception {
        LegacyDirectUserDataTest.Fixture f=fixture();when(f.mysql.getExactStrict(anyString())).thenReturn(new ArrayList<>(Collections.singletonList(new Column("TimedRewards",new DataValueString("retained")))));
        assertEquals(Arrays.asList("retained","new"),f.data.mutateStringListStrict("TimedRewards",list->{list.add("new");return list;}));verify(f.mysql).getExactStrict(f.user.getUUID());
        assertEquals("retained%line%new",f.cache.getCachedValue("TimedRewards").getString());
    }
    @Test void unreadablePredecessorNeverBecomesEmptyOrWrites() throws Exception {
        LegacyDirectUserDataTest.Fixture f=fixture();when(f.mysql.getExactStrict(anyString())).thenThrow(new SQLException("unavailable"));
        assertThrows(IllegalStateException.class,()->f.data.mutateStringListStrict("TimedRewards",list->{list.add("new");return list;}));verify(f.mysql,never()).updateStrict(anyString(),anyList());assertNull(f.cache.getCachedValue("TimedRewards"));
    }
    @Test void checkedAbsentPredecessorIsAnAllowedEmptyQueue() throws Exception {
        LegacyDirectUserDataTest.Fixture f=fixture();when(f.mysql.getExactStrict(anyString())).thenReturn(new ArrayList<Column>());
        assertEquals(Collections.singletonList("new"),f.data.mutateStringListStrict("TimedRewards",list->{assertTrue(list.isEmpty());list.add("new");return list;}));
    }
    @Test void failedWriteDoesNotPublishMutatedCache() throws Exception {
        LegacyDirectUserDataTest.Fixture f=fixture();doThrow(new SQLException("unavailable")).when(f.mysql).updateStrict(anyString(),anyList());
        assertThrows(IllegalStateException.class,()->f.data.mutateStringListStrict("OfflineRewards",list->{list.clear();return list;}));assertEquals("old",f.cache.getCachedValue("OfflineRewards").getString());verify(f.users,never()).onChange(any(),any());
    }
    @Test void uncachedCommitNotificationFailureCarriesActualCommittedValue() throws Exception {
        LegacyDirectUserDataTest.Fixture f=fixture();f.manager.getUserDataCache().clear();when(f.mysql.getExactStrict(anyString())).thenReturn(new ArrayList<>(Collections.singletonList(new Column("OfflineRewards",new DataValueString("stored")))));
        doThrow(new IllegalStateException("listener")).when(f.users).onChange(eq(f.user),any(String[].class));AtomicInteger transformations=new AtomicInteger();
        CommittedUserDataMutationException failure=assertThrows(CommittedUserDataMutationException.class,()->f.data.mutateStringListStrict("OfflineRewards",list->{transformations.incrementAndGet();list.add("new");return list;}));
        assertEquals("stored%line%new",failure.getCommittedValue().getString());assertEquals(1,transformations.get());verify(f.mysql,times(1)).updateStrict(anyString(),anyList());
    }
    @Test void cachedCommitNotificationFailureCarriesActualCommittedValue() throws Exception {
        LegacyDirectUserDataTest.Fixture f=fixture();doThrow(new IllegalStateException("listener")).when(f.users).onChange(eq(f.user),any(String[].class));
        CommittedUserDataMutationException failure=assertThrows(CommittedUserDataMutationException.class,()->f.data.mutateStringListStrict("OfflineRewards",list->{list.add("new");return list;}));
        assertEquals("old%line%new",failure.getCommittedValue().getString());assertEquals("old%line%new",f.cache.getCachedValue("OfflineRewards").getString());verify(f.mysql,times(1)).updateStrict(anyString(),anyList());
    }
    @Test void invalidPredecessorAndNullTransformCannotEraseAQueue() throws Exception {
        LegacyDirectUserDataTest.Fixture f=fixture();HashMap<String,DataValue> values=new HashMap<>();values.put("OfflineRewards",new DataValueInt(7));f.cache.updateCache(values);
        assertThrows(IllegalStateException.class,()->f.data.mutateStringListStrict("OfflineRewards",list->new ArrayList<>()));verify(f.mysql,never()).updateStrict(anyString(),anyList());
        values.put("OfflineRewards",new DataValueString("old"));f.cache.updateCache(values);
        assertThrows(NullPointerException.class,()->f.data.mutateStringListStrict("OfflineRewards",list->null));assertEquals("old",f.cache.getCachedValue("OfflineRewards").getString());
    }
    @Test void uncachedWriteFailureDoesNotNotifyOrBecomeAcknowledged() throws Exception {
        LegacyDirectUserDataTest.Fixture f=fixture();f.manager.getUserDataCache().clear();when(f.mysql.getExactStrict(anyString())).thenReturn(new ArrayList<Column>());doThrow(new SQLException("unavailable")).when(f.mysql).updateStrict(anyString(),anyList());
        IllegalStateException failure=assertThrows(IllegalStateException.class,()->f.data.mutateStringListStrict("OfflineRewards",list->{list.add("new");return list;}));assertFalse(failure instanceof CommittedUserDataMutationException);verify(f.users,never()).onChange(any(),any());
    }

    @Test void strictQueueBoundNeverTrimsAnEntryToMakeTheWriteFit() throws Exception {
        LegacyDirectUserDataTest.Fixture f=fixture();char[] characters=new char[65535];Arrays.fill(characters,'a');String limit=new String(characters);
        assertEquals(Collections.singletonList(limit),f.data.mutateStringListStrict("OfflineRewards",list->new ArrayList<>(Collections.singletonList(limit))));
        assertThrows(IllegalStateException.class,()->f.data.mutateStringListStrict("OfflineRewards",list->{list.add("new");return list;}));
        assertEquals(limit,f.cache.getCachedValue("OfflineRewards").getString());verify(f.mysql,times(1)).updateStrict(anyString(),anyList());
    }

    @Test void concurrentUncachedMutationsSerializeAgainstCommittedPredecessor() throws Exception {
        LegacyDirectUserDataTest.Fixture f=fixture();f.manager.getUserDataCache().clear();java.util.concurrent.atomic.AtomicReference<String> store=new java.util.concurrent.atomic.AtomicReference<>("stored");
        when(f.mysql.getExactStrict(anyString())).thenAnswer(call->new ArrayList<>(Collections.singletonList(new Column("OfflineRewards",new DataValueString(store.get())))));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);doAnswer(call->{List<Column> columns=call.getArgument(1);String value=columns.get(0).getValue().getString();if(value.endsWith("first")){entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));}store.set(value);return null;}).when(f.mysql).updateStrict(anyString(),anyList());
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            Future<?> first=workers.submit(()->f.data.mutateStringListStrict("OfflineRewards",list->{list.add("first");return list;}));assertTrue(entered.await(5,TimeUnit.SECONDS));
            Future<?> second=workers.submit(()->f.data.mutateStringListStrict("OfflineRewards",list->{list.add("second");return list;}));assertThrows(TimeoutException.class,()->second.get(100,TimeUnit.MILLISECONDS));
            release.countDown();first.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS);assertEquals("stored%line%first%line%second",store.get());
        }finally{release.countDown();workers.shutdownNow();}
    }
}
