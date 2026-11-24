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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

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
            // 1. Сбрасываем бинды в игре
            for (KeyBinding key : mc.options.allKeys) {
                if (key.getCategory().equals("key.categories.movement") || 
                    key.getCategory().equals("key.categories.gameplay")) continue;
                key.setBoundKey(InputUtil.UNKNOWN_KEY);
            }
            mc.options.write();

            // Сбрасываем бинды модулей Meteor
            Modules.get().getAll().forEach(module -> module.keybind.set(false, -1, 0));
            Modules.get().save();
            
            // 2. Чистим options.txt от строк читов, чтобы они исчезли из меню навсегда
            cleanOptionsFile();
        }

        if (deleteLogs.get()) {
            deleteDirectory(new File(mc.runDirectory, "logs"));
            deleteDirectory(new File(mc.runDirectory, "crash-reports"));
            File history = new File(mc.runDirectory, "command_history.txt");
            if (history.exists()) history.delete();
        }

        if (moveFolders.get()) {
            // Регистрируем скрипт перемещения папок при выходе из игры
            Runtime.getRuntime().addShutdownHook(new Thread(this::moveFoldersToHidden));
        }

        if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("§c[Panic] §fHidden. Restart game to clear GUI."), false);
    }

    private void disablePanic() {
        if (!isPanic) return;
        isPanic = false;
        
        // Возвращаем только бинды Meteor
        if (clearKeybinds.get()) {
            Modules.get().getAll().forEach(module -> module.keybind.reset());
            Modules.get().save();
            
            // Восстанавливаем стандартные бинды (GUI, Команды) через обычный цикл
            for (KeyBinding k : mc.options.allKeys) {
                if (k.getTranslationKey().equals("key.meteor-client.open-gui")) {
                    k.setBoundKey(InputUtil.fromTranslationKey("key.keyboard.right.shift"));
                }
                if (k.getTranslationKey().equals("key.meteor-client.open-commands")) {
                    k.setBoundKey(InputUtil.fromTranslationKey("key.keyboard.period"));
                }
            }
            mc.options.write();
        }
        
        if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("§a[Panic] §fBinds restored. Folders remain hidden."), false);
    }

    // Удаляет все упоминания читов из options.txt
    private void cleanOptionsFile() {
        File optionsFile = new File(mc.runDirectory, "options.txt");
        if (!optionsFile.exists()) return;

        try {
            List<String> lines = Files.readAllLines(optionsFile.toPath());
            List<String> newLines = new ArrayList<>();

            for (String line : lines) {
                // Пропускаем строки, связанные с Meteor, Waypoints, Zoomify и т.д.
                if (line.startsWith("key_meteor") || 
                    line.startsWith("key_waypoints") || 
                    line.startsWith("key_zoomify") || 
                    line.startsWith("key_zakoopt") || 
                    line.startsWith("key_open_gui") || 
                    line.startsWith("key_open_commands")) {
                    continue;
                }
                newLines.add(line);
            }

            // Перезаписываем файл
            Files.write(optionsFile.toPath(), newLines, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
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

    // Надежное перемещение папок через NIO (работает даже при блокировке)
    private void moveFoldersToHidden() {
        File gameDir = mc.runDirectory;
        File meteorClientDir = new File(gameDir, "meteor-client");
        File meteoristDir = new File(gameDir, "meteorist");
        File hiddenDir = new File(gameDir, ".cache_data");

        if (!hiddenDir.exists()) hiddenDir.mkdirs();

        try {
            if (meteorClientDir.exists()) {
                Path source = meteorClientDir.toPath();
                Path target = new File(hiddenDir, "meteor-client").toPath();
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if (meteoristDir.exists()) {
                Path source = meteoristDir.toPath();
                Path target = new File(hiddenDir, "meteorist").toPath();
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Если Windows все еще блокирует папку, пробуем скопировать содержимое и удалить оригинал
            try {
                if (meteorClientDir.exists()) copyDirectory(meteorClientDir, new File(hiddenDir, "meteor-client"));
                if (meteoristDir.exists()) copyDirectory(meteoristDir, new File(hiddenDir, "meteorist"));
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    private void copyDirectory(File source, File target) throws IOException {
        if (!target.exists()) target.mkdirs();
        File[] files = source.listFiles();
        if (files != null) {
            for (File file : files) {
                File newFile = new File(target, file.getName());
                if (file.isDirectory()) {
                    copyDirectory(file, newFile);
                } else {
                    Files.copy(file.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
