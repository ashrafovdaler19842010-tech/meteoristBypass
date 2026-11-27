package zgoly.meteorist.modules.zaimbot;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import zgoly.meteorist.Meteorist;

import java.util.Random;
import java.util.Set;

import static zgoly.meteorist.utils.MeteoristUtils.calculateFov;

public class ZAimbot extends Module {
    public enum CameraMode { Normal, Spinbot, FakeLook, Backstab, BackstabAlways, MixAim }
    public enum AimPointMode { Center, Random, NearestRay }
    private enum PounceState { IDLE, BACKING_UP, APPROACHING, POUNCING, AIRBORNE }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAim = settings.createGroup("Aim");
    private final SettingGroup sgBackstab = settings.createGroup("Backstab & Pounce");

    // --- General ---
    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
            .name("entities").description("Entities to aim at.").onlyAttackable().defaultValue(EntityType.PLAYER).build()
    );
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range").description("Distance to rotate camera.").defaultValue(4.5).min(0).sliderMax(6).build()
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
    private final Setting<CameraMode> cameraMode = sgAim.add(new EnumSetting.Builder<CameraMode>()
            .name("camera-mode").description("How the camera behaves.").defaultValue(CameraMode.BackstabAlways).build()
    );
    private final Setting<AimPointMode> aimPointMode = sgAim.add(new EnumSetting.Builder<AimPointMode>()
            .name("aim-point").description("Where to aim inside the hitbox.").defaultValue(AimPointMode.Center).build()
    );
    
    // Скорости (расширены лимиты)
    private final Setting<Double> snapSpeed = sgAim.add(new DoubleSetting.Builder()
            .name("snap-speed").description("Speed of turning to target. 180 = instant.").defaultValue(180.0).min(1.0).sliderMax(180.0).build()
    );
    private final Setting<Double> holdTime = sgAim.add(new DoubleSetting.Builder()
            .name("hold-time").description("Time (ms) to hold camera on target.").defaultValue(0.0).min(0.0).sliderMax(500.0).build()
    );
    private final Setting<Double> backstabReturnSpeed = sgAim.add(new DoubleSetting.Builder()
            .name("return-speed").description("Speed of turning back. 180 = instant.").defaultValue(180.0).min(1.0).sliderMax(180.0).build()
    );

    // --- Backstab & Pounce (ФанТайм механика) ---
    private final Setting<Double> attackRangeSync = sgBackstab.add(new DoubleSetting.Builder()
            .name("attack-range-sync").description("Distance where S is pressed instead of W.").defaultValue(3.0).min(1.0).sliderMax(5.0).build()
    );
    private final Setting<Double> pounceRange = sgBackstab.add(new DoubleSetting.Builder()
            .name("pounce-range").description("Max distance to trigger a jump-pounce.").defaultValue(6.0).min(3.0).sliderMax(8.0).build()
    );
    private final Setting<Integer> pounceCooldown = sgBackstab.add(new IntSetting.Builder()
            .name("pounce-cooldown").description("Cooldown between jumps (ms).").defaultValue(300).min(100).sliderMax(1000).build()
    );

    private final Random random = new Random();
    
    // State variables
    private LivingEntity lockedTarget = null;
    private long lockedTargetTime = 0;
    private float originalYaw = 0;
    private float originalPitch = 0;
    private float spinYaw = 0;
    private float spinPitch = 0;

    // Pounce State Machine
    private PounceState pounceState = PounceState.IDLE;
    private long pounceStartTime = 0;
    private boolean pounceJumped = false;
    private long lastPounceTime = 0;
    private boolean keysManaged = false;

    public ZAimbot() {
        super(Meteorist.CATEGORY, "z-aimbot", "Backstab aimbot with FunTime pounce bypass.");
    }

    @Override
    public void onActivate() {
        lockedTarget = null;
        pounceState = PounceState.IDLE;
        releaseManagedKeys();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        // Запоминаем оригинальный поворот мыши для FakeLook
        if (cameraMode.get() == CameraMode.Normal || cameraMode.get() == CameraMode.BackstabAlways) {
            originalYaw = mc.player.getYRot();
            originalPitch = mc.player.getXRot();
        }

        // Обновление таргета
        updateTarget();

        if (lockedTarget == null) {
            if (pounceState != PounceState.IDLE) {
                pounceState = PounceState.IDLE;
                releaseManagedKeys();
            }
            return;
        }

        if (cameraMode.get() == CameraMode.BackstabAlways) {
            handleBackstabAlways();
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.level == null || lockedTarget == null) return;
        if (cameraMode.get() == CameraMode.BackstabAlways) return; // Обрабатывается в onTick

        if (calculateFov(mc.player, lockedTarget) > fov.get()) return;

        // Другие режимы камеры (не менялись)
        if (cameraMode.get() == CameraMode.Backstab) {
            faceAway(lockedTarget);
        } else {
            faceTarget(lockedTarget);
        }
    }

    private void handleBackstabAlways() {
        double dist = mc.player.distanceTo(lockedTarget);
        long now = System.currentTimeMillis();

        // 1. Логика переходов состояний
        switch (pounceState) {
            case IDLE:
                pounceState = dist <= attackRangeSync.get() ? PounceState.BACKING_UP : PounceState.APPROACHING;
                break;
            case BACKING_UP:
                if (dist > attackRangeSync.get()) pounceState = PounceState.APPROACHING;
                break;
            case APPROACHING:
                if (dist <= attackRangeSync.get()) {
                    pounceState = PounceState.BACKING_UP;
                } else if (mc.player.onGround() && dist <= pounceRange.get() && (now - lastPounceTime) > pounceCooldown.get()) {
                    pounceState = PounceState.POUNCING;
                    pounceStartTime = now;
                    pounceJumped = false;
                }
                break;
            case POUNCING:
                if (pounceJumped && (now - pounceStartTime >= holdTime.get().longValue())) {
                    mc.options.keyJump.setDown(false); // отпускаем прыжок
                    pounceState = mc.player.onGround() ? PounceState.APPROACHING : PounceState.AIRBORNE;
                    lastPounceTime = now;
                }
                break;
            case AIRBORNE:
                if (mc.player.onGround()) {
                    pounceState = dist <= attackRangeSync.get() ? PounceState.BACKING_UP : PounceState.APPROACHING;
                }
                break;
        }

        // 2. Применение клавиш и поворота камеры
        switch (pounceState) {
            case BACKING_UP:
            case APPROACHING:
            case AIRBORNE:
                // Спиной к игроку, зажимаем S (двигаемся на игрока т.к. спина)
                faceAway(lockedTarget);
                setKeys(false, true, false, false); // W=false, S=true, Sprint=false, Jump=false
                break;
            case POUNCING:
                if (!pounceJumped) {
                    // Мгновенный снап на игрока, зажимаем W+Ctrl+Пробел
                    spinYaw = mc.player.getYRot();
                    spinPitch = mc.player.getXRot();
                    faceTargetInstant(lockedTarget);
                    setKeys(true, false, true, true); // W=true, S=false, Sprint=true, Jump=true
                    pounceJumped = true;
                } else {
                    // Удерживаем лицом к игроку (пока летим)
                    faceTarget(lockedTarget);
                    setKeys(true, false, true, false); // Прыжок отпускаем, чтобы не спамить
                }
                break;
        }
    }

    private void updateTarget() {
        long currentTime = System.currentTimeMillis();
        if (lockedTarget != null) {
            if (!lockedTarget.isAlive() || PlayerUtils.distanceTo(lockedTarget) > pounceRange.get() + 2 || (currentTime - lockedTargetTime > 5000)) {
                lockedTarget = null;
            }
        }

        if (lockedTarget == null) {
            LivingEntity closest = null;
            double closestDist = Double.MAX_VALUE;
            for (Entity e : mc.level.getEntities().getAll()) {
                if (entityCheck(e)) {
                    double d = mc.player.distanceTo(e);
                    if (d < closestDist) {
                        closest = (LivingEntity) e;
                        closestDist = d;
                    }
                }
            }
            if (closest != null) {
                lockedTarget = closest;
                lockedTargetTime = currentTime;
            }
        }
    }

    // ==========================================
    // ЛОГИКА ТОЧЕК ПРИЦЕЛИВАНИЯ (CENTER, RANDOM, NEAREST)
    // ==========================================
    private Vec3 getAimPoint(Entity target) {
        AABB box = target.getBoundingBox();
        switch (aimPointMode.get()) {
            case Random:
                double x = box.minX + random.nextDouble() * (box.maxX - box.minX);
                double y = box.minY + random.nextDouble() * (box.maxY - box.minY);
                double z = box.minZ + random.nextDouble() * (box.maxZ - box.minZ);
                return new Vec3(x, y, z);
            
            case NearestRay:
                Vec3 eye = mc.player.getEyePosition();
                Vec3 look = mc.player.getLookAngle().normalize();
                // Пересечение луча и хитбокса
                Vec3 intersect = rayAABB(eye, look, box);
                if (intersect != null) return intersect;
                // Если луч не попадает, ищем ближайшую точку к лучу
                Vec3 best = null;
                double bestDist = Double.MAX_VALUE;
                for (int i = 0; i < 30; i++) {
                    Vec3 p = new Vec3(
                        box.minX + random.nextDouble() * (box.maxX - box.minX),
                        box.minY + random.nextDouble() * (box.maxY - box.minY),
                        box.minZ + random.nextDouble() * (box.maxZ - box.minZ)
                    );
                    Vec3 toP = p.subtract(eye);
                    double proj = toP.dot(look);
                    if (proj < 0) continue;
                    Vec3 projP = eye.add(look.scale(proj));
                    double d = p.distanceToSqr(projP);
                    if (d < bestDist) { bestDist = d; best = p; }
                }
                return best != null ? best : box.getCenter();
            
            case Center:
            default:
                // Стандартный центр хитбокса (грудь/голова)
                return new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.75, target.getZ());
        }
    }

    private Vec3 rayAABB(Vec3 origin, Vec3 dir, AABB box) {
        double tMin = 0.0D;
        double tMax = Double.MAX_VALUE;

        double[] min = {box.minX, box.minY, box.minZ};
        double[] max = {box.maxX, box.maxY, box.maxZ};
        double[] o = {origin.x, origin.y, origin.z};
        double[] d = {dir.x, dir.y, dir.z};

        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1.0E-7D) {
                if (o[i] < min[i] || o[i] > max[i]) return null;
            } else {
                double t1 = (min[i] - o[i]) / d[i];
                double t2 = (max[i] - o[i]) / d[i];
                if (t1 > t2) { double temp = t1; t1 = t2; t2 = temp; }
                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);
                if (tMin > tMax) return null;
            }
        }
        if (tMin < 0) tMin = tMax;
        if (tMin < 0) return null;
        return origin.add(dir.scale(tMin));
    }

    // ==========================================
    // ПОВОРОТЫ КАМЕРЫ
    // ==========================================
    private void faceTarget(Entity target) {
        Vec3 aimPoint = getAimPoint(target);
        float targetYaw = (float) Rotations.getYaw(aimPoint);
        float targetPitch = (float) Rotations.getPitch(aimPoint);

        spinYaw = approachAngle(spinYaw, targetYaw, snapSpeed.get().floatValue());
        spinPitch = approachAngle(spinPitch, targetPitch, snapSpeed.get().floatValue());

        mc.player.setYRot(spinYaw);
        mc.player.setXRot(spinPitch);
    }

    private void faceTargetInstant(Entity target) {
        Vec3 aimPoint = getAimPoint(target);
        spinYaw = (float) Rotations.getYaw(aimPoint);
        spinPitch = (float) Rotations.getPitch(aimPoint);
        mc.player.setYRot(spinYaw);
        mc.player.setXRot(spinPitch);
    }

    private void faceAway(Entity target) {
        Vec3 aimPoint = target.position();
        float targetYaw = (float) Rotations.getYaw(aimPoint) + 180.0f;
        
        // Плавно отворачиваемся
        spinYaw = approachAngle(spinYaw, targetYaw, backstabReturnSpeed.get().floatValue());
        spinPitch = approachAngle(spinPitch, 0.0f, backstabReturnSpeed.get().floatValue());

        mc.player.setYRot(spinYaw);
        mc.player.setXRot(spinPitch);
    }

    private float approachAngle(float current, float target, float speed) {
        float diff = Mth.wrapDegrees(target - current);
        if (Math.abs(diff) <= speed) return target;
        return current + Math.signum(diff) * speed;
    }

    // ==========================================
    // УПРАВЛЕНИЕ КЛАВИШАМИ (W, S, SPRINT, JUMP)
    // ==========================================
    private void setKeys(boolean w, boolean s, boolean sprint, boolean jump) {
        mc.options.keyUp.setDown(w);
        mc.options.keyDown.setDown(s);
        mc.options.keySprint.setDown(sprint);
        mc.options.keyJump.setDown(jump);
        keysManaged = true;
    }

    private void releaseManagedKeys() {
        if (keysManaged) {
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(false);
            mc.options.keySprint.setDown(false);
            mc.options.keyJump.setDown(false);
            keysManaged = false;
        }
    }

    private boolean entityCheck(Entity entity) {
        if (entity.equals(mc.player) || entity.equals(mc.getCameraEntity())) return false;
        if (!entity.isAlive()) return false;
        if (!PlayerUtils.isWithin(entity, pounceRange.get() + 2)) return false;
        if (!entities.get().contains(entity.getType())) return false;
        if (ignoreFriends.get() && entity instanceof Player player && !Friends.get().shouldAttack(player)) return false;
        return ignoreWalls.get() || PlayerUtils.canSeeEntity(entity);
    }
}
