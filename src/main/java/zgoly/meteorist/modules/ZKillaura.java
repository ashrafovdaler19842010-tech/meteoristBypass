package zgoly.meteorist.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import zgoly.meteorist.Meteorist;
import zgoly.meteorist.utils.MeteoristUtils;

import java.util.Set;

public class ZKillaura extends Module {
    public enum OnFallMode { None, Value, RandomValue }
    public enum HitSpeedMode { None, Value, RandomValue }
    public enum ShieldMode { Ignore, Break, None }
    private enum ShieldState { Idle, SwapAxe, Attack }

    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgAttack = settings.createGroup("Attack");
    private final SettingGroup sgShield = settings.createGroup("Shield");

    private final Setting<Set<EntityType<?>>> entities = sgFilter.add(new EntityTypeListSetting.Builder()
            .name("entities").description("Entities to attack.").onlyAttackable().defaultValue(EntityType.PLAYER).build()
    );
    private final Setting<Double> range = sgFilter.add(new DoubleSetting.Builder()
            .name("range").description("Range.").defaultValue(4.5).min(0).sliderMax(6).build()
    );
    private final Setting<Boolean> ignoreFriends = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-friends").defaultValue(true).build()
    );
    private final Setting<Boolean> onlySword = sgFilter.add(new BoolSetting.Builder()
            .name("only-sword").defaultValue(false).build()
    );

    private final Setting<OnFallMode> onFallMode = sgAttack.add(new EnumSetting.Builder<OnFallMode>()
            .name("on-fall-mode").defaultValue(OnFallMode.Value).build()
    );
    private final Setting<Double> onFallValue = sgAttack.add(new DoubleSetting.Builder()
            .name("on-fall-value").min(0).defaultValue(0.25).sliderMax(1).visible(() -> onFallMode.get() == OnFallMode.Value).build()
    );
    private final Setting<HitSpeedMode> hitSpeedMode = sgAttack.add(new EnumSetting.Builder<HitSpeedMode>()
            .name("hit-speed-mode").defaultValue(HitSpeedMode.Value).build()
    );
    private final Setting<Double> hitSpeedValue = sgAttack.add(new DoubleSetting.Builder()
            .name("hit-speed-value").defaultValue(0.0).sliderRange(-10, 10).visible(() -> hitSpeedMode.get() == HitSpeedMode.Value).build()
    );

    private final Setting<ShieldMode> shieldMode = sgShield.add(new EnumSetting.Builder<ShieldMode>()
            .name("shield-mode").defaultValue(ShieldMode.Break).build()
    );
    private final Setting<Integer> swapDelayValue = sgShield.add(new IntSetting.Builder()
            .name("swap-delay-value").defaultValue(2).sliderRange(0, 20).visible(() -> shieldMode.get() == ShieldMode.Break).build()
    );

    private ShieldState shieldState = ShieldState.Idle;
    private int swapTimer = 0;

    public ZKillaura() {
        super(Meteorist.CATEGORY, "z-kill-aura", "Fast Triggerbot.");
    }

    @Override
    public void onActivate() {
        shieldState = ShieldState.Idle;
        swapTimer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (onlySword.get() && !mc.player.getMainHandStack().isIn(ItemTags.SWORDS)) return;

        HitResult hitResult = MeteoristUtils.getCrosshairTarget(mc.player, range.get(), false, this::entityCheck);
        if (hitResult == null || hitResult.getType() != HitResult.Type.ENTITY) return;

        LivingEntity livingEntity = (LivingEntity) ((EntityHitResult) hitResult).getEntity();

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
                int originalSlot = mc.player.getInventory().getSelectedSlot();
                InvUtils.swap(InvUtils.findInHotbar(stack -> stack.getItem() instanceof AxeItem).slot(), false);
                shieldState = ShieldState.Attack;
            }
            if (shieldState == ShieldState.Attack) {
                if (livingEntity.isBlocking()) {
                    ZAutoSprint sprint = Modules.get().get(ZAutoSprint.class);
                    if (sprint != null) sprint.performWTap(livingEntity);
                    shieldState = ShieldState.Idle;
                } else {
                    shieldState = ShieldState.Idle;
                }
                return;
            }
        }

        // Проверка падения (Игнорируется в воде)
        if (onFallMode.get() == OnFallMode.Value) {
            if (!mc.player.isTouchingWater() && !(mc.player.fallDistance > onFallValue.get())) {
                return;
            }
        }

        // Проверка скорости атаки
        if (hitSpeedMode.get() == HitSpeedMode.Value) {
            if ((mc.player.getAttackCooldownProgress(hitSpeedValue.get().floatValue()) * 17.0F) < 16) return;
        }

        // Проверка угла
        float yawToTarget = (float) Math.toDegrees(Math.atan2(livingEntity.getZ() - mc.player.getZ(), livingEntity.getX() - mc.player.getX())) - 90f;
        float yawDiff = Math.abs(MathHelper.wrapDegrees(mc.player.getYaw() - yawToTarget));
        if (yawDiff > 75.0) return;

        // Удар
        ZAutoSprint sprint = Modules.get().get(ZAutoSprint.class);
        if (sprint != null) sprint.performWTap(livingEntity);
    }

    private boolean entityCheck(Entity entity) {
        if (entity.equals(mc.player) || entity.equals(mc.cameraEntity)) return false;
        if (!entity.isAlive()) return false;
        if (!entities.get().contains(entity.getType())) return false;
        if (ignoreFriends.get() && entity instanceof PlayerEntity player && !Friends.get().shouldAttack(player)) return false;
        return true;
    }
}
