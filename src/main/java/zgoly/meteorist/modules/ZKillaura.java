package zgoly.meteorist.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules; // ВОТ ЭТОТ ИМПОРТ БЫЛ ПРОПУЩЕН
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

import java.util.Random;
import java.util.Set;

public class ZKillaura extends Module {
    public enum OnFallMode { None, Value, RandomValue }
    public enum HitSpeedMode { None, Value, RandomValue }
    public enum ShieldMode { Ignore, Break, None }
    public enum DelayMode { Value, RandomValue }
    private enum ShieldState { Idle, SwapAxe, Attack, SwapBack }

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

    // --- ATTACK (ОРИГИНАЛЬНЫЕ НАСТРОЙКИ) ---
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

    // --- SHIELD ---
    private final Setting<ShieldMode> shieldMode = sgShield.add(new EnumSetting.Builder<ShieldMode>()
            .name("shield-mode").description("Will try and use an axe to break target shields.").defaultValue(ShieldMode.Break).build()
    );
    private final Setting<DelayMode> swapDelayMode = sgShield.add(new EnumSetting.Builder<DelayMode>()
            .name("swap-delay-mode").description("Selects a delay before swapping.").defaultValue(DelayMode.Value).visible(() -> shieldMode.get() == ShieldMode.Break).build()
    );
    private final Setting<Integer> swapDelayValue = sgShield.add(new IntSetting.Builder()
            .name("swap-delay-value").description("Defines a specific delay (in ticks) before swapping.").defaultValue(2).sliderRange(0, 20).visible(() -> shieldMode.get() == ShieldMode.Break && swapDelayMode.get() == DelayMode.Value).build()
    );
    private final Setting<DelayMode> hitDelayMode = sgShield.add(new EnumSetting.Builder<DelayMode>()
            .name("hit-delay-mode").description("Selects a delay before hitting.").defaultValue(DelayMode.Value).visible(() -> shieldMode.get() == ShieldMode.Break).build()
    );
    private final Setting<Integer> hitDelayValue = sgShield.add(new IntSetting.Builder()
            .name("hit-delay-value").description("Defines a specific delay (in ticks) before hitting.").defaultValue(2).sliderRange(0, 20).visible(() -> shieldMode.get() == ShieldMode.Break && hitDelayMode.get() == DelayMode.Value).build()
    );

    // --- VISUAL ---
    private final Setting<Boolean> swingHand = sgVisual.add(new BoolSetting.Builder()
            .name("swing-hand").description("Makes hand swing visible client-side.").defaultValue(true).build()
    );
    private final Setting<Boolean> onlySword = sgFilter.add(new BoolSetting.Builder()
            .name("only-sword").description("Attack only when holding a sword.").defaultValue(false).build()
    );

    float randomOnFallFloat = 0;
    float randomHitSpeedFloat = 0;
    private ShieldState shieldState = ShieldState.Idle;
    private int swapTimer = 0, hitTimer = 0;
    private int originalSlot = -1;
    
    private final Random random = new Random();

    public ZKillaura() {
        super(Meteorist.CATEGORY, "z-kill-aura", "Killaura with original settings.");
    }

    @Override
    public void onActivate() {
        randomOnFallFloat = 0;
        randomHitSpeedFloat = 0;
        shieldState = ShieldState.Idle;
        swapTimer = hitTimer = 0;
        originalSlot = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (onlySword.get() && !mc.player.getMainHandStack().isIn(ItemTags.SWORDS)) return;

        HitResult hitResult = MeteoristUtils.getCrosshairTarget(mc.player, range.get(), ignoreWalls.get(), this::entityCheck);
        if (hitResult == null || hitResult.getType() != HitResult.Type.ENTITY) return;

        Entity entity = ((EntityHitResult) hitResult).getEntity();
        LivingEntity livingEntity = (LivingEntity) entity;

        // Логика щита
        if (shieldMode.get() == ShieldMode.Break) {
            if (shieldState == ShieldState.Idle && livingEntity.isBlocking()) {
                FindItemResult axe = InvUtils.findInHotbar(stack -> stack.getItem() instanceof AxeItem);
                if (axe.found()) {
                    shieldState = ShieldState.SwapAxe;
                    swapTimer = swapDelayValue.get();
                }
            }
            if (shieldState == ShieldState.SwapAxe) {
                if (swapTimer-- > 0) return;
                originalSlot = mc.player.getInventory().getSelectedSlot();
                InvUtils.swap(InvUtils.findInHotbar(stack -> stack.getItem() instanceof AxeItem).slot(), false);
                shieldState = ShieldState.Attack;
                hitTimer = hitDelayValue.get();
            }
            if (shieldState == ShieldState.Attack) {
                if (hitTimer-- > 0) return;
                if (livingEntity.isBlocking()) {
                    // Передаем удар в ZAimbot
                    ZAimbot aimbot = Modules.get().get(ZAimbot.class);
                    if (aimbot != null) aimbot.performWTap(livingEntity);
                    shieldState = ShieldState.Idle;
                } else {
                    InvUtils.swap(originalSlot, false);
                    shieldState = ShieldState.Idle;
                }
                return;
            }
        }

        // Оригинальный Fall Mode
        OnFallMode currOnFallMode = onFallMode.get();
        if (currOnFallMode != OnFallMode.None) {
            float onFall = currOnFallMode == OnFallMode.Value ? onFallValue.get().floatValue() : randomOnFallFloat;
            if (!(mc.player.fallDistance > onFall)) return;
        }

        // Оригинальный Hit Speed Mode
        HitSpeedMode currHitSpeedMode = hitSpeedMode.get();
        float hitSpeed = currHitSpeedMode == HitSpeedMode.Value ? hitSpeedValue.get().floatValue() : randomHitSpeedFloat;
        if (currHitSpeedMode != HitSpeedMode.None && (mc.player.getAttackCooldownProgress(hitSpeed) * 17.0F) < 16) return;

        // Проверка угла
        float yawToTarget = (float) Math.toDegrees(Math.atan2(livingEntity.getZ() - mc.player.getZ(), livingEntity.getX() - mc.player.getX())) - 90f;
        float yawDiff = Math.abs(MathHelper.wrapDegrees(mc.player.getYaw() - yawToTarget));
        if (yawDiff > 75.0) return;

        // Передаем удар в ZAimbot (там сидит W-Tap и спринт)
        ZAimbot aimbot = Modules.get().get(ZAimbot.class);
        if (aimbot != null) aimbot.performWTap(livingEntity);
        
        // Обновление рандомных значений
        if (currOnFallMode == OnFallMode.RandomValue) {
            float min = Math.min(onFallMinRandomValue.get().floatValue(), onFallMaxRandomValue.get().floatValue());
            float max = Math.max(onFallMinRandomValue.get().floatValue(), onFallMaxRandomValue.get().floatValue());
            randomOnFallFloat = min + mc.world.random.nextFloat() * (max - min);
        }
        if (currHitSpeedMode == HitSpeedMode.RandomValue) {
            float min = Math.min(hitSpeedMinRandomValue.get().floatValue(), hitSpeedMaxRandomValue.get().floatValue());
            float max = Math.max(hitSpeedMinRandomValue.get().floatValue(), hitSpeedMaxRandomValue.get().floatValue());
            randomHitSpeedFloat = min + mc.world.random.nextFloat() * (max - min);
        }
    }

    private boolean entityCheck(Entity entity) {
        if (entity.equals(mc.player) || entity.equals(mc.cameraEntity)) return false;
        if (!entity.isAlive()) return false;
        if (!entities.get().contains(entity.getType())) return false;
        if (ignoreNamed.get() && entity.hasCustomName()) return false;
        if (ignoreTamed.get()) {
            if (entity instanceof Tameable tameable && tameable.getOwner() != null && tameable.getOwner().equals(mc.player)) return false;
        }
        if (ignorePassive.get()) {
            switch (entity) {
                case EndermanEntity enderman when !enderman.isAngry() -> { return false; }
                case ZombifiedPiglinEntity piglin when !piglin.isAttacking() -> { return false; }
                case WolfEntity wolf when !wolf.isAttacking() -> { return false; }
                default -> {}
            }
        }
        if (entity instanceof PlayerEntity player) {
            if (ignoreCreative.get() && player.isCreative()) return false;
            if (ignoreFriends.get() && !Friends.get().shouldAttack(player)) return false;
            if (shieldMode.get() == ShieldMode.Ignore && player.isBlocking()) return false;
        }
        if (entity instanceof AnimalEntity animal) {
            return switch (mobAgeFilter.get()) {
                case Baby -> animal.isBaby();
                case Adult -> !animal.isBaby();
                case Both -> true;
            };
        }
        return true;
    }
}
