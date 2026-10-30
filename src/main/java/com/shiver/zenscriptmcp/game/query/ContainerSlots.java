package com.shiver.zenscriptmcp.game.query;

import net.minecraft.inventory.Container;

/** 原版 addSlotToContainer 同时分配编号并登记同步状态；GUI 直接追加的显示槽不属于该协议。 */
public final class ContainerSlots {

    private ContainerSlots() {
    }

    public static boolean isVanillaSlot(Container container, int slotId) {
        return slotId >= 0 && slotId < container.inventorySlots.size()
                && slotId < container.inventoryItemStacks.size()
                && container.inventorySlots.get(slotId).slotNumber == slotId;
    }
}
