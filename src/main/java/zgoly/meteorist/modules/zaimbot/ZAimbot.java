package zgoly.meteorist.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import zgoly.meteorist.Meteorist;

import java.util.Random;
import java.util.Set;

import static zgoly.meteorist.utils.MeteoristUtils.calculateFov;

public class ZAimbot extends Module {
    public enum AimMode { Random, ClosestPoint, EdgeOnly }
    private enum PounceState { IDLE, BACKING_UP, APPROACHING, POUNCING, AIRBORNE }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAim = settings.createGroup("Aim");
    private final SettingGroup sgSnap = settings.createGroup("Snap Rotation"); // Новая вкладка

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

    // --- Snap Rotation (Новый модуль/вкладка) ---
    private final Setting<Boolean> snapBackstab = sgSnap.add(new BoolSetting.Builder()
            .name("snap-backstab").description("Смотреть спиной и снапать на удар.").defaultValue(false).build()
    );
    private final Setting<Double> snapSpeed = sgSnap.add(new DoubleSetting.Builder()
            .name("snap-speed").description("Скорость снапа на игрока. 180 = мгновенно.").defaultValue(180.0).min(1.0).sliderMax(180.0).visible(snapBackstab::get).build()
    );
    private final Setting<Double> holdTime = sgSnap.add(new DoubleSetting.Builder()
            .name("hold-time").description("Задержка на игроке (мс).").defaultValue(0.0).min(0.0).sliderMax(500.0).visible(snapBackstab::get).build()
    );
    private final Setting<Double> returnSpeed = sgSnap.add(new DoubleSetting.Builder()
            .name("return-speed").description("Скорость отворота спиной. 180 = мгновенно.").defaultValue(180.0).min(1.0).sliderMax(180.0).visible(snapBackstab::get).build()
    );
    private final Setting<Double> attackRangeSync = sgSnap.add(new DoubleSetting.Builder()
            .name("attack-range").description("Дистанция, при которой зажимается S.").defaultValue(3.0).min(1.0).sliderMax(5.0).visible(snapBackstab::get).build()
    );
    private final Setting<Double> pounceRange = sgSnap.add(new DoubleSetting.Builder()
            .name("pounce-range").description("Макс. дистанция для прыжка.").defaultValue(6.0).min(3.0).sliderMax(8.0).visible(snapBackstab::get).build()
    );

    private final Random random = new Random();
    private Vec3 currentAimPoint = null;
    public LivingEntity lockedTarget = null;
    private long lockedTargetTime = 0;

    // Переменные для Snap механики
    private PounceState pounceState = PounceState.IDLE;
    private long pounceStartTime = 0;
    private boolean pounceJumped = false;
    private long lastPounceTime = 0;
    private boolean keysManaged = false;

    public ZAimbot() {
        super(Meteorist.CATEGORY, "z-aimbot", "Aimbot with separate X/Y speeds and snap bypass.");
    }

    @Override
    public void onActivate() {
        currentAimPoint = null;
        lockedTarget = null;
        pounceState = PounceState.IDLE;
        releaseManagedKeys();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (onlySword.get() && !mc.player.getMainHandItem().is(ItemTags.SWORDS)) return;

        long currentTime = System.currentTimeMillis();

        if (lockedTarget != null) {
            if (!lockedTarget.isAlive()
                    || PlayerUtils.distanceTo(lockedTarget) > aggroRange.get()
                    || (currentTime - lockedTargetTime > targetLockTime.get() * 1000)) {
                lockedTarget = null;
                currentAimPoint = null;
            }
        }

        if (lockedTarget == null) {
            LivingEntity closest = null;
            double closestDist = Double.MAX_VALUE;
            for (Entity e : mc.level.getEntities().getAll()) {
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

        if (lockedTarget == null) {
            if (snapBackstab.get() && pounceState != PounceState.IDLE) {
                pounceState = PounceState.IDLE;
                releaseManagedKeys();
            }
            return;
        }

        if (snapBackstab.get()) {
            handleSnapLogic();
        } else {
            if (!PlayerUtils.isWithin(lockedTarget, range.get())) return;
            if (calculateFov(mc.player, lockedTarget) > fov.get()) return;
            aim(mc.player, lockedTarget, false);
        }
    }

    private void handleSnapLogic() {
        double dist = mc.player.distanceTo(lockedTarget);
        long now = System.currentTimeMillis();

        if (dist > pounceRange.get()) {
            if (pounceState != PounceState.IDLE) {
                pounceState = PounceState.IDLE;
                releaseManagedKeys();
            }
            return;
        }

        // Стейт-машина (без тиков, на миллисекундах)
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
                } else if (mc.player.onGround() && dist <= pounceRange.get() && (now - lastPounceTime) > 300) {
                    pounceState = PounceState.POUNCING;
                    pounceStartTime = now;
                    pounceJumped = false;
                }
                break;
            case POUNCING:
                if (pounceJumped && (now - pounceStartTime >= holdTime.get().longValue())) {
                    mc.options.keyJump.setDown(false);
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

        // Применение клавиш
        switch (pounceState) {
            case BACKING_UP:
            case APPROACHING:
            case AIRBORNE:
                faceAway(lockedTarget);
                setKeys(false, true, false, false); // Отпускаем W, зажимаем S
                break;
            case POUNCING:
                if (!pounceJumped) {
                    aim(mc.player, lockedTarget, true); // Снап на игрока
                    setKeys(true, false, true, true); // W + Ctrl + Пробел
                    pounceJumped = true;
                } else {
                    aim(mc.player, lockedTarget, true); // Держим на игроке
                    setKeys(true, false, true, false); // W + Ctrl
                }
                break;
        }
    }

    private void faceAway(Entity target) {
        Vec3 aimPoint = target.position();
        float targetYaw = (float) Rotations.getYaw(aimPoint) + 180.0f;
        
        float diff = Mth.wrapDegrees(targetYaw - mc.player.getYRot());
        float step = Math.min(Math.abs(diff), returnSpeed.get().floatValue());
        mc.player.setYRot(mc.player.getYRot() + Math.signum(diff) * step);
        
        float pitchDiff = 0 - mc.player.getXRot();
        float pitchStep = Math.min(Math.abs(pitchDiff), returnSpeed.get().floatValue());
        mc.player.setXRot(mc.player.getXRot() + Math.signum(pitchDiff) * pitchStep);
    }

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

    // Твоя оригинальная структура аима, добавлен только параметр useSnapSpeed
    private void aim(LivingEntity player, Entity target, boolean useSnapSpeed) {
        AABB box = target.getBoundingBox();
        Vec3 eyePos = player.getEyePosition();

        if (aimMode.get() == AimMode.EdgeOnly) {
            HitResult hit = mc.hitResult;
            if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
                if (((EntityHitResult) hit).getEntity() == target) return;
            }
            double x = Mth.clamp(eyePos.x, box.minX, box.maxX);
            double y = Mth.clamp(eyePos.y, box.minY, box.maxY);
            double z = Mth.clamp(eyePos.z, box.minZ, box.maxZ);
            if (randomizeTarget.get()) {
                x += (random.nextDouble() - 0.5) * 0.1;
                y += (random.nextDouble() - 0.5) * 0.1;
                z += (random.nextDouble() - 0.5) * 0.1;
            }
            currentAimPoint = new Vec3(x, y, z);
        } else if (aimMode.get() == AimMode.ClosestPoint) {
            // Ближайший луч
            double x = Mth.clamp(eyePos.x, box.minX, box.maxX);
            double y = Mth.clamp(eyePos.y, box.minY, box.maxY);
            double z = Mth.clamp(eyePos.z, box.minZ, box.maxZ);
            if (randomizeTarget.get()) {
                x += (random.nextDouble() - 0.5) * 0.2;
                y += (random.nextDouble() - 0.5) * 0.2;
                z += (random.nextDouble() - 0.5) * 0.2;
            }
            currentAimPoint = new Vec3(x, y, z);
        } else {
            // Рандом по всему телу
            if (currentAimPoint == null || random.nextInt(10) < 2) {
                double yBias = (random.nextDouble() + random.nextDouble() - 1.0) * 0.3 + 0.5; 
                double y = target.getY() + target.getBbHeight() * yBias;
                double x = target.getX() + (random.nextDouble() - 0.5) * 0.3;
                double z = target.getZ() + (random.nextDouble() - 0.5) * 0.3;
                currentAimPoint = new Vec3(x, y, z);
            }
        }

        if (currentAimPoint == null) return;

        float targetYaw = (float) Rotations.getYaw(currentAimPoint);
        float targetPitch = (float) Rotations.getPitch(currentAimPoint);

        float yawDifference = Mth.wrapDegrees(targetYaw - player.getYRot());
        float pitchDifference = Mth.wrapDegrees(targetPitch - player.getXRot());

        float factorX = useSnapSpeed ? (snapSpeed.get().floatValue() / 180.0f) : (speedX.get().floatValue() / 10.0f);
        float factorY = useSnapSpeed ? (snapSpeed.get().floatValue() / 180.0f) : (speedY.get().floatValue() / 10.0f);

        float deltaYaw = yawDifference * factorX;
        float deltaPitch = pitchDifference * factorY;

        float sens = mc.options.getMouseSensitivity().getValue().floatValue();
        float f = sens * 0.6f + 0.2f;
        float gcd = f * f * f * 1.2f; 
        
        deltaYaw = Math.round(deltaYaw / gcd) * gcd;
        deltaPitch = Math.round(deltaPitch / gcd) * gcd;

        player.setYRot(player.getYRot() + deltaYaw);
        player.setXRot(Mth.clamp(player.getXRot() + deltaPitch, -90f, 90f));
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
