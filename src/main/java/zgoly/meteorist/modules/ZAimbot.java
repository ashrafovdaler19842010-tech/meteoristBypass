package zgoly.meteorist.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import zgoly.meteorist.Meteorist;

import java.util.Random;
import java.util.Set;

import static zgoly.meteorist.utils.MeteoristUtils.calculateFov;

public class ZAimbot extends Module {
    public enum AimMode { Random, ClosestPoint, EdgeOnly }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAim = settings.createGroup("Aim");

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
            .name("entities").description("Entities to aim at.").onlyAttackable().defaultValue(EntityType.PLAYER).build()
    );
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range").description("Distance to rotate camera.").defaultValue(4.5).min(0).sliderMax(6).build()
    );
    private final Setting<Double> aggroRange = sgGeneral.add(new DoubleSetting.Builder()
            .name("aggro-range").description("Distance to remember and lock target.").defaultValue(10.0).min(1.0).sliderMax(20.0).build()
    );
    private final Setting<Double> fov = sgGeneral.add(new DoubleSetting.Builder()
            .name("fov").description("Field of view.").defaultValue(90).min(10).sliderMax(360).build()
    );
    private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
            .name("ignore-friends").description("Ignore friends.").defaultValue(true).build()
    );
    private final Setting<Boolean> ignoreWalls = sgGeneral.add(new BoolSetting.Builder()
            .name("ignore-walls").description("Allow aiming through walls.").defaultValue(false).build()
    );

    private final Setting<Boolean> onlySword = sgAim.add(new BoolSetting.Builder()
            .name("only-sword").description("Aim only with sword.").defaultValue(false).build()
    );
    private final Setting<Boolean> onlyX = sgAim.add(new BoolSetting.Builder()
            .name("only-x-coordinate").description("Aim only horizontally.").defaultValue(false).build()
    );
    private final Setting<AimMode> aimMode = sgAim.add(new EnumSetting.Builder<AimMode>()
            .name("aim-mode").description("Targeting logic.").defaultValue(AimMode.Random).build()
    );
    private final Setting<Boolean> randomizeTarget = sgAim.add(new BoolSetting.Builder()
            .name("randomize-target").description("Adds random offset (works with Closest Point).").defaultValue(true).build()
    );
    private final Setting<Double> aimSpeed = sgAim.add(new DoubleSetting.Builder()
            .name("speed").description("Speed of smooth aim.").defaultValue(3.0).min(0.1).sliderMax(10.0).build()
    );
    private final Setting<Double> targetLockTime = sgAim.add(new DoubleSetting.Builder()
            .name("target-lock-time").description("How long to remember the target (seconds).").defaultValue(5.0).min(0.0).sliderMax(10.0).build()
    );

    private final Random random = new Random();
    private Vec3d currentAimPoint = null;

    public static LivingEntity lockedTarget = null;
    public static long lockedTargetTime = 0;

    public static void lockTarget(LivingEntity target) {
        lockedTarget = target;
        lockedTargetTime = System.currentTimeMillis();
    }

    public ZAimbot() {
        super(Meteorist.CATEGORY, "z-aimbot", "Smooth aimbot with aggro system.");
    }

    @Override
    public void onActivate() {
        currentAimPoint = null;
        lockedTarget = null;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (onlySword.get() && !mc.player.getMainHandStack().isIn(ItemTags.SWORDS)) return;

        Entity target = null;
        long currentTime = System.currentTimeMillis();

        if (lockedTarget != null) {
            if (lockedTarget.isAlive() && PlayerUtils.isWithin(lockedTarget, aggroRange.get())
                && (currentTime - lockedTargetTime < targetLockTime.get() * 1000)) {
                target = lockedTarget;
            } else {
                lockedTarget = null;
            }
        }

        if (target == null) {
            // Ищем новую цель в радиусе агрессии
            LivingEntity closest = null;
            double closestDist = Double.MAX_VALUE;
            
            for (Entity e : mc.world.getEntities()) {
                if (entityCheck(e) && mc.player.distanceTo(e) < closestDist) {
                    closest = (LivingEntity) e;
                    closestDist = mc.player.distanceTo(e);
                }
            }
            
            if (closest != null) {
                target = closest;
                lockedTarget = closest;
                lockedTargetTime = currentTime;
                currentAimPoint = null;
            }
        }

        if (target == null) return;

        if (!PlayerUtils.isWithin(target, range.get())) return;
        if (calculateFov(mc.player, target) > fov.get()) return;

        aim(mc.player, target);
    }

    private void aim(LivingEntity player, Entity target) {
        Box box = target.getBoundingBox();
        Vec3d eyePos = player.getEyePos();
        
        if (aimMode.get() == AimMode.EdgeOnly) {
            HitResult hit = mc.crosshairTarget;
            if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
                if (((EntityHitResult) hit).getEntity() == target) {
                    return; 
                }
            }
            
            double x = MathHelper.clamp(eyePos.x, box.minX, box.maxX);
            double y = MathHelper.clamp(eyePos.y, box.minY, box.maxY);
            double z = MathHelper.clamp(eyePos.z, box.minZ, box.maxZ);
            
            if (randomizeTarget.get()) {
                x += (random.nextDouble() - 0.5) * 0.1;
                y += (random.nextDouble() - 0.5) * 0.1;
                z += (random.nextDouble() - 0.5) * 0.1;
            }
            
            currentAimPoint = new Vec3d(x, y, z);
            
        } else if (aimMode.get() == AimMode.ClosestPoint) {
            double x = MathHelper.clamp(eyePos.x, box.minX, box.maxX);
            double y = MathHelper.clamp(eyePos.y, box.minY, box.maxY);
            double z = MathHelper.clamp(eyePos.z, box.minZ, box.maxZ);
            
            if (randomizeTarget.get()) {
                x += (random.nextDouble() - 0.5) * 0.2;
                y += (random.nextDouble() - 0.5) * 0.2;
                z += (random.nextDouble() - 0.5) * 0.2;
            }
            currentAimPoint = new Vec3d(x, y, z);
            
        } else {
            if (currentAimPoint == null || random.nextInt(10) < 2) {
                double yBias = (random.nextDouble() + random.nextDouble() - 1.0) * 0.3 + 0.5; 
                double y = target.getY() + target.getHeight() * yBias;
                double x = target.getX() + (random.nextDouble() - 0.5) * 0.3;
                double z = target.getZ() + (random.nextDouble() - 0.5) * 0.3;
                currentAimPoint = new Vec3d(x, y, z);
            }
        }

        if (currentAimPoint == null) return;

        float targetYaw = (float) Rotations.getYaw(currentAimPoint);
        float targetPitch = (float) Rotations.getPitch(currentAimPoint);

        float yawDifference = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        float pitchDifference = MathHelper.wrapDegrees(targetPitch - player.getPitch());

        if (onlyX.get()) pitchDifference = 0;

        float speedFactor = aimSpeed.get().floatValue() / 10.0f;
        float deltaYaw = yawDifference * speedFactor;
        float deltaPitch = pitchDifference * speedFactor;
        
        float sens = mc.options.getMouseSensitivity().getValue().floatValue();
        float f = sens * 0.6f + 0.2f;
        float gcd = f * f * f * 1.2f; 
        deltaYaw = Math.round(deltaYaw / gcd) * gcd;
        deltaPitch = Math.round(deltaPitch / gcd) * gcd;

        player.setYaw(player.getYaw() + deltaYaw);
        player.setPitch(MathHelper.clamp(player.getPitch() + deltaPitch, -90f, 90f));
    }

    private boolean entityCheck(Entity entity) {
        if (entity.equals(mc.player) || entity.equals(mc.cameraEntity)) return false;
        if (!entity.isAlive()) return false;
        if (!PlayerUtils.isWithin(entity, aggroRange.get())) return false; 
        if (!entities.get().contains(entity.getType())) return false;
        if (ignoreFriends.get() && entity instanceof PlayerEntity player && !Friends.get().shouldAttack(player)) return false;
        return ignoreWalls.get() || PlayerUtils.canSeeEntity(entity);
    }
}
