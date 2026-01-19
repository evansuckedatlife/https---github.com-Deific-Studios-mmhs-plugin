package com.mmhs.dungeons.generation;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.Location;
import org.bukkit.World;
import java.util.*;
import java.util.logging.Logger;

public class DungeonGenerator {
    private final List<DungeonTemplate> templates;
    private final Logger logger;
    private final Random random = new Random();

    public DungeonGenerator(List<DungeonTemplate> templates, Logger logger) {
        this.templates = templates;
        this.logger = logger;
    }

    public List<PlacedRoom> generate(int numRooms, Location startLocation) {
        List<PlacedRoom> layout = new ArrayList<>();
        
        // 1. Pick and Place Starter Room
        DungeonTemplate starter = templates.stream()
                .filter(DungeonTemplate::isStarter)
                .findFirst()
                .orElse(templates.get(0));

        // Center the starter room on the player/start location
        BlockVector3 startVec = BlockVector3.at(startLocation.getBlockX(), startLocation.getBlockY(), startLocation.getBlockZ());
        PlacedRoom startRoom = new PlacedRoom(starter, startVec, 0);
        layout.add(startRoom);

        // Queue of open doors (World Space)
        Queue<DungeonTemplate.DoorInfo> openDoors = new LinkedList<>(startRoom.getRotatedDoors());

        // 2. Main Generation Loop
        while (layout.size() < numRooms && !openDoors.isEmpty()) {
            DungeonTemplate.DoorInfo targetDoor = openDoors.poll(); // The existing door we want to attach to
            
            boolean roomPlaced = false;
            // Try random templates to fit here
            Collections.shuffle(templates);

            for (DungeonTemplate candidate : templates) {
                if (roomPlaced) break;
                if (candidate.isStarter()) continue; // Don't place more starters

                // Try all 4 rotations (0, 90, 180, 270)
                List<Integer> rotations = Arrays.asList(0, 90, 180, 270);
                Collections.shuffle(rotations);

                for (int rot : rotations) {
                    // Get candidate's doors in this rotation (relative to candidate origin)
                    // We don't construct the full PlacedRoom yet, just do math
                    List<DungeonTemplate.DoorInfo> candidateDoors = getRelativeRotatedDoors(candidate, rot);
                    
                    for (DungeonTemplate.DoorInfo candDoor : candidateDoors) {
                        // CHECK ALIGNMENT
                        // We want the candidate door direction to oppose the target door direction
                        // Target: (0,0,1) [South]  <-->  Candidate: (0,0,-1) [North]
                        if (!areDirectionsOpposite(targetDoor.direction, candDoor.direction)) continue;

                        // CALCULATE ORIGIN
                        // We want the two doors to OVERLAP (share the same block)
                        // TargetWorldPos = NewOrigin + RotatedCandidateDoorPos
                        // NewOrigin = TargetWorldPos - RotatedCandidateDoorPos
                        BlockVector3 newOrigin = targetDoor.position.subtract(candDoor.position);

                        // CREATE & TEST
                        PlacedRoom proposal = new PlacedRoom(candidate, newOrigin, rot);
                        
                        if (!checkCollision(proposal, layout)) {
                            // Success!
                            layout.add(proposal);
                            
                            // Add new doors to the queue (excluding the one we just used)
                            for (DungeonTemplate.DoorInfo newDoor : proposal.getRotatedDoors()) {
                                // Don't add the door that connects back to the previous room
                                if (!newDoor.position.equals(targetDoor.position)) {
                                    openDoors.add(newDoor);
                                }
                            }
                            
                            roomPlaced = true;
                            break; // Stop checking doors/rotations for this step
                        }
                    }
                    if (roomPlaced) break;
                }
            }
        }
        
        logger.info("Generated layout with " + layout.size() + " rooms.");
        return layout;
    }

    private boolean checkCollision(PlacedRoom proposal, List<PlacedRoom> existing) {
        Region regA = proposal.getOccupiedRegion();
        // Shrink the region slightly (by 1 block) to allow walls to touch, 
        // but not internals to overlap. Adjust based on your preference.
        // For now, strict overlap check:
        
        for (PlacedRoom room : existing) {
            Region regB = room.getOccupiedRegion();
            if (regionsIntersect(regA, regB)) {
                return true;
            }
        }
        return false;
    }

    private boolean regionsIntersect(Region a, Region b) {
        BlockVector3 minA = a.getMinimumPoint();
        BlockVector3 maxA = a.getMaximumPoint();
        BlockVector3 minB = b.getMinimumPoint();
        BlockVector3 maxB = b.getMaximumPoint();

        return minA.getX() <= maxB.getX() && maxA.getX() >= minB.getX() &&
               minA.getY() <= maxB.getY() && maxA.getY() >= minB.getY() &&
               minA.getZ() <= maxB.getZ() && maxA.getZ() >= minB.getZ();
    }

    private boolean areDirectionsOpposite(BlockVector3 d1, BlockVector3 d2) {
        // e.g., (0,0,1) + (0,0,-1) == (0,0,0)
        return d1.add(d2).equals(BlockVector3.ZERO);
    }

    // Helper to get rotated door data without creating a full PlacedRoom
    private List<DungeonTemplate.DoorInfo> getRelativeRotatedDoors(DungeonTemplate temp, int rot) {
        List<DungeonTemplate.DoorInfo> list = new ArrayList<>();
        // Simple rotation transform
        // Note: WorldEdit rotations are usually clockwise
        double theta = Math.toRadians(rot);
        int cos = (int) Math.round(Math.cos(theta)); // 1, 0, -1
        int sin = (int) Math.round(Math.sin(theta)); // 0, 1, -1

        for (DungeonTemplate.DoorInfo d : temp.getDoors()) {
            // Manual rotation math for 90 degree increments to be safe/fast
            int x = d.position.getX();
            int z = d.position.getZ();
            
            // x' = x*cos + z*sin
            // z' = -x*sin + z*cos  (Standard 2D rotation matrix)
            // Note: WorldEdit coordinate system might vary, but for 90 deg steps this logic usually holds
            int rx = x * cos - z * sin; // Check sign conventions if rotation is wrong
            int rz = x * sin + z * cos;
            
            BlockVector3 newPos = d.position.withX(rx).withZ(rz);
            
            // Rotate direction too
            int dx = d.direction.getX();
            int dz = d.direction.getZ();
            int rdx = dx * cos - dz * sin;
            int rdz = dx * sin + dz * cos;
            BlockVector3 newDir = d.direction.withX(rdx).withZ(rdz);
            
            list.add(new DungeonTemplate.DoorInfo(newPos, newDir));
        }
        return list;
    }
}