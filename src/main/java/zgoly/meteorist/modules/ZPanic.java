package zgoly.meteorist.modules;

import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import zgoly.meteorist.Meteorist;

import java.io.File;
import java.util.ArrayList;

public class ZPanic extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> clearKeybinds = sgGeneral.add(new BoolSetting.Builder()
            .name("clear-keybinds").defaultValue(true).build()
    );
    private final Setting<Boolean> moveFolders = sgGeneral.add(new BoolSetting.Builder()
            .name("move-folders").defaultValue(true).build()
    );
    private final Setting<Boolean> deleteLogs = sgGeneral.add(new BoolSetting.Builder()
            .name("delete-logs").defaultValue(true).build()
    );

    private boolean isPanic = false;

    public ZPanic() {
        super(Meteorist.CATEGORY, "z-panic", "Hides all traces via chat commands.");
    }

    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        String msg = event.message.trim();
        
        if (msg.equalsIgnoreCase("daler1")) {
            event.setMessage(""); // Стираем сообщение
            triggerPanic();
        } else if (msg.equalsIgnoreCase("daler2")) {
            event.setMessage("");
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
            for (KeyBinding key : mc.options.allKeys) {
                if (key.getCategory().equals("key.categories.movement") || key.getCategory().equals("key.categories.gameplay")) continue;
                key.setBoundKey(InputUtil.UNKNOWN_KEY);
            }
            mc.options.write();
            Modules.get().getAll().forEach(module -> module.keybind.reset());
        }

        if (deleteLogs.get()) {
            deleteDirectory(new File(mc.runDirectory, "logs"));
            deleteDirectory(new File(mc.runDirectory, "crash-reports"));
            File history = new File(mc.runDirectory, "command_history.txt");
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

    private void deleteDirectory(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) deleteDirectory(file);
                else {
                    try { file.delete(); } catch (Exception ignored) {}
                }
            }
        }
        try { dir.delete(); } catch (Exception ignored) {}
    }

    private void toggleFolders(boolean hide) {
        File gameDir = mc.runDirectory;
        File meteorClientDir = new File(gameDir, "meteor-client");
        File meteorClientHidden = new File(gameDir, ".mc_data");
        File meteoristDir = new File(gameDir, "meteorist");
        File meteoristHidden = new File(gameDir, ".mist_data");

        try {
            if (hide) {
                if (meteorClientDir.exists()) meteorClientDir.renameTo(meteorClientHidden);
                if (meteoristDir.exists()) meteoristDir.renameTo(meteoristHidden);
            } else {
                if (meteorClientHidden.exists()) meteorClientHidden.renameTo(meteorClientDir);
                if (meteoristHidden.exists()) meteoristHidden.renameTo(meteoristDir);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}
