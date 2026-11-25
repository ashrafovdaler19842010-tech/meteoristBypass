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
    private final Setting<Integer> wTapMs = sgGeneral.add(new IntSetting.Builder()
            .name("w-tap-ms").description("Задержка сброса спринта (мс).").defaultValue(30).sliderRange(1, 50).visible(wTap::get).build()
    );

    private boolean isWTapPending = false;
    private long wTapEndTime = 0;
    private LivingEntity pendingWTapTarget = null;

    public ZAutoSprint() {
        super(Meteorist.CATEGORY, "z-auto-sprint", "Автоспринт и W-Tap.");
    }

    @Override
    public void onActivate() {
        isWTapPending = false;
        pendingWTapTarget = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        if (isWTapPending) {
            if (System.currentTimeMillis() >= wTapEndTime) {
                if (pendingWTapTarget != null && mc.level.getEntity(pendingWTapTarget.getId()) != null) {
                    mc.gameMode.attack(mc.player, pendingWTapTarget);
                    mc.player.swing(InteractionHand.MAIN_HAND,
                            mc.player.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(),
                            false);
                }
                mc.options.keySprint.setDown(true);
                mc.player.setSprinting(true);
                isWTapPending = false;
                pendingWTapTarget = null;
            }
            return;
        }

        if (autoSprint.get() && mc.player.onGround() && mc.player.input.hasForwardImpulse()
                && !mc.player.isUsingItem() && mc.player.getFoodData().getFoodLevel() > 6) {
            mc.player.setSprinting(true);
        }
    }

    public void performWTap(LivingEntity target) {
        if (wTap.get() && mc.player.isSprinting()) {
            mc.options.keySprint.setDown(false);
            mc.player.setSprinting(false);
            isWTapPending = true;
            pendingWTapTarget = target;
            wTapEndTime = System.currentTimeMillis() + wTapMs.get();
        } else {
            mc.gameMode.attack(mc.player, target);
            mc.player.swing(InteractionHand.MAIN_HAND,
                    mc.player.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(),
                    false);
        }
    }
}
