package com.bencodez.advancedcore.api.item;

import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.bukkit.inventory.ItemStack;

class LegacyItemOwnershipTest {
    @Test void constructorMutatesItsCopyInsteadOfCallerItem() {
        ItemStack source=mock(ItemStack.class),copy=mock(ItemStack.class);when(source.clone()).thenReturn(copy);
        new ItemBuilder(source).setAmount(7);
        verify(copy).setAmount(7);verify(source,never()).setAmount(anyInt());
    }
    @Test void cloningBuilderSeparatesItsMutableItem() {
        ItemStack source=mock(ItemStack.class),first=mock(ItemStack.class),second=mock(ItemStack.class);
        when(source.clone()).thenReturn(first);when(first.clone()).thenReturn(second);
        ItemBuilder builder=new ItemBuilder(source);ItemBuilder cloned=builder.clone();cloned.setAmount(9);builder.setAmount(3);
        verify(first).setAmount(3);verify(first,never()).setAmount(9);verify(second).setAmount(9);verify(source,never()).setAmount(anyInt());
    }
}
