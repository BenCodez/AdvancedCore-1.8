package com.bencodez.advancedcore.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.UserStorage;
import com.bencodez.advancedcore.api.rewards.ServerThreadRewardDispatch;

class LegacyConversionCommandCompletionTest {
    @Test void commandsDoNotReportCompletionAtAdmission() {
        Fixture f=new Fixture();List<String> messages=new ArrayList<>();
        f.commands.startStorageConversion(UserStorage.MYSQL,UserStorage.SQLITE,messages::add);
        assertEquals(Collections.singletonList("&cStarting convert from MYSQL to SQLITE"),messages);
        f.physical.complete(null);assertEquals("&cFinished converting",messages.get(1));verify(f.owner,times(2)).dispatch(any(),anyLong());
    }
    @Test void sourceFailureReportsFailureWithoutRawProviderDetailsOrSuccess() {
        Fixture f=new Fixture();List<String> messages=new ArrayList<>();f.commands.startStorageConversion(UserStorage.SQLITE,UserStorage.MYSQL,messages::add);
        f.physical.completeExceptionally(new IllegalStateException("jdbc fixture secret details"));
        assertEquals(2,messages.size());assertEquals("&cUser storage conversion failed; see the server log",messages.get(1));assertTrue(messages.stream().noneMatch(s -> s.contains("Finished")||s.contains("jdbc")));
        verify(f.logger).severe(argThat((String s) -> !s.contains("jdbc")&&!s.contains("secret")));
    }
    @Test void notificationFailureDoesNotTurnCommittedCopyIntoARetry() {
        Fixture f=new Fixture();java.util.concurrent.atomic.AtomicInteger replies=new java.util.concurrent.atomic.AtomicInteger();
        f.commands.startStorageConversion(UserStorage.MYSQL,UserStorage.SQLITE,s -> {if(replies.incrementAndGet()==2)throw new IllegalStateException("recipient unavailable");});
        f.physical.complete(null);assertFalse(f.physical.isCompletedExceptionally());verify(f.logger).warning("User storage conversion result message could not be delivered");verify(f.plugin,times(1)).convertDataStorageAsync(any(),any());
    }
    static class Fixture {
        final AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);final ServerThreadRewardDispatch owner=mock(ServerThreadRewardDispatch.class);final Logger logger=mock(Logger.class);
        final CompletableFuture<Void> physical=new CompletableFuture<>();final CommandLoader commands=new CommandLoader(plugin);
        Fixture() {
            when(plugin.getRewardDispatch()).thenReturn(owner);when(plugin.getLogger()).thenReturn(logger);when(plugin.convertDataStorageAsync(any(),any())).thenReturn(physical);
            when(owner.dispatch(any(),anyLong())).thenAnswer(call -> {try{return ((Supplier<CompletionStage<?>>)call.getArgument(0)).get();}catch(Throwable failure){CompletableFuture<Object> result=new CompletableFuture<>();result.completeExceptionally(failure);return result;}});
        }
    }
}
