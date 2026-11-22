package zgoly.meteorist.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
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
    public enum AutoConfig { None, Polar, Shard }

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

    private final Setting<Boolean> smoothAim = sgAim.add(new BoolSetting.Builder()
            .name("smooth-aim").description("Enables smooth aiming.").defaultValue(true).build()
    );
    private final Setting<Double> aimSpeed = sgAim.add(new DoubleSetting.Builder()
            .name("aim-speed").description("Speed of smooth aim.").defaultValue(2.0).min(0.1).sliderMax(10.0).visible(smoothAim::get).build()
    );
    private final Setting<Double> maxRotationPerTick = sgAim.add(new DoubleSetting.Builder()
            .name("max-rotation-per-tick").description("Максимальный поворот за тик в градусах (обходит AimM/AimP).").defaultValue(35.0).min(5.0).sliderMax(180.0).build()
    );
    private final Setting<Boolean> gcdFix = sgAim.add(new BoolSetting.Builder()
            .name("gcd-fix").description("Нормализует углы под GCD мыши (обходит Polar AimQ/AimU).").defaultValue(true).build()
    );
    private final Setting<Double> reactionTime = sgAim.add(new DoubleSetting.Builder()
            .name("reaction-time").description("Delay (in ticks) before aiming at a new target.").defaultValue(3.0).min(0.0).sliderMax(10.0).build()
    );

    private final Setting<Double> aimSpeedVariation = sgAim.add(new DoubleSetting.Builder()
            .name("aim-speed-variation").description("Насколько скорость аима меняется от тика к тику (AimI/AimK).").defaultValue(0.25).min(0.0).sliderMax(0.6).build()
    );
    private final Setting<Double> microJitter = sgAim.add(new DoubleSetting.Builder()
            .name("micro-jitter").description("Микро-дрожание руки.").defaultValue(0.04).min(0.0).sliderMax(0.2).build()
    );
    private final Setting<Boolean> saccadicFix = sgAim.add(new BoolSetting.Builder()
            .name("saccadic-fix").description("Добавляет саккадические движения (микро-рывки) для обхода Shard AI.").defaultValue(true).build()
    );

    private final Setting<Double> targetLockTime = sgAim.add(new DoubleSetting.Builder()
            .name("target-lock-time").description("Сколько секунд удерживать фокус на одной цели (1-10 сек).").defaultValue(3.0).min(0.0).sliderMax(10.0).build()
    );
    private final Setting<Boolean> randomAimPoint = sgAim.add(new BoolSetting.Builder()
            .name("random-aim-point").description("Рандомит точку прицеливания (от поножей до шеи).").defaultValue(true).build()
    );
    private final Setting<Boolean> slowDownInHitbox = sgAim.add(new BoolSetting.Builder()
            .name("slow-down-in-hitbox").description("Замедляет наводку, когда прицел уже внутри хитбокса.").defaultValue(true).build()
    );

    private final Setting<AutoConfig> autoConfigMode = sgAim.add(new EnumSetting.Builder<AutoConfig>()
            .name("auto-config").description("Автоматически подбирает настройки под античит.").defaultValue(AutoConfig.None).build()
    );

    private final Random random = new Random();
    private int reactionTimer = 0;
    private LivingEntity lockedTarget = null;
    private long lockStartTime = 0;
    private Vec3d currentAimPoint = null;

    public ZAimbot() {
        super(Meteorist.CATEGORY, "z-aimbot", "Smart aimbot with humanization, target lock and AC bypass.");
    }

    @Override
    public void onActivate() {
        reactionTimer = 0;
        lockedTarget = null;
        currentAimPoint = null;
        applyAutoConfig();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        long currentTime = System.currentTimeMillis();

        if (lockedTarget != null) {
            boolean lostTarget = !lockedTarget.isAlive() 
                    || !PlayerUtils.isWithin(lockedTarget, range.get()) 
                    || (currentTime - lockStartTime > targetLockTime.get() * 1000);
            
            if (lostTarget) {
                lockedTarget = null;
                currentAimPoint = null;
            }
        }

        if (lockedTarget == null) {
            Entity target = TargetUtils.get(this::entityCheck, priority.get());
            if (target instanceof LivingEntity living) {
                lockedTarget = living;
                lockStartTime = currentTime;
                currentAimPoint = null;
                reactionTimer = 0;
            }
        }

        if (lockedTarget == null) return;

        if (reactionTime.get() > 0) {
            if (reactionTimer < reactionTime.get().intValue()) {
                reactionTimer++;
                return;
            }
        }

        aim(mc.player, lockedTarget);
    }

    private void aim(LivingEntity player, LivingEntity target) {
        Box box = target.getBoundingBox();
        
        if (randomAimPoint.get()) {
            if (currentAimPoint == null || random.nextInt(10) < 3) {
                double y = box.minY + box.getLengthY() * (0.2 + random.nextDouble() * 0.6);
                double x = box.minX + box.getLengthX() * (0.3 + random.nextDouble() * 0.4);
                double z = box.minZ + box.getLengthZ() * (0.3 + random.nextDouble() * 0.4);
                currentAimPoint = new Vec3d(x, y, z);
            }
        } else {
            currentAimPoint = target.getPos().add(0, target.getHeight() / 2.0, 0);
        }

        float targetYaw = (float) Rotations.getYaw(currentAimPoint);
        float targetPitch = (float) Rotations.getPitch(currentAimPoint);

        if (microJitter.get() > 0) {
            targetYaw += (float) ((random.nextDouble() - 0.5) * 2 * microJitter.get());
            targetPitch += (float) ((random.nextDouble() - 0.5) * 2 * microJitter.get());
        }
        if (saccadicFix.get() && random.nextFloat() < 0.15f) {
            targetYaw += (float) ((random.nextDouble() - 0.5) * 4.0f);
            targetPitch += (float) ((random.nextDouble() - 0.5) * 2.0f);
        }

        float yawDifference = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        float pitchDifference = MathHelper.wrapDegrees(targetPitch - player.getPitch());

        boolean lookingAtTarget = false;
        HitResult raycast = mc.player.raycast(range.get(), 0, false);
        if (raycast.getType() == HitResult.Type.ENTITY && mc.crosshairTarget != null) {
            lookingAtTarget = mc.crosshairTarget.getType() == HitResult.Type.ENTITY 
                    && ((EntityHitResult)mc.crosshairTarget).getEntity() == target;
        }

        float speedFactor = aimSpeed.get().floatValue() / 10.0f;
        
        if (slowDownInHitbox.get() && lookingAtTarget) {
            speedFactor *= 0.3f; 
        }

        speedFactor *= 1.0f + (float) (random.nextDouble() - 0.5) * 2.0f * aimSpeedVariation.get().floatValue();

        float deltaYaw = yawDifference * speedFactor;
        float deltaPitch = pitchDifference * speedFactor;

        float maxRot = maxRotationPerTick.get().floatValue();
        deltaYaw = MathHelper.clamp(deltaYaw, -maxRot, maxRot);
        deltaPitch = MathHelper.clamp(deltaPitch, -maxRot, maxRot);

        if (gcdFix.get()) {
            float sensitivity = mc.options.getMouseSensitivity().getValue().floatValue();
            float f = sensitivity * 0.6f + 0.2f;
            float gcd = f * f * f * 1.2f; 
            
            deltaYaw = Math.round(deltaYaw / gcd) * gcd;
            deltaPitch = Math.round(deltaPitch / gcd) * gcd;
        }

        player.setYaw(player.getYaw() + deltaYaw);
        player.setPitch(MathHelper.clamp(player.getPitch() + deltaPitch, -90f, 90f));
    }

    private void applyAutoConfig() {
        switch (autoConfigMode.get()) {
            case Polar -> {
                gcdFix.set(true);
                maxRotationPerTick.set(30.0);
                aimSpeedVariation.set(0.2);
                microJitter.set(0.03);
                saccadicFix.set(false);
                randomAimPoint.set(true);
                slowDownInHitbox.set(true);
                reactionTime.set(2.0);
                smoothAim.set(true);
                aimSpeed.set(2.5);
                targetLockTime.set(3.0);
            }
            case Shard -> {
                gcdFix.set(true);
                maxRotationPerTick.set(45.0);
                aimSpeedVariation.set(0.4);
                microJitter.set(0.06);
                saccadicFix.set(true);
                randomAimPoint.set(true);
                slowDownInHitbox.set(true);
                reactionTime.set(4.0);
                smoothAim.set(true);
                aimSpeed.set(2.0);
                targetLockTime.set(4.0);
            }
            case None -> {}
        }
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
}
