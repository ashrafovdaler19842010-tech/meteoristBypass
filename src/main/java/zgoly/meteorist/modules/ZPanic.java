package zgoly.meteorist.modules;

import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.commands.Commands;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.GameOptions;
import zgoly.meteorist.Meteorist;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
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
    private KeyBinding[] originalKeys = null;

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
        
        // Выключаем все модули
        new ArrayList<>(Modules.get().getActive()).forEach(m -> {
            if (m != this) m.toggle();
        });

        // 1. СКРЫТИЕ КОМАНД METEOR (префикс ".")
        try {
            Commands.get().setPrefix("___hidden___");
        } catch (Exception ignored) {}

        // 2. СКРЫТИЕ БИНДОВ ИЗ УПРАВЛЕНИЯ (без ломки E/F11)
        if (clearKeybinds.get()) {
            try {
                if (originalKeys == null) {
                    originalKeys = mc.options.allKeys.clone();
                }
                
                List<KeyBinding> filtered = new ArrayList<>();
                for (KeyBinding key : mc.options.allKeys) {
                    String category = key.getCategory();
                    String translation = key.getTranslationKey();
                    // Оставляем только ванильные кнопки
                    if (!category.contains("Meteor") && !category.contains("Meteorist") 
                        && !translation.contains("meteor") && !translation.contains("waypoints")
                        && !translation.contains("zoomify") && !translation.contains("zakoopt")) {
                        filtered.add(key);
                    }
                }

                // Подменяем массив через рефлексию
                Field field = GameOptions.class.getDeclaredField("allKeys");
                field.setAccessible(true);
                field.set(mc.options, filtered.toArray(new KeyBinding[0]));
                
                // Сохраняем конфиг
                mc.options.write();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 3. УДАЛЕНИЕ ЛОГОВ
        if (deleteLogs.get()) {
            deleteDirectory(new File(mc.runDirectory, "logs"));
            deleteDirectory(new File(mc.runDirectory, "crash-reports"));
            File history = new File(mc.runDirectory, "command_history.txt");
            if (history.exists()) history.delete();
        }

        // 4. ПЕРЕМЕЩЕНИЕ ПАПОК В ФОНЕ (через 3 секунды)
        if (moveFolders.get()) {
            new Thread(() -> {
                try {
                    // Ждем, пока игра полностью освободит файлы
                    Thread.sleep(3000); 
                    
                    File gameDir = mc.runDirectory;
                    File meteorClientDir = new File(gameDir, "meteor-client");
                    File meteoristDir = new File(gameDir, "meteorist");
                    File mcHidden = new File(gameDir, ".mc_data");
                    File mistHidden = new File(gameDir, ".mist_data");

                    if (meteorClientDir.exists()) {
                        if (!meteorClientDir.renameTo(mcHidden)) {
                            // Если не получилось, пробуем скопировать
                            org.apache.commons.io.FileUtils.copyDirectory(meteorClientDir, mcHidden);
                        }
                    }
                    if (meteoristDir.exists()) {
                        if (!meteoristDir.renameTo(mistHidden)) {
                            org.apache.commons.io.FileUtils.copyDirectory(meteoristDir, mistHidden);
                        }
                    }
                } catch (Exception ignored) {}
            }).start();
        }

        if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("§c[Panic] §fHidden. Restart game to clear UI."), false);
    }

    private void disablePanic() {
        if (!isPanic) return;
        isPanic = false;
        
        // Возвращаем команды
        try {
            Commands.get().setPrefix(".");
        } catch (Exception ignored) {}

        // Возвращаем бинды (если они были скрыты)
        if (clearKeybinds.get() && originalKeys != null) {
            try {
                Field field = GameOptions.class.getDeclaredField("allKeys");
                field.setAccessible(true);
                field.set(mc.options, originalKeys.clone());
                mc.options.write();
                originalKeys = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
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
}
