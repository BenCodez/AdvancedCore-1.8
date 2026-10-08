package com.bencodez.advancedcore.api.rewards.injected;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import org.bukkit.configuration.ConfigurationSection;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.inventory.editgui.EditGUIButton;
import com.bencodez.advancedcore.api.rewards.DefinedReward;
import com.bencodez.advancedcore.api.rewards.Inject;
import com.bencodez.advancedcore.api.rewards.Reward;
import com.bencodez.advancedcore.api.rewards.SubDirectlyDefinedReward;
import com.bencodez.advancedcore.api.user.AdvancedCoreUser;

import lombok.Getter;
import lombok.Setter;

public abstract class RewardInject extends Inject {
	private CompletableFuture<Void> synchronizedAsyncTail = CompletableFuture.completedFuture(null);

	@Getter
	private boolean addAsPlaceholder = false;

	@Getter
	private boolean alwaysForce = false;

	@Getter
	private boolean alwaysForceNoData = false;

	@Getter
	private Object object;

	@Getter
	private String placeholderName;

	@Getter
	private boolean postReward = false;

	@Getter
	@Setter
	private boolean synchronize = false;

	@Getter
	private boolean alwaysValid = false;

	@Getter
	private RewardInjectValidator validate;

	public RewardInject(String path) {
		super(path);
	}

	public RewardInject addEditButton(EditGUIButton button) {
		getEditButtons().add(button);
		return this;
	}

	public RewardInject alwaysForce() {
		this.alwaysForce = true;
		return this;
	}

	public RewardInject alwaysForceNoData() {
		this.alwaysForce = true;
		this.alwaysForceNoData = true;
		return this;
	}

	public RewardInject alwaysValid() {
		alwaysValid = true;
		return this;
	}

	public RewardInject asPlaceholder(String placeholderName) {
		addAsPlaceholder = true;
		this.placeholderName = placeholderName;
		return this;
	}

	public void debug(String str) {
		AdvancedCorePlugin.getInstance().debug(str);
	}

	public void extraDebug(String str) {
		AdvancedCorePlugin.getInstance().extraDebug(str);
	}

	public boolean hasValidator() {
		return getValidate() != null;
	}

	public boolean isEditable() {
		return !getEditButtons().isEmpty();
	}

	public abstract Object onRewardRequest(Reward reward, AdvancedCoreUser user, ConfigurationSection data,
			HashMap<String, String> placeholders);

	/** Existing injectors remain synchronous until they explicitly opt in. */
	public boolean supportsAsyncRequest() { return false; }

	public boolean requiresConfiguredDataForAsync() { return false; }

	public boolean hasPendingReplayWork(HashMap<String, String> placeholders) { return false; }

	/** Nested reward injectors must opt out to avoid waiting on their own unfinished chain. */
	public boolean supportsAsyncSynchronization() { return true; }

	/** Bridge for legacy synchronous callbacks; it does not observe hidden scheduled work. */
	public CompletionStage<Object> onRewardRequestAsync(Reward reward, AdvancedCoreUser user,
			ConfigurationSection data, HashMap<String, String> placeholders) {
		CompletableFuture<Object> result = new CompletableFuture<>();
		try { result.complete(onRewardRequest(reward, user, data, placeholders)); }
		catch (Throwable failure) { result.completeExceptionally(failure); }
		return result;
	}

	/** Additive checkpoint hook; no checkpoint is inferred from dispatch acceptance. */
	public CompletionStage<Void> onReplayCheckpointPersisted(Reward reward, AdvancedCoreUser user,
			String occurrenceId, String injectionKey) {
		return CompletableFuture.completedFuture(null);
	}

	/** Serialize opted-in async requests until their returned stages settle. */
	public CompletionStage<Object> runSynchronizedAsync(Supplier<CompletionStage<Object>> request) {
		java.util.Objects.requireNonNull(request, "request");
		CompletableFuture<Object> result = new CompletableFuture<>();
		CompletableFuture<Void> finished = new CompletableFuture<>();
		CompletableFuture<Void> previous;
		synchronized (this) {
			previous = synchronizedAsyncTail;
			synchronizedAsyncTail = finished;
		}
		// User code and stage callbacks run outside the injection monitor.
		previous.handle((ignored, failure) -> null).thenRun(() -> {
			try {
				CompletionStage<Object> stage = request.get();
				if (stage == null) throw new IllegalStateException("Asynchronous reward injection returned null");
				stage.whenComplete((value, failure) -> {
					try {
						if (failure == null) result.complete(value);
						else result.completeExceptionally(failure);
					} finally { finished.complete(null); }
				});
			} catch (Throwable failure) {
				try { result.completeExceptionally(failure); }
				finally { finished.complete(null); }
			}
		});
		return result;
	}

	public RewardInject postReward() {
		postReward = true;
		return this;
	}

	public RewardInject priority(int priority) {
		setPriority(priority);
		return this;
	}

	public ArrayList<SubDirectlyDefinedReward> subRewards(DefinedReward direct) {
		return new ArrayList<>();
	}

	public RewardInject synchronize() {
		synchronize = true;
		object = new Object();
		return this;
	}

	public void validate(Reward reward, ConfigurationSection data) {
		if (validate != null && data.contains(getPath())) {
			validate.onValidate(reward, this, data);
		}
	}

	public RewardInject validator(RewardInjectValidator validate) {
		this.validate = validate;
		return this;
	}
}
