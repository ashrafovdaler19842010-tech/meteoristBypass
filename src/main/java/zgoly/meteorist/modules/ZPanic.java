package zgoly.meteorist.modules;

import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind; // ВАЖНЫЙ ИМПОРТ
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import zgoly.meteorist.Meteorist;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

public class ZPanic extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> clearKeybinds = sgGeneral.add(new BoolSetting.Builder()
            .name("clear-keybinds").description("Убирает бинды.").defaultValue(true).build()
    );
    private final Setting<Boolean> moveFolders = sgGeneral.add(new BoolSetting.Builder()
            .name("move-folders").description("Переименовывает папки.").defaultValue(true).build()
    );
    private final Setting<Boolean> deleteLogs = sgGeneral.add(new BoolSetting.Builder()
            .name("delete-logs").description("Удаляет логи.").defaultValue(true).build()
    );

    private boolean isPanic = false;

    public ZPanic() {
        super(Meteorist.CATEGORY, "z-panic", "Hides all traces via chat commands.");
    }

    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        String msg = event.message;
        
        if (msg.equalsIgnoreCase("daler1")) {
            event.cancel();
            triggerPanic();
        } else if (msg.equalsIgnoreCase("daler2")) {
            event.cancel();
            disablePanic();
        }
    }

    private void triggerPanic() {
        if (isPanic) return;
        isPanic = true;

        mc.setScreen(null);
        Modules.get().getAll().stream().filter(m -> m.name.equals("Hud")).forEach(Module::toggle);
        new ArrayList<>(Modules.get().getActive()).forEach(Module::toggle);

        if (clearKeybinds.get()) {
            // Очистка ванильных биндов
            for (KeyBinding key : mc.options.allKeys) {
                if (key.getCategory().equals("key.categories.movement") || key.getCategory().equals("key.categories.gameplay")) continue;
                key.setBoundKey(InputUtil.UNKNOWN_KEY);
            }
            mc.options.write();
            
            // Очистка биндов модулей Meteor (исправлено)
            Modules.get().getAll().forEach(module -> module.keybind.set(new Keybind(InputUtil.UNKNOWN_KEY)));
        }

        if (deleteLogs.get()) {
            File gameDir = mc.runDirectory;
            File logsDir = new File(gameDir, "logs");
            if (logsDir.exists() && logsDir.listFiles() != null) for (File f : logsDir.listFiles()) f.delete();
            File crashDir = new File(gameDir, "crash-reports");
            if (crashDir.exists() && crashDir.listFiles() != null) for (File f : crashDir.listFiles()) f.delete();
            File history = new File(gameDir, "command_history.txt");
            if (history.exists()) history.delete();
        }

        if (moveFolders.get()) toggleFolders(true);

        if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("§c[Panic] §fHidden."), false);
    }

    private void disablePanic() {
        if (!isPanic) return;
        isPanic = false;
        if (moveFolders.get()) toggleFolders(false);
        if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("§a[Panic] §fRestored."), false);
    }

    private void toggleFolders(boolean hide) {
        File gameDir = mc.runDirectory;
        File meteorClientDir = new File(gameDir, "meteor-client");
        File meteorClientHidden = new File(gameDir, ".mc_data");
        File meteoristDir = new File(gameDir, "meteorist");
        File meteoristHidden = new File(gameDir, ".mist_data");

        try {
            if (hide) {
                if (meteorClientDir.exists()) Files.move(meteorClientDir.toPath(), meteorClientHidden.toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (meteoristDir.exists()) Files.move(meteoristDir.toPath(), meteoristHidden.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                if (meteorClientHidden.exists()) Files.move(meteorClientHidden.toPath(), meteorClientDir.toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (meteoristHidden.exists()) Files.move(meteoristHidden.toPath(), meteoristDir.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) { e.printStackTrace(); }
    }
}
