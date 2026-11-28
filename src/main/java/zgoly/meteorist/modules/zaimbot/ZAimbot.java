package zgoly.meteorist.modules.zaimbot;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.EntityAgeTest;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import zgoly.meteorist.Meteorist;
import zgoly.meteorist.modules.ZAutoSprint;
import zgoly.meteorist.utils.config.MeteoristConfigManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static meteordevelopment.meteorclient.gui.renderer.GuiRenderer.COPY;
import static zgoly.meteorist.utils.MeteoristUtils.calculateFov;

public class ZAimbot extends Module {
    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgAim = settings.createGroup("Aim");
    private final SettingGroup sgSnap = settings.createGroup("Snap Backstab");
    private final SettingGroup sgHvH = settings.createGroup("HvH 360");

    // --- Filter ---
    private final Setting<Set<EntityType<?>>> entities = sgFilter.add(new EntityTypeListSetting.Builder()
            .name("entities").description("Entities to aim at.").onlyAttackable().defaultValue(EntityTypes.PLAYER).build()
    );
    private final Setting<Double> range = sgFilter.add(new DoubleSetting.Builder()
            .name("range").description("The maximum range the entity can be to aim at it.").defaultValue(12.0).min(0).sliderMax(12.0).build()
    );
    private final Setting<EntityAgeTest> passiveMobAgeFilter = sgFilter.add(new EnumSetting.Builder<EntityAgeTest>().name("passive-mob-age-filter").defaultValue(EntityAgeTest.Adult).build());
    private final Setting<EntityAgeTest> hostileMobAgeFilter = sgFilter.add(new EnumSetting.Builder<EntityAgeTest>().name("hostile-mob-age-filter").defaultValue(EntityAgeTest.Both).build());
    private final Setting<Boolean> ignoreNamed = sgFilter.add(new BoolSetting.Builder().name("ignore-named").defaultValue(false).build());
    private final Setting<Boolean> ignorePassive = sgFilter.add(new BoolSetting.Builder().name("ignore-passive").defaultValue(true).build());
    private final Setting<Boolean> ignoreTamed = sgFilter.add(new BoolSetting.Builder().name("ignore-tamed").defaultValue(false).build());
    private final Setting<Boolean> ignoreCreative = sgFilter.add(new BoolSetting.Builder().name("ignore-creative").defaultValue(true).build());
    private final Setting<Boolean> ignoreFriends = sgFilter.add(new BoolSetting.Builder().name("ignore-friends").defaultValue(true).build());
    private final Setting<Boolean> ignoreShield = sgFilter.add(new BoolSetting.Builder().name("ignore-shield").defaultValue(false).build());
    private final Setting<Boolean> useFovRange = sgFilter.add(new BoolSetting.Builder().name("use-fov-range").defaultValue(true).build());
    private final Setting<Double> fovRange = sgFilter.add(new DoubleSetting.Builder().name("fov-range").sliderRange(0, 180).defaultValue(90).visible(useFovRange::get).build());
    private final Setting<Boolean> ignoreWalls = sgFilter.add(new BoolSetting.Builder().name("ignore-walls").defaultValue(false).build());
    private final Setting<SortPriority> priority = sgFilter.add(new EnumSetting.Builder<SortPriority>().name("priority").defaultValue(SortPriority.ClosestAngle).build());
    private final Setting<OperationMode> itemFilteringMode = sgFilter.add(new EnumSetting.Builder<OperationMode>().name("item-filtering-mode").defaultValue(OperationMode.Blacklist).build());
    private final Setting<List<Item>> itemBlacklist = sgFilter.add(new ItemListSetting.Builder().name("item-blacklist").defaultValue(Items.COBWEB).visible(() -> itemFilteringMode.get() == OperationMode.Blacklist).build());
    private final Setting<List<Item>> itemWhitelist = sgFilter.add(new ItemListSetting.Builder().name("item-whitelist").defaultValue(Items.BOW, Items.CROSSBOW, Items.TRIDENT).visible(() -> itemFilteringMode.get() == OperationMode.Whitelist).build());

    // --- Aim ---
    public final Setting<Double> targetMovementPrediction = sgAim.add(new DoubleSetting.Builder().name("target-movement-prediction").min(0).sliderMax(20).defaultValue(0).build());
    private final Setting<AimAxes> aimAxes = sgAim.add(new EnumSetting.Builder<AimAxes>().name("aim-axes").defaultValue(AimAxes.Both).build());
    private final Setting<Target> bodyTarget = sgAim.add(new EnumSetting.Builder<Target>().name("aim-target").defaultValue(Target.Head).build());
    private final Setting<Boolean> instantYaw = sgAim.add(new BoolSetting.Builder().name("instant-yaw").defaultValue(false).build());
    private final Setting<Boolean> instantPitch = sgAim.add(new BoolSetting.Builder().name("instant-pitch").defaultValue(false).build());
    private final Setting<Boolean> syncYawWithCooldown = sgAim.add(new BoolSetting.Builder().name("sync-yaw-with-cooldown").defaultValue(false).visible(() -> !instantYaw.get()).build());
    private final Setting<Boolean> syncPitchWithCooldown = sgAim.add(new BoolSetting.Builder().name("sync-pitch-with-cooldown").defaultValue(false).visible(() -> !instantPitch.get()).build());
    private final Setting<Double> speedYaw = sgAim.add(new DoubleSetting.Builder().name("speed-yaw").min(0.1).defaultValue(10.0).sliderMax(180.0).visible(() -> !instantYaw.get()).build());
    private final Setting<Double> speedPitch = sgAim.add(new DoubleSetting.Builder().name("speed-pitch").min(0.1).defaultValue(10.0).sliderMax(180.0).visible(() -> !instantPitch.get()).build());

    public enum CameraMode { SnapBackstab, HvH360, Mix }
    private final Setting<CameraMode> cameraMode = sgSnap.add(new EnumSetting.Builder<CameraMode>()
            .name("camera-mode")
            .description("Режим работы: Snap, HvH 360 или Mix.")
            .defaultValue(CameraMode.SnapBackstab)
            .build()
    );

    public enum AimPointMode { Center, RandomBody, NearestRay }
    private final Setting<AimPointMode> aimPointMode = sgSnap.add(new EnumSetting.Builder<AimPointMode>()
            .name("aim-point-mode")
            .description("Куда целиться: Center, RandomBody, NearestRay.")
            .defaultValue(AimPointMode.RandomBody)
            .build()
    );

    private final Setting<Boolean> autoJump = sgSnap.add(new BoolSetting.Builder()
            .name("auto-jump").description("Автоматически прыгать для критов.").defaultValue(false).build()
    );
    private final Setting<Double> snapSpeed = sgSnap.add(new DoubleSetting.Builder().name("snap-speed").description("Скорость снапа. 180 = мгновенно.").defaultValue(180.0).min(0.1).sliderMax(180.0).build());
    private final Setting<Double> returnSpeed = sgSnap.add(new DoubleSetting.Builder().name("return-speed").description("Скорость отворота спиной.").defaultValue(180.0).min(0.1).sliderMax(180.0).build());
    private final Setting<Double> pounceRange = sgSnap.add(new DoubleSetting.Builder().name("pounce-range").description("Радиус взаимодействия.").defaultValue(12.0).min(3.0).sliderMax(12.0).build());

    // --- HvH 360 Settings ---
    private final Setting<Double> hvhSpinSpeed = sgHvH.add(new DoubleSetting.Builder().name("spin-speed").description("Скорость вращения 360.").defaultValue(180.0).min(10.0).sliderMax(360.0).build());
    private final Setting<Double> hvhLookRange = sgHvH.add(new DoubleSetting.Builder().name("look-range").description("Дистанция смотреть прямо на игрока.").defaultValue(3.0).min(1.0).sliderMax(5.0).build());

    private final List<AimTrajectory> trajectories = new ArrayList<>();
    
    private LivingEntity stickyTarget = null;
    private CameraMode currentMixMode = CameraMode.SnapBackstab;

    public ZAimbot() {
        super(Meteorist.CATEGORY, "z-aimbot", "Smart aimbot with snap, HvH360 and Mix.");
    }

    @Override
    public CompoundTag toTag() {
        CompoundTag tag = super.toTag();
        ListTag list = new ListTag();
        for (AimTrajectory trajectory : trajectories) {
            CompoundTag tTag = new CompoundTag();
            tTag.put("trajectory", trajectory.toTag());
            list.add(tTag);
        }
        tag.put("trajectories", list);
        return tag;
    }

    @Override
    public Module fromTag(CompoundTag tag) {
        super.fromTag(tag);
        trajectories.clear();
        ListTag list = tag.getListOrEmpty("trajectories");
        for (Tag tagII : list) {
            CompoundTag tagI = (CompoundTag) tagII;
            AimTrajectory trajectory = new AimTrajectory();
            CompoundTag trajectoryTag = (CompoundTag) tagI.get("trajectory");
            if (trajectoryTag != null) trajectory.fromTag(trajectoryTag);
            trajectories.add(trajectory);
        }
        return this;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        fillWidget(theme, list);
        return list;
    }

    private void fillWidget(GuiTheme theme, WVerticalList list) {
        list.clear();
        for (AimTrajectory trajectory : trajectories) {
            list.add(theme.settings(trajectory.settings)).expandX();
            WContainer controls = list.add(theme.horizontalList()).widget();
            if (trajectories.size() > 1) {
                WContainer moveContainer = controls.add(theme.horizontalList()).expandX().widget();
                int index = trajectories.indexOf(trajectory);
                if (index > 0) {
                    WButton moveUp = moveContainer.add(theme.button(Meteorist.ARROW_UP)).expandX().widget();
                    moveUp.action = () -> { trajectories.remove(index); trajectories.add(index - 1, trajectory); fillWidget(theme, list); };
                }
                if (index < trajectories.size() - 1) {
                    WButton moveDown = moveContainer.add(theme.button(Meteorist.ARROW_DOWN)).expandX().widget();
                    moveDown.action = () -> { trajectories.remove(index); trajectories.add(index + 1, trajectory); fillWidget(theme, list); };
                }
            }
            WButton copy = controls.add(theme.button(COPY)).widget();
            copy.action = () -> { trajectories.add(trajectories.indexOf(trajectory), trajectory.copy()); fillWidget(theme, list); };
            WMinus remove = controls.add(theme.minus()).widget();
            remove.action = () -> { trajectories.remove(trajectory); fillWidget(theme, list); };
        }
        if (!trajectories.isEmpty()) list.add(theme.horizontalSeparator()).expandX();
        WContainer controls = list.add(theme.horizontalList()).expandX().widget();
        WButton add = controls.add(theme.button("Add Trajectory")).expandX().widget();
        add.action = () -> { trajectories.add(new AimTrajectory()); fillWidget(theme, list); };
        WButton removeAll = controls.add(theme.button("Remove All Trajectories")).expandX().widget();
        removeAll.action = () -> { trajectories.clear(); fillWidget(theme, list); };
        MeteoristConfigManager.configManager(theme, list, this);
    }

    @Override
    public void onActivate() {
        stickyTarget = null;
        currentMixMode = CameraMode.SnapBackstab;
        releaseManagedKeys();
    }

    @Override
    public void onDeactivate() {
        // Жесткий сброс всех клавиш
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
        stickyTarget = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        if (ZAutoSprint.wTapActive) return; // Не трогаем клавиши во время W-Tap

        if (stickyTarget == null || !stickyTarget.isAlive() || mc.player.distanceTo(stickyTarget) > pounceRange.get()) {
            Entity found = TargetUtils.get(this::entityCheck, priority.get());
            stickyTarget = (found instanceof LivingEntity living) ? living : null;
        }

        if (stickyTarget == null) {
            releaseManagedKeys();
            return;
        }

        CameraMode mode = cameraMode.get();
        if (mode == CameraMode.Mix) mode = currentMixMode;

        if (mode == CameraMode.SnapBackstab) {
            handleSnapBackstab(stickyTarget);
        } else {
            handleHvH360(stickyTarget);
        }
    }

    // Вызывается из Killaura при ударе для переключения режима Mix
    public void onAttack() {
        if (cameraMode.get() == CameraMode.Mix) {
            currentMixMode = (currentMixMode == CameraMode.SnapBackstab) ? CameraMode.HvH360 : CameraMode.SnapBackstab;
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (cameraMode.get() == CameraMode.SnapBackstab || cameraMode.get() == CameraMode.HvH360 || cameraMode.get() == CameraMode.Mix) return;

        Entity target = TargetUtils.get(this::entityCheck, priority.get());
        Item heldItem = mc.player.getMainHandItem().getItem();
        if (itemFilteringMode.get() == OperationMode.Blacklist ? itemBlacklist.get().contains(heldItem) : !itemWhitelist.get().contains(heldItem)) return;
        if (target == null) return;
        aim(mc.player, target);
    }

    private void handleSnapBackstab(LivingEntity target) {
        boolean isJumping = mc.options.keyJump.isDown();
        boolean isFalling = mc.player.fallDistance > 0.0F && !mc.player.onGround() && !mc.player.isInWater() && !mc.player.onClimbable();

        if (autoJump.get() && mc.player.onGround()) {
            mc.options.keyJump.setDown(true);
            isJumping = true;
        }

        if (isJumping || isFalling) {
            faceTarget(target, snapSpeed.get().floatValue());
            setKeys(true, false, true, null);
        } else {
            faceAway(target);
            setKeys(false, true, false, null);
        }
    }

    private void handleHvH360(LivingEntity target) {
        double dist = mc.player.distanceTo(target);
        boolean isJumping = mc.options.keyJump.isDown();
        boolean isFalling = mc.player.fallDistance > 0.0F && !mc.player.onGround() && !mc.player.isInWater() && !mc.player.onClimbable();

        if (autoJump.get() && mc.player.onGround()) {
            mc.options.keyJump.setDown(true);
            isJumping = true;
        }

        if (isJumping || isFalling) {
            faceTarget(target, snapSpeed.get().floatValue());
        } else {
            float spin = hvhSpinSpeed.get().floatValue();
            mc.player.setYRot(mc.player.getYRot() + spin);
            mc.player.setXRot(mc.player.getXRot() + (float) ((Math.random() - 0.5) * 20));
        }

        if (dist > hvhLookRange.get() || isJumping || isFalling) {
            setKeys(true, false, true, null);
        } else {
            float targetYaw = (float) Rotations.getYaw(target.position());
            float yawDiff = Mth.wrapDegrees(targetYaw - mc.player.getYRot());

            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            mc.options.keySprint.setDown(false);

            if (yawDiff >= -45 && yawDiff <= 45) setKeys(true, false, true, null);
            else if (yawDiff > 45 && yawDiff <= 135) mc.options.keyRight.setDown(true);
            else if (yawDiff < -45 && yawDiff >= -135) mc.options.keyLeft.setDown(true);
            else mc.options.keyDown.setDown(true);
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

    private void faceTarget(Entity target, float speed) {
        Vec3 aimPoint = getAimPoint(target);
        float targetYaw = (float) Rotations.getYaw(aimPoint);
        float targetPitch = (float) Rotations.getPitch(aimPoint);

        if (speed >= 180.0f) {
            mc.player.setYRot(targetYaw);
            mc.player.setXRot(targetPitch);
        } else {
            float diffYaw = Mth.wrapDegrees(targetYaw - mc.player.getYRot());
            float diffPitch = Mth.wrapDegrees(targetPitch - mc.player.getXRot());

            float stepYaw = Math.min(Math.abs(diffYaw), speed);
            float stepPitch = Math.min(Math.abs(diffPitch), speed);

            mc.player.setYRot(mc.player.getYRot() + Math.signum(diffYaw) * stepYaw);
            mc.player.setXRot(mc.player.getXRot() + Math.signum(diffPitch) * stepPitch);
        }
    }

    private Vec3 getAimPoint(Entity target) {
        AABB box = target.getBoundingBox();
        if (aimPointMode.get() == AimPointMode.RandomBody) {
            return new Vec3(box.minX + Math.random() * (box.maxX - box.minX),
                            box.minY + Math.random() * (box.maxY - box.minY),
                            box.minZ + Math.random() * (box.maxZ - box.minZ));
        } else if (aimPointMode.get() == AimPointMode.NearestRay) {
            Vec3 hit = rayAABB(mc.player.getEyePosition(), mc.player.getViewVector(1.0f), box);
            if (hit != null) return hit.add((Math.random() - 0.5) * 0.15, (Math.random() - 0.5) * 0.15, (Math.random() - 0.5) * 0.15);
            return new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.75, target.getZ());
        }
        return new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.75, target.getZ());
    }

    private Vec3 rayAABB(Vec3 origin, Vec3 dir, AABB box) {
        double tMin = 0.0D, tMax = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            double originC = i == 0 ? origin.x : (i == 1 ? origin.y : origin.z);
            double dirC = i == 0 ? dir.x : (i == 1 ? dir.y : dir.z);
            double minC = i == 0 ? box.minX : (i == 1 ? box.minY : box.minZ);
            double maxC = i == 0 ? box.maxX : (i == 1 ? box.maxY : box.maxZ);
            if (Math.abs(dirC) < 1.0E-7D) {
                if (originC < minC || originC > maxC) return null;
            } else {
                double t1 = (minC - originC) / dirC;
                double t2 = (maxC - originC) / dirC;
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

    private void setKeys(boolean w, boolean s, boolean sprint, Boolean jump) {
        mc.options.keyUp.setDown(w);
        mc.options.keyDown.setDown(s);
        mc.options.keySprint.setDown(sprint); // ИСПРАВЛЕНО
        if (jump != null) mc.options.keyJump.setDown(jump);
    }

    private void releaseManagedKeys() {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    private void aim(LivingEntity player, Entity target) {
        Vec3 predictedPos = target.position().add(target.getDeltaMovement().scale(targetMovementPrediction.get()));
        float yaw = (float) Rotations.getYaw(predictedPos);
        float pitch = (float) Rotations.getPitch(target, bodyTarget.get());

        float yawDifference = Mth.wrapDegrees(yaw - player.getYRot());
        float pitchDifference = Mth.wrapDegrees(pitch - player.getXRot());

        if (aimAxes.get() == AimAxes.Both || aimAxes.get() == AimAxes.OnlyYaw) {
            float factor = (speedYaw.get().floatValue() / 180.0f);
            player.setYRot(player.getYRot() + yawDifference * factor);
        }
        if (aimAxes.get() == AimAxes.Both || aimAxes.get() == AimAxes.OnlyPitch) {
            float factor = (speedPitch.get().floatValue() / 180.0f);
            player.setXRot(player.getXRot() + pitchDifference * factor);
        }
    }

    private boolean entityCheck(Entity entity) {
        if (entity == mc.player || entity == mc.getCameraEntity()) return false;
        if (!entity.isAlive()) return false;
        if (entity instanceof LivingEntity livingEntity && livingEntity.isDeadOrDying()) return false;
        if (!PlayerUtils.isWithin(entity, range.get())) return false;
        if (!entities.get().contains(entity.getType())) return false;
        if (ignoreNamed.get() && entity.hasCustomName()) return false;
        if (ignoreTamed.get() && entity instanceof OwnableEntity ownable && Objects.equals(ownable.getOwner(), mc.player)) return false;
        if (ignorePassive.get() && isPassive(entity)) return false;
        if (entity instanceof Player player) {
            if (ignoreCreative.get() && player.isCreative()) return false;
            if (ignoreFriends.get() && !Friends.get().shouldAttack(player)) return false;
            if (ignoreShield.get() && player.isBlocking()) return false;
        }
        if (entity instanceof LivingEntity livingEntity) {
            if (entity instanceof Zombie || entity instanceof Piglin || entity instanceof Hoglin || entity instanceof Zoglin) {
                return hostileMobAgeFilter.get().test(livingEntity);
            }
            if (entity instanceof AgeableMob && (!(entity instanceof Frog || entity instanceof Parrot))) {
                return passiveMobAgeFilter.get().test(livingEntity);
            }
        }
        if (useFovRange.get() && calculateFov(mc.player, entity) > fovRange.get()) return false;
        return ignoreWalls.get() || PlayerUtils.canSeeEntity(entity);
    }

    private boolean isPassive(Entity entity) {
        return switch (entity) {
            case Enderman enderman -> !enderman.isCreepy();
            case ZombifiedPiglin piglin -> !piglin.isAggressive();
            case Wolf wolf -> !wolf.isAggressive();
            default -> false;
        };
    }

    public enum OperationMode { Blacklist, Whitelist }
    public enum AimAxes { Both, OnlyYaw, OnlyPitch }
}
