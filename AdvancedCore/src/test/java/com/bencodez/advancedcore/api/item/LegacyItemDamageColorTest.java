package com.bencodez.advancedcore.api.item;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.bukkit.Material;
import org.bukkit.Color;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

class LegacyItemDamageColorTest {
    @Test void explicitDamageWinsOverLegacyDurability() {
        try(MockedConstruction<ItemStack> items=mockConstruction(ItemStack.class,(item,ctx) -> when(item.getType()).thenReturn(Material.DIAMOND_SWORD))) {
            YamlConfiguration c=config("DIAMOND_SWORD");c.set("Durability",9);c.set("Damage",5);new ItemBuilder(c);
            verify(items.constructed().get(0)).setDurability((short)5);verify(items.constructed().get(0),never()).setDurability((short)9);
        }
    }
    @Test void leatherRgbConfigurationPublishesLegacyMetadata() {
        LeatherArmorMeta meta=mock(LeatherArmorMeta.class);
        try(MockedConstruction<ItemStack> items=mockConstruction(ItemStack.class,(item,ctx) -> {when(item.getType()).thenReturn(Material.LEATHER_CHESTPLATE);when(item.getItemMeta()).thenReturn(meta);})) {
            YamlConfiguration c=config("LEATHER_CHESTPLATE");c.set("LeatherColor.Red",12);c.set("LeatherColor.Green",34);c.set("LeatherColor.Blue",56);new ItemBuilder(c);
            verify(meta).setColor(Color.fromRGB(12,34,56));verify(items.constructed().get(0)).setItemMeta(meta);
        }
    }
    @Test void missingDamageRetainsLegacyDurabilityAndZeroDamageOverridesIt() {
        for(Integer damage:new Integer[]{null,0}) {
            try(MockedConstruction<ItemStack> items=mockConstruction(ItemStack.class,(item,ctx) -> when(item.getType()).thenReturn(Material.DIAMOND_SWORD))) {
                YamlConfiguration c=config("DIAMOND_SWORD");c.set("Durability",9);c.set("Damage",damage);new ItemBuilder(c);
                verify(items.constructed().get(0)).setDurability(damage==null?(short)9:(short)0);
            }
        }
    }
    @Test void publicDamageRejectsShortOverflowAndPreservesNonDamageableMaterialData() {
        ItemStack source=mock(ItemStack.class),copy=mock(ItemStack.class);when(source.clone()).thenReturn(copy);when(copy.getType()).thenReturn(Material.DIAMOND_SWORD);
        ItemBuilder b=new ItemBuilder(source);assertSame(b,b.setDamage(8));verify(copy).setDurability((short)8);
        assertThrows(IllegalArgumentException.class,() -> b.setDamage(-1));assertThrows(IllegalArgumentException.class,() -> b.setDamage(32768));verify(copy,times(1)).setDurability(anyShort());
        when(copy.getType()).thenReturn(Material.WOOL);assertSame(b,b.setDamage(12));verify(copy,times(1)).setDurability(anyShort());
    }
    static YamlConfiguration config(String material) {YamlConfiguration c=new YamlConfiguration();c.set("Material",material);c.set("Amount",1);return c;}
}
