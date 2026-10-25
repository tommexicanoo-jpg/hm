package com.theecho.entity;

import com.theecho.EchoManager;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Optional;
import java.util.UUID;

/**
 * El Eco. Nunca ataca ni hace daño. Según su {@link Mode} y su {@link #getVariant() variante} (skin) hace
 * cosas distintas: mirar, caminar, correr hacia ti y desaparecer, gritar, volar, imitar tus acciones (El Registrador)...
 * No tiene nametag y nunca se guarda en el mundo.
 */
public class EchoEntity extends PathAwareEntity {

    public enum Mode {
        WATCH,      // se queda quieto mirándote fijamente
        WALK,       // camina lentamente hacia ti
        CHOP,       // imita que tala un árbol
        STALK,      // (laberinto) solo se mueve cuando no lo miras; si te toca, te devuelve al inicio
        GLIMPSE,    // aparece de reojo y desaparece cuando lo miras
        JUMP,       // susto: aparece frente a ti un instante
        RUSH,       // corre hacia ti y desaparece justo antes de tocarte
        SCREAM,     // aparece y grita
        FLY_WATCH,  // flota en el aire como en creativo, observándote desde arriba
        FLY_SWOOP,  // flota, y de pronto se lanza hacia ti y desaparece
        REGISTRAR   // El Registrador: replica tus acciones, se acerca un bloque si te quedas quieto
    }

    public static final int V_NORMAL = 0; // skin del jugador al que imita
    public static final int V_SHADOW = 1; // skin negra con ojos blancos
    public static final int V_PALE = 2;   // skin blanca translúcida (El Registrador)

    private static final TrackedData<Optional<UUID>> OWNER =
            DataTracker.registerData(EchoEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);
    private static final TrackedData<Integer> VARIANT =
            DataTracker.registerData(EchoEntity.class, TrackedDataHandlerRegistry.INTEGER);

    private Mode mode = Mode.WATCH;
    private int life = 0;
    private int maxLife = 20 * 100;
    private int seenTicks = 0;

    // CHOP
    private BlockPos chopTarget;
    private int chopTicks = 0;
    private boolean chopped = false;
    private int afterChop = 0;

    // REGISTRAR
    private Vec3d lastOwnerPos = null;
    private int stillTicks = 0;

    // FLY
    private double orbit = Math.random() * Math.PI * 2;

    public EchoEntity(EntityType<? extends EchoEntity> type, World world) {
        super(type, world);
        this.setSilent(true); // sus sonidos los pone el mod, no el mob
    }

    public static DefaultAttributeContainer.Builder createEchoAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.25)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 40.0);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(OWNER, Optional.empty());
        builder.add(VARIANT, V_NORMAL);
    }

    public UUID getOwnerUuid() {
        return this.dataTracker.get(OWNER).orElse(null);
    }

    public int getVariant() {
        return this.dataTracker.get(VARIANT);
    }

    public Mode getMode() {
        return mode;
    }

    /** Sin nametag: ni nombre personalizado ni etiqueta visible. */
    public void init(ServerPlayerEntity player, int variant, Mode mode) {
        this.dataTracker.set(OWNER, Optional.of(player.getUuid()));
        this.dataTracker.set(VARIANT, variant);
        this.setCustomNameVisible(false);
        if (variant == V_NORMAL) copyAppearance(player);
        setMode(mode);
    }

    private void copyAppearance(ServerPlayerEntity p) {
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
        for (EquipmentSlot s : slots) {
            this.equipStack(s, p.getEquippedStack(s).copy());
            this.setEquipmentDropChance(s, 0.0f);
        }
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        switch (mode) {
            case STALK -> this.maxLife = Integer.MAX_VALUE;
            case GLIMPSE -> this.maxLife = 20 * 6;
            case JUMP -> this.maxLife = 40;
            case WATCH -> this.maxLife = 20 * (40 + this.random.nextInt(50));
            case WALK -> this.maxLife = 20 * 70;
            case RUSH -> {
                this.maxLife = 20 * 9;
                this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).setBaseValue(0.36);
            }
            case SCREAM -> this.maxLife = 20 * 4;
            case FLY_WATCH -> { this.maxLife = 20 * 45; this.setNoGravity(true); }
            case FLY_SWOOP -> { this.maxLife = 20 * 12; this.setNoGravity(true); }
            case REGISTRAR -> this.maxLife = 20 * 240;
            case CHOP -> {
                this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
                this.maxLife = 20 * 40;
            }
        }
    }

    public void setChopTarget(BlockPos pos) {
        this.chopTarget = pos;
    }

    private ServerPlayerEntity getOwnerPlayer() {
        UUID id = getOwnerUuid();
        if (id == null || !(getWorld() instanceof ServerWorld sw)) return null;
        return sw.getServer().getPlayerManager().getPlayer(id);
    }

    public void vanish() {
        if (getWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.SCULK_SOUL, getX(), getY() + 1.0, getZ(), 25, 0.3, 0.6, 0.3, 0.05);
        }
        this.discard();
    }

    private void vanishQuiet() {
        this.discard();
    }

    private boolean isBeingLookedAt(ServerPlayerEntity p, double minDot) {
        Vec3d look = p.getRotationVec(1.0f).normalize();
        Vec3d to = this.getEyePos().subtract(p.getEyePos()).normalize();
        return look.dotProduct(to) > minDot && p.canSee(this);
    }

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) return;
        ServerWorld world = (ServerWorld) getWorld();

        life++;
        if (life > maxLife) {
            if (mode == Mode.REGISTRAR) vanishQuiet(); else vanish();
            return;
        }
        ServerPlayerEntity owner = getOwnerPlayer();
        if (owner == null || owner.isRemoved() || !owner.isAlive() || owner.isSpectator() || owner.getServerWorld() != world) {
            this.discard();
            return;
        }
        if (mode != Mode.STALK && life % 20 == 0 && EchoManager.isProtected(world, getBlockPos())) {
            vanish();
            return;
        }

        double d2 = this.squaredDistanceTo(owner);

        switch (mode) {
            case WATCH -> {
                getNavigation().stop();
                getLookControl().lookAt(owner, 30.0f, 30.0f);
                if (d2 < 8.0 * 8.0) { vanish(); return; }
                tickSeen(owner, d2, 120);
            }
            case WALK -> {
                getLookControl().lookAt(owner, 30.0f, 30.0f);
                if (life % 10 == 0) getNavigation().startMovingTo(owner, 0.55);
                if (d2 < 7.0 * 7.0) { vanish(); return; }
                tickSeen(owner, d2, 100);
            }
            case STALK -> {
                getLookControl().lookAt(owner, 30.0f, 30.0f);
                boolean watched = isBeingLookedAt(owner, 0.55);
                if (d2 < 1.9 * 1.9) {
                    EchoManager.caught(owner, this);
                    return;
                }
                if (d2 < 14.0 * 14.0 && life % 40 == 0) {
                    world.playSound(null, getBlockPos(), SoundEvents.ENTITY_WARDEN_NEARBY_CLOSE,
                            SoundCategory.HOSTILE, 0.8f, 0.8f);
                }
                if (watched) {
                    getNavigation().stop();
                } else if (life % 10 == 0) {
                    getNavigation().startMovingTo(owner, 1.15);
                }
            }
            case GLIMPSE -> {
                getNavigation().stop();
                getLookControl().lookAt(owner, 30.0f, 30.0f);
                if (d2 < 4.0 * 4.0) { vanish(); return; }
                if (life > 4 && isBeingLookedAt(owner, 0.82)) { vanish(); return; }
            }
            case JUMP -> {
                getNavigation().stop();
                getLookControl().lookAt(owner, 60.0f, 60.0f);
            }
            case RUSH -> {
                getLookControl().lookAt(owner, 60.0f, 60.0f);
                if (life % 8 == 0) getNavigation().startMovingTo(owner, 1.5);
                if (d2 < 3.4 * 3.4) { EchoManager.rushEnd(owner, this); return; }
            }
            case SCREAM -> {
                getNavigation().stop();
                getLookControl().lookAt(owner, 60.0f, 60.0f);
                if (life == 6) EchoManager.screamAt(owner, this);
                if (life > 55) { vanish(); return; }
            }
            case FLY_WATCH -> tickFlyWatch(owner, d2);
            case FLY_SWOOP -> tickFlySwoop(owner, d2);
            case REGISTRAR -> tickRegistrar(owner, world, d2);
            case CHOP -> tickChop(owner, world, d2);
        }
    }

    private void tickSeen(ServerPlayerEntity owner, double d2, int limit) {
        if (d2 < 40.0 * 40.0 && isBeingLookedAt(owner, 0.97)) {
            seenTicks++;
            if (seenTicks > limit) vanish();
        } else if (seenTicks > 0) {
            seenTicks--;
        }
    }

    // ------------------------------------------------------------------ vuelo "creativo"

    private void faceOwner(ServerPlayerEntity owner, boolean pitchToo) {
        double dx = owner.getX() - getX(), dz = owner.getZ() - getZ();
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
        setYaw(yaw);
        setHeadYaw(yaw);
        setBodyYaw(yaw);
        if (pitchToo) {
            double dy = owner.getEyeY() - getEyeY();
            setPitch((float) -(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180.0 / Math.PI)));
        }
    }

    private void tickFlyWatch(ServerPlayerEntity owner, double d2) {
        faceOwner(owner, true);
        fallDistance = 0;
        orbit += 0.012;
        double r = 15.0;
        Vec3d target = new Vec3d(owner.getX() + Math.cos(orbit) * r, owner.getY() + 9.0, owner.getZ() + Math.sin(orbit) * r);
        Vec3d to = target.subtract(getPos());
        double len = to.length();
        Vec3d vel = len < 0.4 ? Vec3d.ZERO : to.multiply(1.0 / len).multiply(Math.min(0.22, len * 0.1));
        setVelocity(vel);
        tickSeen(owner, d2, 140);
        if (d2 < 6.0 * 6.0) vanish();
    }

    private void tickFlySwoop(ServerPlayerEntity owner, double d2) {
        faceOwner(owner, true);
        fallDistance = 0;
        if (life < 45) { // primero flota mirándote
            setVelocity(0, Math.sin(life * 0.2) * 0.02, 0);
            return;
        }
        Vec3d target = owner.getEyePos();
        Vec3d to = target.subtract(getPos());
        double len = to.length();
        if (len < 2.8) { EchoManager.rushEnd(owner, this); return; }
        double speed = Math.min(0.85, 0.2 + (life - 45) * 0.02);
        setVelocity(to.multiply(1.0 / len).multiply(speed));
    }

    // ------------------------------------------------------------------ El Registrador

    private void tickRegistrar(ServerPlayerEntity owner, ServerWorld world, double d2) {
        // Mira hacia ti, pero la cabeza copia la tuya: si tú miras al suelo, él inclina la cabeza
        faceOwner(owner, false);
        setPitch(owner.getPitch());

        boolean sneak = owner.isSneaking();
        setSneaking(sneak);
        setPose(sneak ? EntityPose.CROUCHING : EntityPose.STANDING);
        if (owner.handSwinging && !this.handSwinging) swingHand(Hand.MAIN_HAND);
        if (!owner.isOnGround() && owner.getVelocity().y > 0.2 && this.isOnGround()) this.jump();

        Vec3d now = owner.getPos();
        if (lastOwnerPos == null) lastOwnerPos = now;
        Vec3d mv = now.subtract(lastOwnerPos);
        lastOwnerPos = now;
        double hs = Math.sqrt(mv.x * mv.x + mv.z * mv.z);

        if (hs > 0.04) {
            // Si te mueves, se mueve en tu misma dirección y a paso lento: nunca corre, nunca te persigue
            stillTicks = 0;
            Vec3d dir = new Vec3d(mv.x, 0, mv.z).normalize();
            double sp = Math.max(0.5, Math.min(1.0, hs / 0.215));
            if (life % 5 == 0) getNavigation().startMovingTo(getX() + dir.x * 8, getY(), getZ() + dir.z * 8, sp);
        } else {
            // Si te quedas quieto, se queda quieto... y cada cierto tiempo aparece un bloque más cerca
            getNavigation().stop();
            stillTicks++;
            if (stillTicks > 100 && stillTicks % 160 == 0 && !isBeingLookedAt(owner, 0.86)) approach(owner, world);
        }

        if (d2 < 10.0 * 10.0 || d2 > 90.0 * 90.0) {
            EchoManager.registrarGone(owner);
            vanishQuiet();
        }
    }

    private void approach(ServerPlayerEntity owner, ServerWorld world) {
        Vec3d to = new Vec3d(owner.getX() - getX(), 0, owner.getZ() - getZ());
        if (to.lengthSquared() < 1e-4) return;
        Vec3d dir = to.normalize();
        double nx = getX() + dir.x, nz = getZ() + dir.z;
        BlockPos feet = BlockPos.ofFloored(nx, getY(), nz);
        for (int dy = 1; dy >= -2; dy--) {
            BlockPos p = feet.up(dy);
            if (world.getBlockState(p).isAir() && world.getBlockState(p.up()).isAir()
                    && world.getBlockState(p.down()).isSolidBlock(world, p.down())) {
                this.requestTeleport(nx, p.getY(), nz);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ imitar que tala

    private void tickChop(ServerPlayerEntity owner, ServerWorld world, double d2) {
        if (chopTarget == null) { vanish(); return; }
        if (d2 < 10.0 * 10.0) { vanish(); return; }

        if (chopped) {
            if (++afterChop > 40) vanish();
            return;
        }

        Vec3d tp = Vec3d.ofCenter(chopTarget);
        if (this.squaredDistanceTo(tp) > 6.25) {
            if (life % 10 == 0) getNavigation().startMovingTo(tp.x, tp.y, tp.z, 0.9);
            if (life > 200) vanish();
            return;
        }
        getNavigation().stop();
        getLookControl().lookAt(tp.x, tp.y, tp.z);
        chopTicks++;
        if (chopTicks % 10 == 0) {
            this.swingHand(Hand.MAIN_HAND);
            world.playSound(null, chopTarget, SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 1.0f, 0.9f);
        }
        if (chopTicks >= 60) {
            for (int i = 0; i < 10; i++) {
                BlockPos p = chopTarget.up(i);
                if (world.getBlockState(p).isIn(BlockTags.LOGS)) {
                    world.breakBlock(p, true);
                } else {
                    break;
                }
            }
            chopped = true;
        }
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (!getWorld().isClient) vanish();
        return false;
    }

    /** El Eco nunca persiste: desaparece al recargar. */
    @Override
    public boolean shouldSave() {
        return false;
    }
}
