package com.bencodez.advancedcore.api.rewards;

import java.util.HashMap;
import java.util.Map.Entry;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.simpleapi.array.ArrayUtils;

import lombok.Getter;
import lombok.Setter;

public class RewardOptions {

    @Getter @Setter private int completedAsyncInjections;
    @Getter @Setter private java.util.Map<String,Integer> asyncReplayProgress = new HashMap<>();
    @Getter @Setter private java.util.Map<String,String> asyncReplayRegistryFingerprints = new HashMap<>();
    @Getter @Setter private boolean legacyAsyncReplayCheckpoint;
    @Getter @Setter private Reward.ReplayState asyncReplayState;
    @Getter @Setter private String asyncReplayKey;
    @Getter @Setter private String asyncReplayOccurrenceId;
    @Getter @Setter private java.util.function.Consumer<Reward.ReplayCheckpoint> asyncReplayCheckpointConsumer;
    @Getter private boolean livePlayerStateSet;
    @Getter private boolean livePlayerVanished;
    public RewardOptions captureLivePlayerState(boolean online, boolean vanished) {
        this.online=online;this.onlineSet=true;this.livePlayerStateSet=true;this.livePlayerVanished=vanished;return this;
    }


	@Getter
	private boolean checkRepeat = true;
	private boolean checkTimed = true;

	@Getter
	private boolean forceOffline = false;

	private boolean giveOffline = true;

	private boolean ignoreChance = false;

	@Getter
	private boolean ignoreRequirements = false;

	private boolean online = true;

	@Getter
	@Setter
	private boolean onlineSet = false;

	private HashMap<String, String> placeholders = new HashMap<>();

	private String prefix = "";

	@Getter
	private String server = "";

	private String suffix = "";

	@Getter
	private boolean useDefaultWorlds = true;

	@Getter
	private long orginalTrigger = -1;

	public RewardOptions() {
	}

	/** Copy one dispatch's mutable options before crossing a scheduler boundary. */
	RewardOptions copyForDispatch() {
		RewardOptions copy = new RewardOptions();
		copy.checkRepeat = checkRepeat;
		copy.checkTimed = checkTimed;
		copy.forceOffline = forceOffline;
		copy.giveOffline = giveOffline;
		copy.ignoreChance = ignoreChance;
		copy.ignoreRequirements = ignoreRequirements;
		copy.online = online;
		copy.onlineSet = onlineSet;
		copy.placeholders = new HashMap<>(placeholders);
		copy.prefix = prefix;
		copy.server = server;
		copy.suffix = suffix;
		copy.useDefaultWorlds = useDefaultWorlds;
		copy.orginalTrigger = orginalTrigger;
		copy.completedAsyncInjections=completedAsyncInjections;
		copy.asyncReplayProgress=new HashMap<>(asyncReplayProgress);
		copy.asyncReplayRegistryFingerprints=new HashMap<>(asyncReplayRegistryFingerprints);
		copy.legacyAsyncReplayCheckpoint=legacyAsyncReplayCheckpoint;
        copy.asyncReplayState=asyncReplayState;
        copy.asyncReplayKey=asyncReplayKey;
        copy.asyncReplayOccurrenceId=asyncReplayOccurrenceId;
        copy.asyncReplayCheckpointConsumer=asyncReplayCheckpointConsumer;
		copy.livePlayerStateSet=livePlayerStateSet;
		copy.livePlayerVanished=livePlayerVanished;
		return copy;
	}

	public RewardOptions addPlaceholder(String arg1, String arg2) {
		getPlaceholders().put(arg1, arg2);
		return this;
	}

	public RewardOptions disableDefaultWorlds() {
		useDefaultWorlds = false;
		return this;
	}

	public RewardOptions forceOffline() {
		forceOffline = true;
		return this;
	}

	public HashMap<String, String> getPlaceholders() {
		return placeholders;
	}

	/**
	 * @return the prefix
	 */
	public String getPrefix() {
		return prefix;
	}

	/**
	 * @return the suffix
	 */
	public String getSuffix() {
		return suffix;
	}

	public boolean isCheckTimed() {
		return checkTimed;
	}

	public boolean isGiveOffline() {
		return giveOffline;
	}

	public boolean isIgnoreChance() {
		return ignoreChance;
	}

	public boolean isOnline() {
		return online;
	}

	public RewardOptions orginalTrigger(long trigger) {
		orginalTrigger = trigger;
		return this;
	}

	public RewardOptions setCheckRepeat(boolean checkRepeat) {
		this.checkRepeat = checkRepeat;
		return this;
	}

	public RewardOptions setCheckTimed(boolean checkTimed) {
		this.checkTimed = checkTimed;
		return this;
	}

	public RewardOptions setGiveOffline(boolean giveOffline) {
		this.giveOffline = giveOffline;
		return this;
	}

	public RewardOptions setIgnoreChance(boolean ignoreChance) {
		this.ignoreChance = ignoreChance;
		return this;
	}

	public RewardOptions setIgnoreRequirements(boolean ignoreRequirements) {
		this.ignoreRequirements = ignoreRequirements;
		return this;
	}

	public RewardOptions setOnline(boolean online) {
		this.online = online;
		this.onlineSet = true;
		return this;
	}

	public RewardOptions setPlaceholders(HashMap<String, String> placeholders) {
		this.placeholders = placeholders;
		return this;
	}

	public RewardOptions setPrefix(String prefix) {
		this.prefix = prefix;
		return this;
	}

	public RewardOptions setServer(boolean b) {
		if (b) {
			this.server = AdvancedCorePlugin.getInstance().getOptions().getServer();
			addPlaceholder("Server", this.server);
		}
		return this;
	}

	public RewardOptions setServer(String server) {
		this.server = server;
		addPlaceholder("Server", this.server);
		return this;
	}

	public RewardOptions setSuffix(String suffix) {
		this.suffix = suffix;
		return this;
	}

	@Override
	public String toString() {
		String str = "Online: " + online + ", ";
		str += "OnlineSet: " + onlineSet + ", ";
		str += "GiveOffline: " + giveOffline + ", ";
		str += "ForceOffline: " + forceOffline + ", ";
		str += "CheckTimed: " + checkTimed + ", ";
		str += "IgnoreChance: " + ignoreChance + ", ";
		str += "IgnoreRequirements: " + ignoreRequirements + ", ";
		str += "Placeholders: " + ArrayUtils.makeString(placeholders) + ", ";
		str += "Prefix: " + prefix + ", ";
		str += "Suffix: " + suffix;
		return str;

	}

	public RewardOptions withPlaceHolder(HashMap<String, String> placeholders2) {
		for (Entry<String, String> entry : placeholders2.entrySet()) {
			placeholders.put(entry.getKey(), entry.getValue());
		}
		return this;
	}

	RewardOptions copyForNestedDispatch(String replayKey) {
		RewardOptions copy = copyForDispatch();
		copy.setCompletedAsyncInjections(0);
		copy.setAsyncReplayProgress(new HashMap<>());
		// The checkpoint consumer belongs to the queued parent occurrence. Nested
		// children share its ReplayState but must never replace or complete that
		// queue entry independently.
		copy.setAsyncReplayCheckpointConsumer(null);
		copy.setAsyncReplayKey(replayKey);
		return copy;
	}

}
