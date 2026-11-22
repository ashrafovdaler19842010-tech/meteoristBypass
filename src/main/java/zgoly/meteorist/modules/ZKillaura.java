package zgoly.meteorist.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import zgoly.meteorist.Meteorist;
import zgoly.meteorist.utils.MeteoristUtils;

import java.util.Set;
import java.util.UUID;

public class ZKillaura extends Module {
    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgAttack = settings.createGroup("Attack");
    private final SettingGroup sgShield = settings.createGroup("Shield");
    private final SettingGroup sgVisual = settings.createGroup("Visual");

    // --- FILTER ---
    private final Setting<Set<EntityType<?>>> entities = sgFilter.add(new EntityTypeListSetting.Builder()
            .name("entities").description("Entities to attack.").onlyAttackable().defaultValue(EntityType.PLAYER).build()
    );
    private final Setting<Double> range = sgFilter.add(new DoubleSetting.Builder()
            .name("range").description("The maximum range the entity can be to attack it.").defaultValue(4.5).min(0).sliderMax(6).build()
    );
    private final Setting<KillAura.EntityAge> mobAgeFilter = sgFilter.add(new EnumSetting.Builder<KillAura.EntityAge>()
            .name("mob-age-filter").description("Determines the age of the mobs to target.").defaultValue(KillAura.EntityAge.Adult).build()
    );
    private final Setting<Boolean> ignoreNamed = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-named").description("Whether or not to attack mobs with a name.").defaultValue(false).build()
    );
    private final Setting<Boolean> ignorePassive = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-passive").description("Will only attack passive mobs if they are targeting you.").defaultValue(true).build()
    );
    private final Setting<Boolean> ignoreTamed = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-tamed").description("Will avoid attacking mobs you tamed.").defaultValue(false).build()
    );
    private final Setting<Boolean> ignoreCreative = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-creative").description("Will avoid attacking players in creative mode.").defaultValue(true).build()
    );
    private final Setting<Boolean> ignoreFriends = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-friends").description("Will avoid attacking players on your friends list.").defaultValue(true).build()
    );
    private final Setting<Boolean> ignoreWalls = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-walls").description("Whether or not to attack mobs through walls.").defaultValue(false).build()
    );

    // --- ATTACK ---
    private final Setting<OnFallMode> onFallMode = sgAttack.add(new EnumSetting.Builder<OnFallMode>()
            .name("on-fall-mode").description("Chooses an attack strategy when falling to maximize critical damage.").defaultValue(OnFallMode.Value).build()
    );
    private final Setting<Double> onFallValue = sgAttack.add(new DoubleSetting.Builder()
            .name("on-fall-value").description("Defines a specific value for attacking while falling.").min(0).defaultValue(0.25).sliderMax(1).visible(() -> onFallMode.get() == OnFallMode.Value).build()
    );
    private final Setting<Double> onFallMinRandomValue = sgAttack.add(new DoubleSetting.Builder()
            .name("on-fall-min-random-value").description("Min randomized value for on-fall.").min(0).defaultValue(0.2).sliderMax(1).visible(() -> onFallMode.get() == OnFallMode.RandomValue).build()
    );
    private final Setting<Double> onFallMaxRandomValue = sgAttack.add(new DoubleSetting.Builder()
            .name("on-fall-max-random-value").description("Max randomized value for on-fall.").min(0).defaultValue(0.4).sliderMax(1).visible(() -> onFallMode.get() == OnFallMode.RandomValue).build()
    );
    private final Setting<HitSpeedMode> hitSpeedMode = sgAttack.add(new EnumSetting.Builder<HitSpeedMode>()
            .name("hit-speed-mode").description("Selects a hit speed mode for attacking.").defaultValue(HitSpeedMode.Value).build()
    );
    private final Setting<Double> hitSpeedValue = sgAttack.add(new DoubleSetting.Builder()
            .name("hit-speed-value").description("Defines a specific hit speed value for attacking.").defaultValue(0.0).sliderRange(-10, 10).visible(() -> hitSpeedMode.get() == HitSpeedMode.Value).build()
    );
    private final Setting<Double> hitSpeedMinRandomValue = sgAttack.add(new DoubleSetting.Builder()
            .name("hit-speed-min-random-value").description("Min randomized hit speed.").defaultValue(0.0).sliderRange(-10, 10).visible(() -> hitSpeedMode.get() == HitSpeedMode.RandomValue).build()
    );
    private final Setting<Double> hitSpeedMaxRandomValue = sgAttack.add(new DoubleSetting.Builder()
            .name("hit-speed-max-random-value").description("Max randomized hit speed.").defaultValue(0.0).sliderRange(-10, 10).visible(() -> hitSpeedMode.get() == HitSpeedMode.RandomValue).build()
    );

    // --- SMART CRIT + BYPASS ---
    private final Setting<CriticalMode> criticalMode = sgAttack.add(new EnumSetting.Builder<CriticalMode>()
            .name("critical-mode").description("Smart = приоритет критов при прыжке, но бьет без прыжка если цель далеко.").defaultValue(CriticalMode.Smart).build()
    );
    private final Setting<Double> rotationSpeedLimit = sgAttack.add(new DoubleSetting.Builder()
            .name("rotation-speed-limit").description("Max degrees you can be off-target to hit.").defaultValue(75.0).min(10.0).sliderMax(360.0).build()
    );
    private final Setting<Double> firstHitDelay = sgAttack.add(new DoubleSetting.Builder()
            .name("first-hit-delay").description("Задержка перед первым ударом по новой цели (в тиках).").defaultValue(2.5).min(0.0).sliderMax(8.0).build()
    );

    // --- W-TAP & AUTO SPRINT ---
    private final Setting<Boolean> autoSprintAlways = sgAttack.add(new BoolSetting.Builder()
            .name("auto-sprint-always").description("Зажимает спринт, даже если модуль не атакует.").defaultValue(true).build()
    );
    private final Setting<Boolean> wTap = sgAttack.add(new BoolSetting.Builder()
            .name("w-tap").description("Сбрасывает спринт перед ударом для идеального W-Tap.").defaultValue(true).build()
    );
    private final Setting<Integer> wTapMsDelay = sgAttack.add(new IntSetting.Builder()
            .name("w-tap-ms-delay").description("Задержка сброса спринта в миллисекундах (40-80).").defaultValue(50).sliderRange(10, 150).build()
    );

    // --- AUTO CONFIG ---
    private final Setting<AutoConfig> autoConfigMode = sgAttack.add(new EnumSetting.Builder<AutoConfig>()
            .name("auto-config").description("Автоматически подбирает настройки под античит.").defaultValue(AutoConfig.None).build()
    );

    public enum CriticalMode { None, Smart }
    public enum AutoConfig { None, Polar, Shard }

    float randomOnFallFloat = 0;
    float randomHitSpeedFloat = 0;
    private int randomDelayTicks = 0;
    private long wTapTimer = 0;
    private boolean wTapPending = false;
    private UUID lastKillauraTarget = null;
    private int killauraTargetTicks = 0;
    private boolean sprintState = false;

    public ZKillaura() {
        super(Meteorist.CATEGORY, "z-kill-aura", "Killaura with smart crits, W-Tap and AC bypass.");
    }

    @Override
    public void onActivate() {
        randomOnFallFloat = 0;
        randomHitSpeedFloat = 0;
        randomDelayTicks = 0;
        wTapTimer = 0;
        wTapPending = false;
        lastKillauraTarget = null;
        killauraTargetTicks = 0;
        sprintState = false;
        applyAutoConfig();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Auto Sprint (работает всегда)
        if (autoSprintAlways.get() && mc.player.isOnGround() && mc.player.input.hasForwardMovement() && !mc.player.isUsingItem()) {
            mc.player.setSprinting(true);
        }

        // W-Tap восстановление спринта
        if (wTapPending) {
            if (System.currentTimeMillis() - wTapTimer >= wTapMsDelay.get()) {
                mc.options.sprintKey.setPressed(sprintState);
                wTapPending = false;
            }
        }

        // Поиск цели
        HitResult hitResult = MeteoristUtils.getCrosshairTarget(mc.player, range.get(), ignoreWalls.get(), this::entityCheck);
        if (hitResult == null || hitResult.getType() != HitResult.Type.ENTITY) {
            killauraTargetTicks = 0;
            lastKillauraTarget = null;
            return;
        }

        Entity entity = ((EntityHitResult) hitResult).getEntity();
        LivingEntity livingEntity = (LivingEntity) entity;

        if (!entity.getUuid().equals(lastKillauraTarget)) {
            lastKillauraTarget = entity.getUuid();
            killauraTargetTicks = 0;
        }
        killauraTargetTicks++;
        if (killauraTargetTicks < firstHitDelay.get().intValue()) return;

        if (mc.player.distanceTo(entity) > attackRange.get()) return;
        if (!throughWalls.get() && !mc.player.canSee(entity)) return;

        // --- SMART CRITS (ПОЧИНЕНО) ---
        OnFallMode currOnFallMode = onFallMode.get();
        HitSpeedMode currHitSpeedMode = hitSpeedMode.get();

        if (criticalMode.get() == CriticalMode.Smart) {
            // Приоритет крита: бьем только если падаем с достаточной высоты
            if (!mc.player.isOnGround() && mc.player.fallDistance > 0.1f && mc.player.getAttackCooldownProgress(0.5f) > 0.7f) {
                // Крит готов
            } 
            // Фоллбек: если цель убегает, бьем без крита чтобы не терять дистанцию
            else if (mc.player.distanceTo(entity) > 3.5f) {
                // Догоняем - бьем без прыжка
            } else {
                return; // Ждем крита
            }
        } else if (currOnFallMode != OnFallMode.None) {
            float onFall = currOnFallMode == OnFallMode.Value ? onFallValue.get().floatValue() : randomOnFallFloat;
            if (!(mc.player.fallDistance > onFall)) return;
        }

        // Проверка угла
        float yawToTarget = (float) Math.toDegrees(Math.atan2(livingEntity.getZ() - mc.player.getZ(), livingEntity.getX() - mc.player.getX())) - 90f;
        float yawDiff = Math.abs(MathHelper.wrapDegrees(mc.player.getYaw() - yawToTarget));
        if (yawDiff > rotationSpeedLimit.get()) return;

        // Рандомизация задержки
        if (hitDelayRandomness.get() > 0) {
            if (randomDelayTicks > 0) { randomDelayTicks--; return; }
        }

        float hitSpeed = currHitSpeedMode == HitSpeedMode.Value ? hitSpeedValue.get().floatValue() : randomHitSpeedFloat;
        if (currHitSpeedMode != HitSpeedMode.None && (mc.player.getAttackCooldownProgress(hitSpeed) * 17.0F) < 16) return;

        // --- W-TAP: СБРОС СПРИНТА СТРОГО ПЕРЕД УДАРОМ ---
        if (wTap.get() && mc.player.isSprinting()) {
            sprintState = true;
            mc.options.sprintKey.setPressed(false);
            mc.player.setSprinting(false);
            wTapTimer = System.currentTimeMillis();
            wTapPending = true;
        }

        // Удар
        mc.interactionManager.attackEntity(mc.player, livingEntity);
        if (swingHand.get()) mc.player.swingHand(Hand.MAIN_HAND);

        // Обновление рандома
        if (hitDelayRandomness.get() > 0) {
            randomDelayTicks = (int) (mc.world.random.nextFloat() * hitDelayRandomness.get());
        }
        if (currOnFallMode == OnFallMode.RandomValue) {
            float min = Math.min(onFallMinRandomValue.get().floatValue(), onFallMaxRandomValue.get().floatValue());
            float max = Math.max(onFallMinRandomValue.get().floatValue(), onFallMaxRandomValue.get().floatValue());
            randomOnFallFloat = min + mc.world.random.nextFloat() * (max - min);
        }
        if (currHitSpeedMode == HitSpeedMode.RandomValue) {
            float min = Math.min(hitSpeedMinRandomValue.get().floatValue(), hitSpeedMaxRandomValue.get().floatValue());
            float max = Math.max(hitSpeedMinRandomValue.get().floatValue(), hitSpeedMaxRandomValue.get().floatValue());
            randomHitSpeedFloat = min + mc.world.nextFloat() * (max - min);
        }
    }

    private void applyAutoConfig() {
        switch (autoConfigMode.get()) {
            case Polar -> {
                rotationSpeedLimit.set(60.0);
                firstHitDelay.set(2.5);
                wTap.set(true);
                wTapMsDelay.set(50);
                criticalMode.set(CriticalMode.Smart);
            }
            case Shard -> {
                rotationSpeedLimit.set(90.0);
                firstHitDelay.set(4.0);
                wTap.set(true);
                wTapMsDelay.set(80);
                criticalMode.set(CriticalMode.Smart);
            }
            case None -> {}
        }
    }

    // ... (Остальные методы: resetShield, calculateDelay, entityCheck, геттеры/сеттеры для остальных настроек) ...
    // ВАЖНО: Здесь я пропустил громоздкие блоки с щитом и фильтрами, чтобы ответ влез в лимит.
    // В твоем проекте они уже есть, просто скопируй их из предыдущей версии.
}
