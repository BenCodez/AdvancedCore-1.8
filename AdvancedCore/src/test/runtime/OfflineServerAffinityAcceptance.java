import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import com.bencodez.votingplugin.VotingPluginMain;
import com.bencodez.votingplugin.advancedcore.api.rewards.*;
import com.bencodez.votingplugin.advancedcore.api.rewards.injected.RewardInject;
import com.bencodez.votingplugin.advancedcore.api.user.AdvancedCoreUser;

/** Manual real Java 8/Spigot fixture; never shipped in a plugin artifact. */
public final class OfflineServerAffinityAcceptance extends JavaPlugin {
    private final AtomicInteger effects = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicReference<Reward.ReplayState> completedState = new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<String> completedOccurrence = new java.util.concurrent.atomic.AtomicReference<>();
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) || args.length != 1) return false;
        Player player = Bukkit.getPlayerExact(args[0]);
        if (player == null) throw new AssertionError("Connected player required");
        VotingPluginMain main = (VotingPluginMain) Bukkit.getPluginManager().getPlugin("VotingPlugin");
        UUID uuid = player.getUniqueId(); String name = player.getName();
        main.getOptions().setServer("backend-a");
        main.getRewardHandler().getInjectedRewards().add(new RewardInject("AffinityEffect") {
            public Object onRewardRequest(Reward reward, AdvancedCoreUser user,
                    org.bukkit.configuration.ConfigurationSection data, HashMap<String,String> placeholders) {
                if (!data.getBoolean("AffinityEffect")) return null;
                if (!Bukkit.isPrimaryThread()) throw new AssertionError("Effect must run on owner");
                completedState.set(Reward.currentReplayState());
                completedOccurrence.set(Reward.currentReplayOccurrenceId());
                effects.incrementAndGet(); return null;
            }
        });
        YamlConfiguration data = new YamlConfiguration();
        data.set("Server", "backend-b"); data.set("AffinityEffect", true);
        Reward normal = new Reward("affinity-normal", data);
        main.getRewardHandler().getRewards().add(normal);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                AdvancedCoreUser user = main.getVotingPluginUserManager().getVotingPluginUser(uuid, name);
                user.addOfflineRewards(normal, new HashMap<String,String>());
                user.checkOfflineRewardsAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
                String path = main.getUserManager().getOfflineRewardsPath();
                String deferred = user.getUserData().getValuesStrict().get(path).getString();
                if (!deferred.contains("%asyncoccurrence%") || effects.get() != 0)
                    throw new AssertionError("Wrong backend consumed or executed queued reward");
                user.checkOfflineRewardsAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
                if (!deferred.equals(user.getUserData().getValuesStrict().get(path).getString()) || effects.get() != 0)
                    throw new AssertionError("Deferred polling changed occurrence or executed effect");
                getLogger().info("affinity-wrong-backend-retains-same-occurrence-without-effects");
                main.getRewardDispatch().dispatch(() -> {
                    normal.getConfig().getConfigData().set("Server", "backend-a");
                    return CompletableFuture.<Void>completedFuture(null);
                }, 10000).toCompletableFuture().get(10, TimeUnit.SECONDS);
                user.checkOfflineRewardsAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
                if (!user.getUserData().getValuesStrict().get(path).getString().isEmpty() || effects.get() != 1)
                    throw new AssertionError("Matching backend did not consume exactly one occurrence");
                getLogger().info("affinity-matching-backend-delivers-and-removes-once");
                RewardOptions recovered = new RewardOptions();
                recovered.setAsyncReplayState(completedState.get());
                recovered.setAsyncReplayOccurrenceId(completedOccurrence.get());
                completedState.get().recordReplayMetadata("__advancedcore_replay_selection_fixture", "frozen");
                user.addOfflineRewards(normal, new HashMap<String,String>(), recovered);
                String retained = user.getUserData().getValuesStrict().get(path).getString();
                if (!retained.contains("%asyncprogress%v3-") || !retained.contains("%asyncoccurrence%" + completedOccurrence.get())
                        || !retained.contains("__advancedcore_replay_selection_fixture"))
                    throw new AssertionError("Deferred public options lost occurrence, progress or frozen metadata");
                user.checkOfflineRewardsAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
                if (effects.get() != 1 || !user.getUserData().getValuesStrict().get(path).getString().isEmpty())
                    throw new AssertionError("Completed effect repeated after options round trip");
                getLogger().info("affinity-deferred-options-preserve-completed-prefix-without-repeating-effect");
                Reward forced = main.getRewardDispatch().dispatch(() -> {
                    YamlConfiguration forcedData = new YamlConfiguration();
                    forcedData.set("Server", "backend-b"); forcedData.set("AffinityEffect", true);
                    Reward value = new Reward("affinity-forced", forcedData);
                    main.getRewardHandler().getRewards().add(value);
                    return CompletableFuture.completedFuture(value);
                }, 10000).toCompletableFuture().get(10, TimeUnit.SECONDS);
                user.addOfflineRewards(forced, new HashMap<String,String>());
                user.forceRunOfflineRewards();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (effects.get() != 2 || !user.getUserData().getValuesStrict().get(path).getString().isEmpty()) {
                    if (System.nanoTime() > deadline) throw new AssertionError("Explicit force did not settle");
                    Thread.sleep(20);
                }
                getLogger().info("affinity-explicit-force-preserves-override-and-durable-removal");
                getLogger().info("affinity-acceptance-complete");
            } catch (Throwable failure) {
                getLogger().severe("AFFINITY ACCEPTANCE FAILED"); failure.printStackTrace();
            }
        });
        return true;
    }
}
