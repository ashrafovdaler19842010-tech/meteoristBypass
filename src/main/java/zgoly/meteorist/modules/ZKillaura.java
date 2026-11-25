package zgoly.meteorist.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import zgoly.meteorist.Meteorist;

import java.util.Random;
import java.util.Set;

public class ZKillaura extends Module {
    public enum MissMode { Random, Interval }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMiss = settings.createGroup("Miss Settings");

    // --- General Settings ---
    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
            .name("entities").description("Entities to attack.").onlyAttackable().defaultValue(EntityType.PLAYER).build()
    );
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range").description("Attack range.").defaultValue(3.0).min(1.0).sliderMax(6.0).build()
    );
    private final Setting<Integer> cps = sgGeneral.add(new IntSetting.Builder()
            .name("cps").description("Clicks per second.").defaultValue(12).min(1).sliderMax(20).build()
    );
    private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
            .name("ignore-friends").defaultValue(true).build()
    );

    // --- Miss Settings ---
    private final Setting<Boolean> missOnCrit = sgMiss.add(new BoolSetting.Builder()
            .name("miss-on-crit").description("Специально промахиваться на критах.").defaultValue(false).build()
    );
    private final Setting<MissMode> missMode = sgMiss.add(new EnumSetting.Builder<MissMode>()
            .name("miss-mode").description("Random = шанс, Interval = каждый N-й крит.").defaultValue(MissMode.Interval).visible(missOnCrit::get).build()
    );
    private final Setting<Integer> missInterval = sgMiss.add(new IntSetting.Builder()
            .name("miss-interval").description("Каждый какой по счету крит будет промахом.").defaultValue(3).min(1).sliderMax(10).visible(() -> missOnCrit.get() && missMode.get() == MissMode.Interval).build()
    );
    private final Setting<Integer> missChance = sgMiss.add(new IntSetting.Builder()
            .name("miss-chance").description("Шанс промаха на крите (1-100%).").defaultValue(20).min(1).sliderMax(100).visible(() -> missOnCrit.get() && missMode.get() == MissMode.Random).build()
    );
    private final Setting<Double> missAngle = sgMiss.add(new DoubleSetting.Builder()
            .name("miss-angle").description("На сколько градусов отвернуть камеру.").defaultValue(45.0).min(10).sliderMax(90).visible(missOnCrit::get).build()
    );

    private final Random random = new Random();
    private int currentCrits = 0;
    private long lastAttackTime = 0;

    public ZKillaura() {
        super(Meteorist.CATEGORY, "z-killaura", "Killaura with crit miss simulation.");
    }

    @Override
    public void onActivate() {
        currentCrits = 0;
        lastAttackTime = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Кулдаун CPS
        long now = System.currentTimeMillis();
        if (now - lastAttackTime < (1000 / cps.get())) return;

        LivingEntity target = getTarget();
        if (target == null) return;

        // Проверка, является ли удар критическим
        boolean isCrit = mc.player.fallDistance > 0.0F 
                && !mc.player.isOnGround() 
                && !mc.player.isClimbing() 
                && !mc.player.isTouchingWater() 
                && !mc.player.hasStatusEffect(StatusEffects.BLINDNESS) 
                && !mc.player.hasVehicle();

        // --- ЛОГИКА МИССА ---
        if (isCrit && missOnCrit.get()) {
            boolean shouldMiss = false;

            if (missMode.get() == MissMode.Interval) {
                currentCrits++;
                if (currentCrits >= missInterval.get()) {
                    shouldMiss = true;
                    currentCrits = 0; // Сбрасываем счетчик
                }
            } else if (missMode.get() == MissMode.Random) {
                if (random.nextInt(100) < missChance.get()) {
                    shouldMiss = true;
                }
            }

            if (shouldMiss) {
                performMissAttack(target);
                lastAttackTime = now;
                return;
            }
        }

        // Обычный удар
        attack(target);
        lastAttackTime = now;
    }

    private void performMissAttack(LivingEntity target) {
        // Запоминаем оригинальный поворот
        float oldYaw = mc.player.getYaw();
        float oldPitch = mc.player.getPitch();

        // Отворачиваем камеру в рандомную сторону
        float offsetYaw = (random.nextBoolean() ? missAngle.get().floatValue() : -missAngle.get().floatValue());
        float offsetPitch = (random.nextBoolean() ? missAngle.get().floatValue() / 2 : -missAngle.get().floatValue() / 2);

        mc.player.setYaw(oldYaw + offsetYaw);
        mc.player.setPitch(Math.max(-90, Math.min(90, oldPitch + offsetPitch)));

        // Отправляем пакет удара (сервер видит промах)
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);

        // Возвращаем камеру обратно
        mc.player.setYaw(oldYaw);
        mc.player.setPitch(oldPitch);
    }

    private void attack(LivingEntity target) {
        // Если у тебя есть W-Tap, можно раскомментировать вызов ниже:
        // ZAutoSprint autoSprint = Modules.get().get(ZAutoSprint.class);
        // if (autoSprint != null && autoSprint.isActive()) {
        //     autoSprint.performWTap(target);
        // } else {
            mc.interactionManager.attackEntity(mc.player, target);
            mc.player.swingHand(Hand.MAIN_HAND);
        // }
    }

    private LivingEntity getTarget() {
        LivingEntity bestTarget = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player || e == mc.cameraEntity) continue;
            if (!(e instanceof LivingEntity)) continue;
            if (!e.isAlive()) continue;
            if (!entities.get().contains(e.getType())) continue;
            if (ignoreFriends.get() && e instanceof PlayerEntity player && !Friends.get().shouldAttack(player)) continue;
            if (!PlayerUtils.isWithin(e, range.get())) continue;

            double dist = mc.player.distanceTo(e);
            if (dist < bestDist) {
                bestDist = dist;
                bestTarget = (LivingEntity) e;
            }
        }

        return bestTarget;
    }
}
