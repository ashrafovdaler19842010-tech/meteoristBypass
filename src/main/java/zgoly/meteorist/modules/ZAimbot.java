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
import net.minecraft.entity.Tameable;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import zgoly.meteorist.Meteorist;

import java.util.Random;
import java.util.Set;

import static zgoly.meteorist.utils.MeteoristUtils.calculateFov;

public class ZAimbot extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAim = settings.createGroup("Aim");

    // --- FILTER ---
    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
            .name("entities").description("Entities to aim at.").onlyAttackable().defaultValue(EntityType.PLAYER).build()
    );
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range").description("The maximum range the entity can be to aim at it.").defaultValue(4.5).min(0).sliderMax(6).build()
    );
    private final Setting<Double> fov = sgGeneral.add(new DoubleSetting.Builder()
            .name("fov").description("Field of view to target entities.").defaultValue(90).min(10).sliderMax(360).build()
    );
    private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
            .name("ignore-friends").description("Will avoid aiming at players on your friends list.").defaultValue(true).build()
    );
    private final Setting<Boolean> ignoreWalls = sgGeneral.add(new BoolSetting.Builder()
            .name("ignore-walls").description("Allow aiming through walls.").defaultValue(false).build()
    );

    // --- AIM ---
    private final Setting<Boolean> onlySword = sgAim.add(new BoolSetting.Builder()
            .name("only-sword").description("Aim only when holding a sword.").defaultValue(false).build()
    );
    private final Setting<Boolean> onlyX = sgAim.add(new BoolSetting.Builder()
            .name("only-x-coordinate").description("Aim only horizontally (ignore vertical pitch).").defaultValue(false).build()
    );
    private final Setting<Double> aimSpeed = sgAim.add(new DoubleSetting.Builder()
            .name("speed").description("Speed of smooth aim.").defaultValue(3.0).min(0.1).sliderMax(10.0).build()
    );
    private final Setting<Boolean> smoothAim = sgAim.add(new BoolSetting.Builder()
            .name("smooth-aim").description("Enables smooth aiming.").defaultValue(true).build()
    );
    private final Setting<Boolean> randomizeTarget = sgAim.add(new BoolSetting.Builder()
            .name("randomize-target").description("Randomizes the exact aim point on the hitbox.").defaultValue(true).build()
    );
    private final Setting<Boolean> predictMovement = sgAim.add(new BoolSetting.Builder()
            .name("predict-movement").description("Predicts target movement.").defaultValue(false).build()
    );
    private final Setting<Double> predictionFactor = sgAim.add(new DoubleSetting.Builder()
            .name("prediction-factor").description("How much to predict movement.").defaultValue(0.5).min(0.0).sliderMax(2.0).visible(predictMovement::get).build()
    );
    private final Setting<Double> targetLockTime = sgAim.add(new DoubleSetting.Builder()
            .name("target-lock-time").description("Сколько секунд удерживать фокус на цели.").defaultValue(5.0).min(0.0).sliderMax(10.0).build()
    );

    private final Random random = new Random();
    private Vec3d currentAimPoint = null;

    // Шарим лок с ZKillaura
    public static LivingEntity lockedTarget = null;
    public static long lockedTargetTime = 0;

    public static void lockTarget(LivingEntity target) {
        lockedTarget = target;
        lockedTargetTime = System.currentTimeMillis();
    }

    public ZAimbot() {
        super(Meteorist.CATEGORY, "z-aimbot", "Simple smooth aimbot.");
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
            if (lockedTarget.isAlive() && PlayerUtils.isWithin(lockedTarget, range.get()) 
                && calculateFov(mc.player, lockedTarget) <= fov.get()
                && (currentTime - lockedTargetTime < targetLockTime.get() * 1000)) {
                target = lockedTarget;
            } else {
                lockedTarget = null;
            }
        }

        if (target == null) {
            target = TargetUtils.get(this::entityCheck, SortPriority.ClosestAngle);
            if (target != null) {
                lockedTarget = (LivingEntity) target;
                lockedTargetTime = currentTime;
                currentAimPoint = null;
            }
        }

        if (target == null) return;

        aim(mc.player, target);
    }

    private void aim(LivingEntity player, Entity target) {
        if (currentAimPoint == null || randomizeTarget.get()) {
            double yBias = (random.nextDouble() + random.nextDouble() - 1.0) * 0.3 + 0.5; 
            double y = target.getY() + target.getHeight() * yBias;
            double x = target.getX() + (random.nextDouble() - 0.5) * 0.3;
            double z = target.getZ() + (random.nextDouble() - 0.5) * 0.3;
            
            if (predictMovement.get()) {
                Vec3d vel = target.getVelocity();
                x += vel.x * predictionFactor.get();
                y += vel.y * predictionFactor.get();
                z += vel.z * predictionFactor.get();
            }
            
            currentAimPoint = new Vec3d(x, y, z);
        }

        float targetYaw = (float) Rotations.getYaw(currentAimPoint);
        float targetPitch = (float) Rotations.getPitch(currentAimPoint);

        float yawDifference = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        float pitchDifference = MathHelper.wrapDegrees(targetPitch - player.getPitch());

        if (onlyX.get()) pitchDifference = 0;

        if (smoothAim.get()) {
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
        } else {
            player.setYaw(targetYaw);
            player.setPitch(MathHelper.clamp(targetPitch, -90f, 90f));
        }
    }

    private boolean entityCheck(Entity entity) {
        if (entity.equals(mc.player) || entity.equals(mc.cameraEntity)) return false;
        if (!entity.isAlive()) return false;
        if (!PlayerUtils.isWithin(entity, range.get())) return false;
        if (!entities.get().contains(entity.getType())) return false;
        if (calculateFov(mc.player, entity) > fov.get()) return false;
        if (ignoreFriends.get() && entity instanceof PlayerEntity player && !Friends.get().shouldAttack(player)) return false;
        return ignoreWalls.get() || PlayerUtils.canSeeEntity(entity);
    }
}
