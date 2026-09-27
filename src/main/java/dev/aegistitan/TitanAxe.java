package dev.aegistitan;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** The Titan Cleaver's sneak + right click "Titan Slam". */
final class TitanAxe implements Listener {

    // Axe model size (blocks)
    private static final double HANDLE = 11.0;
    private static final double BLADE = 3.4;
    private static final double HEAD_POS = HANDLE - 1.0;
    private static final double AXIS_HEIGHT = BLADE - 0.8;

    // Timeline (ticks)
    private static final int SUMMON = 14;
    private static final int SWING = 7;
    private static final int IMPACT = SUMMON + SWING;
    private static final int LINGER = 26;

    private static final double WIND_UP = Math.toRadians(-38);
    private static final double START = Math.toRadians(-8);
    private static final double DOWN = Math.toRadians(90);

    private static final Particle.DustOptions HANDLE_DARK = Fx.dust(0x2B2527, 2.0f);
    private static final Particle.DustOptions HANDLE_WRAP = Fx.dust(0x5A4636, 2.0f);
    private static final Particle.DustOptions BLADE_BODY = Fx.dust(0x4D494F, 2.2f);
    private static final Particle.DustOptions BLADE_TRIM = Fx.dust(0x6B3F7A, 2.0f);
    private static final Particle.DustOptions BLADE_EDGE = Fx.dust(0x9C97A0, 1.8f);
    private static final Particle.DustTransition EDGE_FIRE = Fx.fade(0xFF7A1A, 0xFFE9A0, 1.7f);
    private static final Particle.DustOptions SHOCK = Fx.dust(0xB9AE9C, 2.2f);
    private static final Particle.DustOptions SHOCK_HOT = Fx.dust(0xFF8A2A, 1.6f);

    private final AegisTitan plugin;
    private final Items items;
    private final WallManager walls;
    private final Terrain terrain;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Random random = new Random();

    TitanAxe(AegisTitan plugin, Items items, WallManager walls, Terrain terrain) {
        this.plugin = plugin;
        this.items = items;
        this.walls = walls;
        this.terrain = terrain;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking() || !items.isAxe(player.getInventory().getItemInMainHand())) {
            return;
        }
        event.setCancelled(true); // no log stripping etc.

        long now = System.currentTimeMillis();
        long ready = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now < ready) {
            double left = (ready - now) / 1000.0;
            player.sendActionBar(Component.text(String.format("Titan Slam recharging\u2026 %.1fs", left), NamedTextColor.GOLD));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.5f);
            return;
        }
        long cooldownMs = Math.max(0, plugin.getConfig().getInt("axe.cooldown-seconds", 15)) * 1000L;
        cooldowns.put(player.getUniqueId(), now + cooldownMs);
        if (cooldownMs > 0) {
            player.setCooldown(Material.NETHERITE_AXE, (int) (cooldownMs / 50));
        }
        new Slam(player).runTaskTimer(plugin, 0L, 1L);
    }

    // ================================================================== the slam animation

    private final class Slam extends BukkitRunnable {

        private final UUID casterId;
        private final World world;
        private final Vector impact;
        private final Vector forward;
        private final Vector side;
        private final Vector up = new Vector(0, 1, 0);
        private final Vector pivot;
        private final BlockData groundData;
        private final Set<UUID> hit = new HashSet<>();

        private final double radius;
        private final double maxDamage;
        private final double minDamage;
        private final double knockback;
        private final double cutRadius;
        private final boolean crater;
        private final boolean flyingDebris;

        private int t;
        private double lastTheta = START;

        Slam(Player player) {
            FileConfiguration cfg = plugin.getConfig();
            this.radius = cfg.getDouble("axe.shockwave-radius", 10);
            this.maxDamage = cfg.getDouble("axe.max-damage", 12);
            this.minDamage = cfg.getDouble("axe.min-damage", 4);
            this.knockback = cfg.getDouble("axe.knockback", 1.6);
            this.cutRadius = cfg.getDouble("axe.shield-cut-radius", 7);
            this.crater = cfg.getBoolean("axe.crater", true);
            this.flyingDebris = cfg.getBoolean("axe.flying-debris", true);
            double range = cfg.getDouble("axe.range", 30);

            this.casterId = player.getUniqueId();
            this.world = player.getWorld();
            Location eye = player.getEyeLocation();
            Vector origin = eye.toVector();
            Vector dir = eye.getDirection().normalize();

            // What are we aiming at? Blocks, creatures, or someone's shield wall.
            Vector target = null;
            double hitDist = range;
            RayTraceResult rt = world.rayTrace(eye, dir, range, FluidCollisionMode.NEVER, true, 0.8,
                    e -> e instanceof LivingEntity && !e.getUniqueId().equals(casterId));
            if (rt != null) {
                hitDist = rt.getHitPosition().distance(origin);
                target = rt.getHitEntity() != null ? rt.getHitEntity().getLocation().toVector() : rt.getHitPosition();
            }
            WallManager.WallHit wallHit = walls.rayCast(world, origin, dir, hitDist, casterId);
            if (wallHit != null) {
                target = wallHit.point();
            }

            Vector yawDir = new Vector(-Math.sin(Math.toRadians(eye.getYaw())), 0, Math.cos(Math.toRadians(eye.getYaw())));
            if (target == null) {
                Vector flat = dir.clone().setY(0);
                if (flat.lengthSquared() < 1e-4) {
                    flat = yawDir.clone();
                }
                target = origin.clone().add(flat.normalize().multiply(12));
            }

            Vector fwd = target.clone().subtract(origin).setY(0);
            if (fwd.lengthSquared() < 0.01) {
                fwd = yawDir.clone();
            }
            fwd.normalize();
            double flatDist = Math.hypot(target.getX() - origin.getX(), target.getZ() - origin.getZ());
            if (flatDist < 3.5) {
                double y = target.getY();
                target = origin.clone().add(fwd.clone().multiply(3.5));
                target.setY(y);
            }

            this.impact = groundBelow(target);
            this.forward = fwd;
            this.side = fwd.getCrossProduct(up).normalize();
            this.pivot = impact.clone().subtract(forward.clone().multiply(HEAD_POS)).add(up.clone().multiply(AXIS_HEIGHT));

            Block ground = world.getBlockAt(impact.getBlockX(), impact.getBlockY() - 1, impact.getBlockZ());
            this.groundData = ground.getType().isSolid() ? ground.getBlockData() : Material.DIRT.createBlockData();
        }

        private Vector groundBelow(Vector target) {
            int x = (int) Math.floor(target.getX());
            int z = (int) Math.floor(target.getZ());
            int y = (int) Math.floor(target.getY() + 0.5);
            for (int i = 0; i < 6 && world.getBlockAt(x, y, z).getType().isSolid(); i++) {
                y++;
            }
            for (int i = 0; i < 48 && y > world.getMinHeight(); i++) {
                if (world.getBlockAt(x, y - 1, z).getType().isSolid()) {
                    break;
                }
                y--;
            }
            return new Vector(target.getX(), y, target.getZ());
        }

        @Override
        public void run() {
            Player caster = Bukkit.getPlayer(casterId);
            if (t == 0) {
                Location loc = pivot.toLocation(world);
                world.playSound(loc, Sound.ITEM_ARMOR_EQUIP_NETHERITE, 2f, 0.5f);
                world.playSound(loc, Sound.BLOCK_BEACON_ACTIVATE, 1.5f, 0.5f);
            } else if (t == 6) {
                world.playSound(pivot.toLocation(world), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 2f, 0.6f);
            } else if (t == SUMMON) {
                Location loc = pivot.toLocation(world);
                world.playSound(loc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 2f, 0.5f);
                world.playSound(loc, Sound.ENTITY_BREEZE_WIND_BURST, 2f, 0.6f);
            }

            if (t < SUMMON) {
                summonFrame();
            } else if (t < IMPACT) {
                swingFrame();
            } else if (t == IMPACT) {
                impactFrame(caster);
            }

            if (t >= IMPACT) {
                int since = t - IMPACT;
                if (since <= LINGER) {
                    drawAxe(DOWN, 1.0 - since / (double) LINGER);
                    dissolve(since);
                }
                shockwave(caster, since);
                if (since > LINGER && since * 0.85 > radius + 1) {
                    cancel();
                    return;
                }
            }
            t++;
        }

        // ------------------------------------------------------------ phases

        private void summonFrame() {
            double p = t / (double) SUMMON;
            double ease = 1 - Math.pow(1 - p, 3);
            double theta = START + (WIND_UP - START) * ease;
            lastTheta = theta;
            drawAxe(theta, Math.min(1, 0.15 + p * 1.1));

            // Dark energy spiralling into the axe head
            Vector head = pivot.clone().add(axis(theta).multiply(HEAD_POS));
            for (int i = 0; i < 6; i++) {
                double a = t * 0.6 + i * Math.PI / 3;
                double r = 3.5 * (1 - p) + 0.6;
                Vector from = head.clone().add(side.clone().multiply(Math.cos(a) * r)).add(up.clone().multiply(Math.sin(a) * r));
                Fx.move(world, Particle.SOUL_FIRE_FLAME, from, head.clone().subtract(from), 0.12);
            }
            Fx.spawn(world, Particle.REVERSE_PORTAL, head, 10, 1.2, 0.02);
            if (t % 3 == 0) {
                Fx.spawn(world, Particle.ENCHANT, head, 20, 1.5, 1.0);
            }
        }

        private void swingFrame() {
            double p = (t - SUMMON + 1) / (double) SWING;
            double theta = WIND_UP + (DOWN - WIND_UP) * p * p * p;
            // Ghost trail between last frame and this one
            for (int k = 1; k <= 2; k++) {
                double mid = lastTheta + (theta - lastTheta) * k / 3.0;
                drawAxe(mid, 0.3);
            }
            drawAxe(theta, 1.0);
            Vector head = pivot.clone().add(axis(theta).multiply(HEAD_POS)).add(bladeDir(theta).multiply(BLADE * 0.6));
            Fx.spawn(world, Particle.SWEEP_ATTACK, head, 3, 1.0, 0);
            Fx.spawn(world, Particle.CLOUD, head, 6, 0.8, 0.05);
            lastTheta = theta;
        }

        private void impactFrame(Player caster) {
            Location loc = impact.toLocation(world);
            world.playSound(loc, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 3f, 0.6f);
            world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.55f);
            world.playSound(loc, Sound.ENTITY_WARDEN_ATTACK_IMPACT, 3f, 0.5f);
            world.playSound(loc, Sound.BLOCK_ANVIL_LAND, 2f, 0.5f);
            world.playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM, 2f, 0.7f);

            Vector top = impact.clone().add(new Vector(0, 0.3, 0));
            Fx.spawn(world, Particle.EXPLOSION_EMITTER, top, 1, 0, 0);
            Fx.spawn(world, Particle.EXPLOSION, top, 8, 1.5, 0);
            Fx.spawn(world, Particle.FLASH, top, 1, 0, 0, 0, 0, Color.WHITE);
            Fx.spawn(world, Particle.SONIC_BOOM, top, 1, 0, 0);
            Fx.spawn(world, Particle.BLOCK, top, 160, 1.5, 0.4, 1.5, 0.4, groundData);
            Fx.spawn(world, Particle.CAMPFIRE_COSY_SMOKE, top, 20, 1.2, 0.3, 1.2, 0.03, null);
            Fx.spawn(world, Particle.LAVA, top, 14, 1.0, 0);
            Fx.spawn(world, Particle.DUST_PLUME, top, 40, 1.5, 0.05);

            if (crater) {
                terrain.carve(world, impact, forward, side, random);
            }

            // Any shield wall caught in the blast gets split in two
            for (Wall wall : walls.wallsNear(world, impact, cutRadius, casterId)) {
                walls.shatter(wall, impact);
            }
        }

        private void dissolve(int since) {
            // Glowing crack + ash drifting off the embedded axe
            if (since < 12) {
                Fx.spawn(world, Particle.LAVA, impact, 2, 0.8, 0);
                Fx.spawn(world, Particle.CAMPFIRE_COSY_SMOKE, impact, 2, 1.5, 0.2, 1.5, 0.02, null);
            }
            for (int i = 0; i < 8; i++) {
                double s = random.nextDouble() * (HANDLE + 0.5);
                Vector p = pivot.clone().add(axis(DOWN).multiply(s));
                Fx.move(world, Particle.WHITE_ASH, p, new Vector(0, 1, 0), 0.05);
                if (random.nextInt(3) == 0) {
                    Fx.move(world, Particle.SOUL, p, new Vector(0, 1, 0), 0.04);
                }
            }
        }

        private void shockwave(Player caster, int since) {
            double r = since * 0.85;
            if (r > radius) {
                return;
            }
            int n = (int) (2 * Math.PI * r / 0.6) + 8;
            for (int k = 0; k < n; k++) {
                double a = k * 2 * Math.PI / n;
                double x = impact.getX() + Math.cos(a) * r;
                double z = impact.getZ() + Math.sin(a) * r;
                Block top = Terrain.surface(world, x, z, impact.getY());
                if (top == null) {
                    continue;
                }
                Vector p = new Vector(x, top.getY() + 1.05, z);
                BlockData data = top.getBlockData();
                Fx.spawn(world, Particle.BLOCK, p, 2, 0.15, 0.05, 0.15, 0.1, data);
                Fx.dust(world, p.clone().add(new Vector(0, 0.25, 0)), since < 4 ? SHOCK_HOT : SHOCK);
                if (k % 3 == 0) {
                    Fx.spawn(world, Particle.POOF, p, 1, 0.1, 0.02);
                }
                if (k % 6 == 0) {
                    Fx.spawn(world, Particle.SWEEP_ATTACK, p.clone().add(new Vector(0, 0.4, 0)), 1, 0, 0);
                }
                if (flyingDebris && since % 2 == 0 && random.nextInt(7) == 0 && top.getType().isOccluding()) {
                    Vector out = new Vector(Math.cos(a), 0, Math.sin(a)).multiply(0.12);
                    out.setY(0.32 + random.nextDouble() * 0.18);
                    terrain.launchDebris(world, new Vector(Math.floor(x) + 0.5, top.getY() + 1.0, Math.floor(z) + 0.5), data, out);
                }
            }
            if (since % 3 == 0) {
                world.playSound(impact.toLocation(world), Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 0.4f + (float) (r / radius) * 0.3f);
            }

            // Hit everything the ring has reached
            Location center = impact.toLocation(world);
            for (Entity e : world.getNearbyEntities(center, radius + 1, 6, radius + 1)) {
                if (!(e instanceof LivingEntity le) || e.getUniqueId().equals(casterId) || hit.contains(e.getUniqueId())) {
                    continue;
                }
                if (le.isDead() || (le instanceof Player pl
                        && (pl.getGameMode() == GameMode.CREATIVE || pl.getGameMode() == GameMode.SPECTATOR))) {
                    continue;
                }
                Vector pos = le.getLocation().toVector();
                double dist = Math.hypot(pos.getX() - impact.getX(), pos.getZ() - impact.getZ());
                if (dist > r || dist > radius || Math.abs(pos.getY() - impact.getY()) > 5) {
                    continue;
                }
                hit.add(e.getUniqueId());

                // Another (uncut) shield wall in the way still protects them
                WallManager.WallHit block = walls.blockingWall(le, impact.clone().add(new Vector(0, 1, 0)), casterId);
                if (block != null) {
                    walls.impact(block.wall(), block.point());
                    continue;
                }

                double damage = maxDamage - (maxDamage - minDamage) * (dist / Math.max(0.1, radius));
                if (caster != null) {
                    le.damage(damage, caster);
                } else {
                    le.damage(damage);
                }
                Vector push = pos.clone().subtract(impact).setY(0);
                if (push.lengthSquared() < 1e-4) {
                    push = new Vector(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5);
                }
                push.normalize().multiply(knockback * (1 - 0.5 * dist / Math.max(0.1, radius)));
                push.setY(0.55);
                le.setVelocity(push);
                Fx.spawn(world, Particle.CRIT, pos.clone().add(new Vector(0, 1, 0)), 12, 0.3, 0.3);
            }
        }

        // ------------------------------------------------------------ the particle axe itself

        /** Direction from the handle end to the axe head. theta 0 = straight up, 90 = flat forward. */
        private Vector axis(double theta) {
            return forward.clone().multiply(Math.sin(theta)).add(up.clone().multiply(Math.cos(theta)));
        }

        /** Direction the cutting edge faces. */
        private Vector bladeDir(double theta) {
            return forward.clone().multiply(Math.cos(theta)).subtract(up.clone().multiply(Math.sin(theta)));
        }

        private void drawAxe(double theta, double visibility) {
            Vector a = axis(theta);
            Vector b = bladeDir(theta);

            // Handle
            for (double s = -0.4; s <= HANDLE + 0.5; s += 0.3) {
                if (visibility < 1 && random.nextDouble() > visibility) {
                    continue;
                }
                boolean wrap = s < HANDLE * 0.55 && ((int) Math.floor(s / 1.1)) % 2 == 0;
                Fx.dust(world, at(a, b, s, 0), wrap ? HANDLE_WRAP : HANDLE_DARK);
            }
            // Pommel
            if (visibility >= 1 || random.nextDouble() < visibility) {
                Fx.dust(world, at(a, b, -0.6, 0.25), BLADE_TRIM);
                Fx.dust(world, at(a, b, -0.6, -0.25), BLADE_TRIM);
            }

            // Main blade (flares out toward a curved cutting edge)
            for (double tt = 0; tt <= BLADE; tt += 0.3) {
                double hl = 0.75 + 1.55 * Math.pow(tt / BLADE, 1.6);
                for (double q = -hl; q <= hl; q += 0.3) {
                    double edgeT = BLADE - 0.55 * (q / 2.3) * (q / 2.3);
                    if (tt > edgeT) {
                        continue;
                    }
                    if (visibility < 1 && random.nextDouble() > visibility) {
                        continue;
                    }
                    Particle.DustOptions color;
                    if (tt > edgeT - 0.45) {
                        color = BLADE_EDGE;
                    } else if (Math.abs(q) > hl - 0.3 || tt < 0.3) {
                        color = BLADE_TRIM;
                    } else {
                        color = BLADE_BODY;
                    }
                    Fx.dust(world, at(a, b, HEAD_POS + q, tt), color);
                }
            }

            // Red-hot cutting edge (it has Fire Aspect after all)
            for (double q = -2.3; q <= 2.3; q += 0.35) {
                if (visibility < 1 && random.nextDouble() > visibility) {
                    continue;
                }
                double tt = BLADE - 0.55 * (q / 2.3) * (q / 2.3);
                Vector p = at(a, b, HEAD_POS + q, tt);
                Fx.fade(world, p, EDGE_FIRE);
                if (random.nextInt(8) == 0) {
                    Fx.spawn(world, Particle.FLAME, p, 1, 0.05, 0.01);
                }
            }

            // Back spike
            for (double tt = 0.3; tt <= 1.3; tt += 0.25) {
                double hl = 0.55 * (1 - (tt - 0.3));
                for (double q = -hl; q <= hl + 1e-9; q += 0.25) {
                    if (visibility < 1 && random.nextDouble() > visibility) {
                        continue;
                    }
                    Fx.dust(world, at(a, b, HEAD_POS + q, -tt), BLADE_BODY);
                }
            }
        }

        private Vector at(Vector axis, Vector blade, double s, double tt) {
            return new Vector(
                    pivot.getX() + axis.getX() * s + blade.getX() * tt,
                    pivot.getY() + axis.getY() * s + blade.getY() * tt,
                    pivot.getZ() + axis.getZ() * s + blade.getZ() * tt);
        }
    }
}
