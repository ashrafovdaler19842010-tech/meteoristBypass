package zgoly.meteorist.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.Target;
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
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import zgoly.meteorist.Meteorist;

import java.util.Random;
import java.util.Set;

import static zgoly.meteorist.utils.MeteoristUtils.calculateFov;

public class ZAimbot extends Module {
    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgAim = settings.createGroup("Aim");

    private final Setting<Set<EntityType<?>>> entities = sgFilter.add(new EntityTypeListSetting.Builder()
            .name("entities").description("Entities to aim at.").onlyAttackable().defaultValue(EntityType.PLAYER).build()
    );
    private final Setting<Double> range = sgFilter.add(new DoubleSetting.Builder()
            .name("range").description("The maximum range the entity can be to aim at it.").defaultValue(4.5).min(0).sliderMax(6).build()
    );
    private final Setting<KillAura.EntityAge> mobAgeFilter = sgFilter.add(new EnumSetting.Builder<KillAura.EntityAge>()
            .name("mob-age-filter").description("Determines the age of the mobs to target.").defaultValue(KillAura.EntityAge.Adult).build()
    );
    private final Setting<Boolean> ignoreNamed = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-named").description("Whether or not to aim at mobs with a name.").defaultValue(false).build()
    );
    private final Setting<Boolean> ignorePassive = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-passive").description("Will only aim at passive mobs if they are targeting you.").defaultValue(true).build()
    );
    private final Setting<Boolean> ignoreTamed = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-tamed").description("Will avoid aiming at mobs you tamed.").defaultValue(false).build()
    );
    private final Setting<Boolean> ignoreCreative = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-creative").description("Will avoid aiming at players in creative mode.").defaultValue(true).build()
    );
    private final Setting<Boolean> ignoreFriends = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-friends").description("Will avoid aiming at players on your friends list.").defaultValue(true).build()
    );
    private final Setting<Boolean> ignoreShield = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-shield").description("Will avoid aiming at players who are using a shield.").defaultValue(false).build()
    );
    private final Setting<Boolean> useFovRange = sgFilter.add(new BoolSetting.Builder()
            .name("use-fov-range").description("Restrict aiming to entities within the specified FOV.").defaultValue(true).build()
    );
    private final Setting<Double> fovRange = sgFilter.add(new DoubleSetting.Builder()
            .name("fov-range").description("Maximum Field of View (FOV) range for targeting entities.").sliderRange(0, 180).defaultValue(90).visible(useFovRange::get).build()
    );
    private final Setting<Boolean> ignoreWalls = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-walls").description("Allow aiming through walls.").defaultValue(false).build()
    );
    private final Setting<SortPriority> priority = sgFilter.add(new EnumSetting.Builder<SortPriority>()
            .name("priority").description("Sorting method to prioritize targets within range.").defaultValue(SortPriority.ClosestAngle).build()
    );

    public final Setting<Double> targetMovementPrediction = sgAim.add(new DoubleSetting.Builder()
            .name("target-movement-prediction").description("Amount to predict the target's movement when aiming.").min(0).sliderMax(20).defaultValue(0).build()
    );
    private final Setting<Target> bodyTarget = sgAim.add(new EnumSetting.Builder<Target>()
            .name("aim-target").description("Part of the target entity's body to aim at.").defaultValue(Target.Head).build()
    );
    private final Setting<Boolean> instantAim = sgAim.add(new BoolSetting.Builder()
            .name("instant-aim").description("Aim at the target entity instantly.").defaultValue(false).build()
    );
    private final Setting<Boolean> syncSpeedWithCooldown = sgAim.add(new BoolSetting.Builder()
            .name("sync-speed-with-cooldown").description("Synchronize aim speed with attack cooldown progress.").defaultValue(false).visible(() -> !instantAim.get()).build()
    );
    private final Setting<Double> speed = sgAim.add(new DoubleSetting.Builder()
            .name("speed").description("Speed at which to adjust aim.").min(0).defaultValue(1).sliderRange(0.1, 10).visible(() -> !instantAim.get()).build()
    );

    private final Setting<Boolean> smoothAim = sgAim.add(new BoolSetting.Builder()
            .name("smooth-aim").description("Enables smooth aiming.").defaultValue(false).build()
    );
    private final Setting<Double> aimSpeed = sgAim.add(new DoubleSetting.Builder()
            .name("aim-speed").description("Speed of smooth aim.").defaultValue(1.0).min(0.1).sliderMax(10.0).visible(smoothAim::get).build()
    );
    private final Setting<Double> shakeIntensity = sgAim.add(new DoubleSetting.Builder()
            .name("shake-intensity").description("How much the aim shakes.").defaultValue(0.0).min(0.0).sliderMax(1.0).build()
    );
    private final Setting<Double> shakeSpeed = sgAim.add(new DoubleSetting.Builder()
            .name("shake-speed").description("Speed of the shake.").defaultValue(1.0).min(0.1).sliderMax(5.0).visible(() -> shakeIntensity.get() > 0).build()
    );
    private final Setting<Double> randomPointRadius = sgAim.add(new DoubleSetting.Builder()
            .name("random-point-radius").description("Aim at a random point within this radius.").defaultValue(0.0).min(0.0).sliderMax(0.5).build()
    );
    private final Setting<Boolean> prioritizeCenter = sgAim.add(new BoolSetting.Builder()
            .name("prioritize-center").description("Bias random point toward the center.").defaultValue(true).visible(() -> randomPointRadius.get() > 0).build()
    );
    private final Setting<Double> aimJitterAmplitude = sgAim.add(new DoubleSetting.Builder()
            .name("aim-jitter-amplitude").description("How far the aim jitters.").defaultValue(0.0).min(0.0).sliderMax(0.2).build()
    );
    private final Setting<Double> reactionTime = sgAim.add(new DoubleSetting.Builder()
            .name("reaction-time").description("Delay (in ticks) before aiming at a new target.").defaultValue(0.0).min(0.0).sliderMax(10.0).build()
    );

    private final Random random = new Random();
    private double shakePhase = 0;
    private int reactionTimer = 0;

    public ZAimbot() {
        super(Meteorist.CATEGORY, "z-aimbot", "Smart aimbot that takes many settings into account when targeting.");
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        Entity target = TargetUtils.get(this::entityCheck, priority.get());
        if (target == null) {
            reactionTimer = 0;
            return;
        }

        if (reactionTime.get() > 0) {
            if (reactionTimer < reactionTime.get().intValue()) {
                reactionTimer++;
                return;
            }
        } else {
            reactionTimer = 0;
        }

        aim(mc.player, target);
    }

    private boolean entityCheck(Entity entity) {
        if (entity.equals(mc.player) || entity.equals(mc.cameraEntity)) return false;
        if (!entity.isAlive()) return false;
        if (!PlayerUtils.isWithin(entity, range.get())) return false;
        if (!entities.get().contains(entity.getType())) return false;
        if (ignoreNamed.get() && entity.hasCustomName()) return false;

        if (ignoreTamed.get()) {
            if (entity instanceof Tameable tameable && tameable.getOwner() != null && tameable.getOwner().equals(mc.player)) return false;
        }
        if (ignorePassive.get()) {
            switch (entity) {
                case EndermanEntity enderman when !enderman.isAngry() -> { return false; }
                case ZombifiedPiglinEntity piglin when !piglin.isAttacking() -> { return false; }
                case WolfEntity wolf when !wolf.isAttacking() -> { return false; }
                default -> {}
            }
        }
        if (entity instanceof PlayerEntity player) {
            if (ignoreCreative.get() && player.isCreative()) return false;
            if (ignoreFriends.get() && !Friends.get().shouldAttack(player)) return false;
            if (ignoreShield.get() && player.isBlocking()) return false;
        }
        if (entity instanceof AnimalEntity animal) {
            return switch (mobAgeFilter.get()) {
                case Baby -> animal.isBaby();
                case Adult -> !animal.isBaby();
                case Both -> true;
            };
        }
        if (useFovRange.get() && calculateFov(mc.player, entity) > fovRange.get()) return false;
        return ignoreWalls.get() || PlayerUtils.canSeeEntity(entity);
    }

    private void aim(LivingEntity player, Entity target) {
        Vec3d aimPos = target.getPos().add(target.getVelocity().multiply(targetMovementPrediction.get()));
        
        if (randomPointRadius.get() > 0) {
            Vec3d center = target.getPos().add(0, target.getHeight() / 2, 0);
            Vec3d offset = new Vec3d(
                    (random.nextDouble() - 0.5) * 2 * randomPointRadius.get(),
                    (random.nextDouble() - 0.5) * 2 * randomPointRadius.get(),
                    (random.nextDouble() - 0.5) * 2 * randomPointRadius.get()
            );
            if (prioritizeCenter.get()) offset = offset.multiply(0.5);
            aimPos = center.add(offset);
        }

        float targetYaw = (float) Rotations.getYaw(aimPos);
        float targetPitch = (float) Rotations.getPitch(target, bodyTarget.get());

        if (shakeIntensity.get() > 0) {
            shakePhase += shakeSpeed.get() * 0.1;
            if (shakePhase > Math.PI * 2) shakePhase -= Math.PI * 2;
            targetYaw += (float) (Math.sin(shakePhase) * shakeIntensity.get());
            targetPitch += (float) (Math.cos(shakePhase * 1.3) * shakeIntensity.get());
        }

        if (aimJitterAmplitude.get() > 0) {
            targetYaw += (float) ((random.nextDouble() - 0.5) * 2 * aimJitterAmplitude.get());
            targetPitch += (float) ((random.nextDouble() - 0.5) * 2 * aimJitterAmplitude.get());
        }

        float yawDifference = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        float pitchDifference = MathHelper.wrapDegrees(targetPitch - player.getPitch());

        if (instantAim.get()) {
            player.setYaw(targetYaw);
            player.setPitch(targetPitch);
        } else if (smoothAim.get()) {
            float cooldownProgress = syncSpeedWithCooldown.get() ? mc.player.getAttackCooldownProgress(0) : 1;
            player.setYaw(player.getYaw() + yawDifference * cooldownProgress * aimSpeed.get().floatValue() / 10);
            player.setPitch(player.getPitch() + pitchDifference * cooldownProgress * aimSpeed.get().floatValue() / 10);
        } else {
            float cooldownProgress = syncSpeedWithCooldown.get() ? mc.player.getAttackCooldownProgress(0) : 1;
            player.setYaw(player.getYaw() + yawDifference * cooldownProgress * speed.get().floatValue() / 10);
            player.setPitch(player.getPitch() + pitchDifference * cooldownProgress * speed.get().floatValue() / 10);
        }
    }
}
