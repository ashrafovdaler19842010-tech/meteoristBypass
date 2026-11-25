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

    // Связываем поле keyBindings из ванильного класса с нашей переменной.
    // Это позволяет безопасно читать и перезаписывать список клавиш.
    @Shadow
    private KeyBinding[] keyBindings;

    // Внедряемся в самый конец метода init(), когда игра уже создала полный список клавиш.
    @Inject(method = "init()V", at = @At("TAIL"))
    private void meteorist$hideKeybinds(CallbackInfo ci) {
        ZHideBinds hideModule = Modules.get().get(ZHideBinds.class);

        // Проверяем, что модуль скрытия включен и список клавиш не пустой.
        if (hideModule != null && hideModule.isActive() && this.keyBindings != null) {
            // Фильтруем массив, убирая клавиши, которые относятся к Meteor и другим модам.
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
