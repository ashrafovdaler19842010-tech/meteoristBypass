package zgoly.meteorist.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import zgoly.meteorist.Meteorist;

import java.util.Random;
import java.util.Set;

import static zgoly.meteorist.utils.MeteoristUtils.calculateFov;

public class ZAimbot extends Module {
    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgAim = settings.createGroup("Aim");

    // --- FILTER ---
    private final Setting<Set<EntityType<?>>> entities = sgFilter.add(new EntityTypeListSetting.Builder()
            .name("entities").description("Entities to aim at.").onlyAttackable().defaultValue(EntityType.PLAYER).build()
    );
    private final Setting<Double> range = sgFilter.add(new DoubleSetting.Builder()
            .name("range").description("The maximum range the entity can be to aim at it.").defaultValue(4.5).min(0).sliderMax(6).build()
    );
    private final Setting<KillAura.EntityAge> mobAgeFilter = sgFilter.add(new EnumSetting.Builder<KillAura.EntityAge>()
            .name("mob-age-filter").description("Determines the age of the mobs to target.").defaultValue(KillAura.EntityAge.Adult).build()
    );
    private final Setting<Boolean> ignoreNamed = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-named").description("Whether or not to aim at mobs with a name.").defaultValue(false).build()
    );
    private final Setting<Boolean> ignorePassive = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-passive").description("Will only aim at passive mobs if they are targeting you.").defaultValue(true).build()
    );
    private final Setting<Boolean> ignoreTamed = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-tamed").description("Will avoid aiming at mobs you tamed.").defaultValue(false).build()
    );
    private final Setting<Boolean> ignoreCreative = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-creative").description("Will avoid aiming at players in creative mode.").defaultValue(true).build()
    );
    private final Setting<Boolean> ignoreFriends = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-friends").description("Will avoid aiming at players on your friends list.").defaultValue(true).build()
    );
    private final Setting<Boolean> ignoreShield = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-shield").description("Will avoid aiming at players who are using a shield.").defaultValue(false).build()
    );
    private final Setting<Boolean> useFovRange = sgFilter.add(new BoolSetting.Builder()
            .name("use-fov-range").description("Restrict aiming to entities within the specified FOV.").defaultValue(true).build()
    );
    private final Setting<Double> fovRange = sgFilter.add(new DoubleSetting.Builder()
            .name("fov-range").description("Maximum Field of View (FOV) range for targeting entities.").sliderRange(0, 180).defaultValue(90).visible(useFovRange::get).build()
    );
    private final Setting<Boolean> ignoreWalls = sgFilter.add(new BoolSetting.Builder()
            .name("ignore-walls").description("Allow aiming through walls.").defaultValue(false).build()
    );
    private final Setting<SortPriority> priority = sgFilter.add(new EnumSetting.Builder<SortPriority>()
            .name("priority").description("Sorting method to prioritize targets within range.").defaultValue(SortPriority.ClosestAngle).build()
    );

    // --- AIM ---
    private final Setting<Boolean> onlySword = sgAim.add(new BoolSetting.Builder()
            .name("only-sword").description("Aim only when holding a sword.").defaultValue(false).build()
    );
    private final Setting<Boolean> onlyX = sgAim.add(new BoolSetting.Builder()
            .name("only-x-coordinate").description("Aim only horizontally (ignore vertical pitch).").defaultValue(false).build()
    );
    private final Setting<Double> aimSpeed = sgAim.add(new DoubleSetting.Builder()
            .name("speed").description("Speed of smooth aim.").defaultValue(2.0).min(0.1).sliderMax(10.0).build()
    );
    private final Setting<Double> smoothness = sgAim.add(new DoubleSetting.Builder()
            .name("smoothness").description("How smooth the aim is (0 = instant, higher = smoother but slower).").defaultValue(5.0).min(0.0).sliderMax(20.0).build()
    );
    
    // --- DYNAMIC POINT ---
    private final Setting<Boolean> dynamicAimPoint = sgAim.add(new BoolSetting.Builder()
            .name("dynamic-aim-point").description("Aims at different spots (biased to center) after each hit.").defaultValue(true).build()
    );

    // --- HITBOX SLOWDOWN ---
    private final Setting<Boolean> slowDownInHitbox = sgAim.add(new BoolSetting.Builder()
            .name("slow-down-in-hitbox").description("Slows down aim when crosshair is on target.").defaultValue(true).build()
    );
    private final Setting<Double> hitboxSlowFactor = sgAim.add(new DoubleSetting.Builder()
            .name("hitbox-slow-factor").description("Multiplier for aim speed inside hitbox (0.1 - 1.0).").defaultValue(0.3).min(0.1).sliderMax(1.0).visible(slowDownInHitbox::get).build()
    );

    private final Setting<Double> reactionTime = sgAim.add(new DoubleSetting.Builder()
            .name("reaction-time").description("Delay (in ticks) before aiming at a new target.").defaultValue(2.0).min(0.0).sliderMax(10.0).build()
    );

    private final Setting<Double> targetLockTime = sgAim.add(new DoubleSetting.Builder()
            .name("target-lock-time").description("Сколько секунд удерживать фокус на цели после удара (1-10 сек).").defaultValue(5.0).min(0.0).sliderMax(10.0).build()
    );

    private final Random random = new Random();
    private int reactionTimer = 0;
    private Vec3d currentAimPoint = null;

    // Глобальный лок для шаринга с ZKillaura
    public static LivingEntity lockedTarget = null;
    public static long lockedTargetTime = 0;

    public static void lockTarget(LivingEntity target) {
        lockedTarget = target;
        lockedTargetTime = System.currentTimeMillis();
    }

    public ZAimbot() {
        super(Meteorist.CATEGORY, "z-aimbot", "Simple smooth aimbot with dynamic hitbox targeting.");
    }

    @Override
    public void onActivate() {
        reactionTimer = 0;
        currentAimPoint = null;
        lockedTarget = null;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (onlySword.get() && !mc.player.getMainHandStack().isIn(ItemTags.SWORDS)) return;

        Entity target = null;
        long currentTime = System.currentTimeMillis();

        // Проверяем, жив ли залоченный таргет и не вышло ли время
        if (lockedTarget != null) {
            if (lockedTarget.isAlive() && PlayerUtils.isWithin(lockedTarget, range.get()) 
                && (currentTime - lockedTargetTime < targetLockTime.get() * 1000)) {
                target = lockedTarget;
            } else {
                lockedTarget = null;
            }
        }

        if (target == null) {
            target = TargetUtils.get(this::entityCheck, priority.get());
            if (target != null) {
                lockedTarget = (LivingEntity) target;
                lockedTargetTime = currentTime;
                currentAimPoint = null;
                reactionTimer = 0;
            }
        }

        if (target == null) {
            reactionTimer = 0;
            return;
        }

        if (reactionTime.get() > 0) {
            if (reactionTimer < reactionTime.get().intValue()) {
                reactionTimer++;
                return;
            }
        } else {
            reactionTimer = 0;
        }

        aim(mc.player, target);
    }

    private void aim(LivingEntity player, Entity target) {
        Box box = target.getBoundingBox();
        
        if (currentAimPoint == null || dynamicAimPoint.get()) {
            // Смещение к центру (сумма двух рандомов), от 20% до 80% высоты
            double yBias = (random.nextDouble() + random.nextDouble() - 1.0) * 0.3 + 0.5; 
            double y = box.minY + box.getLengthY() * yBias;
            
            double x = box.minX + box.getLengthX() * (0.2 + random.nextDouble() * 0.6);
            double z = box.minZ + box.getLengthZ() * (0.2 + random.nextDouble() * 0.6);
            
            currentAimPoint = new Vec3d(x, y, z);
        }

        float targetYaw = (float) Rotations.getYaw(currentAimPoint);
        float targetPitch = (float) Rotations.getPitch(currentAimPoint);

        float yawDifference = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        float pitchDifference = MathHelper.wrapDegrees(targetPitch - player.getPitch());

        if (onlyX.get()) {
            pitchDifference = 0; 
        }

        boolean lookingAtTarget = false;
        HitResult raycast = mc.player.raycast(range.get(), 0, false);
        if (raycast.getType() == HitResult.Type.ENTITY && mc.crosshairTarget != null) {
            lookingAtTarget = mc.crosshairTarget.getType() == HitResult.Type.ENTITY 
                    && ((EntityHitResult)mc.crosshairTarget).getEntity() == target;
        }

        // Правильная формула скорости с ползунком от 0
        float speedFactor = aimSpeed.get().floatValue() / 100.0f;
        if (smoothness.get() > 0) {
            speedFactor *= (10.0f / (10.0f + smoothness.get().floatValue()));
        }
        
        if (slowDownInHitbox.get() && lookingAtTarget) {
            speedFactor *= hitboxSlowFactor.get().floatValue(); 
        }

        float deltaYaw = yawDifference * speedFactor;
        float deltaPitch = pitchDifference * speedFactor;

        float sensitivity = mc.options.getMouseSensitivity().getValue().floatValue();
        float f = sensitivity * 0.6f + 0.2f;
        float gcd = f * f * f * 1.2f; 
        
        deltaYaw = Math.round(deltaYaw / gcd) * gcd;
        deltaPitch = Math.round(deltaPitch / gcd) * gcd;

        player.setYaw(player.getYaw() + deltaYaw);
        player.setPitch(MathHelper.clamp(player.getPitch() + deltaPitch, -90f, 90f));
    }

    private boolean entityCheck(Entity entity) {
        if (entity.equals(mc.player) || entity.equals(mc.cameraEntity)) return false;
        if (!entity.isAlive()) return false;
        if (!PlayerUtils.isWithin(entity, range.get())) return false;
        if (!entities.get().contains(entity.getType())) return false;
        if (ignoreNamed.get() && entity.hasCustomName()) return false;
        if (ignoreTamed.get()) {
            if (entity instanceof Tameable tameable && tameable.getOwner() != null && tameable.getOwner().equals(mc.player)) return false;
        }
        if (ignorePassive.get()) {
            switch (entity) {
                case EndermanEntity enderman when !enderman.isAngry() -> { return false; }
                case ZombifiedPiglinEntity piglin when !piglin.isAttacking() -> { return false; }
                case WolfEntity wolf when !wolf.isAttacking() -> { return false; }
                default -> {}
            }
        }
        if (entity instanceof PlayerEntity player) {
            if (ignoreCreative.get() && player.isCreative()) return false;
            if (ignoreFriends.get() && !Friends.get().shouldAttack(player)) return false;
            if (ignoreShield.get() && player.isBlocking()) return false;
        }
        if (entity instanceof AnimalEntity animal) {
            return switch (mobAgeFilter.get()) {
                case Baby -> animal.isBaby();
                case Adult -> !animal.isBaby();
                case Both -> true;
            };
        }
        if (useFovRange.get() && calculateFov(mc.player, entity) > fovRange.get()) return false;
        return ignoreWalls.get() || PlayerUtils.canSeeEntity(entity);
    }
}
