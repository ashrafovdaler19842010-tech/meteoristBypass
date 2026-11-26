package zgoly.meteorist.modules.zaimbot;

import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
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
import meteordevelopment.meteorclient.systems.modules.Modules;
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
import net.minecraft.world.phys.Vec3;
import zgoly.meteorist.Meteorist;
import zgoly.meteorist.modules.ZAutoSprint;
import zgoly.meteorist.utils.config.MeteoristConfigManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import static meteordevelopment.meteorclient.gui.renderer.GuiRenderer.COPY;
import static zgoly.meteorist.utils.MeteoristUtils.calculateFov;

public class ZAimbot extends Module {
    public enum CameraMode {
        Normal,
        Backstab,
        BackstabAlways,
        Spinbot,
        FakeLook,
        MixAim
    }

    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgAim = settings.createGroup("Aim");
    private final SettingGroup sgModes = settings.createGroup("Camera Modes");

    // --- Фильтры ---
    private final Setting<Set<EntityType<?>>> entities = sgFilter.add(new EntityTypeListSetting.Builder()
            .name("entities").description("Entities to aim at.").onlyAttackable().defaultValue(EntityTypes.PLAYER).build()
    );
    private final Setting<Double> range = sgFilter.add(new DoubleSetting.Builder()
            .name("range").description("Detection range.").defaultValue(6.0).min(1.0).sliderMax(8.0).build()
    );
    private final Setting<Boolean> targetLock = sgFilter.add(new BoolSetting.Builder()
            .name("target-lock").description("Locks onto the first target until module is toggled.").defaultValue(true).build()
    );
    private final Setting<EntityAgeTest> passiveMobAgeFilter = sgFilter.add(new EnumSetting.Builder<EntityAgeTest>()
            .name("passive-mob-age-filter").description("Age of passive mobs.").defaultValue(EntityAgeTest.Adult).build()
    );
    private final Setting<EntityAgeTest> hostileMobAgeFilter = sgFilter.add(new EnumSetting.Builder<EntityAgeTest>()
            .name("hostile-mob-age-filter").description("Age of hostile mobs.").defaultValue(EntityAgeTest.Both).build()
    );
    private final Setting<Boolean> ignoreNamed = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-named").description("Ignore named mobs.").defaultValue(false).build()
    );
    private final Setting<Boolean> ignorePassive = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-passive").description("Ignore passive mobs unless angry.").defaultValue(true).build()
    );
    private final Setting<Boolean> ignoreTamed = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-tamed").description("Ignore tamed mobs.").defaultValue(false).build()
    );
    private final Setting<Boolean> ignoreCreative = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-creative").description("Ignore creative players.").defaultValue(true).build()
    );
    private final Setting<Boolean> ignoreFriends = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-friends").description("Ignore friends.").defaultValue(true).build()
    );
    private final Setting<Boolean> ignoreShield = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-shield").description("Ignore players with shield.").defaultValue(false).build()
    );
    private final Setting<Boolean> useFovRange = sgFilter.add(new BoolSetting.Builder()
            .name("use-fov-range").description("Restrict to FOV.").defaultValue(true).build()
    );
    private final Setting<Double> fovRange = sgFilter.add(new DoubleSetting.Builder()
            .name("fov-range").description("Max FOV.").sliderRange(0, 180).defaultValue(90).visible(useFovRange::get).build()
    );
    private final Setting<Boolean> ignoreWalls = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-walls").description("Aim through walls.").defaultValue(false).build()
    );
    private final Setting<SortPriority> priority = sgFilter.add(new EnumSetting.Builder<SortPriority>()
            .name("priority").description("Target priority.").defaultValue(SortPriority.ClosestAngle).build()
    );

    // --- Настройки наводки ---
    public final Setting<Double> targetMovementPrediction = sgAim.add(new DoubleSetting.Builder()
            .name("target-movement-prediction").min(0).sliderMax(20).defaultValue(0).build()
    );
    private final Setting<AimAxes> aimAxes = sgAim.add(new EnumSetting.Builder<AimAxes>()
            .name("aim-axes").defaultValue(AimAxes.Both).build()
    );
    private final Setting<Target> bodyTarget = sgAim.add(new EnumSetting.Builder<Target>()
            .name("aim-target").defaultValue(Target.Head).build()
    );

    // --- Настройки режимов камеры ---
    private final Setting<CameraMode> cameraMode = sgModes.add(new EnumSetting.Builder<CameraMode>()
            .name("camera-mode").description("Special camera movement.").defaultValue(CameraMode.BackstabAlways).build()
    );
    private final Setting<Double> attackRangeSync = sgModes.add(new DoubleSetting.Builder()
            .name("attack-range").description("Distance to target to start hitting.").defaultValue(3.0).min(2.0).sliderMax(6.0).build()
    );
    private final Setting<Double> pounceRange = sgModes.add(new DoubleSetting.Builder()
            .name("pounce-range").description("Max distance to look at target for jumping.").defaultValue(6.0).min(3.0).sliderMax(8.0).build()
    );
    private final Setting<Double> snapSpeed = sgModes.add(new DoubleSetting.Builder()
            .name("snap-speed").description("Speed of snapping to target (20 = instant).").defaultValue(20.0).min(1.0).sliderMax(20.0).build()
    );
    private final Setting<Double> holdTime = sgModes.add(new DoubleSetting.Builder()
            .name("hold-time").description("Time (ms) to hold camera on target before snapping back.").defaultValue(50.0).min(10.0).sliderMax(500.0).build()
    );
    private final Setting<Double> backstabReturnSpeed = sgModes.add(new DoubleSetting.Builder()
            .name("backstab-return-speed").description("Speed to turn away.").defaultValue(15.0).min(1.0).sliderMax(20.0).build()
    );
    private final Setting<Double> spinSpeedX = sgModes.add(new DoubleSetting.Builder()
            .name("spin-speed-x").description("Horizontal spin speed.").defaultValue(15.0).min(1.0).sliderMax(50.0).visible(() -> cameraMode.get() == CameraMode.Spinbot).build()
    );
    private final Setting<Double> spinSpeedY = sgModes.add(new DoubleSetting.Builder()
            .name("spin-speed-y").description("Vertical spin speed.").defaultValue(5.0).min(1.0).sliderMax(20.0).visible(() -> cameraMode.get() == CameraMode.Spinbot).build()
    );
    private final Setting<Double> fakeLookAngle = sgModes.add(new DoubleSetting.Builder()
            .name("fake-look-angle").description("Max angle for fake look.").defaultValue(180.0).min(45.0).sliderMax(360.0).visible(() -> cameraMode.get() == CameraMode.FakeLook).build()
    );
    private final Setting<Double> fakeLookSpeed = sgModes.add(new DoubleSetting.Builder()
            .name("fake-look-speed").description("Speed of fake look.").defaultValue(2.0).min(0.5).sliderMax(10.0).visible(() -> cameraMode.get() == CameraMode.FakeLook).build()
    );

    private final List<AimTrajectory> trajectories = new ArrayList<>();
    private final Random random = new Random();

    private float originalYaw = 0;
    private float originalPitch = 0;
    private float spinYaw = 0;
    private float spinPitch = 0;
    private long lastMixSwitch = 0;
    private CameraMode currentMixMode = CameraMode.Normal;
    
    private LivingEntity lockedTarget = null;

    // Переменные для прыжков и удержания
    private long holdEndTime = 0;
    private long lastPounceTime = 0;

    public ZAimbot() {
        super(Meteorist.CATEGORY, "z-aimbot", "Smart aimbot that syncs with ZKillaura for attacks.");
    }

    @Override
    public void onActivate() {
        if (mc.player != null) {
            originalYaw = mc.player.getYRot();
            originalPitch = mc.player.getXRot();
            spinYaw = originalYaw;
            spinPitch = originalPitch;
            currentMixMode = CameraMode.Normal;
            lastMixSwitch = System.currentTimeMillis();
            lockedTarget = null;
            holdEndTime = 0;
            lastPounceTime = 0;
            
            if (targetLock.get()) {
                Entity target = TargetUtils.get(this::entityCheck, priority.get());
                if (target instanceof LivingEntity) {
                    lockedTarget = (LivingEntity) target;
                }
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        if (lockedTarget != null && !lockedTarget.isAlive()) {
            lockedTarget = null;
        }

        if (cameraMode.get() == CameraMode.Normal) {
            originalYaw = mc.player.getYRot();
            originalPitch = mc.player.getXRot();
            spinYaw = originalYaw;
            spinPitch = originalPitch;
        } else {
            float mouseDeltaYaw = Mth.wrapDegrees(mc.player.getYRot() - spinYaw);
            float mouseDeltaPitch = mc.player.getXRot() - spinPitch;
            
            originalYaw = Mth.wrapDegrees(originalYaw + mouseDeltaYaw);
            originalPitch = Mth.clamp(originalPitch + mouseDeltaPitch, -90, 90);
            
            spinYaw = mc.player.getYRot();
            spinPitch = mc.player.getXRot();
        }

        if (cameraMode.get() == CameraMode.MixAim) {
            long now = System.currentTimeMillis();
            if (now - lastMixSwitch > (1000 + random.nextInt(2000))) {
                CameraMode[] modes = {CameraMode.Normal, CameraMode.Backstab, CameraMode.Spinbot, CameraMode.FakeLook};
                currentMixMode = modes[random.nextInt(modes.length)];
                lastMixSwitch = now;
            }
        }
    }

    // --- Коррекция ходьбы (Инверсия W/S при взгляде назад) ---
    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (cameraMode.get() == CameraMode.Normal) return;
        if (cameraMode.get() == CameraMode.MixAim && currentMixMode == CameraMode.Normal) return;

        Entity target = getCurrentTarget();
        if (target == null) return;

        // Если мы смотрим спиной к цели, инвертируем вектор движения
        if (cameraMode.get() == CameraMode.BackstabAlways || cameraMode.get() == CameraMode.Backstab) {
            float yawToTarget = (float) Rotations.getYaw(target.getEyePosition());
            float delta = Math.abs(Mth.wrapDegrees(mc.player.getYRot() - yawToTarget));
            
            // Если угол между взглядом и целью больше 90 градусов (смотрим назад)
            if (delta > 90.0f) {
                event.movement = new Vec3(-event.movement.x, event.movement.y, -event.movement.z);
            }
        }
    }

    private Entity getCurrentTarget() {
        if (targetLock.get() && lockedTarget != null && lockedTarget.isAlive()) {
            return lockedTarget;
        }
        
        Entity target = TargetUtils.get(this::entityCheck, priority.get());
        if (targetLock.get() && target instanceof LivingEntity) {
            lockedTarget = (LivingEntity) target;
        }
        return target;
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
                    moveUp.tooltip = "Move trajectory up.";
                    moveUp.action = () -> {
                        trajectories.remove(index);
                        trajectories.add(index - 1, trajectory);
                        fillWidget(theme, list);
                    };
                }

                if (index < trajectories.size() - 1) {
                    WButton moveDown = moveContainer.add(theme.button(Meteorist.ARROW_DOWN)).expandX().widget();
                    moveDown.tooltip = "Move trajectory down.";
                    moveDown.action = () -> {
                        trajectories.remove(index);
                        trajectories.add(index + 1, trajectory);
                        fillWidget(theme, list);
                    };
                }
            }

            WButton copy = controls.add(theme.button(COPY)).widget();
            copy.tooltip = "Duplicate trajectory.";
            copy.action = () -> {
                trajectories.add(trajectories.indexOf(trajectory), trajectory.copy());
                fillWidget(theme, list);
            };

            WMinus remove = controls.add(theme.minus()).widget();
            remove.tooltip = "Remove trajectory.";
            remove.action = () -> {
                trajectories.remove(trajectory);
                fillWidget(theme, list);
            };
        }

        if (!trajectories.isEmpty()) list.add(theme.horizontalSeparator()).expandX();

        WContainer controls = list.add(theme.horizontalList()).expandX().widget();

        WButton add = controls.add(theme.button("Add Trajectory")).expandX().widget();
        add.action = () -> {
            trajectories.add(new AimTrajectory());
            fillWidget(theme, list);
        };

        WButton removeAll = controls.add(theme.button("Remove All Trajectories")).expandX().widget();
        removeAll.action = () -> {
            trajectories.clear();
            fillWidget(theme, list);
        };

        MeteoristConfigManager.configManager(theme, list, this);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;

        Entity target = getCurrentTarget();

        if (target == null) {
            if (cameraMode.get() == CameraMode.BackstabAlways) {
                mc.player.setYRot(originalYaw);
                mc.player.setXRot(originalPitch);
            }
            return;
        }

        CameraMode modeToUse = cameraMode.get();
        if (modeToUse == CameraMode.MixAim) modeToUse = currentMixMode;

        if (modeToUse == CameraMode.BackstabAlways) {
            double dist = mc.player.distanceTo(target);
            long now = System.currentTimeMillis();

            if (dist <= attackRangeSync.get()) {
                // В зоне 3 блоков (Атака)
                if (mc.player.fallDistance > 0.0F) {
                    // ПАДАЕМ -> Поворот на крит (моментальный снап)
                    faceTarget(target);
                    holdEndTime = now + holdTime.get().longValue();
                } else {
                    // СТОИМ -> Смотрим спиной
                    if (now < holdEndTime) {
                        faceTarget(target); // Удерживаем взгляд на цели
                    } else {
                        faceAway(target);
                    }
                }
            } else if (dist <= pounceRange.get()) {
                // Зона от 3 до 6 блоков (Прыжок для инерции)
                if (now < holdEndTime) {
                    faceTarget(target); // Удерживаем
                } else if (mc.player.onGround() && now > lastPounceTime + 300) {
                    // СНАП + ПРЫЖОК + СБРОС СПРИНТА
                    faceTarget(target);
                    mc.options.keyJump.setDown(true);
                    mc.player.setSprinting(false); // Сброс спринта для инерции
                    
                    ZAutoSprint autoSprint = Modules.get().get(ZAutoSprint.class);
                    if (autoSprint != null && autoSprint.isActive()) {
                         // Если у нас есть ZAutoSprint, можно дернуть его сброс, 
                         // но setSprinting(false) обычно достаточно.
                    }
                    
                    holdEndTime = now + holdTime.get().longValue();
                    lastPounceTime = now;
                } else {
                    mc.options.keyJump.setDown(false);
                    faceAway(target);
                }
            } else {
                // Вне зоны 6 блоков -> Смотрим спиной
                mc.options.keyJump.setDown(false);
                faceAway(target);
            }
        } else {
            switch (modeToUse) {
                case Backstab -> handleBackstab(target);
                case Spinbot -> handleSpinbot(target);
                case FakeLook -> handleFakeLook(target);
                default -> aim(mc.player, target);
            }
        }
    }

    private void faceTarget(Entity target) {
        float targetYaw = (float) Rotations.getYaw(target.getEyePosition());
        float targetPitch = (float) Rotations.getPitch(target, bodyTarget.get());
        
        spinYaw = approachAngle(spinYaw, targetYaw, snapSpeed.get().floatValue());
        spinPitch = approachAngle(spinPitch, targetPitch, snapSpeed.get().floatValue());
        
        mc.player.setYRot(spinYaw);
        mc.player.setXRot(spinPitch);
    }

    private void faceAway(Entity target) {
        float yawToTarget = (float) Rotations.getYaw(target.getEyePosition());
        float oppositeYaw = yawToTarget + 180f;
        
        spinYaw = approachAngle(spinYaw, oppositeYaw, backstabReturnSpeed.get().floatValue());
        spinPitch = approachAngle(spinPitch, 0, backstabReturnSpeed.get().floatValue());
        
        mc.player.setYRot(spinYaw);
        mc.player.setXRot(spinPitch);
    }

    private void handleBackstab(Entity target) {
        float yawToTarget = (float) Rotations.getYaw(target.getEyePosition());
        float oppositeYaw = yawToTarget + 180f;
        spinYaw = approachAngle(spinYaw, oppositeYaw, backstabReturnSpeed.get().floatValue());
        spinPitch = approachAngle(spinPitch, 0, backstabReturnSpeed.get().floatValue());
        mc.player.setYRot(spinYaw);
        mc.player.setXRot(spinPitch);
    }

    private void handleSpinbot(Entity target) {
        spinYaw += spinSpeedX.get().floatValue();
        if (spinYaw > 360) spinYaw -= 360;
        spinPitch += spinSpeedY.get().floatValue();
        if (spinPitch > 90) spinPitch = -90;
        if (spinPitch < -90) spinPitch = 90;

        mc.player.setYRot(spinYaw);
        mc.player.setXRot(spinPitch);
    }

    private void handleFakeLook(Entity target) {
        float offset = (float) Math.sin(System.currentTimeMillis() / 1000.0 * fakeLookSpeed.get()) * fakeLookAngle.get().floatValue();
        spinYaw = Mth.wrapDegrees(originalYaw + offset);
        spinPitch = 0;
        mc.player.setYRot(spinYaw);
        mc.player.setXRot(spinPitch);
    }

    private float approachAngle(float current, float target, float speed) {
        float diff = Mth.wrapDegrees(target - current);
        if (Math.abs(diff) <= speed) return target;
        return current + Math.signum(diff) * speed;
    }

    private boolean entityCheck(Entity entity) {
        if (entity == mc.player || entity == mc.getCameraEntity()) return false;
        if (!entity.isAlive()) return false;
        if (entity instanceof LivingEntity livingEntity && livingEntity.isDeadOrDying()) return false;
        if (!PlayerUtils.isWithin(entity, range.get())) return false;
        if (!entities.get().contains(entity.getType())) return false;
        if (ignoreNamed.get() && entity.hasCustomName()) return false;

        if (ignoreTamed.get()
                && entity instanceof OwnableEntity ownable
                && Objects.equals(ownable.getOwner(), mc.player)) return false;

        if (ignorePassive.get() && isPassive(entity)) return false;

        if (entity instanceof Player player) {
            if (ignoreCreative.get() && player.isCreative()) return false;
            if (ignoreFriends.get() && !Friends.get().shouldAttack(player)) return false;
            if (ignoreShield.get() && player.isBlocking()) return false;
        }

        if (entity instanceof LivingEntity livingEntity) {
            if (entity instanceof Zombie || entity instanceof Piglin
                    || entity instanceof Hoglin || entity instanceof Zoglin) {
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

    private void aim(LivingEntity player, Entity target) {
        Vec3 predictedPos = target.position().add(target.getDeltaMovement().scale(targetMovementPrediction.get()));
        float yaw = (float) Rotations.getYaw(predictedPos);
        float pitch = (float) Rotations.getPitch(target, bodyTarget.get());

        float yawDifference = Mth.wrapDegrees(yaw - player.getYRot());
        float pitchDifference = Mth.wrapDegrees(pitch - player.getXRot());

        if (aimAxes.get() == AimAxes.Both || aimAxes.get() == AimAxes.OnlyYaw) {
            player.setYRot(player.getYRot() + yawDifference * 0.5f);
        }
        if (aimAxes.get() == AimAxes.Both || aimAxes.get() == AimAxes.OnlyPitch) {
            player.setXRot(player.getXRot() + pitchDifference * 0.5f);
        }
    }

    public enum OperationMode { Blacklist, Whitelist }
    public enum AimAxes { Both, OnlyYaw, OnlyPitch }
}
