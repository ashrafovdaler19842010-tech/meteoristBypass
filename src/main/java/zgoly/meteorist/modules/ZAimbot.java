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
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
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
            .name("aim-target").description("Part of the target entity's body to aim at.").defaultValue(Target.Body).build()
    );
    private final Setting<Boolean> instantAim = sgAim.add(new BoolSetting.Builder()
            .name("instant-aim").description("Aim at the target entity instantly.").defaultValue(false).build()
    );
    private final Setting<Boolean> smoothAim = sgAim.add(new BoolSetting.Builder()
            .name("smooth-aim").description("Enables smooth aiming.").defaultValue(true).build()
    );
    private final Setting<Double> aimSpeed = sgAim.add(new DoubleSetting.Builder()
            .name("aim-speed").description("Speed of smooth aim.").defaultValue(2.0).min(0.1).sliderMax(10.0).visible(smoothAim::get).build()
    );
    private final Setting<Double> speed = sgAim.add(new DoubleSetting.Builder()
            .name("speed").description("Speed at which to adjust aim.").min(0).defaultValue(1).sliderRange(0.1, 10).visible(() -> !instantAim.get()).build()
    );
    
    // --- НОВЫЕ НАСТРОЙКИ ДЛЯ ОБХОДА ---
    private final Setting<Boolean> gcdFix = sgAim.add(new BoolSetting.Builder()
            .name("gcd-fix").description("Нормализует углы под GCD мыши (обходит Polar AimQ/AimU).").defaultValue(true).build()
    );
    private final Setting<Double> maxRotationPerTick = sgAim.add(new DoubleSetting.Builder()
            .name("max-rotation-per-tick").description("Максимальный поворот за тик в градусах (обходит AimM/AimP).").defaultValue(35.0).min(5.0).sliderMax(180.0).build()
    );
    private final Setting<Double> aimSpeedVariation = sgAim.add(new DoubleSetting.Builder()
            .name("aim-speed-variation").description("Насколько скорость аима меняется от тика к тику (AimI/AimK).").defaultValue(0.25).min(0.0).sliderMax(0.6).build()
    );
    private final Setting<Double> microJitter = sgAim.add(new DoubleSetting.Builder()
            .name("micro-jitter").description("Микро-дрожание руки (не синусоида, а человеческий шум).").defaultValue(0.04).min(0.0).sliderMax(0.2).build()
    );
    private final Setting<Double> reactionTime = sgAim.add(new DoubleSetting.Builder()
            .name("reaction-time").description("Delay (in ticks) before aiming at a new target.").defaultValue(3.0).min(0.0).sliderMax(10.0).build()
    );
    private final Setting<Boolean> saccadicFix = sgAim.add(new BoolSetting.Builder()
            .name("saccadic-fix").description("Добавляет саккадические движения (микро-рывки) для обхода Shard AI.").defaultValue(true).build()
    );

    // --- TARGET LOCK И РАНДОМ ТОЧКИ ---
    private final Setting<Double> targetLockTime = sgAim.add(new DoubleSetting.Builder()
            .name("target-lock-time").description("Сколько секунд удерживать фокус на одной цели (1-10 сек).").defaultValue(3.0).min(0.0).sliderMax(10.0).build()
    );
    private final Setting<Boolean> randomAimPoint = sgAim.add(new BoolSetting.Builder()
            .name("random-aim-point").description("Рандомит точку прицеливания (от поножей до шеи), исключая центр.").defaultValue(true).build()
    );
    private final Setting<Double> randomAimRadius = sgAim.add(new DoubleSetting.Builder()
            .name("random-aim-radius").description("Радиус разброса точки в хитбоксе.").defaultValue(0.15).min(0.0).sliderMax(0.3).visible(randomAimPoint::get).build()
    );
    private final Setting<Boolean> slowDownInHitbox = sgAim.add(new BoolSetting.Builder()
            .name("slow-down-in-hitbox").description("Замедляет наводку, когда прицел уже внутри хитбокса.").defaultValue(true).build()
    );

    private final Setting<AutoConfig> autoConfigMode = sgAim.add(new EnumSetting.Builder<AutoConfig>()
            .name("auto-config").description("Автоматически подбирает настройки под античит.").defaultValue(AutoConfig.None).build()
    );

    public enum AutoConfig { None, Polar, Shard }

    private final Random random = new Random();
    private int reactionTimer = 0;
    private Entity lastTarget = null;
    private long lastTargetTime = 0;
    private Vec3d currentAimPoint = null;
    private int aimPointUpdateTimer = 0;

    public ZAimbot() {
        super(Meteorist.CATEGORY, "z-aimbot", "Smart aimbot with humanization, target lock and AC bypass.");
    }

    @Override
    public void onActivate() {
        reactionTimer = 0;
        lastTarget = null;
        currentAimPoint = null;
        applyAutoConfig();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        // --- ЛОГИКА TARGET LOCK ---
        Entity target = null;
        long currentTime = System.currentTimeMillis();

        if (lastTarget != null && lastTarget.isAlive() && PlayerUtils.isWithin(lastTarget, range.get()) 
                && (currentTime - lastTargetTime < targetLockTime.get() * 1000)) {
            target = lastTarget;
        } else {
            target = TargetUtils.get(this::entityCheck, priority.get());
            if (target != null) {
                lastTarget = target;
                lastTargetTime = currentTime;
            }
        }

        if (target == null) {
            reactionTimer = 0;
            currentAimPoint = null;
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

    private void aim(LivingEntity player, Entity target) {
        Vec3d aimPos = target.getPos().add(0, target.getHeight() * 0.5, 0);
        
        // Рандомная точка (поножи - шея)
        if (randomAimPoint.get()) {
            aimPointUpdateTimer--;
            if (currentAimPoint == null || aimPointUpdateTimer <= 0) {
                double yOffset = 0.2 + random.nextDouble() * 0.65; // 20% до 85% высоты
                aimPos = target.getPos().add(0, target.getHeight() * yOffset, 0);
                
                double xzOffset = randomAimRadius.get();
                aimPos = aimPos.add(
                    (random.nextDouble() - 0.5) * 2 * xzOffset,
                    0,
                    (random.nextDouble() - 0.5) * 2 * xzOffset
                );
                
                currentAimPoint = aimPos;
                aimPointUpdateTimer = 2 + random.nextInt(4); 
            } else {
                aimPos = currentAimPoint;
            }
        }

        float targetYaw = (float) Rotations.getYaw(aimPos);
        float targetPitch = (float) Rotations.getPitch(target, bodyTarget.get());

        if (microJitter.get() > 0) {
            targetYaw += (float) ((random.nextDouble() - 0.5) * 2 * microJitter.get());
            targetPitch += (float) ((random.nextDouble() - 0.5) * 2 * microJitter.get());
        }
        
        if (saccadicFix.get() && random.nextFloat() < 0.2f) {
            targetYaw += (float) ((random.nextDouble() - 0.5) * 3.0f);
            targetPitch += (float) ((random.nextDouble() - 0.5) * 3.0f);
        }

        float yawDifference = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        float pitchDifference = MathHelper.wrapDegrees(targetPitch - player.getPitch());

        // Замедление в хитбоксе
        boolean inHitbox = mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.ENTITY 
                && ((EntityHitResult)mc.crosshairTarget).getEntity() == target;
        if (slowDownInHitbox.get() && inHitbox) {
            yawDifference *= 0.3f;
            pitchDifference *= 0.3f;
        }

        if (instantAim.get()) {
            player.setYaw(normalizeToGCD(targetYaw));
            player.setPitch(MathHelper.clamp(normalizeToGCD(targetPitch), -90f, 90f));
        } else {
            float speedFactor = smoothAim.get() ? aimSpeed.get().floatValue() : speed.get().floatValue();
            speedFactor /= 10.0f;
            speedFactor *= 1.0f + (float) (random.nextDouble() - 0.5) * 2.0f * aimSpeedVariation.get().floatValue();
            
            float deltaYaw = yawDifference * speedFactor;
            float deltaPitch = pitchDifference * speedFactor;
            
            deltaYaw = clampAngleDelta(deltaYaw, maxRotationPerTick.get().floatValue());
            deltaPitch = clampAngleDelta(deltaPitch, maxRotationPerTick.get().floatValue());
            
            player.setYaw(normalizeToGCD(player.getYaw() + deltaYaw));
            player.setPitch(MathHelper.clamp(normalizeToGCD(player.getPitch() + deltaPitch), -90f, 90f));
        }
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
                randomAimRadius.set(0.15);
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
                randomAimRadius.set(0.2);
                slowDownInHitbox.set(true);
                reactionTime.set(4.0);
                smoothAim.set(true);
                aimSpeed.set(2.0);
                targetLockTime.set(4.0);
            }
            case None -> {}
        }
    }

    private float normalizeToGCD(float angle) {
        if (!gcdFix.get()) return angle;
        float sensitivity = mc.options.getMouseSensitivity().getValue().floatValue();
        float gcd = sensitivity * 0.6f + 0.2f;
        float step = gcd * gcd * gcd * 1.2f;
        if (step <= 0) return angle;
        return Math.round(angle / step) * step;
    }

    private float clampAngleDelta(float delta, float maxDelta) {
        if (Math.abs(delta) > maxDelta) {
            return Math.signum(delta) * maxDelta;
        }
        return delta;
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
