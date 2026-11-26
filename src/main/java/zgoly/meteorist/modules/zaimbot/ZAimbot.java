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
import meteordevelopment.meteorclient.systems.modules.combat.BowAimbot;
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
        Spinbot,
        FakeLook,
        MixAim
    }

    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgAim = settings.createGroup("Aim");
    private final SettingGroup sgModes = settings.createGroup("Camera Modes");

    // --- Фильтры (без изменений) ---
    private final Setting<Set<EntityType<?>>> entities = sgFilter.add(new EntityTypeListSetting.Builder()
            .name("entities").description("Entities to aim at.").onlyAttackable().defaultValue(EntityTypes.PLAYER).build()
    );
    private final Setting<Double> range = sgFilter.add(new DoubleSetting.Builder()
            .name("range").description("The maximum range the entity can be to aim at it.").defaultValue(4.5).min(0).sliderMax(6).build()
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
    private final Setting<OperationMode> itemFilteringMode = sgFilter.add(new EnumSetting.Builder<OperationMode>()
            .name("item-filtering-mode").description("Item filter mode.").defaultValue(OperationMode.Blacklist).build()
    );
    private final Setting<List<Item>> itemBlacklist = sgFilter.add(new ItemListSetting.Builder()
            .name("item-blacklist").defaultValue(Items.COBWEB).visible(() -> itemFilteringMode.get() == OperationMode.Blacklist).build()
    );
    private final Setting<List<Item>> itemWhitelist = sgFilter.add(new ItemListSetting.Builder()
            .name("item-whitelist").defaultValue(Items.BOW, Items.CROSSBOW, Items.TRIDENT).visible(() -> itemFilteringMode.get() == OperationMode.Whitelist).build()
    );

    // --- Настройки наводки (без изменений) ---
    public final Setting<Double> targetMovementPrediction = sgAim.add(new DoubleSetting.Builder()
            .name("target-movement-prediction").min(0).sliderMax(20).defaultValue(0).build()
    );
    private final Setting<AimAxes> aimAxes = sgAim.add(new EnumSetting.Builder<AimAxes>()
            .name("aim-axes").defaultValue(AimAxes.Both).build()
    );
    private final Setting<Target> bodyTarget = sgAim.add(new EnumSetting.Builder<Target>()
            .name("aim-target").defaultValue(Target.Head).build()
    );
    private final Setting<Boolean> instantYaw = sgAim.add(new BoolSetting.Builder()
            .name("instant-yaw").defaultValue(false).build()
    );
    private final Setting<Boolean> instantPitch = sgAim.add(new BoolSetting.Builder()
            .name("instant-pitch").defaultValue(false).build()
    );
    private final Setting<Boolean> syncYawWithCooldown = sgAim.add(new BoolSetting.Builder()
            .name("sync-yaw-with-cooldown").defaultValue(false).visible(() -> !instantYaw.get()).build()
    );
    private final Setting<Boolean> syncPitchWithCooldown = sgAim.add(new BoolSetting.Builder()
            .name("sync-pitch-with-cooldown").defaultValue(false).visible(() -> !instantPitch.get()).build()
    );
    private final Setting<Double> speedYaw = sgAim.add(new DoubleSetting.Builder()
            .name("speed-yaw").min(0).defaultValue(1).sliderRange(0.1, 10).visible(() -> !instantYaw.get()).build()
    );
    private final Setting<Double> speedPitch = sgAim.add(new DoubleSetting.Builder()
            .name("speed-pitch").min(0).defaultValue(1).sliderRange(0.1, 10).visible(() -> !instantPitch.get()).build()
    );

    // --- Настройки режимов камеры ---
    private final Setting<CameraMode> cameraMode = sgModes.add(new EnumSetting.Builder<CameraMode>()
            .name("camera-mode").description("Special camera movement.").defaultValue(CameraMode.Normal).build()
    );
    private final Setting<Double> backstabSpeed = sgModes.add(new DoubleSetting.Builder()
            .name("backstab-speed").defaultValue(5.0).min(1.0).sliderMax(20.0).visible(() -> cameraMode.get() == CameraMode.Backstab).build()
    );
    private final Setting<Double> spinSpeedX = sgModes.add(new DoubleSetting.Builder()
            .name("spin-speed-x").defaultValue(15.0).min(1.0).sliderMax(50.0).visible(() -> cameraMode.get() == CameraMode.Spinbot).build()
    );
    private final Setting<Double> spinSpeedY = sgModes.add(new DoubleSetting.Builder()
            .name("spin-speed-y").defaultValue(5.0).min(1.0).sliderMax(20.0).visible(() -> cameraMode.get() == CameraMode.Spinbot).build()
    );
    private final Setting<Double> fakeLookAngle = sgModes.add(new DoubleSetting.Builder()
            .name("fake-look-angle").defaultValue(180.0).min(45.0).sliderMax(360.0).visible(() -> cameraMode.get() == CameraMode.FakeLook).build()
    );
    private final Setting<Double> fakeLookSpeed = sgModes.add(new DoubleSetting.Builder()
            .name("fake-look-speed").defaultValue(2.0).min(0.5).sliderMax(10.0).visible(() -> cameraMode.get() == CameraMode.FakeLook).build()
    );

    private final List<AimTrajectory> trajectories = new ArrayList<>();
    private final Random random = new Random();

    // Переменные для режимов
    private float originalYaw = 0;
    private float originalPitch = 0;
    private float spinYaw = 0;
    private float spinPitch = 0;
    private long lastMixSwitch = 0;
    private CameraMode currentMixMode = CameraMode.Normal;

    public ZAimbot() {
        super(Meteorist.CATEGORY, "z-aimbot", "Smart aimbot that only aims, attack is handled by ZKillaura.");
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
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        // Обновляем "истинный" угол, если не в специальном режиме
        if (cameraMode.get() == CameraMode.Normal) {
            originalYaw = mc.player.getYRot();
            originalPitch = mc.player.getXRot();
        } else {
            // Считываем движение мыши для коррекции
            float mouseDeltaYaw = Mth.wrapDegrees(mc.player.getYRot() - spinYaw);
            float mouseDeltaPitch = mc.player.getXRot() - spinPitch;
            originalYaw = Mth.wrapDegrees(originalYaw + mouseDeltaYaw);
            originalPitch = Mth.clamp(originalPitch + mouseDeltaPitch, -90, 90);
        }

        // Логика MixAim
        if (cameraMode.get() == CameraMode.MixAim) {
            long now = System.currentTimeMillis();
            if (now - lastMixSwitch > (1000 + random.nextInt(2000))) {
                CameraMode[] modes = {CameraMode.Normal, CameraMode.Backstab, CameraMode.Spinbot, CameraMode.FakeLook};
                currentMixMode = modes[random.nextInt(modes.length)];
                lastMixSwitch = now;
            }
        }
    }

    // Коррекция ходьбы
    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (cameraMode.get() == CameraMode.Normal) return;
        if (cameraMode.get() == CameraMode.MixAim && currentMixMode == CameraMode.Normal) return;

        Vec3 movement = event.movement;
        if (movement.horizontalDistanceSqr() > 0) {
            float deltaYaw = Mth.wrapDegrees(originalYaw - mc.player.getYRot());
            float rad = (float) Math.toRadians(deltaYaw);
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);

            double newX = movement.x * cos - movement.z * sin;
            double newZ = movement.x * sin + movement.z * cos;

            event.movement = new Vec3(newX, movement.y, newZ);
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;

        Entity target = TargetUtils.get(this::entityCheck, priority.get());

        Item heldItem = mc.player.getMainHandItem().getItem();
        if (itemFilteringMode.get() == OperationMode.Blacklist
                ? itemBlacklist.get().contains(heldItem)
                : !itemWhitelist.get().contains(heldItem)) return;

        CameraMode modeToUse = cameraMode.get();
        if (modeToUse == CameraMode.MixAim) modeToUse = currentMixMode;

        switch (modeToUse) {
            case Backstab -> handleBackstab(target);
            case Spinbot -> handleSpinbot(target);
            case FakeLook -> handleFakeLook(target);
            default -> {
                if (target != null) aim(mc.player, target);
            }
        }
    }

    private void handleBackstab(Entity target) {
        if (target == null) return;
        float yawToTarget = (float) Rotations.getYaw(target.getEyePosition());
        float oppositeYaw = yawToTarget + 180f;

        spinYaw = approachAngle(spinYaw, oppositeYaw, backstabSpeed.get().floatValue());
        spinPitch = approachAngle(spinPitch, 0, backstabSpeed.get().floatValue());

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
        float offset = (float) Math.sin(System.currentTimeMillis() / 1000.0) * fakeLookAngle.get().floatValue();
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

        float pitch;
        Item usedItem = mc.player.getMainHandItem().getItem();
        AimTrajectory matchingTrajectory = trajectories.stream()
                .filter(t -> t.items.get().contains(usedItem))
                .findFirst().orElse(null);

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

    public enum OperationMode { Blacklist, Whitelist }
    public enum AimAxes { Both, OnlyYaw, OnlyPitch }
}
