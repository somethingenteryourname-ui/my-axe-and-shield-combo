package dev.aegistitan;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Carves the smashed-ground crater and puts everything back later. */
final class Terrain implements Listener {

    private record Change(Block block, BlockData original, BlockData placed) {
    }

    private static final BlockFace[] CHECK_FACES = {
            BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final AegisTitan plugin;
    private final NamespacedKey debrisKey;
    private final List<List<Change>> pending = new ArrayList<>();
    private final Set<FallingBlock> debris = new HashSet<>();

    Terrain(AegisTitan plugin) {
        this.plugin = plugin;
        this.debrisKey = new NamespacedKey(plugin, "titan_debris");
    }

    /** Top solid block of the column at (x, z), searching around y. */
    static Block surface(World world, double x, double z, double y) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int start = (int) Math.floor(y) + 3;
        for (int by = start; by > start - 9 && by > world.getMinHeight(); by--) {
            Block b = world.getBlockAt(bx, by, bz);
            if (b.getType().isSolid()) {
                return b;
            }
        }
        return null;
    }

    /** Split the ground where the axe blade landed. */
    void carve(World world, Vector impact, Vector forward, Vector side, Random random) {
        List<Change> batch = new ArrayList<>();
        Set<Block> done = new HashSet<>();

        // The gash: deepest in the middle of the blade, shallower at the ends
        for (double a = -3.4; a <= 3.4; a += 0.45) {
            int depth = Math.abs(a) < 1.6 ? 3 : (Math.abs(a) < 2.8 ? 2 : 1);
            for (double b = -0.7; b <= 0.7; b += 0.45) {
                double x = impact.getX() + forward.getX() * a + side.getX() * b;
                double z = impact.getZ() + forward.getZ() * a + side.getZ() * b;
                Block top = surface(world, x, z, impact.getY());
                if (top == null) {
                    continue;
                }
                clearSoftAbove(top, batch, done);
                for (int d = 0; d < depth; d++) {
                    Block bl = top.getRelative(0, -d, 0);
                    if (!done.contains(bl) && canModify(bl)) {
                        done.add(bl);
                        set(bl, Material.AIR.createBlockData(), batch);
                    }
                }
            }
        }

        // Cracked, busted-up ground around it, with chunks pushed up near the gash
        for (int k = 0; k < 75; k++) {
            double ang = random.nextDouble() * Math.PI * 2;
            double r = 1.2 + random.nextDouble() * 3.3;
            double a = Math.cos(ang) * r * 1.35;
            double b = Math.sin(ang) * r * 0.9;
            double x = impact.getX() + forward.getX() * a + side.getX() * b;
            double z = impact.getZ() + forward.getZ() * a + side.getZ() * b;
            Block top = surface(world, x, z, impact.getY());
            if (top == null || done.contains(top) || !canModify(top)) {
                continue;
            }
            done.add(top);
            BlockData cracked = crackedVersion(top.getType(), random);
            clearSoftAbove(top, batch, done);
            set(top, cracked, batch);
            if (r < 2.6 && random.nextDouble() < 0.4) {
                Block above = top.getRelative(BlockFace.UP);
                if (above.getType().isAir() && !done.contains(above)) {
                    done.add(above);
                    set(above, crackedVersion(top.getType(), random), batch);
                }
            }
        }

        if (batch.isEmpty()) {
            return;
        }
        int seconds = plugin.getConfig().getInt("axe.crater-restore-seconds", 30);
        if (seconds <= 0) {
            return; // permanent craters
        }
        pending.add(batch);
        long delay = seconds * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(batch)) {
                restore(batch, true);
            }
        }, delay);
    }

    /** Remove a flower/grass/snow layer sitting on a block so it doesn't float or drop. */
    private void clearSoftAbove(Block top, List<Change> batch, Set<Block> done) {
        Block above = top.getRelative(BlockFace.UP);
        Material m = above.getType();
        if (!m.isAir() && !m.isSolid() && !above.isLiquid() && !done.contains(above)
                && !(above.getState() instanceof TileState)) {
            done.add(above);
            set(above, Material.AIR.createBlockData(), batch);
        }
    }

    private boolean canModify(Block b) {
        Material m = b.getType();
        if (m.isAir() || !m.isSolid() || m.hasGravity()) {
            return false;
        }
        float hardness = m.getHardness();
        if (hardness < 0 || hardness >= 50) {
            return false; // bedrock, obsidian, barriers...
        }
        if (b.getState() instanceof TileState) {
            return false; // chests, furnaces, signs... never touch
        }
        for (BlockFace face : CHECK_FACES) {
            Block n = b.getRelative(face);
            if (n.isLiquid() || n.getType().hasGravity()) {
                return false;
            }
        }
        return true;
    }

    private static void set(Block block, BlockData data, List<Change> batch) {
        batch.add(new Change(block, block.getBlockData(), data));
        block.setBlockData(data, false);
    }

    private static BlockData crackedVersion(Material m, Random random) {
        String n = m.name();
        Material[] pool;
        if (n.contains("DEEPSLATE")) {
            pool = new Material[]{Material.COBBLED_DEEPSLATE, Material.CRACKED_DEEPSLATE_TILES, Material.CRACKED_DEEPSLATE_BRICKS};
        } else if (m == Material.GRASS_BLOCK || n.contains("DIRT") || n.contains("MUD") || n.contains("MOSS")
                || m == Material.PODZOL || m == Material.MYCELIUM || m == Material.FARMLAND) {
            pool = new Material[]{Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.DIRT, Material.PACKED_MUD};
        } else if (n.contains("RED_SAND")) {
            pool = new Material[]{Material.RED_SANDSTONE, Material.CUT_RED_SANDSTONE};
        } else if (n.contains("SAND")) {
            pool = new Material[]{Material.SANDSTONE, Material.CUT_SANDSTONE};
        } else if (n.contains("NETHERRACK") || n.contains("NYLIUM") || n.contains("NETHER")) {
            pool = new Material[]{Material.NETHERRACK, Material.CRACKED_NETHER_BRICKS, Material.BLACKSTONE};
        } else if (n.contains("END_STONE")) {
            pool = new Material[]{Material.END_STONE, Material.END_STONE_BRICKS};
        } else if (n.contains("SNOW") || n.contains("ICE")) {
            pool = new Material[]{Material.SNOW_BLOCK, Material.PACKED_ICE};
        } else {
            pool = new Material[]{Material.COBBLESTONE, Material.CRACKED_STONE_BRICKS, Material.TUFF, Material.ANDESITE};
        }
        return pool[random.nextInt(pool.length)].createBlockData();
    }

    private void restore(List<Change> batch, boolean effects) {
        Set<Block> restored = new HashSet<>();
        for (int i = batch.size() - 1; i >= 0; i--) {
            Change c = batch.get(i);
            if (c.block().getBlockData().equals(c.placed())) {
                c.block().setBlockData(c.original(), false);
                restored.add(c.block());
                if (effects && i % 4 == 0 && !c.original().getMaterial().isAir()) {
                    Vector p = c.block().getLocation().toVector().add(new Vector(0.5, 1.0, 0.5));
                    Fx.spawn(c.block().getWorld(), Particle.BLOCK, p, 4, 0.3, 0.1, 0.3, 0.05, c.original());
                }
            }
        }
        if (restored.isEmpty()) {
            return;
        }
        Block first = restored.iterator().next();
        World world = first.getWorld();
        Location center = first.getLocation();
        if (effects) {
            world.playSound(center, Sound.BLOCK_ROOTED_DIRT_PLACE, 1.2f, 0.7f);
        }
        // Don't bury anyone who is standing in the crater
        for (Entity e : world.getNearbyEntities(center, 8, 6, 8)) {
            if (!(e instanceof LivingEntity)) {
                continue;
            }
            Location loc = e.getLocation();
            int lift = 0;
            while (lift < 6 && (restored.contains(loc.getBlock()) || loc.getBlock().getType().isSolid())) {
                loc.add(0, 1, 0);
                lift++;
            }
            if (lift > 0) {
                e.teleport(loc);
            }
        }
    }

    /** Put everything back immediately (used when the server stops). */
    void restoreAll() {
        for (List<Change> batch : new ArrayList<>(pending)) {
            restore(batch, false);
        }
        pending.clear();
        for (FallingBlock fb : debris) {
            if (fb.isValid()) {
                fb.remove();
            }
        }
        debris.clear();
    }

    // ------------------------------------------------------------------ flying chunks of ground

    void launchDebris(World world, Vector at, BlockData data, Vector velocity) {
        debris.removeIf(fb -> !fb.isValid());
        if (debris.size() >= 120) {
            return;
        }
        FallingBlock fb = world.spawnFallingBlock(at.toLocation(world), data);
        fb.setDropItem(false);
        fb.setCancelDrop(true);
        fb.setHurtEntities(false);
        fb.getPersistentDataContainer().set(debrisKey, PersistentDataType.BYTE, (byte) 1);
        fb.setVelocity(velocity);
        debris.add(fb);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (fb.isValid()) {
                fb.remove();
            }
            debris.remove(fb);
        }, 60L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDebrisLand(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof FallingBlock fb
                && fb.getPersistentDataContainer().has(debrisKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            Vector p = fb.getLocation().toVector();
            Fx.spawn(fb.getWorld(), Particle.BLOCK, p, 8, 0.25, 0.1, 0.25, 0.1, fb.getBlockData());
            fb.remove();
            debris.remove(fb);
        }
    }
}
