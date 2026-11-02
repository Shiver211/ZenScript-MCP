package com.shiver.zenscriptmcp.game.query;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.SlotItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.items.wrapper.PlayerArmorInvWrapper;
import net.minecraftforge.items.wrapper.PlayerInvWrapper;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;

/** 原版 addSlotToContainer 同时分配编号并登记同步状态；GUI 直接追加的显示槽不属于该协议。 */
public final class ContainerSlots {

    private ContainerSlots() {
    }

    public static boolean isVanillaSlot(Container container, int slotId) {
        return slotId >= 0 && slotId < container.inventorySlots.size()
                && slotId < container.inventoryItemStacks.size()
                && container.inventorySlots.get(slotId).slotNumber == slotId;
    }

    public static boolean sameStorage(Slot first, Slot second, EntityPlayer player) {
        if (first == second) {
            return true;
        }
        Integer firstPlayerIndex = playerIndex(first, player);
        Integer secondPlayerIndex = playerIndex(second, player);
        if (firstPlayerIndex != null && secondPlayerIndex != null) {
            return firstPlayerIndex.equals(secondPlayerIndex);
        }
        if (first instanceof SlotItemHandler || second instanceof SlotItemHandler) {
            // SlotItemHandler 共享同一个空 IInventory，不能以该占位对象推断存储相同。
            return first instanceof SlotItemHandler && second instanceof SlotItemHandler
                    && ((SlotItemHandler) first).getItemHandler() == ((SlotItemHandler) second).getItemHandler()
                    && first.getSlotIndex() == second.getSlotIndex();
        }
        return first.inventory == second.inventory && first.getSlotIndex() == second.getSlotIndex();
    }

    public static Integer playerIndex(Slot slot, EntityPlayer player) {
        if (slot.inventory == player.inventory) {
            return slot.getSlotIndex();
        }
        if (!(slot instanceof SlotItemHandler)) {
            return null;
        }
        IItemHandler handler = ((SlotItemHandler) slot).getItemHandler();
        if (handler instanceof PlayerMainInvWrapper
                && ((PlayerMainInvWrapper) handler).getInventoryPlayer() == player.inventory) {
            return slot.getSlotIndex();
        }
        if (handler instanceof PlayerArmorInvWrapper
                && ((PlayerArmorInvWrapper) handler).getInventoryPlayer() == player.inventory) {
            return player.inventory.mainInventory.size() + slot.getSlotIndex();
        }
        if (handler instanceof InvWrapper && ((InvWrapper) handler).getInv() == player.inventory) {
            return slot.getSlotIndex();
        }
        if (handler instanceof PlayerInvWrapper
                && handler == player.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)) {
            return slot.getSlotIndex();
        }
        return null;
    }
}
