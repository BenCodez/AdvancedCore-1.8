package com.bencodez.advancedcore.api.rewards.injected;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.bukkit.configuration.ConfigurationSection;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.rewards.Reward;
import com.bencodez.advancedcore.api.user.AdvancedCoreUser;

import lombok.Getter;
import lombok.Setter;

public abstract class RewardInjectInt extends RewardInject {

	@Getter
	@Setter
	private int defaultValue = 0;

	public RewardInjectInt(String path) {
		super(path);
	}

	public RewardInjectInt(String path, int defaultValue) {
		super(path);
		this.defaultValue = defaultValue;
	}

	@Override
	public String onRewardRequest(Reward reward, AdvancedCoreUser user, ConfigurationSection data,
			HashMap<String, String> placeholders) {
		if (data.isInt(getPath()) || (isAlwaysForce() && data.contains(getPath())) || isAlwaysForceNoData()) {
			int value = data.getInt(getPath(), getDefaultValue());
			AdvancedCorePlugin.getInstance()
					.extraDebug(reward.getRewardName() + ": Giving " + getPath() + ", value: " + value);
			String re = onRewardRequest(reward, user, value, placeholders);
			if (re == null) {
				return "" + value;
			}
			return re;
		}
		return null;
	}

	/** Typed async adapter with the same configuration/default semantics as the legacy callback. */
	@Override
	public CompletionStage<Object> onRewardRequestAsync(Reward reward, AdvancedCoreUser user,
			ConfigurationSection data, HashMap<String, String> placeholders) {
		try {
			if (data.isInt(getPath()) || (isAlwaysForce() && data.contains(getPath())) || isAlwaysForceNoData()) {
				int value = data.getInt(getPath(), getDefaultValue());
				AdvancedCorePlugin.getInstance()
						.extraDebug(reward.getRewardName() + ": Giving " + getPath() + ", value: " + value);
				CompletionStage<String> result = onRewardRequestAsync(reward, user, value, placeholders);
				if (result == null) throw new IllegalStateException("Integer reward injection returned null stage");
				return result.thenApply(output -> (Object) (output == null ? String.valueOf(value) : output));
			}
			return CompletableFuture.completedFuture(null);
		} catch (Throwable failure) {
			CompletableFuture<Object> failed = new CompletableFuture<>();
			failed.completeExceptionally(failure);
			return failed;
		}
	}

	/** Legacy implementations inherit a synchronous bridge until they opt in to async dispatch. */
	public CompletionStage<String> onRewardRequestAsync(Reward reward, AdvancedCoreUser user, int num,
			HashMap<String, String> placeholders) {
		CompletableFuture<String> result = new CompletableFuture<>();
		try { result.complete(onRewardRequest(reward, user, num, placeholders)); }
		catch (Throwable failure) { result.completeExceptionally(failure); }
		return result;
	}

	public abstract String onRewardRequest(Reward reward, AdvancedCoreUser user, int num,
			HashMap<String, String> placeholders);

}
