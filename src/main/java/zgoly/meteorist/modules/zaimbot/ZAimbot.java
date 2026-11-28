package zgoly.meteorist.modules.zaimbot;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
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
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
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
    private final SettingGroup sgHvH = settings.createGroup("HvH 360");
    private final SettingGroup sgLogger = settings.createGroup("Anti-Cheat Logger");

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

    // --- Aim (Легит настройки) ---
    public final Setting<Double> targetMovementPrediction = sgAim.add(new DoubleSetting.Builder().name("target-movement-prediction").min(0).sliderMax(20).defaultValue(0).build());
    private final Setting<AimAxes> aimAxes = sgAim.add(new EnumSetting.Builder<AimAxes>().name("aim-axes").defaultValue(AimAxes.Both).build());
    private final Setting<Target> bodyTarget = sgAim.add(new EnumSetting.Builder<Target>().name("aim-target").defaultValue(Target.Head).build());
    private final Setting<Boolean> instantYaw = sgAim.add(new BoolSetting.Builder().name("instant-yaw").defaultValue(false).build());
    private final Setting<Boolean> instantPitch = sgAim.add(new BoolSetting.Builder().name("instant-pitch").defaultValue(false).build());
    private final Setting<Boolean> syncYawWithCooldown = sgAim.add(new BoolSetting.Builder().name("sync-yaw-with-cooldown").defaultValue(false).visible(() -> !instantYaw.get()).build());
    private final Setting<Boolean> syncPitchWithCooldown = sgAim.add(new BoolSetting.Builder().name("sync-pitch-with-cooldown").defaultValue(false).visible(() -> !instantPitch.get()).build());
    private final Setting<Double> speedYaw = sgAim.add(new DoubleSetting.Builder().name("speed-yaw").min(0.1).defaultValue(10.0).sliderMax(180.0).visible(() -> !instantYaw.get()).build());
    private final Setting<Double> speedPitch = sgAim.add(new DoubleSetting.Builder().name("speed-pitch").min(0.1).defaultValue(10.0).sliderMax(180.0).visible(() -> !instantPitch.get()).build());

    public enum CameraMode { None, HvH360 }
    private final Setting<CameraMode> cameraMode = sgHvH.add(new EnumSetting.Builder<CameraMode>()
            .name("camera-mode")
            .description("None - стандартный аим. HvH 360 - спин.")
            .defaultValue(CameraMode.None)
            .build()
    );

    public enum AimPointMode { Center, RandomBody, NearestRay }
    private final Setting<AimPointMode> aimPointMode = sgHvH.add(new EnumSetting.Builder<AimPointMode>()
            .name("aim-point-mode")
            .description("Куда целиться (для HvH 360 при ударе).")
            .defaultValue(AimPointMode.RandomBody)
            .build()
    );

    private final Setting<Boolean> autoJump = sgHvH.add(new BoolSetting.Builder()
            .name("auto-jump").description("Автоматически прыгать для критов.").defaultValue(false).build()
    );
    private final Setting<Double> hvhSpinSpeed = sgHvH.add(new DoubleSetting.Builder().name("spin-speed").description("Скорость вращения 360.").defaultValue(180.0).min(10.0).sliderMax(360.0).build());
    private final Setting<Double> hvhLookRange = sgHvH.add(new DoubleSetting.Builder().name("look-range").description("Дистанция смотреть прямо на игрока.").defaultValue(3.0).min(1.0).sliderMax(5.0).build());

    // --- Anti-Cheat Logger ---
    private final Setting<Boolean> logAntiCheat = sgLogger.add(new BoolSetting.Builder()
            .name("log-anticheat").description("Записывать откаты сервера и твои лучи.").defaultValue(false).build()
    );
    private final List<String> logs = new ArrayList<>();
    private float lastYaw = 0;
    private float lastPitch = 0;

    private final List<AimTrajectory> trajectories = new ArrayList<>();
    private LivingEntity stickyTarget = null;

    public ZAimbot() {
        super(Meteorist.CATEGORY, "z-aimbot", "Aimbot with HvH 360 and Logger.");
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
        
        list.add(theme.label("Anti-Cheat Logger Controls"));
        WContainer logControls = list.add(theme.horizontalList()).expandX().widget();
        
        WButton copyBtn = logControls.add(theme.button("Copy Logs")).expandX().widget();
        copyBtn.action = () -> {
            if (!logs.isEmpty()) {
                mc.keyboardHandler.setClipboard(String.join("\n", logs));
                logs.add("[SYSTEM] Logs copied.");
            }
        };
        
        WButton clearBtn = logControls.add(theme.button("Clear Logs")).expandX().widget();
        clearBtn.action = () -> logs.clear();
        
        list.add(theme.label("Logs (" + logs.size() + "):"));
        WVerticalList logList = list.add(theme.verticalList()).expandX().widget();
        int start = Math.max(0, logs.size() - 5); 
        for (int i = start; i < logs.size(); i++) {
            logList.add(theme.label(logs.get(i)));
        }
        
        list.add(theme.horizontalSeparator()).expandX();

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
        releaseManagedKeys();
        lastYaw = mc.player != null ? mc.player.getYRot() : 0;
        lastPitch = mc.player != null ? mc.player.getXRot() : 0;
    }

    @Override
    public void onDeactivate() {
        releaseManagedKeys();
        stickyTarget = null;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!logAntiCheat.get() || mc.player == null) return;
        
        if (event.packet instanceof ClientboundPlayerPositionPacket) {
            float deltaYaw = Mth.wrapDegrees(mc.player.getYRot() - lastYaw);
            float deltaPitch = Mth.wrapDegrees(mc.player.getXRot() - lastPitch);
            String log = String.format("[SETBACK] Rot: %.1f/%.1f | Delta: %.1f/%.1f | Mode: %s", 
                    mc.player.getYRot(), mc.player.getXRot(), deltaYaw, deltaPitch, cameraMode.get().name());
            logs.add(log);
            if (logs.size() > 100) logs.remove(0);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        if (ZAutoSprint.wTapActive) return; 

        if (stickyTarget == null || !stickyTarget.isAlive() || mc.player.distanceTo(stickyTarget) > range.get()) {
            Entity found = TargetUtils.get(this::entityCheck, priority.get());
            stickyTarget = (found instanceof LivingEntity living) ? living : null;
        }

        if (stickyTarget == null) {
            releaseManagedKeys();
            return;
        }

        if (logAntiCheat.get()) {
            float deltaYaw = Mth.wrapDegrees(mc.player.getYRot() - lastYaw);
            float deltaPitch = Mth.wrapDegrees(mc.player.getXRot() - lastPitch);
            if (Math.abs(deltaYaw) > 30 || Math.abs(deltaPitch) > 30) {
                logs.add(String.format("[TICK] Fast Rot: %.1f/%.1f | Delta: %.1f/%.1f", 
                    mc.player.getYRot(), mc.player.getXRot(), deltaYaw, deltaPitch));
                if (logs.size() > 100) logs.remove(0);
            }
            lastYaw = mc.player.getYRot();
            lastPitch = mc.player.getXRot();
        }

        if (cameraMode.get() == CameraMode.None) {
            // В режиме None не трогаем повороты здесь, это делает onRender3D
        } else if (cameraMode.get() == CameraMode.HvH360) {
            handleHvH360(stickyTarget);
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (cameraMode.get() == CameraMode.HvH360) return; // HvH управляется в onTick
        
        // Режим None - оригинальная логика аима
        Entity target = TargetUtils.get(this::entityCheck, priority.get());
        Item heldItem = mc.player.getMainHandItem().getItem();
        if (itemFilteringMode.get() == OperationMode.Blacklist ? itemBlacklist.get().contains(heldItem) : !itemWhitelist.get().contains(heldItem)) return;
        if (target == null) return;
        aim(mc.player, target);
    }

    private void handleHvH360(LivingEntity target) {
        double dist = mc.player.distanceTo(target);
        
        if (autoJump.get() && mc.player.onGround() && !mc.player.isInWater()) {
            mc.options.keyJump.setDown(true);
        }

        // Логика спина
        boolean isJumpingOrFalling = mc.options.keyJump.isDown() || mc.player.fallDistance > 0.0F || !mc.player.onGround();
        
        // Если мы в воздухе и близко - просто спим и бьем
        if (dist <= hvhLookRange.get() || isJumpingOrFalling) {
            // Мы не наводимся на игрока (оставляем спин), чтобы Grim не палил резкие повороты в воздухе
            float spin = hvhSpinSpeed.get().floatValue();
            mc.player.setYRot(mc.player.getYRot() + spin);
            mc.player.setXRot(mc.player.getXRot() + (float) ((Math.random() - 0.5) * 20));
            
            // Двигаемся на игрока
            setKeys(true, false, true, null);
        } else {
            // Если далеко - двигаемся стрейфами
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

    // Оригинальная логика аима из Meteorist
    private void aim(LivingEntity player, Entity target) {
        Vec3 predictedPos = target.position().add(target.getDeltaMovement().scale(targetMovementPrediction.get()));
        float yaw = (float) Rotations.getYaw(predictedPos);
        float pitch;
        Item usedItem = mc.player.getMainHandItem().getItem();
        AimTrajectory matchingTrajectory = trajectories.stream().filter(t -> t.items.get().contains(usedItem)).findFirst().orElse(null);
        if (matchingTrajectory != null) {
            pitch = calculatePitchWithTrajectory(player, target, matchingTrajectory);
        } else {
            pitch = (float) Rotations.getPitch(target, bodyTarget.get());
        }

        float yawDifference = Mth.wrapDegrees(yaw - player.getYRot());
        float pitchDifference = Mth.wrapDegrees(pitch - player.getXRot());

        if (instantYaw.get()) {
            if (aimAxes.get() == AimAxes.Both || aimAxes.get() == AimAxes.OnlyYaw) player.setYRot(yaw);
        } else if (aimAxes.get() == AimAxes.Both || aimAxes.get() == AimAxes.OnlyYaw) {
            float cooldownProgress = syncYawWithCooldown.get() ? mc.player.getAttackStrengthScale(0) : 1;
            float yawSpeedFactor = speedYaw.get().floatValue() / 10;
            player.setYRot(player.getYRot() + yawDifference * cooldownProgress * yawSpeedFactor);
        }

        if (instantPitch.get()) {
            if (aimAxes.get() == AimAxes.Both || aimAxes.get() == AimAxes.OnlyPitch) player.setXRot(pitch);
        } else if (aimAxes.get() == AimAxes.Both || aimAxes.get() == AimAxes.OnlyPitch) {
            float cooldownProgress = syncPitchWithCooldown.get() ? mc.player.getAttackStrengthScale(0) : 1;
            float pitchSpeedFactor = speedPitch.get().floatValue() / 10;
            player.setXRot(player.getXRot() + pitchDifference * cooldownProgress * pitchSpeedFactor);
        }
    }

    private float calculatePitchWithTrajectory(LivingEntity player, Entity target, AimTrajectory traj) {
        Vec3 playerEye = player.getEyePosition();
        Vec3 targetPos = target.position().add(0, target.getBoundingBox().getYsize() / 2, 0);
        double dx = targetPos.x - playerEye.x;
        double dy = targetPos.y - playerEye.y;
        double dz = targetPos.z - playerEye.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);
        if (hDist == 0) return 0;
        double g = 0.006 * traj.gravity.get();
        double v = traj.velocityScale.get();
        double vSq = v * v;
        double sqrtPart = vSq * vSq - g * (g * hDist * hDist + 2 * dy * vSq);
        if (sqrtPart < 0) return (float) Rotations.getPitch(target, bodyTarget.get());
        double pitchRad = Math.atan((vSq - Math.sqrt(sqrtPart)) / (g * hDist));
        return (float) -Math.toDegrees(pitchRad);
    }

    private void setKeys(boolean w, boolean s, boolean sprint, Boolean jump) {
        mc.options.keyUp.setDown(w);
        mc.options.keyDown.setDown(s);
        mc.options.keySprint.setDown(sprint);
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
