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

    // --- FILTER (ОСТАВЛЯЕМ КАК БЫЛО) ---
    private final Setting<Set<EntityType<?>>> entities = sgFilter.add(new EntityTypeListSetting.Builder()
            .name("entities").description("Entities to aim at.").onlyAttackable().defaultValue(EntityType.PLAYER).build()
    );
    private final Setting<Double> range = sgFilter.add(new DoubleSetting.Builder()
            .name("range").description("The maximum range the entity can be to aim at it.").defaultValue(4.5).min(0).sliderMax(6).build()
    );
    // ... (Остальные фильтры: mobAgeFilter, ignoreNamed, ignorePassive, ignoreTamed, ignoreCreative, ignoreFriends, ignoreShield, useFovRange, fovRange, ignoreWalls, priority)

    // --- AIM ---
    private final Setting<Double> targetMovementPrediction = sgAim.add(new DoubleSetting.Builder()
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

    // --- HUMANIZATION & BYPASS ---
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

    // --- RANDOM AIM POINT ---
    private final Setting<Boolean> randomAimPoint = sgAim.add(new BoolSetting.Builder()
            .name("random-aim-point").description("Рандомит точку прицеливания в хитбоксе (от поножей до шеи), исключая центр.").defaultValue(true).build()
    );
    private final Setting<Double> randomAimRadius = sgAim.add(new DoubleSetting.Builder()
            .name("random-aim-radius").description("Радиус разброса точки в хитбоксе.").defaultValue(0.15).min(0.0).sliderMax(0.3).visible(randomAimPoint::get).build()
    );
    private final Setting<Boolean> slowDownInHitbox = sgAim.add(new BoolSetting.Builder()
            .name("slow-down-in-hitbox").description("Замедляет наводку, когда прицел уже внутри хитбокса.").defaultValue(true).build()
    );

    // --- AUTO CONFIG ---
    private final Setting<AutoConfig> autoConfigMode = sgAim.add(new EnumSetting.Builder<AutoConfig>()
            .name("auto-config").description("Автоматически подбирает настройки под античит.").defaultValue(AutoConfig.None).build()
    );

    public enum AutoConfig { None, Polar, Shard }

    private final Random random = new Random();
    private double shakePhase = 0;
    private int reactionTimer = 0;
    private Vec3d currentAimPoint = null;
    private int aimPointUpdateTimer = 0;

    public ZAimbot() {
        super(Meteorist.CATEGORY, "z-aimbot", "Smart aimbot with humanization and AC bypass.");
    }

    @Override
    public void onActivate() {
        reactionTimer = 0;
        currentAimPoint = null;
        applyAutoConfig();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        Entity target = TargetUtils.get(this::entityCheck, priority.get());
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
        
        // Рандомная точка в хитбоксе (от поножей до шеи)
        if (randomAimPoint.get()) {
            aimPointUpdateTimer--;
            if (currentAimPoint == null || aimPointUpdateTimer <= 0) {
                // Выбираем точку от 0.2 (ноги) до 0.9 (шея)
                double yOffset = 0.2 + random.nextDouble() * 0.7;
                aimPos = target.getPos().add(0, target.getHeight() * yOffset, 0);
                
                // Разброс по X и Z (но не в самый центр)
                double xzOffset = randomAimRadius.get();
                aimPos = aimPos.add(
                    (random.nextDouble() - 0.5) * 2 * xzOffset,
                    0,
                    (random.nextDouble() - 0.5) * 2 * xzOffset
                );
                
                currentAimPoint = aimPos;
                aimPointUpdateTimer = 2 + random.nextInt(4); // Обновляем каждые 2-5 тиков
            } else {
                aimPos = currentAimPoint;
            }
        } else {
            // Старая логика через bodyTarget
            aimPos = target.getPos().add(0, target.getHeight() * 0.85, 0); // Примерно шея
        }

        float targetYaw = (float) Rotations.getYaw(aimPos);
        float targetPitch = (float) Rotations.getPitch(aimPos);

        // --- HUMANIZATION ---
        if (microJitter.get() > 0) {
            targetYaw += (float) ((random.nextDouble() - 0.5) * 2 * microJitter.get());
            targetPitch += (float) ((random.nextDouble() - 0.5) * 2 * microJitter.get());
        }
        
        // Саккадические движения для Shard
        if (saccadicFix.get()) {
            if (random.nextFloat() < 0.2f) { // 20% шанс микро-рывка
                targetYaw += (float) ((random.nextDouble() - 0.5) * 3.0f);
                targetPitch += (float) ((random.nextDouble() - 0.5) * 3.0f);
            }
        }

        float yawDifference = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        float pitchDifference = MathHelper.wrapDegrees(targetPitch - player.getPitch());

        // Замедление внутри хитбокса
        if (slowDownInHitbox.get()) {
            double distanceToTarget = player.getPos().distanceTo(target.getPos());
            if (distanceToTarget < 3.0) {
                yawDifference *= 0.7f;
                pitchDifference *= 0.7f;
            }
        }

        if (instantAim.get()) {
            player.setYaw(normalizeToGCD(targetYaw));
            player.setPitch(MathHelper.clamp(normalizeToGCD(targetPitch), -90f, 90f));
        } else {
            float speedFactor = smoothAim.get() ? aimSpeed.get().floatValue() : speed.get().floatValue();
            speedFactor /= 10.0f;
            
            // Вариативность скорости
            speedFactor *= 1.0f + (float) (random.nextDouble() - 0.5) * 2.0f * aimSpeedVariation.get().floatValue();
            
            float deltaYaw = yawDifference * speedFactor;
            float deltaPitch = pitchDifference * speedFactor;
            
            // Ограничение поворота
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
                saccadicFix.set(false); // Polar не любит рывки
                randomAimPoint.set(true);
                randomAimRadius.set(0.15);
                slowDownInHitbox.set(true);
                reactionTime.set(2.0);
                smoothAim.set(true);
                aimSpeed.set(2.5);
            }
            case Shard -> {
                gcdFix.set(true);
                maxRotationPerTick.set(45.0);
                aimSpeedVariation.set(0.4);
                microJitter.set(0.06);
                saccadicFix.set(true); // Shard любит саккады
                randomAimPoint.set(true);
                randomAimRadius.set(0.2);
                slowDownInHitbox.set(true);
                reactionTime.set(4.0);
                smoothAim.set(true);
                aimSpeed.set(2.0);
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

    // ... (Метод entityCheck оставляем без изменений)
}
