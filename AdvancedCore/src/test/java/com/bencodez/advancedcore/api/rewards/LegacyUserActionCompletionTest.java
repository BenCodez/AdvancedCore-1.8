package com.bencodez.advancedcore.api.rewards;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.bencodez.advancedcore.api.user.AdvancedCoreUser;

class LegacyUserActionCompletionTest {
    @Test void conclusivelyUnstartedActionReleasesReservationBeforeChangedPayloadRetry() {
        fixture(f->{
            java.util.List<Reward.ReplayCheckpoint> writes=new java.util.ArrayList<>();
            RewardOptions options=new RewardOptions();options.setAsyncReplayCheckpointConsumer(writes::add);
            Reward.ReplayState state=Reward.replayStateFor(options);java.util.HashMap<String,String> metadata=new java.util.HashMap<>();
            AdvancedCoreUser.AsyncActionCollection first=f.user.beginAsyncActionCollection(state,metadata,"native-exp");
            f.user.giveExp(7);CompletionStage<Void> rejected=f.user.endAsyncActionCollection(first);
            when(f.player.isOnline()).thenReturn(false);f.dispatch.runNext();drain(f);
            assertTrue(AdvancedCoreUser.isReplayActionNotStarted(failure(rejected)));verify(f.player,never()).giveExp(7);
            assertEquals(2,writes.size(),"Admission and reservation release must both be checkpointed");
            when(f.player.isOnline()).thenReturn(true);
            AdvancedCoreUser.AsyncActionCollection retry=f.user.beginAsyncActionCollection(state,metadata,"native-exp");
            f.user.giveExp(9);CompletionStage<Void> result=f.user.endAsyncActionCollection(retry);drain(f);result.toCompletableFuture().join();
            verify(f.player).giveExp(9);verify(f.player,never()).giveExp(7);
        });
    }
    @Test void startedFailureRetainsReservationAndCannotRerollItsPayload() {
        fixture(f->{
            RewardOptions options=new RewardOptions();options.setAsyncReplayCheckpointConsumer(checkpoint->{});
            Reward.ReplayState state=Reward.replayStateFor(options);java.util.HashMap<String,String> metadata=new java.util.HashMap<>();
            IllegalStateException uncertain=new IllegalStateException("Native action entered before failure");doThrow(uncertain).when(f.player).giveExp(7);
            AdvancedCoreUser.AsyncActionCollection first=f.user.beginAsyncActionCollection(state,metadata,"native-exp");
            f.user.giveExp(7);CompletionStage<Void> failed=f.user.endAsyncActionCollection(first);drain(f);
            assertSame(uncertain,failure(failed));assertFalse(AdvancedCoreUser.isReplayActionNotStarted(uncertain));
            AdvancedCoreUser.AsyncActionCollection retry=f.user.beginAsyncActionCollection(state,metadata,"native-exp");
            f.user.giveExp(9);CompletionStage<Void> result=f.user.endAsyncActionCollection(retry);drain(f);
            assertInstanceOf(IllegalStateException.class,failure(result));verify(f.player,times(1)).giveExp(7);verify(f.player,never()).giveExp(9);
        });
    }
    private void drain(Fixture f) {
        int count=0;
        while(!f.dispatch.queued.isEmpty() || !f.dispatch.asyncQueued.isEmpty()) {
            assertTrue(count++<100,"Action scope did not settle");
            if(!f.dispatch.queued.isEmpty())f.dispatch.runNext();else f.dispatch.runAsyncNext();
        }
    }

    @Test void experienceWaitsForNativeOwnerExecution() {
        fixture(f -> {
            AdvancedCoreUser.AsyncActionCollection scope=f.user.beginAsyncActionCollection();f.user.giveExp(7);
            verify(f.player,never()).giveExp(anyInt());
            CompletionStage<Void> result=f.user.endAsyncActionCollection(scope);assertFalse(result.toCompletableFuture().isDone());
            f.dispatch.runNext();result.toCompletableFuture().join();verify(f.player).giveExp(7);
        });
    }

    @Test void levelReadsAndMutationBothRunOnTheOwner() {
        fixture(f -> {
            when(f.player.getLevel()).thenAnswer(call->{assertTrue(Bukkit.isPrimaryThread());return 5;});
            AdvancedCoreUser.AsyncActionCollection scope=f.user.beginAsyncActionCollection();f.user.giveExpLevels(3);verify(f.player,never()).getLevel();
            CompletionStage<Void> result=f.user.endAsyncActionCollection(scope);assertFalse(result.toCompletableFuture().isDone());f.dispatch.runNext();result.toCompletableFuture().join();verify(f.player).setLevel(8);
        });
    }
    @Test void disconnectBeforeAdmissionFailsWithoutMutation() {
        fixture(f -> {
            AdvancedCoreUser.AsyncActionCollection scope=f.user.beginAsyncActionCollection();f.user.giveExp(7);CompletionStage<Void> result=f.user.endAsyncActionCollection(scope);
            when(f.player.isOnline()).thenReturn(false);f.dispatch.runNext();assertTrue(AdvancedCoreUser.isReplayActionNotStarted(failure(result)));verify(f.player,never()).giveExp(anyInt());
        });
    }
    @Test void closedCapturedOwnerCannotBeReplacedForAnOldAction() {
        fixture(f -> {
            AdvancedCoreUser.AsyncActionCollection scope=f.user.beginAsyncActionCollection();f.user.giveExp(7);f.dispatch.owner.close();
            ServerThreadRewardDispatch replacement=new ServerThreadRewardDispatch(f.dispatch.plugin);when(f.dispatch.plugin.getRewardDispatch()).thenReturn(replacement);
            try {CompletionStage<Void> result=f.user.endAsyncActionCollection(scope);assertTrue(AdvancedCoreUser.isReplayActionNotStarted(failure(result)));assertTrue(f.dispatch.queued.isEmpty());verify(f.player,never()).giveExp(anyInt());}finally{replacement.close();}
        });
    }
    @Test void timeoutFencesLateOwnerCallbackAndObserverCancellationCannotHideWork() {
        fixture(f -> {
            AdvancedCoreUser.AsyncActionCollection scope=f.user.beginAsyncActionCollection();f.user.giveExp(7);CompletionStage<Void> result=f.user.endAsyncActionCollection(scope);
            assertFalse(result.toCompletableFuture().cancel(true));f.dispatch.deadlines.get(0).run();assertTrue(AdvancedCoreUser.isReplayActionNotStarted(failure(result)));f.dispatch.runNext();verify(f.player,never()).giveExp(anyInt());
        });
    }
    @Test void startedNativeFailureIsNotMisclassifiedAsSafeToReplay() {
        fixture(f -> {
            IllegalStateException nativeFailure=new IllegalStateException("native effect failed after entry");doThrow(nativeFailure).when(f.player).giveExp(7);
            AdvancedCoreUser.AsyncActionCollection scope=f.user.beginAsyncActionCollection();f.user.giveExp(7);CompletionStage<Void> result=f.user.endAsyncActionCollection(scope);f.dispatch.runNext();
            assertSame(nativeFailure,failure(result));assertFalse(AdvancedCoreUser.isReplayActionNotStarted(failure(result)));
        });
    }
    @Test void unscopedExperienceRetainsItsImmediateBehavior() {
        fixture(f -> {f.user.giveExp(7);verify(f.player).giveExp(7);assertTrue(f.dispatch.queued.isEmpty());});
    }
    @Test void moneyDepositAndWithdrawalWaitForActualProviderInvocation() {
        fixture(f -> {
            com.bencodez.advancedcore.VaultHandler vault=mock(com.bencodez.advancedcore.VaultHandler.class);net.milkbowl.vault.economy.Economy economy=mock(net.milkbowl.vault.economy.Economy.class);
            when(f.dispatch.plugin.getVaultHandler()).thenReturn(vault);when(vault.getEcon()).thenReturn(economy);org.bukkit.OfflinePlayer account=mock(org.bukkit.OfflinePlayer.class);doReturn(account).when(f.user).getOfflinePlayer();
            when(economy.depositPlayer(account,5)).thenAnswer(call->{assertTrue(Bukkit.isPrimaryThread());return new net.milkbowl.vault.economy.EconomyResponse(5,10,net.milkbowl.vault.economy.EconomyResponse.ResponseType.SUCCESS,"");});
            when(economy.withdrawPlayer(account,2)).thenAnswer(call->{assertTrue(Bukkit.isPrimaryThread());return new net.milkbowl.vault.economy.EconomyResponse(2,8,net.milkbowl.vault.economy.EconomyResponse.ResponseType.SUCCESS,"");});
            AdvancedCoreUser.AsyncActionCollection scope=f.user.beginAsyncActionCollection();f.user.giveMoney(5);f.user.giveMoney(-2);CompletionStage<Void> result=f.user.endAsyncActionCollection(scope);verifyNoInteractions(economy);
            f.dispatch.runNext();result.toCompletableFuture().join();verify(economy).depositPlayer(account,5);verify(economy).withdrawPlayer(account,2);
        });
    }
    @Test void potionConstructionAndApplicationRemainOnOwner() {
        fixture(f -> {
            org.bukkit.potion.PotionEffectType type=mock(org.bukkit.potion.PotionEffectType.class);
            try(MockedStatic<org.bukkit.potion.PotionEffectType> types=mockStatic(org.bukkit.potion.PotionEffectType.class)) {
                types.when(()->org.bukkit.potion.PotionEffectType.getByName("SPEED")).thenAnswer(call->{assertTrue(Bukkit.isPrimaryThread());return type;});
                when(f.player.addPotionEffect(any())).thenAnswer(call->{assertTrue(Bukkit.isPrimaryThread());org.bukkit.potion.PotionEffect effect=call.getArgument(0);assertEquals(120,effect.getDuration());assertEquals(2,effect.getAmplifier());return true;});
                AdvancedCoreUser.AsyncActionCollection scope=f.user.beginAsyncActionCollection();f.user.givePotionEffect("SPEED",6,2);CompletionStage<Void> result=f.user.endAsyncActionCollection(scope);verify(f.player,never()).addPotionEffect(any());f.dispatch.runNext();result.toCompletableFuture().join();verify(f.player).addPotionEffect(any());
            }
        });
    }
    @Test void temporaryPermissionWaitsForInitialPermissionMutation() {
        fixture(f -> {
            com.bencodez.advancedcore.api.permissions.PermissionHandler permissions=mock(com.bencodez.advancedcore.api.permissions.PermissionHandler.class);when(f.dispatch.plugin.getPermissionHandler()).thenReturn(permissions);
            doAnswer(call->{assertTrue(Bukkit.isPrimaryThread());return null;}).when(permissions).addPermission(f.player,"test.permission",1000L);
            AdvancedCoreUser.AsyncActionCollection scope=f.user.beginAsyncActionCollection();f.user.addPermission("test.permission",1000L);CompletionStage<Void> result=f.user.endAsyncActionCollection(scope);verify(permissions,never()).addPermission(any(Player.class),anyString(),anyLong());f.dispatch.runNext();result.toCompletableFuture().join();verify(permissions).addPermission(f.player,"test.permission",1000L);
        });
    }
    @Test void missingPlayerItemBuilderDoesNotPretendDeliverySucceeded() {
        fixture(f -> {
            doReturn(null).when(f.user).getPlayer();com.bencodez.advancedcore.api.item.ItemBuilder builder=mock(com.bencodez.advancedcore.api.item.ItemBuilder.class);
            AdvancedCoreUser.AsyncActionCollection scope=f.user.beginAsyncActionCollection();f.user.giveItem(builder);CompletionStage<Void> result=f.user.endAsyncActionCollection(scope);assertInstanceOf(IllegalStateException.class,failure(result));verify(builder,never()).toItemStack(any());
        });
    }

    @Test void missingPlayerPlaceholderOverloadUsesTheSameItemBuilderFailureBoundary() {
        fixture(f -> {
            doReturn(null).when(f.user).getPlayer();AdvancedCoreUser.AsyncActionCollection scope=f.user.beginAsyncActionCollection();
            assertDoesNotThrow(()->f.user.giveItem(new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND,3),new java.util.HashMap<>()));
            assertInstanceOf(IllegalStateException.class,failure(f.user.endAsyncActionCollection(scope)));
        });
    }

    private Throwable failure(CompletionStage<?> stage){return assertThrows(CompletionException.class,()->stage.toCompletableFuture().join()).getCause();}

    private void fixture(Consumer<Fixture> test) {
        LegacyRewardDispatchTest.Fixture dispatch=new LegacyRewardDispatchTest.Fixture();
        when(dispatch.plugin.getRewardDispatch()).thenReturn(dispatch.owner);
        try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenAnswer(ignored->dispatch.primary.get());bukkit.when(Bukkit::getScheduler).thenReturn(dispatch.scheduler);
            Player player=mock(Player.class);UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);when(player.isOnline()).thenReturn(true);bukkit.when(()->Bukkit.getPlayer(id)).thenReturn(player);
            AdvancedCoreUser user=mock(AdvancedCoreUser.class,CALLS_REAL_METHODS);doReturn(player).when(user).getPlayer();
            try {java.lang.reflect.Field field=AdvancedCoreUser.class.getDeclaredField("plugin");field.setAccessible(true);field.set(user,dispatch.plugin);}catch(Exception failure){throw new AssertionError(failure);}
            try{test.accept(new Fixture(dispatch,user,player,bukkit));}finally{dispatch.owner.close();}
        }
    }
    static class Fixture {
        final LegacyRewardDispatchTest.Fixture dispatch;final AdvancedCoreUser user;final Player player;final MockedStatic<Bukkit> bukkit;
        Fixture(LegacyRewardDispatchTest.Fixture dispatch,AdvancedCoreUser user,Player player,MockedStatic<Bukkit> bukkit){this.dispatch=dispatch;this.user=user;this.player=player;this.bukkit=bukkit;}
    }
}
