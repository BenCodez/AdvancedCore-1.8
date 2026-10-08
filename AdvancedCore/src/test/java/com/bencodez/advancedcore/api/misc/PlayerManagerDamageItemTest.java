package com.bencodez.advancedcore.api.misc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Random;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class PlayerManagerDamageItemTest {
	private final PlayerManager manager = PlayerManager.getInstance();

	@Test
	void missingItemAndMetaAreSafe() {
		assertFalse(manager.damageItemInHand(null, 1));
		Player player = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(player.getInventory()).thenReturn(inventory);
		assertFalse(manager.damageItemInHand(player, 1));
		ItemStack item = mock(ItemStack.class);
		when(inventory.getItemInHand()).thenReturn(item);
		when(item.getType()).thenReturn(Material.AIR);
		assertFalse(manager.damageItemInHand(player, 1));
		when(item.getType()).thenReturn(Material.DIAMOND_SWORD);
		assertFalse(manager.damageItemInHand(player, 1));
	}

	@Test
	void nonPositiveDamageAndUnbreakableItemDoNotChangeDurability() {
		Fixture fixture = new Fixture(3);
		assertTrue(manager.damageItemInHand(fixture.player, 0));
		assertTrue(manager.damageItemInHand(fixture.player, -100));
		verify(fixture.item, never()).setDurability(anyShort());
		when(fixture.spigot.isUnbreakable()).thenReturn(true);
		assertFalse(manager.damageItemInHand(fixture.player, 100));
		verify(fixture.item, never()).setItemMeta(any());
	}

	@Test
	void removesItemWhenDamageReachesMaterialLimit() {
		Fixture fixture = new Fixture(Material.WOOD_SWORD.getMaxDurability() - 1);
		try (MockedStatic<MiscUtils> misc = mockStatic(MiscUtils.class)) {
			misc.when(MiscUtils::getInstance).thenReturn(mock(MiscUtils.class));
			when(fixture.item.getEnchantmentLevel(Enchantment.DURABILITY)).thenReturn(0);
			assertFalse(manager.damageItemInHand(fixture.player, 1));
			verify(fixture.inventory).setItemInHand(argThat(item -> item.getType() == Material.AIR));
			verify(fixture.item, never()).setDurability(anyShort());
		}
	}

	@Test
	void appliesDamageAndRemovesItemPastMaximum() {
		Fixture fixture = new Fixture(56);
		try (MockedStatic<MiscUtils> misc = mockStatic(MiscUtils.class)) {
			MiscUtils utils = mock(MiscUtils.class);
			misc.when(MiscUtils::getInstance).thenReturn(utils);
			when(fixture.item.getEnchantmentLevel(Enchantment.DURABILITY)).thenReturn(0);
			assertTrue(manager.damageItemInHand(fixture.player, 2));
			verify(fixture.item).setDurability((short)58);
			verify(fixture.inventory).setItemInHand(fixture.item);
			Fixture breaking = new Fixture(59);
			assertFalse(manager.damageItemInHand(breaking.player, 1));
			verify(breaking.inventory).setItemInHand(any(ItemStack.class));
			Fixture overflow = new Fixture(Short.MAX_VALUE);
			assertFalse(manager.damageItemInHand(overflow.player, Integer.MAX_VALUE));
			verify(overflow.inventory).setItemInHand(any(ItemStack.class));
		}
	}

	@Test
	void extremeDamageFinishesPromptlyWithUnbreaking() {
		Fixture fixture = new Fixture(0);
		try (MockedStatic<MiscUtils> misc = mockStatic(MiscUtils.class)) {
			MiscUtils utils = mock(MiscUtils.class);
			misc.when(MiscUtils::getInstance).thenReturn(utils);
			when(fixture.item.getEnchantmentLevel(Enchantment.DURABILITY)).thenReturn(1);
			assertTimeout(Duration.ofSeconds(2), () ->
					assertFalse(manager.damageItemInHand(fixture.player, Integer.MAX_VALUE)));
			verify(fixture.inventory).setItemInHand(any(ItemStack.class));
		}
	}

	@Test
	void boundedSamplerRetainsBinomialMoments() {
		Random random = new Random(47291);
		int count = 20_000;
		double sum = 0;
		double squared = 0;
		for (int i = 0; i < count; i++) {
			int hits = PlayerManager.sampleDamage(1_000, 50, 1_001, random);
			sum += hits;
			squared += (double) hits * hits;
		}
		double mean = sum / count;
		double variance = squared / count - mean * mean;
		assertEquals(500, mean, 1);
		assertEquals(250, variance, 10);
		assertEquals(0, PlayerManager.sampleDamage(100, 0, 10, random));
		assertEquals(10, PlayerManager.sampleDamage(Integer.MAX_VALUE, 100, 10, random));
	}

	@Test
	void samplerStopsAtCapAndInvalidCapsCannotProduceNegativeDamage() {
		class CountingRandom extends Random {int calls;@Override public double nextDouble(){calls++;return 0;} }
		CountingRandom random=new CountingRandom();assertEquals(3,PlayerManager.sampleDamage(Integer.MAX_VALUE,50,3,random));assertEquals(3,random.calls);
		assertEquals(0,PlayerManager.sampleDamage(100,100,0,random));assertEquals(0,PlayerManager.sampleDamage(100,100,-1,random));assertEquals(3,random.calls);
	}
	@Test
	void extremeEnchantLevelsCannotOverflowChanceArithmetic() {
		try(MockedStatic<MiscUtils> misc=mockStatic(MiscUtils.class)) {
			misc.when(MiscUtils::getInstance).thenReturn(mock(MiscUtils.class));
			Fixture high=new Fixture(0);when(high.item.getEnchantmentLevel(Enchantment.DURABILITY)).thenReturn(Integer.MAX_VALUE);assertTrue(manager.damageItemInHand(high.player,Integer.MAX_VALUE));verify(high.item,never()).setDurability(anyShort());
			Fixture negative=new Fixture(0);when(negative.item.getEnchantmentLevel(Enchantment.DURABILITY)).thenReturn(Integer.MIN_VALUE);assertTrue(manager.damageItemInHand(negative.player,1));verify(negative.item).setDurability((short)1);
		}
	}
	@Test
	void unavailableUnbreakableProtectionCannotSilentlyDamageTheItem() {
		Fixture fixture=new Fixture(3);when(fixture.meta.spigot()).thenReturn(new ItemMeta.Spigot());org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,() -> manager.damageItemInHand(fixture.player,10));verify(fixture.item,never()).setDurability(anyShort());verify(fixture.inventory,never()).setItemInHand(any());
	}

	private static final class Fixture {
		final Player player = mock(Player.class);
		final PlayerInventory inventory = mock(PlayerInventory.class);
		final ItemStack item = mock(ItemStack.class);
		final ItemMeta meta = mock(ItemMeta.class);
		final ItemMeta.Spigot spigot = mock(ItemMeta.Spigot.class);

		Fixture(int currentDamage) {
			when(player.getInventory()).thenReturn(inventory);
			when(inventory.getItemInHand()).thenReturn(item);
			when(item.getType()).thenReturn(Material.WOOD_SWORD);
			when(item.getItemMeta()).thenReturn(meta);
			when(meta.spigot()).thenReturn(spigot);
			when(item.getDurability()).thenReturn((short)currentDamage);
		}
	}
}
