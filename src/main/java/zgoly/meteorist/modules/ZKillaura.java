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

public class ZKillaura extends Module {
    public enum CriticalMode { None, Smart }
    public enum ShieldMode { Ignore, Break, None }
    private enum ShieldState { Idle, SwapAxe, Attack, SwapBack }

    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgAttack = settings.createGroup("Attack");
    private final SettingGroup sgShield = settings.createGroup("Shield");

    // --- FILTER ---
    private final Setting<Set<EntityType<?>>> entities = sgFilter.add(new EntityTypeListSetting.Builder()
            .name("entities").description("Entities to attack.").onlyAttackable().defaultValue(EntityType.PLAYER).build()
    );
    private final Setting<Double> range = sgFilter.add(new DoubleSetting.Builder()
            .name("range").description("The maximum range to attack.").defaultValue(4.5).min(0).sliderMax(6).build()
    );
    private final Setting<Boolean> ignoreFriends = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-friends").description("Will avoid attacking friends.").defaultValue(true).build()
    );
    private final Setting<Boolean> ignoreWalls = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-walls").description("Attack through walls.").defaultValue(false).build()
    );

    // --- ATTACK ---
    private final Setting<CriticalMode> criticalMode = sgAttack.add(new EnumSetting.Builder<CriticalMode>()
            .name("critical-mode").description("Smart = ждет падения для крита (если в воздухе).").defaultValue(CriticalMode.Smart).build()
    );
    private final Setting<Boolean> onlySword = sgFilter.add(new BoolSetting.Builder()
            .name("only-sword").description("Attack only when holding a sword.").defaultValue(false).build()
    );
    private final Setting<Boolean> autoSprint = sgAttack.add(new BoolSetting.Builder()
            .name("auto-sprint").description("Автоматически зажимает спринт.").defaultValue(true).build()
    );
    private final Setting<Boolean> wTap = sgAttack.add(new BoolSetting.Builder()
            .name("w-tap").description("Сброс спринта прямо перед ударом.").defaultValue(true).build()
    );

    // --- SHIELD ---
    private final Setting<ShieldMode> shieldMode = sgShield.add(new EnumSetting.Builder<ShieldMode>()
            .name("shield-mode").description("Break shields.").defaultValue(ShieldMode.Break).build()
    );
    private final Setting<Integer> hitDelayValue = sgShield.add(new IntSetting.Builder()
            .name("hit-delay-value").description("Delay before hitting shield.").defaultValue(2).sliderRange(0, 20).visible(() -> shieldMode.get() == ShieldMode.Break).build()
    );

    private ShieldState shieldState = ShieldState.Idle;
    private int hitTimer = 0;

    public ZKillaura() {
        super(Meteorist.CATEGORY, "z-kill-aura", "Killaura with instant W-Tap.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Автоспринт
        if (autoSprint.get() && mc.player.isOnGround() && mc.player.input.hasForwardMovement() && !mc.player.isUsingItem()) {
            mc.player.setSprinting(true);
        }

        if (onlySword.get() && !mc.player.getMainHandStack().isIn(ItemTags.SWORDS)) return;

        HitResult hitResult = MeteoristUtils.getCrosshairTarget(mc.player, range.get(), ignoreWalls.get(), this::entityCheck);
        if (hitResult == null || hitResult.getType() != HitResult.Type.ENTITY) return;

        Entity entity = ((EntityHitResult) hitResult).getEntity();
        LivingEntity livingEntity = (LivingEntity) entity;

        // Проверка на неуязвимость
        if (livingEntity.hurtTime > 0 || livingEntity.timeUntilRegen > 0) return;

        // Логика щита
        if (shieldMode.get() == ShieldMode.Break) {
            if (shieldState == ShieldState.Idle && livingEntity.isBlocking()) {
                FindItemResult axe = InvUtils.findInHotbar(stack -> stack.getItem() instanceof AxeItem);
                if (axe.found()) {
                    shieldState = ShieldState.Attack;
                    hitTimer = hitDelayValue.get();
                    InvUtils.swap(axe.slot(), false);
                }
            }
            if (shieldState == ShieldState.Attack) {
                if (hitTimer-- > 0) return;
                if (livingEntity.isBlocking()) {
                    doAttack(livingEntity);
                    shieldState = ShieldState.Idle; // Сброс
                } else {
                    shieldState = ShieldState.Idle;
                }
                return;
            }
        }

        // Кулдаун атаки
        if ((mc.player.getAttackCooldownProgress(0.5f) * 17.0F) < 16) return;

        // Проверка критов
        if (criticalMode.get() == CriticalMode.Smart) {
            if (!mc.player.isOnGround() && mc.player.fallDistance <= 0) {
                return; // Ждем падения для крита
            }
        }

        // Удар
        doAttack(livingEntity);
        ZAimbot.lockTarget(livingEntity); // Лочим цель в аиме на 5 сек
    }

    private void doAttack(LivingEntity target) {
        // Мгновенный W-Tap
        boolean wasSprinting = mc.player.isSprinting();
        if (wTap.get() && wasSprinting) {
            mc.player.setSprinting(false);
            mc.options.sprintKey.setPressed(false);
        }

        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);

        if (wTap.get() && wasSprinting) {
            mc.player.setSprinting(true);
            mc.options.sprintKey.setPressed(true);
        }
    }

    private boolean entityCheck(Entity entity) {
        if (entity.equals(mc.player) || entity.equals(mc.cameraEntity)) return false;
        if (!entity.isAlive()) return false;
        if (!entities.get().contains(entity.getType())) return false;
        if (ignoreFriends.get() && entity instanceof PlayerEntity player && !Friends.get().shouldAttack(player)) return false;
        return true;
    }
}
