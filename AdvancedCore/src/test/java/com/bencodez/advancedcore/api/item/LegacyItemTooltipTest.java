package com.bencodez.advancedcore.api.item;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;

class LegacyItemTooltipTest {
    @Test void configuredHideAddsEveryLegacyFlagAndPublishesMeta() {
        ItemMeta meta=mock(ItemMeta.class);
        try(MockedConstruction<ItemStack> items=mockConstruction(ItemStack.class,(item,context) -> when(item.getItemMeta()).thenReturn(meta))) {
            YamlConfiguration config=new YamlConfiguration();config.set("Material","STONE");config.set("Amount",1);config.set("HideToolTip",true);
            new ItemBuilder(config);
            assertEquals(1,items.constructed().size());verify(meta).addItemFlags(ItemFlag.values());verify(items.constructed().get(0)).setItemMeta(meta);
        }
    }
    @Test void missingOrFalseOptionDoesNotRemoveExistingFlags() {
        for(Boolean hide:new Boolean[]{null,false}) {
            ItemMeta meta=mock(ItemMeta.class);
            try(MockedConstruction<ItemStack> items=mockConstruction(ItemStack.class,(item,context) -> when(item.getItemMeta()).thenReturn(meta))) {
                YamlConfiguration config=new YamlConfiguration();config.set("Material","STONE");config.set("Amount",1);if(hide!=null)config.set("HideToolTip",hide);
                new ItemBuilder(config);verifyNoInteractions(meta);verify(items.constructed().get(0),never()).setItemMeta(any());
            }
        }
    }
    @Test void publicUnhidePublishesFlagsWithoutChangingNameOrLore() {
        ItemStack item=mock(ItemStack.class);ItemMeta meta=mock(ItemMeta.class);when(item.getItemMeta()).thenReturn(meta);
        new ItemBuilder(item).setHideTooltipCompat(item,false);
        verify(meta).removeItemFlags(ItemFlag.values());verify(item).setItemMeta(meta);verify(meta,never()).setDisplayName(anyString());verify(meta,never()).setLore(anyList());
    }
    @Test void nullItemOrMetadataIsSafe() {
        ItemStack item=mock(ItemStack.class);ItemBuilder builder=new ItemBuilder(item);builder.setHideTooltipCompat(null,true);builder.setHideTooltipCompat(item,true);verify(item,never()).setItemMeta(any());
    }
}
