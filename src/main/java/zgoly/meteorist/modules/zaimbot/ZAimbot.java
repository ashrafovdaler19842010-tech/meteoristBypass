package zgoly.meteorist.modules.zaimbot;

import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.EntityAgeTest;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import zgoly.meteorist.Meteorist;

import java.util.Random;
import java.util.Set;

import static zgoly.meteorist.utils.MeteoristUtils.calculateFov;

public class ZAimbot extends Module {
    public enum AimMode { 
        Random, ClosestPoint, EdgeOnly, 
        Backstab, Spinbot, FakeLook // Новые режимы
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAim = settings.createGroup("Aim");
    private final SettingGroup sgNewModes = settings.createGroup("New Modes");

    // --- Старые настройки (сохранены) ---
    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
            .name("entities").description("Entities to aim at.").onlyAttackable().defaultValue(EntityTypes.PLAYER).build()
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
            .name("ignore-friends").defaultValue(true).build()
    );
    private final Setting<Boolean> ignoreWalls = sgGeneral.add(new BoolSetting.Builder()
            .name("ignore-walls").defaultValue(false).build()
    );

    private final Setting<AimMode> aimMode = sgAim.add(new EnumSetting.Builder<AimMode>()
            .name("aim-mode").defaultValue(AimMode.Random).build()
    );
    private final Setting<Boolean> randomizeTarget = sgAim.add(new BoolSetting.Builder()
            .name("randomize-target").defaultValue(true).build()
    );
    private final Setting<Double> speedX = sgAim.add(new DoubleSetting.Builder()
            .name("speed-x").description("Скорость по горизонтали (Yaw). 0 = выкл.").defaultValue(3.0).min(0.0).sliderMax(10.0).build()
    );
    private final Setting<Double> speedY = sgAim.add(new DoubleSetting.Builder()
            .name("speed-y").description("Скорость по вертикали (Pitch). 0 = выкл.").defaultValue(3.0).min(0.0).sliderMax(10.0).build()
    );
    private final Setting<Double> targetLockTime = sgAim.add(new DoubleSetting.Builder()
            .name("target-lock-time").defaultValue(5.0).min(0.0).sliderMax(10.0).build()
    );

    // --- Новые настройки для режимов ---
    private final Setting<Double> rotationSpeed = sgNewModes.add(new DoubleSetting.Builder()
            .name("rotation-speed").description("Скорость поворота (для Backstab и Spinbot).").defaultValue(5.0).min(0.5).sliderMax(20.0).build()
    );
    private final Setting<Double> spinSpeed = sgNewModes.add(new DoubleSetting.Builder()
            .name("spin-speed").description("Скорость вращения камеры в режиме Spinbot.").defaultValue(15.0).min(1.0).sliderMax(50.0).build()
    );
    private final Setting<Double> fakeLookAngle = sgNewModes.add(new DoubleSetting.Builder()
            .name("fake-look-angle").description("Угол отклонения камеры в режиме FakeLook.").defaultValue(180.0).min(45.0).sliderMax(360.0).build()
    );

    private final Random random = new Random();
    private Vec3 currentAimPoint = null;
    public LivingEntity lockedTarget = null;
    private long lockedTargetTime = 0;

    // Для новых режимов
    private float originalYaw = 0;
    private float originalPitch = 0;
    private boolean isAttacking = false;
    private float spinYaw = 0;
    private float fakeLookYaw = 0;
    private long lastAttackTime = 0;

    public ZAimbot() {
        super(Meteorist.CATEGORY, "z-aimbot", "Aimbot with separate X/Y speeds and new camera modes.");
    }

    @Override
    public void onActivate() {
        currentAimPoint = null;
        lockedTarget = null;
        originalYaw = mc.player != null ? mc.player.getYRot() : 0;
        originalPitch = mc.player != null ? mc.player.getXRot() : 0;
        isAttacking = false;
        spinYaw = originalYaw;
        fakeLookYaw = originalYaw;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        // Обновляем "истинный" угол для ходьбы, если не атакуем
        if (!isAttacking) {
            originalYaw = mc.player.getYRot();
            originalPitch = mc.player.getXRot();
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;

        // Поиск цели
        if (lockedTarget == null || !lockedTarget.isAlive() 
                || PlayerUtils.distanceTo(lockedTarget) > aggroRange.get()
                || (System.currentTimeMillis() - lockedTargetTime > targetLockTime.get() * 1000)) {
            lockedTarget = findTarget();
            if (lockedTarget != null) {
                lockedTargetTime = System.currentTimeMillis();
                currentAimPoint = null;
            }
        }

        if (lockedTarget == null) return;
        if (!PlayerUtils.isWithin(lockedTarget, range.get())) return;
        if (calculateFov(mc.player, lockedTarget) > fov.get()) return;

        // Применяем выбранный режим
        switch (aimMode.get()) {
            case Backstab -> handleBackstab();
            case Spinbot -> handleSpinbot();
            case FakeLook -> handleFakeLook();
            default -> {
                // Старые режимы (Random, ClosestPoint, EdgeOnly)
                aim(mc.player, lockedTarget);
            }
        }
    }

    // --- Новые режимы ---

    private void handleBackstab() {
        // Смотрим в противоположную сторону от цели
        float yawToTarget = (float) Rotations.getYaw(lockedTarget.getEyePosition());
        float oppositeYaw = yawToTarget + 180f;
        
        // Плавно поворачиваем камеру в противоположную сторону
        mc.player.setYRot(approachAngle(mc.player.getYRot(), oppositeYaw, rotationSpeed.get().floatValue()));
        mc.player.setXRot(approachAngle(mc.player.getXRot(), 0, rotationSpeed.get().floatValue()));

        // Если цель в зоне атаки, делаем быстрый "выпад"
        if (PlayerUtils.isWithin(lockedTarget, range.get()) && mc.player.getAttackStrengthScale(0) >= 1.0f) {
            isAttacking = true;
            // Мгновенный поворот к цели
            float attackYaw = (float) Rotations.getYaw(lockedTarget.getEyePosition());
            float attackPitch = (float) Rotations.getPitch(lockedTarget.getEyePosition());
            
            mc.player.setYRot(attackYaw);
            mc.player.setXRot(attackPitch);

            // Атака
            mc.gameMode.attack(mc.player, lockedTarget);
            mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND,
                    mc.player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND).getAttackAnimation(),
                    false);

            lastAttackTime = System.currentTimeMillis();
            isAttacking = false;
        }
    }

    private void handleSpinbot() {
        // Постоянное вращение
        spinYaw += spinSpeed.get().floatValue();
        if (spinYaw > 360) spinYaw -= 360;
        
        // Визуальное вращение (но с коррекцией движения)
        mc.player.setYRot(spinYaw);
        mc.player.setXRot(0);

        // Если цель в зоне, атакуем с поворотом
        if (PlayerUtils.isWithin(lockedTarget, range.get()) && mc.player.getAttackStrengthScale(0) >= 1.0f) {
            isAttacking = true;
            float attackYaw = (float) Rotations.getYaw(lockedTarget.getEyePosition());
            float attackPitch = (float) Rotations.getPitch(lockedTarget.getEyePosition());

            mc.player.setYRot(attackYaw);
            mc.player.setXRot(attackPitch);

            mc.gameMode.attack(mc.player, lockedTarget);
            mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND,
                    mc.player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND).getAttackAnimation(),
                    false);

            lastAttackTime = System.currentTimeMillis();
            isAttacking = false;
        }
    }

    private void handleFakeLook() {
        // Плавное "блуждание" камерой
        fakeLookYaw += 2.0f; // скорость блуждания
        if (fakeLookYaw > originalYaw + fakeLookAngle.get().floatValue()) {
            fakeLookYaw = originalYaw - fakeLookAngle.get().floatValue();
        }
        
        mc.player.setYRot(fakeLookYaw);
        mc.player.setXRot(0);

        // Если цель в зоне и готов крит, наводимся и бьем
        if (PlayerUtils.isWithin(lockedTarget, range.get()) && mc.player.getAttackStrengthScale(0) >= 1.0f) {
            isAttacking = true;
            float attackYaw = (float) Rotations.getYaw(lockedTarget.getEyePosition());
            float attackPitch = (float) Rotations.getPitch(lockedTarget.getEyePosition());

            mc.player.setYRot(attackYaw);
            mc.player.setXRot(attackPitch);

            mc.gameMode.attack(mc.player, lockedTarget);
            mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND,
                    mc.player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND).getAttackAnimation(),
                    false);

            lastAttackTime = System.currentTimeMillis();
            isAttacking = false;
        }
    }

    // --- Старая логика aim (Random, ClosestPoint, EdgeOnly) ---
    private void aim(LivingEntity player, Entity target) {
        // ... (оставляем без изменений, как в предыдущей версии) ...
        // Вставь сюда старый код метода aim()
    }

    // --- Коррекция движения (чтобы не отлетать в сторону взгляда) ---
    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (aimMode.get() != AimMode.Spinbot && aimMode.get() != AimMode.FakeLook) return;
        if (isAttacking) return; // Во время атаки не корректируем

        // Корректируем вектор движения, используя оригинальный yaw
        Vec3 movement = event.movement;
        if (movement.horizontalDistanceSqr() > 0) {
            float originalRad = (float) Math.toRadians(originalYaw);
            double cos = Math.cos(originalRad);
            double sin = Math.sin(originalRad);

            double newX = movement.x * cos - movement.z * sin;
            double newZ = movement.x * sin + movement.z * cos;

            // Применяем скорректированное движение
            event.movement = new Vec3(newX, movement.y, newZ);
        }
    }

    // Вспомогательный метод для плавного поворота
    private float approachAngle(float current, float target, float speed) {
        float diff = Mth.wrapDegrees(target - current);
        if (Math.abs(diff) <= speed) return target;
        return current + Math.signum(diff) * speed;
    }

    private LivingEntity findTarget() {
        LivingEntity closest = null;
        double closestDist = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities().getAll()) {
            if (entityCheck(e) && mc.player.distanceTo(e) < closestDist) {
                closest = (LivingEntity) e;
                closestDist = mc.player.distanceTo(e);
            }
        }
        return closest;
    }

    private boolean entityCheck(Entity entity) {
        if (entity.equals(mc.player) || entity.equals(mc.getCameraEntity())) return false;
        if (!entity.isAlive()) return false;
        if (!PlayerUtils.isWithin(entity, aggroRange.get())) return false;
        if (!entities.get().contains(entity.getType())) return false;
        if (ignoreFriends.get() && entity instanceof Player player && !Friends.get().shouldAttack(player)) return false;
        return ignoreWalls.get() || PlayerUtils.canSeeEntity(entity);
    }
}
