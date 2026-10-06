package com.bencodez.advancedcore.api.inventory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.inventory.BInventory.ClickEvent;
import com.bencodez.advancedcore.api.item.ItemBuilder;
import com.bencodez.simpleapi.player.PlayerUtils;

import lombok.Getter;

public abstract class UpdatingBInventoryButton extends BInventoryButton {
	@Getter
	private final long delay;
	@Getter
	private final long updateInterval;
	private final AdvancedCorePlugin plugin;
	@Getter
	private boolean updateOnClick = false;
	@Getter
	private long clickUpdateDelay = 0;

	public UpdatingBInventoryButton(AdvancedCorePlugin plugin, ItemBuilder item, long delay, long updateInterval) {
		super(item);
		this.plugin = plugin;
		this.updateInterval = updateInterval;
		this.delay = delay;
	}

	public UpdatingBInventoryButton(AdvancedCorePlugin plugin, ItemStack item, long delay, long updateInterval) {
		super(item);
		this.plugin = plugin;
		this.updateInterval = updateInterval;
		this.delay = delay;
	}

	public UpdatingBInventoryButton(AdvancedCorePlugin plugin, String name, String[] lore, ItemStack item, long delay,
			long updateInterval) {
		super(name, lore, item);
		this.plugin = plugin;
		this.updateInterval = updateInterval;
		this.delay = delay;
	}

	public UpdatingBInventoryButton delay(long milliseconds) {
		this.clickUpdateDelay = milliseconds;
		return this;
	}

	@Override
	public void load(Player player) {
		BInventory inventory = getInv();
		if (inventory == null) {
			return;
		}
		Inventory target = inventory.getRenderingInventory();
		AtomicBoolean queued = new AtomicBoolean();
		inventory.addUpdatingButton(player, plugin, delay, updateInterval, () -> {
			if (queued.compareAndSet(false, true)) {
				scheduleUpdate(inventory, target, player, true, queued);
			}
		});
	}

	@Override
	public void onClick(ClickEvent event, BInventory inventory) {
		super.onClick(event, inventory);
		if (!updateOnClick) {
			return;
		}

		if (clickUpdateDelay > 0) {
			inventory.addDelayedTask(event.getPlayer(), plugin, clickUpdateDelay,
					() -> scheduleUpdate(inventory, event.getInventory(), event.getPlayer(), false, null));
		} else {
			update(event.getPlayer());
		}
	}

	public abstract ItemBuilder onUpdate(Player player);

	public void update(Player player) {
		scheduleUpdate(getInv(), null, player, false, null);
	}

	public UpdatingBInventoryButton updateOnClick() {
		updateOnClick = true;
		return this;
	}

	private void scheduleUpdate(BInventory inventory, Inventory target, Player player, boolean cancelWhenUnavailable,
			AtomicBoolean queued) {
		if (inventory == null) {
			if (queued != null) queued.set(false);
			return;
		}
		if (!plugin.isEnabled()) {
			cancelIfRequested(inventory, player, cancelWhenUnavailable);
			if (queued != null) queued.set(false);
			return;
		}

		try {
			plugin.getBukkitScheduler().runTask(plugin, () -> {
				try {
					applyUpdate(inventory, target, player, cancelWhenUnavailable);
				} finally {
					if (queued != null) queued.set(false);
				}
			}, player);
		} catch (RuntimeException failure) {
			if (queued != null) queued.set(false);
			throw failure;
		}
	}

	private void applyUpdate(BInventory inventory, Inventory target, Player player, boolean cancelWhenUnavailable) {
		if (inventory == null || player == null || !plugin.isEnabled()) {
			cancelIfRequested(inventory, player, cancelWhenUnavailable);
			return;
		}

		if (!inventory.isOpen(player)) {
			cancelIfRequested(inventory, player, cancelWhenUnavailable);
			return;
		}
		Inventory topInventory = PlayerUtils.getTopInventory(player);
		if (topInventory == null || (target != null && !topInventory.equals(target))) {
			// An old page's queued update must not touch or cancel its replacement.
			return;
		}
		if (plugin.isLoadUserData() && !plugin.getUserManager().getDataManager().isCached(player.getUniqueId())) {
			return;
		}

		try {
			ItemBuilder builder = onUpdate(player);
			if (builder == null) {
				cancelIfRequested(inventory, player, cancelWhenUnavailable);
				return;
			}

			ItemStack item = builder.toItemStack(player);
			if (item == null) {
				cancelIfRequested(inventory, player, cancelWhenUnavailable);
				return;
			}

			List<Integer> fillSlots = getFillSlots();
			if (fillSlots != null && !fillSlots.isEmpty()) {
				for (Integer slot : fillSlots) {
					if (slot != null) {
						setDisplayedItem(inventory, topInventory, slot.intValue(), item);
					}
				}
			} else {
				setDisplayedItem(inventory, topInventory, getSlot(), item);
			}
		} catch (Exception exception) {
			plugin.debug(exception);
			cancelIfRequested(inventory, player, cancelWhenUnavailable);
		}
	}

	private void setDisplayedItem(BInventory inventory, Inventory topInventory, int sourceSlot,
			ItemStack item) {
		int displayedSlot = sourceSlot;
		if (inventory.isPages()) {
			GUISession session = GUISession.extractSession(topInventory);
			if (session == null || session.getInventoryGUI() != inventory) {
				return;
			}
			displayedSlot -= InventoryPagination.getButtonSlot(session.getPage(), 0, inventory.getMaxInvSize());
			if (!InventoryPagination.isContentSlot(displayedSlot, inventory.getMaxInvSize())) {
				return;
			}
		}
		if (displayedSlot >= 0 && displayedSlot < topInventory.getSize()) {
			topInventory.setItem(displayedSlot, item);
		}
	}

	private void cancelIfRequested(BInventory inventory, Player player, boolean cancelWhenUnavailable) {
		if (cancelWhenUnavailable && inventory != null) {
			inventory.cancelTimer(player);
		}
	}
}
