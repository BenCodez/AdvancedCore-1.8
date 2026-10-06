package com.bencodez.advancedcore.api.rewards;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.AdvancedCoreUser;
import com.bencodez.advancedcore.api.user.PersistedQueueReference;

class LegacyPersistedRewardResolutionTest {
    @TempDir File directory;
    @Test void legacyRegisteredRewardWinsOverStaleGeneratedSnapshotAndWaitsForCompletion() {
        fixture(f->{
            Reward reward=mock(Reward.class);when(reward.getName()).thenReturn("daily");when(reward.getConfig()).thenReturn(mock(RewardFileData.class));when(f.handler.getRewards()).thenReturn(Collections.singletonList(reward));
            CompletableFuture<Void> effect=new CompletableFuture<>();when(reward.giveRewardAsync(eq(f.user),any())).thenReturn(effect);
            CompletionStage<Void> result=f.handler.givePersistedQueueRewardAsync(f.user,reference("daily"),new RewardOptions());
            assertFalse(result.toCompletableFuture().isDone());verify(f.handler,never()).getQueuedGeneratedReward(any(),any());
            effect.complete(null);result.toCompletableFuture().join();
        });
    }
    @Test void explicitNormalModeDoesNotBorrowGeneratedSnapshot() {
        fixture(f->{
            assertThrows(CompletionException.class,()->f.handler.givePersistedQueueRewardAsync(f.user,reference(encoded("normal","missing")),new RewardOptions()).toCompletableFuture().join());
            verify(f.handler,never()).getQueuedGeneratedReward(any(),any());verify(f.handler,never()).getReward(anyString());
        });
    }
    @Test void explicitSnapshotModeRetainsSnapshotProvenanceEvenWhenLiveRewardExists() {
        fixture(f->{
            Reward snapshot=mock(Reward.class);doReturn(snapshot).when(f.handler).getQueuedGeneratedReward("daily","user");when(snapshot.giveRewardAsync(eq(f.user),any())).thenReturn(CompletableFuture.completedFuture(null));
            f.handler.givePersistedQueueRewardAsync(f.user,reference(encoded("snapshot","daily")),new RewardOptions()).toCompletableFuture().join();
            verify(snapshot).giveRewardAsync(eq(f.user),any());verify(f.handler,never()).getReward(anyString());
        });
    }
    @Test void generatedFileInLegacyGlobalRegistryDoesNotBecomeNormalQueueReward() {
        fixture(f->{
            Reward stale=mock(Reward.class);RewardFileData config=mock(RewardFileData.class);when(stale.getName()).thenReturn("daily");when(stale.getConfig()).thenReturn(config);when(config.isDirectlyDefinedReward()).thenReturn(true);when(f.handler.getRewards()).thenReturn(Collections.singletonList(stale));
            Reward restricted=mock(Reward.class);doReturn(restricted).when(f.handler).getQueuedGeneratedReward("daily","user");when(restricted.giveRewardAsync(eq(f.user),any())).thenReturn(CompletableFuture.completedFuture(null));
            f.handler.givePersistedQueueRewardAsync(f.user,reference("daily"),new RewardOptions()).toCompletableFuture().join();verify(restricted).giveRewardAsync(eq(f.user),any());verify(stale,never()).giveRewardAsync(any(),any());
            assertThrows(CompletionException.class,()->f.handler.givePersistedQueueRewardAsync(f.user,reference(encoded("normal","daily")),new RewardOptions()).toCompletableFuture().join());
        });
    }

    @Test void malformedOrCommandReferencesCannotCreateFilesOrExecuteCommands() {
        fixture(f->{
            try(MockedConstruction<Reward> constructed=mockConstruction(Reward.class)) {
                for(String ref:Arrays.asList("/say test","../outside","\\AdvancedCoreQueue/1/other/YQ","\\AdvancedCoreQueue/1/normal/!","\\AdvancedCoreQueue/1/normal/"))
                    assertThrows(CompletionException.class,()->f.handler.givePersistedQueueRewardAsync(f.user,reference(ref),new RewardOptions()).toCompletableFuture().join());
                assertTrue(constructed.constructed().isEmpty());
            }
        });
    }
    @Test void missingLegacyReferenceDoesNotCreateAnEmptyReward() {
        fixture(f->{
            try(MockedConstruction<Reward> constructed=mockConstruction(Reward.class)) {
                assertThrows(CompletionException.class,()->f.handler.givePersistedQueueRewardAsync(f.user,reference("missing"),new RewardOptions()).toCompletableFuture().join());
                assertTrue(constructed.constructed().isEmpty());assertFalse(new File(directory,"missing.yml").exists());
            }
        });
    }
    @Test void generatedLoaderRequiresExplicitMarkerAndBindsOnlyRequestedUser() throws Exception {
        File folder=new File(directory,"DirectlyDefined");assertTrue(folder.mkdir());File file=new File(folder,"daily.yml");
        Files.write(file.toPath(),"DirectlyDefinedReward: false\nEXP: 7\n".getBytes(StandardCharsets.UTF_8));
        fixture(f->{assertNull(f.handler.getQueuedGeneratedReward("daily","user"));});
        Files.write(file.toPath(),"DirectlyDefinedReward: true\nEXP: 7\n".getBytes(StandardCharsets.UTF_8));
        fixture(f->{
            List<List<?>> arguments=new ArrayList<>();
            try(MockedConstruction<QueuedGeneratedReward> constructed=mockConstruction(QueuedGeneratedReward.class,(mock,context)->arguments.add(context.arguments()))) {
                Reward loaded=f.handler.getQueuedGeneratedReward("daily","user");assertSame(constructed.constructed().get(0),loaded);
                assertEquals(Collections.singleton("user"),arguments.get(0).get(2));
            }
        });
    }
    @Test void capturedGeneratedSnapshotDoesNotReopenOrRecreateDeletedFile() throws Exception {
        File folder=new File(directory,"DirectlyDefined");assertTrue(folder.mkdir());File file=new File(folder,"daily.yml");
        Files.write(file.toPath(),"DirectlyDefinedReward: true\nEXP: 7\n".getBytes(StandardCharsets.UTF_8));
        fixture(f->{
            try(MockedConstruction<RewardFileData> data=mockConstruction(RewardFileData.class,(mock,context)->{
                assertTrue(context.arguments().get(1) instanceof org.bukkit.configuration.ConfigurationSection);
                org.bukkit.configuration.ConfigurationSection captured=(org.bukkit.configuration.ConfigurationSection)context.arguments().get(1);
                assertEquals(7,captured.getInt("EXP"));when(mock.getConfigData()).thenReturn(captured);
                Files.delete(file.toPath());
            })) {
                Reward loaded=f.handler.getQueuedGeneratedReward("daily","user");
                assertEquals(file,loaded.getFile());assertTrue(loaded.isGeneratedSnapshotCreated());
                loaded.checkRewardFile();assertFalse(file.exists());assertEquals(1,data.constructed().size());
                verify(data.constructed().get(0),never()).saveStrict(any());
            }catch(java.io.IOException failure){throw new AssertionError(failure);}
        });
    }

    @Test void malformedGeneratedYamlIsFailureRatherThanAnEmptyReward() throws Exception {
        File folder=new File(directory,"DirectlyDefined");assertTrue(folder.mkdir());Files.write(new File(folder,"daily.yml").toPath(),"DirectlyDefinedReward: [\n".getBytes(StandardCharsets.UTF_8));
        fixture(f->{assertThrows(IllegalStateException.class,()->f.handler.getQueuedGeneratedReward("daily","user"));});
    }
    @Test void disabledRuntimeCannotResolveOrDeliverPersistedReward() {
        fixture(f->{when(f.plugin.isEnabled()).thenReturn(false);assertThrows(CompletionException.class,()->f.handler.givePersistedQueueRewardAsync(f.user,reference("daily"),new RewardOptions()).toCompletableFuture().join());verify(f.handler,never()).getReward(anyString());});
    }
    @Test void generatedSnapshotPublicationFailureDoesNotMarkCreatedOrRegisterIt() {
        fixture(f->{
            Reward source=new Reward("direct",new org.bukkit.configuration.file.YamlConfiguration());Reward snapshot=mock(Reward.class);RewardFileData data=mock(RewardFileData.class);
            doReturn(snapshot).when(f.handler).getRewardDirectlyDefined("direct");when(f.plugin.getRewardHandler()).thenReturn(f.handler);when(snapshot.getConfig()).thenReturn(data);when(data.getFileData()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
            try {doThrow(new java.io.IOException("snapshot write failed")).when(data).saveStrict(any());}catch(java.io.IOException impossible){throw new AssertionError(impossible);}
            assertThrows(IllegalStateException.class,source::checkRewardFile);assertFalse(source.isGeneratedSnapshotCreated());verify(f.handler,never()).updateReward(snapshot);
        });
    }

    private String encoded(String mode,String name){return "\\AdvancedCoreQueue/1/"+mode+"/"+Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8));}
    private PersistedQueueReference reference(String value) {
        try {java.lang.reflect.Constructor<PersistedQueueReference> constructor=PersistedQueueReference.class.getDeclaredConstructor(String.class);constructor.setAccessible(true);return constructor.newInstance(value);}
        catch(Exception failure){throw new AssertionError(failure);}
    }
    private void fixture(Consumer<Fixture> body) {
        AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);when(plugin.isEnabled()).thenReturn(true);when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        ServerThreadRewardDispatch owner=new ServerThreadRewardDispatch(plugin);when(plugin.getRewardDispatch()).thenReturn(owner);
        try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class);MockedStatic<AdvancedCorePlugin> global=mockStatic(AdvancedCorePlugin.class)) {
            global.when(AdvancedCorePlugin::getInstance).thenReturn(plugin);bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            RewardHandler handler=mock(RewardHandler.class,CALLS_REAL_METHODS);handler.plugin=plugin;when(handler.getDefaultFolder()).thenReturn(directory);
            when(handler.getRewards()).thenReturn(Collections.emptyList());when(handler.getDirectlyDefinedRewards()).thenReturn(new ArrayList<>());when(handler.getSubDirectlyDefinedRewards()).thenReturn(new ArrayList<>());
            AdvancedCoreUser user=mock(AdvancedCoreUser.class);when(user.getUUID()).thenReturn("user");
            try{body.accept(new Fixture(plugin,handler,user));}finally{owner.close();RewardHandler.getInstance().getRepeatTimer().cancel();}
        }
    }
    static class Fixture {
        final AdvancedCorePlugin plugin;final RewardHandler handler;final AdvancedCoreUser user;
        Fixture(AdvancedCorePlugin plugin,RewardHandler handler,AdvancedCoreUser user){this.plugin=plugin;this.handler=handler;this.user=user;}
    }
}
