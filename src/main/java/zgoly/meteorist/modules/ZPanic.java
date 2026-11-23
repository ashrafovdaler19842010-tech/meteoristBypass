package zgoly.meteorist.modules;

import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
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
import java.util.ArrayList;
import java.util.List;

public class ZPanic extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> hideHud = sgGeneral.add(new BoolSetting.Builder()
            .name("hide-hud").description("Скрывает HUD.").defaultValue(true).build()
    );
    private final Setting<Boolean> clearKeybinds = sgGeneral.add(new BoolSetting.Builder()
            .name("clear-keybinds").description("Убирает бинды из настроек управления.").defaultValue(true).build()
    );
    private final Setting<Boolean> moveFolders = sgGeneral.add(new BoolSetting.Builder()
            .name("move-folders").description("Переименовывает папки meteor-client и meteorist для скрытности.").defaultValue(true).build()
    );
    private final Setting<Boolean> deleteLogs = sgGeneral.add(new BoolSetting.Builder()
            .name("delete-logs").description("Удаляет логи и историю чата.").defaultValue(true).build()
    );

    private boolean isPanic = false;

    public ZPanic() {
        super(Meteorist.CATEGORY, "z-panic", "Hides all traces of the client via chat commands.");
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (mc.player == null) return;
        
        String msg = event.getMessage().getString();
        
        // Активация паники
        if (msg.equals("daler1")) {
            event.cancel(); // Отменяем сообщение, чтобы его не было в чате
            triggerPanic();
        } 
        // Деактивация паники
        else if (msg.equals("daler2")) {
            event.cancel();
            disablePanic();
        }
    }

    private void triggerPanic() {
        if (isPanic) return;
        isPanic = true;

        // 1. Скрыть GUI и HUD
        mc.setScreen(null);
        if (hideHud.get()) {
            Modules.get().getAll().stream()
                    .filter(m -> m.name.equals("Hud"))
                    .forEach(Module::toggle);
        }

        // 2. Отключить все модули
        List<Module> activeModules = new ArrayList<>(Modules.get().getActive());
        activeModules.forEach(Module::toggle);

        // 3. Очистить бинды
        if (clearKeybinds.get()) {
            clearKeybinds();
        }

        // 4. Удалить логи и историю
        if (deleteLogs.get()) {
            deleteLogsAndHistory();
        }

        // 5. Переименовать папки
        if (moveFolders.get()) {
            toggleFolders(true);
        }

        // Отправить сообщение в чат (локально), чтобы ты знал, что все сработало
        mc.player.sendMessage(net.minecraft.text.Text.literal("§c[Panic] §fAll traces hidden."), false);
    }

    private void disablePanic() {
        if (!isPanic) return;
        isPanic = false;

        // Возвращаем папки
        if (moveFolders.get()) {
            toggleFolders(false);
        }

        mc.player.sendMessage(net.minecraft.text.Text.literal("§a[Panic] §fTraces restored."), false);
    }

    private void clearKeybinds() {
        for (KeyBinding key : mc.options.allKeys) {
            if (key.getCategory().equals("key.categories.movement") || 
                key.getCategory().equals("key.categories.gameplay")) continue;

            key.setBoundKey(InputUtil.UNKNOWN_KEY);
        }
        mc.options.write(); // Сохраняем options.txt
    }

    private void deleteLogsAndHistory() {
        File gameDir = mc.runDirectory;
        
        // Удаляем логи
        File logsDir = new File(gameDir, "logs");
        if (logsDir.exists()) {
            File[] files = logsDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
        }
        
        // Удаляем crash-reports
        File crashDir = new File(gameDir, "crash-reports");
        if (crashDir.exists()) {
            File[] files = crashDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
        }

        // Удаляем историю чата
        File history = new File(gameDir, "command_history.txt");
        if (history.exists()) {
            history.delete();
        }
    }

    private void toggleFolders(boolean hide) {
        File gameDir = mc.runDirectory;
        
        // Папка meteor-client
        File meteorClientDir = new File(gameDir, "meteor-client");
        File meteorClientHidden = new File(gameDir, ".mc_data");
        // Папка meteorist
        File meteoristDir = new File(gameDir, "meteorist");
        File meteoristHidden = new File(gameDir, ".mist_data");

        try {
            if (hide) {
                if (meteorClientDir.exists()) {
                    Files.move(meteorClientDir.toPath(), meteorClientHidden.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                if (meteoristDir.exists()) {
                    Files.move(meteoristDir.toPath(), meteoristHidden.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                if (meteorClientHidden.exists()) {
                    Files.move(meteorClientHidden.toPath(), meteorClientDir.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                if (meteoristHidden.exists()) {
                    Files.move(meteoristHidden.toPath(), meteoristDir.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
