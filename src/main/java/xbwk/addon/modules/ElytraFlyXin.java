package xbwk.addon.modules;

import xbwk.addon.Elytraskyaddon;
import xbwk.addon.events.impl.MoveEvent;
import xbwk.addon.events.impl.TravelEvent;
import net.minecraft.client.MinecraftClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ElytraItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class ElytraFlyXin extends Module {
    static MinecraftClient mc = MinecraftClient.getInstance();

    public ElytraFlyXin() {
        super(Elytraskyaddon.CATEGORY, "elytrafly-xin", "Specialized Elytra Flight for Xin");
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Boolean> autoStop = sgGeneral.add(new BoolSetting.Builder()
            .name("stop-in-unloaded-chunks")
            .description("Stop flying when entering unloaded chunks")
            .defaultValue(true)
            .build()
    );

    public final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
            .name("flight-speed")
            .description("Horizontal flight speed")
            .defaultValue(1.5)
            .min(0.1)
            .sliderMin(0.1)
            .max(3)
            .sliderMax(3)
            .build()
    );

    public final Setting<Double> downSpeed = sgGeneral.add(new DoubleSetting.Builder()
            .name("descent-speed")
            .description("Speed when descending")
            .defaultValue(1)
            .min(0.1)
            .sliderMin(0.1)
            .max(3)
            .sliderMax(3)
            .build()
    );

    // Flag indicating if player is wearing elytra
    private boolean hasElytra = false;

    @Override
    public void onActivate() {
        if (mc.player != null) {
            // Disable vanilla flying abilities if not in creative mode
            if (!mc.player.isCreative()) mc.player.getAbilities().allowFlying = false;
            mc.player.getAbilities().flying = false;
        }
        hasElytra = false;
    }

    @Override
    public void onDeactivate() {
        hasElytra = false;
        if (mc.player != null) {
            // Disable vanilla flying abilities if not in creative mode
            if (!mc.player.isCreative()) mc.player.getAbilities().allowFlying = false;
            mc.player.getAbilities().flying = false;
        }
    }

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        // Check player's armor slots for elytra
        for (ItemStack is : mc.player.getArmorItems()) {
            if (is.getItem() instanceof ElytraItem) {
                hasElytra = true;
                break;
            } else {
                hasElytra = false;
            }
        }
    }

    /**
     * Calculates rotation vector from pitch and yaw angles
     * Core method for 3D direction calculation
     *
     * @param pitch Vertical angle (up/down)
     * @param yaw Horizontal angle (left/right)
     * @return Normalized 3D direction vector
     */
    protected final Vec3d getRotationVector(float pitch, float yaw) {
        // Convert angles to radians (approximation of π/180)
        float f = pitch * 0.017453292F;
        float g = -yaw * 0.017453292F;

        // Calculate trigonometric values
        float h = MathHelper.cos(g);  // cos(yaw)
        float i = MathHelper.sin(g);  // sin(yaw)
        float j = MathHelper.cos(f);  // cos(pitch)
        float k = MathHelper.sin(f);  // sin(pitch)

        // Return 3D direction vector (x, y, z)
        return new Vec3d(i * j, -k, h * j);
    }

    /**
     * Gets current rotation vector (horizontal only)
     * Fixes pitch at 0 for horizontal flight
     *
     * @param tickDelta Frame interpolation
     * @return Horizontal direction vector
     */
    public final Vec3d getRotationVec(float tickDelta) {
        // Fixed pitch at 0 for horizontal flight
        return this.getRotationVector(0, mc.player.getYaw(tickDelta));
    }

    /**
     * Re-initiates elytra flight
     * Sends packet to server to start elytra flight
     *
     * @param player Player entity
     * @return Whether elytra flight was successfully started
     */
    public static boolean recastElytra(ClientPlayerEntity player) {
        // Check conditions and attempt to start elytra flight
        if (checkConditions(player) && ignoreGround(player)) {
            // Send packet to server to start elytra flight
            player.networkHandler.sendPacket(new ClientCommandC2SPacket(player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            return true;
        } else return false;
    }

    /**
     * Checks basic conditions for elytra flight
     *
     * @param player Player entity
     * @return Whether elytra flight conditions are met
     */
    public static boolean checkConditions(ClientPlayerEntity player) {
        ItemStack itemStack = player.getEquippedStack(EquipmentSlot.CHEST);
        return (!player.getAbilities().flying &&     // Not in creative flight
                !player.hasVehicle() &&              // Not riding a vehicle
                !player.isClimbing() &&              // Not climbing
                itemStack.isOf(Items.ELYTRA) &&      // Wearing elytra
                ElytraItem.isUsable(itemStack));     // Elytra is usable (has durability)
    }

    /**
     * Ignores ground detection to force elytra flight
     *
     * @param player Player entity
     * @return Whether flight was successfully started
     */
    private static boolean ignoreGround(ClientPlayerEntity player) {
        // Check player is not in water and doesn't have levitation effect
        if (!player.isTouchingWater() && !player.hasStatusEffect(StatusEffects.LEVITATION)) {
            ItemStack itemStack = player.getEquippedStack(EquipmentSlot.CHEST);
            // Verify elytra equipment and usability
            if (itemStack.isOf(Items.ELYTRA) && ElytraItem.isUsable(itemStack)) {
                // Force start elytra flight
                player.startFallFlying();
                return true;
            } else return false;
        } else return false;
    }

    public static double[] directionSpeedKey(double speed) {
        float forward = (mc.options.forwardKey.isPressed() ? 1 : 0) + (mc.options.backKey.isPressed() ? -1 : 0);
        float side = (mc.options.leftKey.isPressed() ? 1 : 0) + (mc.options.rightKey.isPressed() ? -1 : 0);
        float yaw = mc.player.prevYaw + (mc.player.getYaw() - mc.player.prevYaw) * mc.getTickDelta();
        if (forward != 0.0f) {
            if (side > 0.0f) {
                yaw += ((forward > 0.0f) ? -45 : 45);
            } else if (side < 0.0f) {
                yaw += ((forward > 0.0f) ? 45 : -45);
            }
            side = 0.0f;
            if (forward > 0.0f) {
                forward = 1.0f;
            } else if (forward < 0.0f) {
                forward = -1.0f;
            }
        }
        final double sin = Math.sin(Math.toRadians(yaw + 90.0f));
        final double cos = Math.cos(Math.toRadians(yaw + 90.0f));
        final double posX = forward * speed * cos + side * speed * sin;
        final double posZ = forward * speed * sin - side * speed * cos;
        return new double[]{posX, posZ};
    }

    @EventHandler
    public void onPlayerMove(MoveEvent event) {
        // Check if player is wearing elytra
        if (!(mc.player.getEquippedStack(EquipmentSlot.CHEST).getItem() instanceof ElytraItem)) return;

        // If player is elytra flying
        if (mc.player.isFallFlying()) {
            // Calculate current chunk coordinates
            int chunkX = (int) ((mc.player.getX()) / 16);
            int chunkZ = (int) ((mc.player.getZ()) / 16);

            // If auto-stop is enabled
            if (autoStop.get()) {
                // Check if current chunk is loaded
                if (!mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                    // Stop all movement in unloaded chunks to prevent anti-cheat triggers
                    event.setX(0);
                    event.setY(0);
                    event.setZ(0);
                }
            }
        }
    }

    @EventHandler
    public void onMove(TravelEvent event) {
        // Basic checks: null pointers, elytra equipped, flying state, post event
        if (mc.player == null || mc.world == null || !hasElytra || !mc.player.isFallFlying() || event.isPost()) return;

        // Get player's current look vector (horizontal)
        Vec3d lookVec = getRotationVec(mc.getTickDelta());
        // Calculate horizontal look distance (for direction calculation)
        double lookDist = Math.sqrt(lookVec.x * lookVec.x + lookVec.z * lookVec.z);
        // Calculate current horizontal movement distance
        double motionDist = Math.sqrt(getX() * getX() + getZ() * getZ());

        // Handle vertical movement control
        if (mc.player.input.sneaking) {
            // Descend when sneaking, using configured descent speed
            setY(-downSpeed.get());
        } else if (!mc.player.input.jumping) {
            // When not jumping, set Y velocity near 0 (maintain horizontal flight)
            setY(-0.00000000003D * 0);
        }

        // Handle ascending logic when jumping
        if (mc.player.input.jumping) {
            // If there's horizontal movement speed
            if (motionDist > 0 / 10) {
                // Calculate ascent speed based on current movement speed
                double rawUpSpeed = motionDist * 0.01325D;
                // Apply ascent speed
                setY(getY() + rawUpSpeed * 3.2D);
                // Adjust horizontal speed to maintain balance
                setX(getX() - lookVec.x * rawUpSpeed / lookDist);
                setZ(getZ() - lookVec.z * rawUpSpeed / lookDist);
            } else {
                // If no horizontal movement, apply direction speed directly
                double[] dir = directionSpeedKey(speed.get());
                setX(dir[0]);
                setZ(dir[1]);
            }
        }

        // Smooth direction adjustment - makes flight more natural
        if (lookDist > 0.0D) {
            // Use interpolation to smoothly adjust flight direction
            setX(getX() + (lookVec.x / lookDist * motionDist - getX()) * 0.1D);
            setZ(getZ() + (lookVec.z / lookDist * motionDist - getZ()) * 0.1D);
        }

        // Horizontal movement control when not jumping
        if (!mc.player.input.jumping) {
            // Set horizontal movement based on keyboard input and configured speed
            double[] dir = directionSpeedKey(speed.get());
            setX(dir[0]);
            setZ(dir[1]);
        }

        // Apply air resistance - simulates realistic flight physics
        // These precise values simulate vanilla air resistance to avoid anti-cheat detection
        setY(getY() * 0.9900000095367432D);  // Y-axis resistance (1% decay)
        setX(getX() * 0.9800000190734863D);  // X-axis resistance (2% decay)
        setZ(getZ() * 0.9900000095367432D);  // Z-axis resistance (1% decay)

        // Cancel original event and use our custom movement logic
        event.cancel();
        // Apply calculated movement
        mc.player.move(MovementType.SELF, mc.player.getVelocity());
    }

    /**
     * Gets X-axis velocity
     *
     * @return X-axis movement speed
     */
    private double getX() {
        return mc.player.getVelocity().x;
    }

    /**
     * Gets Y-axis velocity
     *
     * @return Y-axis movement speed
     */
    private double getY() {
        return mc.player.getVelocity().y;
    }

    /**
     * Gets Z-axis velocity
     *
     * @return Z-axis movement speed
     */
    private double getZ() {
        return mc.player.getVelocity().z;
    }

    /**
     * Sets X-axis velocity
     *
     * @param f New X-axis speed
     */
    private void setX(double f) {
        Vec3d currentVel = mc.player.getVelocity();
        // Create new velocity vector
        Vec3d newVel = new Vec3d(f, currentVel.y, currentVel.z);
        mc.player.setVelocity(newVel);
    }

    /**
     * Sets Y-axis velocity
     *
     * @param f New Y-axis speed
     */
    private void setY(double f) {
        Vec3d currentVel = mc.player.getVelocity();
        // Create new velocity vector
        Vec3d newVel = new Vec3d(currentVel.x, f, currentVel.z);
        mc.player.setVelocity(newVel);
    }

    /**
     * Sets Z-axis velocity
     *
     * @param f New Z-axis speed
     */
    private void setZ(double f) {
        Vec3d currentVel = mc.player.getVelocity();
        // Create new velocity vector
        Vec3d newVel = new Vec3d(currentVel.x, currentVel.y, f);
        mc.player.setVelocity(newVel);
    }
}
