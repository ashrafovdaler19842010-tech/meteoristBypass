package zgoly.meteorist.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import zgoly.meteorist.modules.ZHideBinds;

import java.util.Arrays;

@Mixin(KeybindsScreen.class)
public class KeybindsScreenMixin {
    
    // Связываем поле keyBindings из класса KeybindsScreen с нашей переменной
    @Shadow private KeyBinding[] keyBindings;

    // Внедряемся в конец метода init() (когда список уже создан)
    @Inject(method = "init()V", at = @At("TAIL"))
    private void meteorist$hideKeybinds(CallbackInfo ci) {
        // Проверяем, включен ли модуль скрытия
        ZHideBinds hideModule = Modules.get().get(ZHideBinds.class);
        
        if (hideModule != null && hideModule.isActive() && this.keyBindings != null) {
            // Фильтруем массив клавиш, оставляя только ванильные
            this.keyBindings = Arrays.stream(this.keyBindings)
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
        }
    }
}
