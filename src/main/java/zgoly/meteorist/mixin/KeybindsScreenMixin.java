package zgoly.meteorist.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import zgoly.meteorist.modules.ZHideBinds;

import java.util.Arrays;

@Mixin(KeybindsScreen.class)
public class KeybindsScreenMixin {

    @ModifyVariable(method = "init()V", at = @At("STORE"), ordinal = 0)
    private KeyBinding[] meteorist$hideKeybinds(KeyBinding[] original) {
        ZHideBinds hideModule = Modules.get().get(ZHideBinds.class);
        
        if (hideModule != null && hideModule.isActive()) {
            return Arrays.stream(original)
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
        return original;
    }
}
