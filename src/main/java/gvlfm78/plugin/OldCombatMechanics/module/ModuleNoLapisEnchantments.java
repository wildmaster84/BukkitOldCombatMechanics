package kernitus.plugin.OldCombatMechanics.module;

import kernitus.plugin.OldCombatMechanics.OCMMain;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.EnchantingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.material.Dye;
import org.bukkit.permissions.Permissible;

public class ModuleNoLapisEnchantments extends Module {

    private Material lapisLazuliMaterial;

    public ModuleNoLapisEnchantments(OCMMain plugin){
        super(plugin, "no-lapis-enchantments");

        try{
            lapisLazuliMaterial = Material.valueOf("INK_SACK");
        } catch(IllegalArgumentException ignored){
            lapisLazuliMaterial = Material.LAPIS_LAZULI;
        }
    }

    @EventHandler
    public void onEnchant(EnchantItemEvent e){
        Block block = e.getEnchantBlock();
        if(!isEnabled(block.getWorld())) return;

        if(!hasPermission(e.getEnchanter())) return;

        EnchantingInventory ei = (EnchantingInventory) e.getInventory(); //Not checking here because how else would event be fired?
        ei.setSecondary(getLapis());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e){
        if(!isEnabled(e.getWhoClicked().getWorld())) return;

        if(e.getInventory().getType() != InventoryType.ENCHANTING) return;

        if(!hasPermission(e.getWhoClicked())) return;

        ItemStack item = e.getCurrentItem();

        if(item == null){
            return;
        }

        if(item.getType() == lapisLazuliMaterial && e.getRawSlot() == 1){
            e.setCancelled(true);
        } else if(e.getCursor() != null && e.getCursor().getType() == lapisLazuliMaterial && e.getClick() == ClickType.DOUBLE_CLICK){
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e){
        if(!isEnabled(e.getPlayer().getWorld())) return;

        Inventory inv = e.getInventory();
        if(inv == null || inv.getType() != InventoryType.ENCHANTING || !hasPermission(e.getPlayer())) return;
        ((EnchantingInventory) inv).setSecondary(new ItemStack(Material.AIR));
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent e){
        if(!isEnabled(e.getPlayer().getWorld())) return;

        Inventory inv = e.getInventory();
        if(inv == null || inv.getType() != InventoryType.ENCHANTING || !hasPermission(e.getPlayer())) return;
        ((EnchantingInventory) inv).setSecondary(getLapis());
    }

    private ItemStack getLapis(){
        Dye dye = new Dye();
        dye.setColor(DyeColor.BLUE);
        return dye.toItemStack(64);
    }

    private boolean hasPermission(Permissible player){
        return !isSettingEnabled("usePermission") || player.hasPermission("oldcombatmechanics.nolapis");
    }
}
