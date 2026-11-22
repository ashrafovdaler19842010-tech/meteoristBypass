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

import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class ZKillaura extends Module {
    public enum CriticalMode { None, Smart }
    public enum AutoConfig { None, Polar, Shard }
    public enum OnFallMode { None, Value, RandomValue }
    public enum HitSpeedMode { None, Value, RandomValue }
    public enum ShieldMode { Ignore, Break, None }
    public enum DelayMode { Value, RandomValue }
    private enum ShieldState { Idle, SwapAxe, Attack, SwapBack }

    // ... (все группы настроек sgFilter, sgAttack, sgShield, sgVisual остаются как были) ...

    private final Setting<Boolean> autoSprintAlways = sgAttack.add(new BoolSetting.Builder()
            .name("auto-sprint-always").description("Зажимает спринт.").defaultValue(true).build()
    );
    private final Setting<Boolean> wTap = sgAttack.add(new BoolSetting.Builder()
            .name("w-tap").description("Сброс спринта перед ударом с задержкой 35-50мс.").defaultValue(true).build()
    );
    private final Setting<Integer> wTapMinMs = sgAttack.add(new IntSetting.Builder()
            .name("w-tap-min-ms").description("Минимальная задержка сброса (мс).").defaultValue(35).sliderRange(10, 100).build()
    );
    private final Setting<Integer> wTapMaxMs = sgAttack.add(new IntSetting.Builder()
            .name("w-tap-max-ms").description("Максимальная задержка сброса (мс).").defaultValue(50).sliderRange(10, 150).build()
    );

    private final Random random = new Random();
    
    // Состояние для отложенного W-Tap
    private long wTapReleaseTime = 0;
    private boolean isWTapPending = false;
    private LivingEntity pendingTarget = null;
    
    // ... (остальные поля: randomOnFallFloat, randomHitSpeedFloat, randomDelayTicks, lastKillauraTarget, killauraTargetTicks, shieldState, swapTimer, hitTimer, swapBackTimer, originalSlot) ...

    public ZKillaura() {
        super(Meteorist.CATEGORY, "z-kill-aura", "Killaura with delayed W-Tap and AC bypass.");
    }

    @Override
    public void onActivate() {
        // ... (сброс переменных)
        isWTapPending = false;
        pendingTarget = null;
        applyAutoConfig();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // 1. ОБРАБОТКА ОТЛОЖЕННОГО W-TAP УДАРА
        if (isWTapPending) {
            if (System.currentTimeMillis() >= wTapReleaseTime) {
                // Время вышло (прошло 35-50 мс), наносим удар
                if (pendingTarget != null && mc.world.getEntityById(pendingTarget.getId()) != null) {
                    mc.interactionManager.attackEntity(mc.player, pendingTarget);
                    if (swingHand.get()) mc.player.swingHand(Hand.MAIN_HAND);
                }
                // Возвращаем спринт
                if (sprintState) {
                    mc.options.sprintKey.setPressed(true);
                    mc.player.setSprinting(true);
                }
                isWTapPending = false;
                pendingTarget = null;
            }
            return; // Ждём завершения таймера, не ищем новых целей
        }

        // 2. Автоспринт (если не в процессе сброса)
        if (autoSprintAlways.get() && mc.player.isOnGround() && mc.player.input.hasForwardMovement() && !mc.player.isUsingItem()) {
            mc.player.setSprinting(true);
        }

        // ... (проверка onlySword, поиск цели через MeteoristUtils.getCrosshairTarget) ...

        // ... (проверки firstHitDelay, attackRange, throughWalls) ...

        // ... (Логика щита ShieldMode.Break остаётся, но внутри attackState вызов удара тоже нужно заменить на запуск отложенного W-Tap по аналогии) ...

        // 3. ПРОВЕРКА КРИТОВ (Smart)
        OnFallMode currOnFallMode = onFallMode.get();
        HitSpeedMode currHitSpeedMode = hitSpeedMode.get();

        if (criticalMode.get() == CriticalMode.Smart) {
            if (!mc.player.isOnGround() && mc.player.fallDistance > 0.1f && mc.player.getAttackCooldownProgress(0.5f) > 0.7f) {
                // крит готов
            } else if (mc.player.distanceTo(entity) > 3.5f) {
                // догоняем - бьем без прыжка
            } else {
                return; 
            }
        } else if (currOnFallMode != OnFallMode.None) {
            float onFall = currOnFallMode == OnFallMode.Value ? onFallValue.get().floatValue() : randomOnFallFloat;
            if (!(mc.player.fallDistance > onFall)) return;
        }

        // ... (проверка rotationSpeedLimit, hitDelayRandomness, hitSpeedMode) ...

        // 4. ЗАПУСК W-TAP
        if (wTap.get() && mc.player.isSprinting()) {
            // Отпускаем спринт СЕЙЧАС
            sprintState = true;
            mc.options.sprintKey.setPressed(false);
            mc.player.setSprinting(false);
            
            // Запоминаем цель и вычисляем случайную задержку 35-50 мс
            pendingTarget = livingEntity;
            isWTapPending = true;
            int delayMs = wTapMinMs.get() + random.nextInt(wTapMaxMs.get() - wTapMinMs.get() + 1);
            wTapReleaseTime = System.currentTimeMillis() + delayMs;
            
            return; // Выходим, удар произойдёт в следующем тике после таймера
        } else {
            // Если W-Tap выключен, бьем сразу
            mc.interactionManager.attackEntity(mc.player, livingEntity);
            if (swingHand.get()) mc.player.swingHand(Hand.MAIN_HAND);
        }

        // ... (обновление рандомных значений) ...
    }

    // ... (applyAutoConfig, resetShield, calculateDelay, entityCheck остаются как были, но в applyAutoConfig для Shard можно выставить wTap = false, если Shard всё равно палит) ...
}
