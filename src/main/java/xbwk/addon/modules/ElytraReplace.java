package xbwk.addon.modules;

import xbwk.addon.Elytraskyaddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.InventoryTweaks;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public class ElytraReplace extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // Elytra replacement durability threshold settings
    private final Setting<Integer> replaceDurability = sgGeneral.add(new IntSetting.Builder()
            .name("durability-threshold")
            .description("Durability threshold for elytra replacement")
            .defaultValue(2)
            .range(1, Items.ELYTRA.getMaxDamage() - 1)
            .sliderRange(1, Items.ELYTRA.getMaxDamage() - 1)
            .build()
    );

    // Chat feedback settings
    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
            .name("chat-notifications")
            .description("Send chat notifications when replacing elytra")
            .defaultValue(true)
            .build()
    );

    // Replace only when flying settings
    private final Setting<Boolean> onlyWhenFlying = sgGeneral.add(new BoolSetting.Builder()
            .name("replace-only-when-flying")
            .description("Only replace elytra when actively flying")
            .defaultValue(false)
            .build()
    );

    // Conflict resolution settings
    private final Setting<Boolean> temporaryDisableInventoryTweaks = sgGeneral.add(new BoolSetting.Builder()
            .name("inventory-tweaks-compatibility")
            .description("Temporarily disable InventoryTweaks module during elytra replacement")
            .defaultValue(true)
            .build()
    );

    private final Setting<Integer> reEnableDelay = sgGeneral.add(new IntSetting.Builder()
            .name("compatibility-delay")
            .description("Delay (in ticks) before re-enabling InventoryTweaks after elytra replacement")
            .defaultValue(10)
            .range(1, 60)
            .sliderMax(60)
            .visible(temporaryDisableInventoryTweaks::get)
            .build()
    );

    // State tracking variables
    private boolean inventoryTweaksWasActive = false;
    private int reEnableCountdown = 0;

    public ElytraReplace() {
        super(Elytraskyaddon.CATEGORY, "elytra-replace", "Automatically replaces damaged elytra");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Check if player and world exist
        if (mc.player == null || mc.world == null) return;

        // Handle InventoryTweaks re-enable countdown
        if (reEnableCountdown > 0) {
            reEnableCountdown--;
            if (reEnableCountdown == 0 && inventoryTweaksWasActive) {
                reEnableInventoryTweaks();
            }
        }

        // Get current chestplate equipment (elytra slot)
        ItemStack chestStack = mc.player.getEquippedStack(EquipmentSlot.CHEST);

        // Check if currently wearing elytra
        if (chestStack.getItem() == Items.ELYTRA) {
            // Calculate remaining durability
            int remainingDurability = chestStack.getMaxDamage() - chestStack.getDamage();

            // Check if elytra needs replacement
            checkAndReplaceElytra(chestStack, remainingDurability);
        }
    }

    /**
     * Checks and replaces elytra if needed
     * @param chestStack Current chestplate equipment
     * @param remainingDurability Remaining durability points
     */
    private void checkAndReplaceElytra(ItemStack chestStack, int remainingDurability) {
        // Check if should only replace when flying
        if (onlyWhenFlying.get() && !mc.player.isFallFlying()) return;

        // Check if elytra needs replacement
        if (remainingDurability > replaceDurability.get()) return;

        // Find replacement elytra in inventory
        FindItemResult elytra = InvUtils.find(stack -> {
            if (stack.getItem() != Items.ELYTRA) return false;
            int stackDurability = stack.getMaxDamage() - stack.getDamage();
            return stackDurability > replaceDurability.get();
        });

        // Send warning if no suitable elytra found
        if (!elytra.found()) {
            if (chatFeedback.get()) {
                warning("No replacement elytra found with durability > %d", replaceDurability.get());
            }
            return;
        }

        // Temporarily disable InventoryTweaks if enabled
        if (temporaryDisableInventoryTweaks.get()) {
            temporaryDisableInventoryTweaks();
        }

        // Perform elytra replacement
        InvUtils.move().from(elytra.slot()).toArmor(2);

        // Send success notification
        if (chatFeedback.get()) {
            info("Replaced elytra (Durability: %d -> %d)",
                    remainingDurability,
                    mc.player.getEquippedStack(EquipmentSlot.CHEST).getMaxDamage() -
                            mc.player.getEquippedStack(EquipmentSlot.CHEST).getDamage());
        }

        // Set re-enable countdown if needed
        if (temporaryDisableInventoryTweaks.get() && inventoryTweaksWasActive) {
            reEnableCountdown = reEnableDelay.get();
        }
    }

    /**
     * Temporarily disables InventoryTweaks module
     */
    private void temporaryDisableInventoryTweaks() {
        InventoryTweaks inventoryTweaks = Modules.get().get(InventoryTweaks.class);
        if (inventoryTweaks != null && inventoryTweaks.isActive()) {
            inventoryTweaksWasActive = true;
            inventoryTweaks.toggle();
            if (chatFeedback.get()) {
                info("Temporarily disabled InventoryTweaks module");
            }
        } else {
            inventoryTweaksWasActive = false;
        }
    }

    /**
     * Re-enables InventoryTweaks module
     */
    private void reEnableInventoryTweaks() {
        InventoryTweaks inventoryTweaks = Modules.get().get(InventoryTweaks.class);
        if (inventoryTweaks != null && !inventoryTweaks.isActive()) {
            inventoryTweaks.toggle();
            if (chatFeedback.get()) {
                info("Re-enabled InventoryTweaks module");
            }
        }
        inventoryTweaksWasActive = false;
    }

    @Override
    public void onDeactivate() {
        // Re-enable InventoryTweaks if it was temporarily disabled
        if (inventoryTweaksWasActive) {
            reEnableInventoryTweaks();
        }
        reEnableCountdown = 0;
    }

    @Override
    public String getInfoString() {
        if (mc.player == null) return null;

        ItemStack chestStack = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (chestStack.getItem() != Items.ELYTRA) return "No elytra";

        int remainingDurability = chestStack.getMaxDamage() - chestStack.getDamage();
        return String.valueOf(remainingDurability);
    }
}
