package com.bencodez.advancedcore.api.item;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.bukkit.Material;
import org.bukkit.Color;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.*;
import org.bukkit.material.MaterialData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.potion.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.mockito.MockedConstruction;

class LegacyItemSerializationTest {
    @Test void leatherAndDamageAreAvailableToBothExistingSerializers() {
        LeatherArmorMeta meta=mock(LeatherArmorMeta.class);when(meta.getColor()).thenReturn(Color.fromRGB(12,34,56));
        ItemBuilder b=builder(Material.LEATHER_CHESTPLATE,meta);
        for(Map<String,Object> m:Arrays.asList(b.getConfiguration(false),b.createConfigurationData())) {
            assertEquals(5,m.get("Damage"));assertEquals(12,m.get("LeatherColor.Red"));assertEquals(34,m.get("LeatherColor.Green"));assertEquals(56,m.get("LeatherColor.Blue"));
        }
    }
    @Test void simplifiedSerializerRetainsLegacyVariantFields() {
        Map<String,Object> m=builder(Material.WOOL,mock(ItemMeta.class)).getConfiguration(false);
        assertEquals((short)5,m.get("Durability"));assertEquals((byte)5,m.get("Data"));assertFalse(m.containsKey("Damage"));
    }
    @Test void storedBookEnchantsAndPotionEffectsAreRepresented() {
        Enchantment enchant=mock(Enchantment.class);when(enchant.getName()).thenReturn("DAMAGE_ALL");EnchantmentStorageMeta book=mock(EnchantmentStorageMeta.class);when(book.getEnchants()).thenReturn(Collections.singletonMap(enchant,1));when(book.getStoredEnchants()).thenReturn(Collections.singletonMap(enchant,3));assertEquals(3,builder(Material.ENCHANTED_BOOK,book).getConfiguration(false).get("Enchants.DAMAGE_ALL"));
        PotionEffect effect=mock(PotionEffect.class);PotionEffectType type=mock(PotionEffectType.class);when(type.getName()).thenReturn("SPEED");when(effect.getType()).thenReturn(type);when(effect.getDuration()).thenReturn(120);when(effect.getAmplifier()).thenReturn(2);PotionMeta potion=mock(PotionMeta.class);when(potion.getCustomEffects()).thenReturn(Collections.singletonList(effect));Map<String,Object> m=builder(Material.POTION,potion).createConfigurationData();assertEquals(120,m.get("Potions.SPEED.Duration"));assertEquals(2,m.get("Potions.SPEED.Amplifier"));
    }
    @Test void ownerAndFireworkPowerRemainPortable() {
        SkullMeta skull=mock(SkullMeta.class);when(skull.hasOwner()).thenReturn(true);when(skull.getOwner()).thenReturn("LegacyOwner");assertEquals("LegacyOwner",builder(Material.SKULL_ITEM,skull).createConfigurationData().get("Skull"));
        FireworkMeta firework=mock(FireworkMeta.class);when(firework.getPower()).thenReturn(2);assertEquals(2,builder(Material.FIREWORK,firework).getConfigurationData(false).get("Power"));
    }
    @Test void unbreakableIsSerializedAndExplicitConfigPublishesSpigotMetadata() {
        ItemMeta meta=mock(ItemMeta.class);ItemMeta.Spigot spigot=mock(SupportedSpigot.class);ItemBuilder b=builder(Material.DIAMOND_SWORD,meta);when(meta.spigot()).thenReturn(spigot);when(spigot.isUnbreakable()).thenReturn(true);assertEquals(true,b.getConfiguration(false).get("Unbreakable"));
        for(boolean value:new boolean[]{false,true}) {
            try(MockedConstruction<ItemStack> items=mockConstruction(ItemStack.class,(item,ctx) -> when(item.getItemMeta()).thenReturn(meta))) {
                YamlConfiguration c=new YamlConfiguration();c.set("Material","STONE");c.set("Amount",1);c.set("Unbreakable",value);new ItemBuilder(c);verify(spigot).setUnbreakable(value);verify(items.constructed().get(0)).setItemMeta(meta);
            }
        }
    }
    @Test void loreExportIsIndependentAndFullSerializationIsNotReinterpreted() {
        ItemMeta meta=mock(ItemMeta.class);List<String> lore=new ArrayList<>(Collections.singletonList("original"));when(meta.hasLore()).thenReturn(true);when(meta.getLore()).thenReturn(lore);Map<String,Object> m=builder(Material.STONE,meta).getConfigurationData(false);((List<String>)m.get("Lore")).add("export only");assertEquals(Collections.singletonList("original"),lore);
        ItemStack item=mock(ItemStack.class);when(item.clone()).thenReturn(item);Map<String,Object> full=Collections.singletonMap("meta",meta);when(item.serialize()).thenReturn(full);assertSame(full,new ItemBuilder(item).getConfigurationData(true));
    }
    @Test void absentMetaCanStillExportLegacyIdentityAndVariant() {
        Map<String,Object> m=builder(Material.STONE,null).getConfiguration(false);assertEquals("STONE",m.get("Material"));assertEquals((short)5,m.get("Durability"));assertEquals((byte)5,m.get("Data"));
    }
    @Test void baseBukkitSpigotPlaceholderDoesNotBreakReadOnlyExport() {
        ItemMeta meta=mock(ItemMeta.class);ItemBuilder b=builder(Material.STONE,meta);when(meta.spigot()).thenReturn(new ItemMeta.Spigot());assertFalse(b.getConfiguration(false).containsKey("Unbreakable"));assertThrows(UnsupportedOperationException.class,() -> b.setUnbreakable(true));
    }
    static class SupportedSpigot extends ItemMeta.Spigot {
        @Override public boolean isUnbreakable() {return false;}
        @Override public void setUnbreakable(boolean value) {}
    }
    static ItemBuilder builder(Material material,ItemMeta meta) {
        ItemStack item=mock(ItemStack.class);when(item.clone()).thenReturn(item);when(item.getType()).thenReturn(material);when(item.getAmount()).thenReturn(1);when(item.getDurability()).thenReturn((short)5);when(item.getData()).thenReturn(new MaterialData(material,(byte)5));when(item.getItemMeta()).thenReturn(meta);if(meta!=null)when(meta.spigot()).thenReturn(mock(ItemMeta.Spigot.class));return new ItemBuilder(item);
    }
}
