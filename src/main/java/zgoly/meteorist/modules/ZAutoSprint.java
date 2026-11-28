package zgoly.meteorist.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.InteractionHand;
import zgoly.meteorist.Meteorist;

public class ZAutoSprint extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> autoSprint = sgGeneral.add(new BoolSetting.Builder()
            .name("auto-sprint").description("Автоматический спринт.").defaultValue(true).build()
    );
    private final Setting<Boolean> wTap = sgGeneral.add(new BoolSetting.Builder()
            .name("w-tap").description("Сброс спринта перед ударом.").defaultValue(true).build()
    );
    private final Setting<Integer> wTapTicks = sgGeneral.add(new IntSetting.Builder()
            .name("w-tap-ticks").description("Задержка сброса спринта (в тиках).").defaultValue(1).sliderRange(1, 3).visible(wTap::get).build()
    );

    // Этот флаг видит ZAimbot, чтобы не перебивать клавиши во время сброса
    public static boolean wTapActive = false; 
    private int wTapTimer = 0;
    private LivingEntity pendingWTapTarget = null;

    public ZAutoSprint() {
        super(Meteorist.CATEGORY, "z-auto-sprint", "Автоспринт и W-Tap.");
    }

    @Override
    public void onActivate() {
        wTapActive = false;
        wTapTimer = 0;
        pendingWTapTarget = null;
    }

    @Override
    public void onDeactivate() {
        // Принудительный сброс при выключении
        mc.options.keySprint.setDown(false);
        if (mc.player != null) mc.player.setSprinting(false);
        wTapActive = false;
        wTapTimer = 0;
        pendingWTapTarget = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        // Логика W-Tap
        if (wTapTimer > 0) {
            wTapTimer--;
            if (wTapTimer == 0 && wTapActive) {
                if (pendingWTapTarget != null && mc.level.getEntity(pendingWTapTarget.getId()) != null) {
                    mc.gameMode.attack(mc.player, pendingWTapTarget);
                    mc.player.swing(InteractionHand.MAIN_HAND,
                            mc.player.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(),
                            false);
                }
                // Возвращаем спринт
                mc.options.keySprint.setDown(true);
                mc.player.setSprinting(true);
                wTapActive = false;
                pendingWTapTarget = null;
            }
        }

        // Автоспринт
        if (!wTapActive && autoSprint.get() && mc.player.onGround() && mc.player.input.hasForwardImpulse()
                && !mc.player.isUsingItem() && mc.player.getFoodData().getFoodLevel() > 6) {
            mc.player.setSprinting(true);
        }
    }

    public void performWTap(LivingEntity target) {
        if (wTapActive || wTapTimer > 0) return; // Уже в процессе, не спамим

        if (wTap.get() && mc.player.isSprinting()) {
            mc.options.keySprint.setDown(false);
            mc.player.setSprinting(false);
            wTapActive = true; // Блокируем управление клавишами в ZAimbot
            wTapTimer = wTapTicks.get();
            pendingWTapTarget = target;
        } else {
            mc.gameMode.attack(mc.player, target);
            mc.player.swing(InteractionHand.MAIN_HAND,
                    mc.player.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(),
                    false);
        }
    }
}
