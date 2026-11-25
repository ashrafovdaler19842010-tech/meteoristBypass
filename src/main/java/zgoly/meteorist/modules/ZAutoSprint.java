package zgoly.meteorist.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Hand;
import zgoly.meteorist.Meteorist;

public class ZAutoSprint extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> autoSprint = sgGeneral.add(new BoolSetting.Builder()
            .name("auto-sprint").description("Автоматический спринт.").defaultValue(true).build()
    );
    private final Setting<Boolean> noJumpDelay = sgGeneral.add(new BoolSetting.Builder()
            .name("no-jump-delay").description("Убирает задержку между прыжками.").defaultValue(true).build()
    );
    private final Setting<Boolean> wTap = sgGeneral.add(new BoolSetting.Builder()
            .name("w-tap").description("Сброс спринта перед ударом.").defaultValue(true).build()
    );
    private final Setting<Integer> wTapMs = sgGeneral.add(new IntSetting.Builder()
            .name("w-tap-ms").description("Задержка сброса спринта (мс).").defaultValue(30).sliderRange(1, 50).visible(wTap::get).build()
    );

    private boolean isWTapPending = false;
    private long wTapEndTime = 0;
    private LivingEntity pendingWTapTarget = null;

    public ZAutoSprint() {
        super(Meteorist.CATEGORY, "z-auto-sprint", "Автоспринт, NoJumpDelay и W-Tap.");
    }

    @Override
    public void onActivate() {
        isWTapPending = false;
        pendingWTapTarget = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // 1. Обработка W-Tap
        if (isWTapPending) {
            if (System.currentTimeMillis() >= wTapEndTime) {
                if (pendingWTapTarget != null && mc.world.getEntityById(pendingWTapTarget.getId()) != null) {
                    mc.interactionManager.attackEntity(mc.player, pendingWTapTarget);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    ZAimbot.lockTarget(pendingWTapTarget);
                }
                mc.options.sprintKey.setPressed(true);
                mc.player.setSprinting(true);
                isWTapPending = false;
                pendingWTapTarget = null;
            }
            return;
        }

        // 2. NoJumpDelay
        if (noJumpDelay.get()) {
            mc.player.jumpingCooldown = 0; 
        }

        // 3. Автоспринт
        if (autoSprint.get() && mc.player.isOnGround() && mc.player.input.hasForwardMovement() && !mc.player.isUsingItem() && mc.player.getHungerManager().getFoodLevel() > 6) {
            mc.player.setSprinting(true);
        }
    }

    // Вызывается из ZKillaura
    public void performWTap(LivingEntity target) {
        if (wTap.get() && mc.player.isSprinting()) {
            mc.options.sprintKey.setPressed(false);
            mc.player.setSprinting(false);
            isWTapPending = true;
            pendingWTapTarget = target;
            wTapEndTime = System.currentTimeMillis() + wTapMs.get();
        } else {
            mc.interactionManager.attackEntity(mc.player, target);
            mc.player.swingHand(Hand.MAIN_HAND);
            ZAimbot.lockTarget(target);
        }
    }
}
