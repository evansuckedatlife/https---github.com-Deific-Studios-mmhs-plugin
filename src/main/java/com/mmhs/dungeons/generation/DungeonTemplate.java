package com.mmhs.dungeons.generation;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;

import java.util.ArrayList;
import java.util.List;

public class DungeonTemplate {
    private final String id;
    private final Clipboard clipboard; // Holds blocks, NBT, and dimensions
    private final List<DoorInfo> doors;
    private final boolean isStarter;

    public DungeonTemplate(String id, Clipboard clipboard, List<DoorInfo> doors, boolean isStarter) {
        this.id = id;
        this.clipboard = clipboard;
        this.doors = doors;
        this.isStarter = isStarter;
    }

    public Clipboard getClipboard() {
        return clipboard;
    }

    public List<DoorInfo> getDoors() {
        return doors;
    }
    
    public boolean isStarter() {
        return isStarter;
    }

    public String getId() {
        return id;
    }

    // Helper class to store door data relative to the clipboard origin
    public static class DoorInfo {
        public final BlockVector3 position; // Relative to clipboard origin
        public final BlockVector3 direction; // Vector pointing OUT of the room (North/South/East/West)

        public DoorInfo(BlockVector3 position, BlockVector3 direction) {
            this.position = position;
            this.direction = direction;
        }
    }
}