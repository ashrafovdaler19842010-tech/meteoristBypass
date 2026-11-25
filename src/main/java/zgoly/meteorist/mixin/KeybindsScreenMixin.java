package zgoly.meteorist.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import zgoly.meteorist.modules.ZHideBinds;

import java.util.Arrays;

@Mixin(KeybindsScreen.class)
public class KeybindsScreenMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void meteorist$hideKeybinds(CallbackInfo ci) {
        ZHideBinds hideModule = Modules.get().get(ZHideBinds.class);
        if (hideModule == null || !hideModule.isActive()) return;

        // Исправлено: получаем клиент через MinecraftClient.getInstance()
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options == null) return;

        KeyBinding[] originalKeys = ((GameOptionsAccessor) mc.options).meteorist$getAllKeys();
        if (originalKeys == null) return;

        KeyBinding[] filteredKeys = Arrays.stream(originalKeys)
                .filter(key -> {
                    String translation = key.getTranslationKey().toLowerCase();
                    return !(translation.contains("meteor")
                            || translation.contains("waypoints")
                            || translation.contains("zoomify")
                            || translation.contains("zakoopt")
                            || translation.contains("open_gui")
                            || translation.contains("open_commands"));
                })
                .toArray(KeyBinding[]::new);

        ((GameOptionsAccessor) mc.options).meteorist$setAllKeys(filteredKeys);
    }
}
