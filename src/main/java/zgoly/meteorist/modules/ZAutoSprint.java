package zgoly.meteorist.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import zgoly.meteorist.Meteorist;

public class ZAutoSprint extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> onlyForward = sgGeneral.add(new BoolSetting.Builder()
            .name("only-forward")
            .description("Зажимать спринт только при движении вперед.")
            .defaultValue(true)
            .build()
    );

    public ZAutoSprint() {
        super(Meteorist.CATEGORY, "z-auto-sprint", "Автоматический спринт.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        
        boolean canSprint = onlyForward.get() ? mc.player.input.hasForwardMovement() : 
                (mc.player.input.hasForwardMovement() || mc.player.input.pressingLeft || mc.player.input.pressingRight);

        if (canSprint && !mc.player.isSprinting() && !mc.player.isUsingItem() && mc.player.getHungerManager().getFoodLevel() > 6) {
            mc.player.setSprinting(true);
        }
    }
}
