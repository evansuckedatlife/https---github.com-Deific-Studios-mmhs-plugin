package com.mmhs.dungeons.generation;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.*;
import java.util.logging.Logger;

public class TemplateScanner {
    private final World world;
    private final Logger logger;
    private final Set<BlockVector3> processedBlocks = new HashSet<>();

    public TemplateScanner(World world, Logger logger) {
        this.world = world;
        this.logger = logger;
    }

    public List<DungeonTemplate> scan(int radius, int roomHeight) {
        logger.info("Starting WorldEdit-based template scan...");
        List<DungeonTemplate> templates = new ArrayList<>();
        processedBlocks.clear();

        // 1. Find all Red Concrete blocks
        // Optimization: Instead of checking every block, we loop chunks or use a user-defined center?
        // For now, simple loop is okay if run ONCE during setup.
        List<Location> seeds = findSeeds(radius);

        int count = 0;
        for (Location seed : seeds) {
            BlockVector3 vector = BukkitAdapter.asBlockVector(seed);
            if (processedBlocks.contains(vector)) continue;

            // 2. Flood fill to find the floor layout
            Set<BlockVector3> floorLayout = floodFillFloor(vector);
            if (floorLayout.size() < 10) continue; // Skip tiny noise

            // Mark these as processed so we don't scan this room again
            processedBlocks.addAll(floorLayout);

            // 3. Create a Clipboard from this layout
            try {
                DungeonTemplate template = createTemplateFromLayout(floorLayout, count++, roomHeight);
                templates.add(template);
                logger.info("Captured Room #" + template.getId() + " with " + template.getDoors().size() + " doors.");
            } catch (Exception e) {
                logger.warning("Failed to capture room at " + vector + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        return templates;
    }

    private List<Location> findSeeds(int radius) {
        List<Location> seeds = new ArrayList<>();
        int yStart = -60; // Adjust based on where your builders build
        int yEnd = 60; // Scan range vertically

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                // Optimization: Only check specific Y levels if you know where floors are
                for (int y = yStart; y <= yEnd; y++) {
                    if (world.getBlockAt(x, y, z).getType() == Material.RED_CONCRETE) {
                        seeds.add(new Location(world, x, y, z));
                    }
                }
            }
        }
        return seeds;
    }

    private Set<BlockVector3> floodFillFloor(BlockVector3 start) {
        Set<BlockVector3> floor = new HashSet<>();
        Queue<BlockVector3> queue = new LinkedList<>();
        
        queue.add(start);
        floor.add(start);

        int[][] directions = {{1,0}, {-1,0}, {0,1}, {0,-1}};

        while (!queue.isEmpty()) {
            BlockVector3 current = queue.poll();

            for (int[] dir : directions) {
                BlockVector3 next = current.add(dir[0], 0, dir[1]);
                if (!floor.contains(next) && !processedBlocks.contains(next)) {
                    // Check Bukkit world
                    Block block = world.getBlockAt(next.getBlockX(), next.getBlockY(), next.getBlockZ());
                    if (block.getType() == Material.RED_CONCRETE) {
                        floor.add(next);
                        queue.add(next);
                    }
                }
            }
        }
        return floor;
    }

    private DungeonTemplate createTemplateFromLayout(Set<BlockVector3> floorLayout, int id, int height) throws Exception {
        // Calculate Bounding Box
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (BlockVector3 v : floorLayout) {
            minX = Math.min(minX, v.getBlockX());
            minY = Math.min(minY, v.getBlockY());
            minZ = Math.min(minZ, v.getBlockZ());
            maxX = Math.max(maxX, v.getBlockX());
            maxY = Math.max(maxY, v.getBlockY()); // Use floor Y
            maxZ = Math.max(maxZ, v.getBlockZ());
        }

        // Extend height upwards
        maxY += height;

        BlockVector3 minPoint = BlockVector3.at(minX, minY, minZ);
        BlockVector3 maxPoint = BlockVector3.at(maxX, maxY, maxZ);
        CuboidRegion region = new CuboidRegion(BukkitAdapter.adapt(world), minPoint, maxPoint);

        // Copy to Clipboard
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
        // Important: Set origin to minPoint so relative math is easier
        clipboard.setOrigin(minPoint);

        try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            ForwardExtentCopy copy = new ForwardExtentCopy(
                editSession, region, clipboard, region.getMinimumPoint()
            );
            // Ignore AIR if you only want structure, but usually for dungeons you WANT air
            // copy.setSourceMask(new ExistingBlockMask(editSession)); 
            Operations.complete(copy);
        }

        // Find Doors (Gold Blocks)
        List<DungeonTemplate.DoorInfo> doors = new ArrayList<>();
        
        // We iterate the clipboard's region to find Gold Blocks
        // Note: Using Bukkit world to scan is faster than querying Clipboard sometimes, 
        // but let's query the world to be safe since we have the coords.
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.getBlockAt(x, y, z).getType() == Material.GOLD_BLOCK) {
                        BlockVector3 worldPos = BlockVector3.at(x, y, z);
                        
                        // Calculate vector relative to Clipboard Origin (0,0,0 inside the clipboard)
                        BlockVector3 relativePos = worldPos.subtract(minPoint);
                        
                        // Calculate Direction (Pointing AWAY from the red concrete floor)
                        BlockVector3 direction = calculateDoorDirection(worldPos, floorLayout);
                        
                        doors.add(new DungeonTemplate.DoorInfo(relativePos, direction));
                    }
                }
            }
        }

        return new DungeonTemplate("room_" + id, clipboard, doors, false);
    }

    private BlockVector3 calculateDoorDirection(BlockVector3 doorPos, Set<BlockVector3> floor) {
        // Check 4 cardinal directions. If a direction leads to NO red concrete, that's "Out"
        // Since the door is usually ABOVE the concrete, we check the block BELOW the neighbor
        
        int[][] dirs = {{1,0}, {-1,0}, {0,1}, {0,-1}}; // East, West, South, North
        
        for (int[] dir : dirs) {
            BlockVector3 neighbor = doorPos.add(dir[0], 0, dir[1]);
            // Check the floor block below this neighbor
            BlockVector3 floorCheck = neighbor.withY(doorPos.getBlockY() - 1); 
            // Or if the door is on the same level as floor, just check neighbor.
            // Assuming Gold Block is ON the red concrete (Y+1), or part of the wall.
            // Let's assume Gold Block is at Y, Floor is at Y-1.
            
            // If the floor set DOES NOT contain this block, it's outside the room
            if (!floor.contains(floorCheck) && !floor.contains(neighbor)) {
                return BlockVector3.at(dir[0], 0, dir[1]);
            }
        }
        
        // Fallback: Default North
        return BlockVector3.at(0, 0, -1);
    }
}