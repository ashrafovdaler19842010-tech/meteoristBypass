package zgoly.meteorist.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
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
    public enum AxisMode { Both, YawOnly, PitchOnly }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAim = settings.createGroup("Aim");
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced");

    // --- General ---
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
            .name("ignore-friends").defaultValue(true).build()
    );
    private final Setting<Boolean> ignoreWalls = sgGeneral.add(new BoolSetting.Builder()
            .name("ignore-walls").defaultValue(false).build()
    );

    // --- Aim ---
    private final Setting<Boolean> onlySword = sgAim.add(new BoolSetting.Builder()
            .name("only-sword").defaultValue(false).build()
    );
    private final Setting<AxisMode> axisMode = sgAim.add(new EnumSetting.Builder<AxisMode>()
            .name("axis-mode").description("Which axes to rotate.").defaultValue(AxisMode.Both).build()
    );
    private final Setting<AimMode> aimMode = sgAim.add(new EnumSetting.Builder<AimMode>()
            .name("aim-mode").defaultValue(AimMode.Random).build()
    );
    private final Setting<Boolean> randomizeTarget = sgAim.add(new BoolSetting.Builder()
            .name("randomize-target").defaultValue(true).build()
    );
    private final Setting<Double> aimSpeed = sgAim.add(new DoubleSetting.Builder()
            .name("speed").defaultValue(3.0).min(0.1).sliderMax(10.0).build()
    );
    private final Setting<Double> targetLockTime = sgAim.add(new DoubleSetting.Builder()
            .name("target-lock-time").description("How long to remember the target (seconds).").defaultValue(5.0).min(0.0).sliderMax(10.0).build()
    );

    // --- Advanced ---
    private final Setting<Boolean> turnAround = sgAdvanced.add(new BoolSetting.Builder()
            .name("turn-around").description("Поворачивает на 180° если цель вне поля зрения.").defaultValue(false).build()
    );
    private final Setting<Boolean> snapOnAttack = sgAdvanced.add(new BoolSetting.Builder()
            .name("snap-on-attack").description("Резкий доворот в момент удара.").defaultValue(false).build()
    );
    private final Setting<Boolean> randomizeMovement = sgAdvanced.add(new BoolSetting.Builder()
            .name("randomize-movement").description("Добавляет случайный шум к движениям.").defaultValue(false).build()
    );
    private final Setting<Double> randomStrength = sgAdvanced.add(new DoubleSetting.Builder()
            .name("random-strength").description("Сила случайного шума.").defaultValue(0.5).min(0.0).sliderMax(3.0).visible(randomizeMovement::get).build()
    );

    private final Random random = new Random();
    private Vec3d currentAimPoint = null;
    private LivingEntity lockedTarget = null;
    private long lockedTargetTime = 0;

    public ZAimbot() {
        super(Meteorist.CATEGORY, "z-aimbot", "Smooth aimbot with sticky target and advanced rotation.");
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

        long currentTime = System.currentTimeMillis();

        // Проверка, жива ли текущая «липкая» цель
        if (lockedTarget != null) {
            if (!lockedTarget.isAlive()
                    || PlayerUtils.distanceTo(lockedTarget) > aggroRange.get()
                    || (currentTime - lockedTargetTime > targetLockTime.get() * 1000)) {
                lockedTarget = null;
                currentAimPoint = null;
            }
        }

        // Поиск новой цели, если текущей нет
        if (lockedTarget == null) {
            LivingEntity closest = null;
            double closestDist = Double.MAX_VALUE;

            for (Entity e : mc.world.getEntities()) {
                if (entityCheck(e) && mc.player.distanceTo(e) < closestDist) {
                    closest = (LivingEntity) e;
                    closestDist = mc.player.distanceTo(e);
                }
            }

            if (closest != null) {
                lockedTarget = closest;
                lockedTargetTime = currentTime;
                currentAimPoint = null;
            }
        }

        if (lockedTarget == null) return;
        if (!PlayerUtils.isWithin(lockedTarget, range.get())) return;
        if (calculateFov(mc.player, lockedTarget) > fov.get()) return;

        // Поворот на 180°, если цель вне поля зрения
        if (turnAround.get() && !mc.player.canSee(lockedTarget)) {
            mc.player.setYaw(mc.player.getYaw() + 180f);
            return;
        }

        aim(mc.player, lockedTarget);
    }

    private void aim(LivingEntity player, Entity target) {
        Box box = target.getBoundingBox();
        Vec3d eyePos = player.getEyePos();

        // Выбор точки прицеливания
        if (aimMode.get() == AimMode.EdgeOnly) {
            HitResult hit = mc.crosshairTarget;
            if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
                if (((EntityHitResult) hit).getEntity() == target) return;
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

        float yawDiff = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        float pitchDiff = MathHelper.wrapDegrees(targetPitch - player.getPitch());

        // Разделение по осям
        if (axisMode.get() == AxisMode.YawOnly) pitchDiff = 0;
        if (axisMode.get() == AxisMode.PitchOnly) yawDiff = 0;

        // Плавность
        float speedFactor = aimSpeed.get().floatValue() / 10.0f;
        float deltaYaw = yawDiff * speedFactor;
        float deltaPitch = pitchDiff * speedFactor;

        // Рандомизация движений
        if (randomizeMovement.get()) {
            float jitter = (float) ((random.nextDouble() - 0.5) * randomStrength.get());
            deltaYaw += jitter;
            deltaPitch += jitter * 0.5f;
        }

        // Привязка к сенсе (GCD)
        float sens = mc.options.getMouseSensitivity().getValue().floatValue();
        float f = sens * 0.6f + 0.2f;
        float gcd = f * f * f * 1.2f;
        deltaYaw = Math.round(deltaYaw / gcd) * gcd;
        deltaPitch = Math.round(deltaPitch / gcd) * gcd;

        player.setYaw(player.getYaw() + deltaYaw);
        player.setPitch(MathHelper.clamp(player.getPitch() + deltaPitch, -90f, 90f));
    }

    /**
     * Вызывается из KillAura / ZAutoSprint перед атакой.
     * Если включён snapOnAttack — резко доворачивает на цель.
     */
    public void snapToTarget() {
        if (snapOnAttack.get() && lockedTarget != null && lockedTarget.isAlive()) {
            Vec3d eye = mc.player.getEyePos();
            Vec3d target = lockedTarget.getPos().add(0, lockedTarget.getHeight() / 2, 0);
            mc.player.setYaw((float) Rotations.getYaw(target));
            mc.player.setPitch((float) Rotations.getPitch(target));
        }
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
