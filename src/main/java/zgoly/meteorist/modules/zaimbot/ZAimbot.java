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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import zgoly.meteorist.Meteorist;
import zgoly.meteorist.utils.config.MeteoristConfigManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static meteordevelopment.meteorclient.gui.renderer.GuiRenderer.COPY;
import static zgoly.meteorist.utils.MeteoristUtils.calculateFov;

public class ZAimbot extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAim = settings.createGroup("Aim");
    private final SettingGroup sgHuman = settings.createGroup("Humanizer");
    private final SettingGroup sgLogger = settings.createGroup("Anti-Cheat Logger");

    // --- General / Filter ---
    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
            .name("entities").description("Entities to aim at.").onlyAttackable().defaultValue(EntityTypes.PLAYER).build()
    );
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range").description("Distance to rotate camera.").defaultValue(4.5).min(0).sliderMax(6).build()
    );
    private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
            .name("ignore-friends").defaultValue(true).build()
    );
    private final Setting<Boolean> ignoreWalls = sgGeneral.add(new BoolSetting.Builder()
            .name("ignore-walls").defaultValue(false).build()
    );

    // --- Aim ---
    public enum AimPointMode { Head, Body, Feet, Random }
    private final Setting<AimPointMode> aimPoint = sgAim.add(new EnumSetting.Builder<AimPointMode>()
            .name("aim-point").description("Where to aim on the target.").defaultValue(AimPointMode.Head).build()
    );
    private final Setting<Double> speedYaw = sgAim.add(new DoubleSetting.Builder()
            .name("speed-yaw").description("Speed for horizontal aim. (Matches original)").min(0.1).defaultValue(4.0).sliderRange(0.1, 10).build()
    );
    private final Setting<Double> speedPitch = sgAim.add(new DoubleSetting.Builder()
            .name("speed-pitch").description("Speed for vertical aim.").min(0.1).defaultValue(4.0).sliderRange(0.1, 10).build()
    );
    private final Setting<Boolean> syncYawWithCooldown = sgAim.add(new BoolSetting.Builder()
            .name("sync-yaw").description("Syncs horizontal speed with attack cooldown.").defaultValue(false).build()
    );
    private final Setting<Boolean> syncPitchWithCooldown = sgAim.add(new BoolSetting.Builder()
            .name("sync-pitch").description("Syncs vertical speed with attack cooldown.").defaultValue(false).build()
    );

    // --- Humanizer ---
    private final Setting<Boolean> humanJitter = sgHuman.add(new BoolSetting.Builder()
            .name("human-jitter").description("Adds random mouse tremor.").defaultValue(true).build()
    );
    private final Setting<Double> jitterAmount = sgHuman.add(new DoubleSetting.Builder()
            .name("jitter-amount").description("Strength of tremor.").defaultValue(0.5).min(0.1).sliderMax(3.0).visible(humanJitter::get).build()
    );
    private final Setting<Integer> reactionTime = sgHuman.add(new IntSetting.Builder()
            .name("reaction-time").description("Delay before aiming at a new target (ms).").defaultValue(50).sliderRange(0, 200).build()
    );

    // --- Logger ---
    private final Setting<Boolean> logAntiCheat = sgLogger.add(new BoolSetting.Builder()
            .name("log-anticheat").description("Logs rotations and setbacks.").defaultValue(true).build()
    );
    private final List<String> logs = new ArrayList<>();
    private float lastYaw = 0;
    private float lastPitch = 0;
    private long lastLogTime = 0;

    private final List<AimTrajectory> trajectories = new ArrayList<>();
    private LivingEntity stickyTarget = null;
    private long targetLockTime = 0;

    public ZAimbot() {
        super(Meteorist.CATEGORY, "z-aimbot", "Smart aimbot based on Meteorist original.");
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

        // --- GUI Логгера и Автоконфига ---
        list.add(theme.label("Anti-Cheat Logger & AI"));
        WContainer logControls = list.add(theme.horizontalList()).expandX().widget();
        
        WButton copyBtn = logControls.add(theme.button("Copy Logs")).expandX().widget();
        copyBtn.action = () -> {
            if (!logs.isEmpty()) {
                mc.keyboardHandler.setClipboard(String.join("\n", logs));
                logs.add("[SYSTEM] Logs copied to clipboard.");
            }
        };
        
        WButton clearBtn = logControls.add(theme.button("Clear")).widget();
        clearBtn.action = () -> logs.clear();

        WButton autoCfgBtn = logControls.add(theme.button("Auto-Config")).widget();
        autoCfgBtn.action = this::analyzeAndAutoConfig;

        list.add(theme.label("Logs (" + logs.size() + "):"));
        WVerticalList logList = list.add(theme.verticalList()).expandX().widget();
        int start = Math.max(0, logs.size() - 4); // Показываем последние 4
        for (int i = start; i < logs.size(); i++) {
            logList.add(theme.label(logs.get(i)));
        }
        list.add(theme.horizontalSeparator()).expandX();

        // --- Траектории (Оригинал) ---
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
        WButton removeAll = controls.add(theme.button("Remove All")).expandX().widget();
        removeAll.action = () -> { trajectories.clear(); fillWidget(theme, list); };
        MeteoristConfigManager.configManager(theme, list, this);
    }

    private void analyzeAndAutoConfig() {
        if (logs.isEmpty()) {
            logs.add("[AI] Not enough data to analyze. Play more.");
            return;
        }
        int flags = 0;
        for (String log : logs) {
            if (log.contains("[SETBACK]")) flags++;
        }
        if (flags > 0) {
            // Если были флаги, снижаем скорость и добавляем дрожь
            speedYaw.set(Math.max(1.0, speedYaw.get() - 1.0));
            speedPitch.set(Math.max(1.0, speedPitch.get() - 1.0));
            if (!humanJitter.get()) humanJitter.set(true);
            jitterAmount.set(Math.min(3.0, jitterAmount.get() + 0.5));
            logs.add("[AI] Found " + flags + " flags. Lowered speed, added jitter. Try again!");
        } else {
            logs.add("[AI] No flags detected. Config is safe!");
        }
    }

    @Override
    public void onActivate() {
        stickyTarget = null;
        lastYaw = mc.player != null ? mc.player.getYRot() : 0;
        lastPitch = mc.player != null ? mc.player.getXRot() : 0;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!logAntiCheat.get() || mc.player == null) return;
        
        if (event.packet instanceof ClientboundPlayerPositionPacket) {
            float deltaYaw = Mth.wrapDegrees(mc.player.getYRot() - lastYaw);
            float deltaPitch = Mth.wrapDegrees(mc.player.getXRot() - lastPitch);
            String log = String.format("[SETBACK] Rot:%.1f/%.1f | Delta:%.1f/%.1f | Yspd:%.1f | Pspd:%.1f", 
                    mc.player.getYRot(), mc.player.getXRot(), deltaYaw, deltaPitch, speedYaw.get(), speedPitch.get());
            logs.add(log);
            if (logs.size() > 500) logs.remove(0);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        // Sticky Target Logic
        if (stickyTarget == null || !stickyTarget.isAlive() || mc.player.distanceTo(stickyTarget) > range.get()) {
            Entity found = TargetUtils.get(this::entityCheck, meteordevelopment.meteorclient.utils.entity.SortPriority.ClosestAngle);
            if (found instanceof LivingEntity living) {
                stickyTarget = living;
                targetLockTime = System.currentTimeMillis();
                if (logAntiCheat.get()) logs.add("[AI] Locked: " + living.getName().getString());
            }
        }

        if (stickyTarget == null) return;

        // Задержка реакции (чтобы не снапить мгновенно на нового игрока)
        if (System.currentTimeMillis() - targetLockTime < reactionTime.get()) return;

        aim(mc.player, stickyTarget);

        // Логгируем дельту раз в секунду, если крутимся быстро
        if (logAntiCheat.get() && System.currentTimeMillis() - lastLogTime > 1000) {
            float dYaw = Mth.wrapDegrees(mc.player.getYRot() - lastYaw);
            if (Math.abs(dYaw) > 15) {
                logs.add(String.format("[TICK] Rot:%.1f | Delta:%.1f", mc.player.getYRot(), dYaw));
                if (logs.size() > 500) logs.remove(0);
            }
            lastYaw = mc.player.getYRot();
            lastPitch = mc.player.getXRot();
            lastLogTime = System.currentTimeMillis();
        }
    }

    // ОРИГИНАЛЬНАЯ ЛОГИКА С MATH И GCD
    private void aim(LivingEntity player, Entity target) {
        Vec3 predictedPos = target.position().add(target.getDeltaMovement().scale(0.0)); // Убрал предикт для легитности
        Vec3 aimPoint = getAimPoint(target, predictedPos);
        
        float targetYaw = (float) Rotations.getYaw(aimPoint);
        float targetPitch = (float) Rotations.getPitch(aimPoint);

        float yawDifference = Mth.wrapDegrees(targetYaw - player.getYRot());
        float pitchDifference = Mth.wrapDegrees(targetPitch - player.getXRot());

        float cooldownProgressYaw = syncYawWithCooldown.get() ? mc.player.getAttackStrengthScale(0) : 1;
        float cooldownProgressPitch = syncPitchWithCooldown.get() ? mc.player.getAttackStrengthScale(0) : 1;
        
        float yawSpeedFactor = speedYaw.get().floatValue() / 10;
        float pitchSpeedFactor = speedPitch.get().floatValue() / 10;

        float deltaYaw = yawDifference * cooldownProgressYaw * yawSpeedFactor;
        float deltaPitch = pitchDifference * cooldownProgressPitch * pitchSpeedFactor;

        // ФИКС GCD (Античит требует, чтобы движение мыши было кратно чувствительности)
        float sens = mc.options.getMouseSensitivity().getValue().floatValue();
        float f = sens * 0.6f + 0.2f;
        float gcd = f * f * f * 1.2f; 
        
        deltaYaw = Math.round(deltaYaw / gcd) * gcd;
        deltaPitch = Math.round(deltaPitch / gcd) * gcd;

        // HUMAN JITTER (дрожание рук)
        if (humanJitter.get()) {
            deltaYaw += (float) ((Math.random() - 0.5) * jitterAmount.get());
            deltaPitch += (float) ((Math.random() - 0.5) * jitterAmount.get());
        }

        player.setYRot(player.getYRot() + deltaYaw);
        player.setXRot(Mth.clamp(player.getXRot() + deltaPitch, -90f, 90f));
    }

    private Vec3 getAimPoint(Entity target, Vec3 predictedPos) {
        AABB box = target.getBoundingBox();
        return switch (aimPoint.get()) {
            case Head -> predictedPos.add(0, target.getBbHeight() * 0.9, 0);
            case Body -> predictedPos.add(0, target.getBbHeight() * 0.6, 0);
            case Feet -> predictedPos.add(0, target.getBbHeight() * 0.1, 0);
            case Random -> new Vec3(
                    box.minX + Math.random() * (box.maxX - box.minX),
                    box.minY + Math.random() * (box.maxY - box.minY),
                    box.minZ + Math.random() * (box.maxZ - box.minZ)
            );
        };
    }

    private boolean entityCheck(Entity entity) {
        if (entity == mc.player || entity == mc.getCameraEntity()) return false;
        if (!entity.isAlive()) return false;
        if (!PlayerUtils.isWithin(entity, range.get())) return false;
        if (!entities.get().contains(entity.getType())) return false;
        if (ignoreFriends.get() && entity instanceof Player player && !Friends.get().shouldAttack(player)) return false;
        return ignoreWalls.get() || PlayerUtils.canSeeEntity(entity);
    }
}
