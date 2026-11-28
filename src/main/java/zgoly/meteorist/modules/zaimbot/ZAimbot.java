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
    private final SettingGroup sgAim = settings.createGroup("Aim Settings");
    private final SettingGroup sgHuman = settings.createGroup("Humanizer");
    private final SettingGroup sgAi = settings.createGroup("AI Auto-Config");
    private final SettingGroup sgLogger = settings.createGroup("Anti-Cheat Logger");

    // --- Filter ---
    private final Setting<Set<EntityType<?>>> entities = sgFilter.add(new EntityTypeListSetting.Builder()
            .name("entities").description("Entities to aim at.").onlyAttackable().defaultValue(EntityTypes.PLAYER).build()
    );
    private final Setting<Double> minRange = sgFilter.add(new DoubleSetting.Builder()
            .name("min-range").description("Min distance to start aiming.").defaultValue(4.0).min(1.0).sliderMax(6.0).build()
    );
    private final Setting<Double> maxRange = sgFilter.add(new DoubleSetting.Builder()
            .name("max-range").description("Max distance to start aiming.").defaultValue(9.0).min(6.0).sliderMax(12.0).build()
    );
    private final Setting<Boolean> ignoreFriends = sgFilter.add(new BoolSetting.Builder().name("ignore-friends").defaultValue(true).build());
    private final Setting<Boolean> ignoreWalls = sgFilter.add(new BoolSetting.Builder().name("ignore-walls").defaultValue(false).build());

    // --- Aim Settings (Управляется ИИ) ---
    private final Setting<AimPointMode> aimPointMode = sgAim.add(new EnumSetting.Builder<AimPointMode>()
            .name("aim-point-mode").description("Humanized vs Center.").defaultValue(AimPointMode.RandomBody).build()
    );
    private final Setting<Double> maxRotationSpeed = sgAim.add(new DoubleSetting.Builder()
            .name("max-rotation-speed").description("Макс. градусов за тик (Защита от 180 snap).").defaultValue(25.0).min(5.0).sliderMax(180.0).build()
    );
    private final Setting<Double> aimSpeedMultiplier = sgAim.add(new DoubleSetting.Builder()
            .name("aim-speed").description("Множитель скорости доводки.").defaultValue(5.0).min(1.0).sliderMax(10.0).build()
    );

    // --- Humanizer (Управляется ИИ) ---
    private final Setting<Boolean> humanJitter = sgHuman.add(new BoolSetting.Builder()
            .name("human-jitter").description("Добавляет дрожание мыши.").defaultValue(true).build()
    );
    private final Setting<Double> jitterAmount = sgHuman.add(new DoubleSetting.Builder()
            .name("jitter-amount").description("Сила дрожи.").defaultValue(1.5).min(0.1).sliderMax(5.0).visible(humanJitter::get).build()
    );
    private final Setting<Integer> reactionTime = sgHuman.add(new IntSetting.Builder()
            .name("reaction-time").description("Задержка перед наводкой (мс).").defaultValue(50).sliderRange(0, 200).build()
    );

    // --- AI Auto-Config ---
    private final Setting<Boolean> autoConfig = sgAi.add(new BoolSetting.Builder()
            .name("auto-config").description("Автоматически снижает агрессию при откатах сервера.").defaultValue(true).build()
    );
    private int aiLevel = 0; // 0 = Fast, 1 = Med, 2 = Slow, 3 = Legit

    // --- Logger ---
    private final Setting<Boolean> logAntiCheat = sgLogger.add(new BoolSetting.Builder()
            .name("log-anticheat").description("Записывать всё для анализа.").defaultValue(true).build()
    );
    private final List<String> logs = new ArrayList<>();
    private float lastYaw = 0, lastPitch = 0;
    private long lastLogTime = 0;

    private final List<AimTrajectory> trajectories = new ArrayList<>();
    private LivingEntity stickyTarget = null;
    private long targetLockTime = 0;

    public ZAimbot() {
        super(Meteorist.CATEGORY, "z-aimbot", "AI Humanized Aimbot with Auto-Config.");
    }

    // ... (Стандартные методы toTag, fromTag, getWidget опущены для краткости, они не менялись) ...

    @Override
    public void onActivate() {
        stickyTarget = null;
        releaseManagedKeys();
        lastYaw = mc.player != null ? mc.player.getYRot() : 0;
        lastPitch = mc.player != null ? mc.player.getXRot() : 0;
        if (autoConfig.get()) {
            applyAiConfig(0); // Начинаем с самой быстрой
            logs.add("[AI] Init: Level 0 (Aggressive). Target Lock Enabled.");
        }
    }

    @Override
    public void onDeactivate() {
        releaseManagedKeys();
        stickyTarget = null;
    }

    // --- AI ЛОГИКА ---
    private void applyAiConfig(int level) {
        aiLevel = level;
        switch (level) {
            case 0 -> {
                maxRotationSpeed.set(40.0);
                aimSpeedMultiplier.set(8.0);
                jitterAmount.set(0.5);
                reactionTime.set(10);
            }
            case 1 -> {
                maxRotationSpeed.set(25.0);
                aimSpeedMultiplier.set(6.0);
                jitterAmount.set(1.5);
                reactionTime.set(40);
            }
            case 2 -> {
                maxRotationSpeed.set(15.0);
                aimSpeedMultiplier.set(4.0);
                jitterAmount.set(3.0);
                reactionTime.set(80);
            }
            case 3 -> {
                maxRotationSpeed.set(8.0);
                aimSpeedMultiplier.set(2.5);
                jitterAmount.set(5.0);
                reactionTime.set(150);
            }
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!logAntiCheat.get() || mc.player == null) return;

        if (event.packet instanceof ClientboundPlayerPositionPacket) {
            float deltaYaw = Mth.wrapDegrees(mc.player.getYRot() - lastYaw);
            float deltaPitch = Mth.wrapDegrees(mc.player.getXRot() - lastPitch);
            
            String log = String.format("[SETBACK] Lvl:%d | Rot:%.1f/%.1f | Delta:%.1f/%.1f | Dist:%.1f | Sprint:%b | Ground:%b", 
                    aiLevel, mc.player.getYRot(), mc.player.getXRot(), deltaYaw, deltaPitch, 
                    stickyTarget != null ? mc.player.distanceTo(stickyTarget) : -1, 
                    mc.player.isSprinting(), mc.player.onGround());
            
            logs.add(log);
            if (logs.size() > 200) logs.remove(0);

            // АВТО-КОНФИГ: Если нас откатили, и мы не на максимуме легитности, повышаем уровень
            if (autoConfig.get() && aiLevel < 3) {
                applyAiConfig(aiLevel + 1);
                logs.add("[AI] Setback detected! Degrading to Legit Level: " + aiLevel);
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        if (ZAutoSprint.wTapActive) return;

        // Sticky Target Logic (Ищем только если нет цели или она вышла за макс. радиус)
        if (stickyTarget == null || !stickyTarget.isAlive() || mc.player.distanceTo(stickyTarget) > maxRange.get()) {
            Entity found = TargetUtils.get(this::entityCheck, SortPriority.ClosestAngle);
            if (found instanceof LivingEntity living) {
                stickyTarget = living;
                targetLockTime = System.currentTimeMillis();
                if (logAntiCheat.get()) logs.add("[AI] Locked target: " + living.getName().getString());
            }
        }

        if (stickyTarget == null) {
            releaseManagedKeys();
            return;
        }

        double dist = mc.player.distanceTo(stickyTarget);
        if (dist < minRange.get()) {
            // Слишком близко - не крутим камеру (защита от 180 snap)
            return;
        }

        // Реакция человека (задержка перед наводкой)
        if (System.currentTimeMillis() - targetLockTime < reactionTime.get()) return;

        aim(mc.player, stickyTarget);
    }

    private void aim(LivingEntity player, Entity target) {
        Vec3 aimPoint = getAimPoint(target);
        float targetYaw = (float) Rotations.getYaw(aimPoint);
        float targetPitch = (float) Rotations.getPitch(aimPoint);

        float yawDifference = Mth.wrapDegrees(targetYaw - player.getYRot());
        float pitchDifference = Mth.wrapDegrees(targetPitch - player.getXRot());

        // Вычисляем скорость (человекоподобная кривая: чем ближе, тем медленнее)
        float distFactor = (float) Math.max(0.2, Math.min(1.0, 10.0 / player.distanceTo(target)));
        float yawSpeed = (aimSpeedMultiplier.get().floatValue() / 10.0f) * distFactor;
        
        float deltaYaw = yawDifference * yawSpeed;
        float deltaPitch = pitchDifference * yawSpeed;

        // ОГРАНИЧИТЕЛЬ (чтобы камера не делала моментальный 180)
        float maxSpeed = maxRotationSpeed.get().floatValue();
        if (Math.abs(deltaYaw) > maxSpeed) deltaYaw = Math.signum(deltaYaw) * maxSpeed;
        if (Math.abs(deltaPitch) > maxSpeed) deltaPitch = Math.signum(deltaPitch) * maxSpeed;

        // Математика с человеческим шумом
        if (humanJitter.get()) {
            deltaYaw += (float) ((Math.random() - 0.5) * jitterAmount.get());
            deltaPitch += (float) ((Math.random() - 0.5) * jitterAmount.get());
        }

        player.setYRot(player.getYRot() + deltaYaw);
        player.setXRot(Mth.clamp(player.getXRot() + deltaPitch, -90f, 90f));

        // Логгируем быстрые повороты
        if (logAntiCheat.get() && System.currentTimeMillis() - lastLogTime > 1000) {
            float dYaw = Mth.wrapDegrees(player.getYRot() - lastYaw);
            if (Math.abs(dYaw) > 20) {
                logs.add(String.format("[TICK] Rot:%.1f | Delta:%.1f | Spd:%.2f", player.getYRot(), dYaw, yawSpeed));
                if (logs.size() > 200) logs.remove(0);
            }
            lastYaw = player.getYRot();
            lastPitch = player.getXRot();
            lastLogTime = System.currentTimeMillis();
        }
    }

    private Vec3 getAimPoint(Entity target) {
        AABB box = target.getBoundingBox();
        if (aimPointMode.get() == AimPointMode.RandomBody) {
            // Рандом по телу, чуть выше центра (грудь)
            double x = box.minX + Math.random() * (box.maxX - box.minX);
            double y = box.minY + target.getBbHeight() * 0.6 + (Math.random() - 0.5) * 0.2;
            double z = box.minZ + Math.random() * (box.maxZ - box.minZ);
            return new Vec3(x, y, z);
        }
        return new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.75, target.getZ());
    }

    private boolean entityCheck(Entity entity) {
        if (entity == mc.player || entity == mc.getCameraEntity()) return false;
        if (!entity.isAlive()) return false;
        if (!PlayerUtils.isWithin(entity, maxRange.get())) return false;
        if (!entities.get().contains(entity.getType())) return false;
        if (ignoreFriends.get() && entity instanceof Player player && !Friends.get().shouldAttack(player)) return false;
        return ignoreWalls.get() || PlayerUtils.canSeeEntity(entity);
    }

    private void releaseManagedKeys() {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    public enum AimPointMode { Center, RandomBody }
}
