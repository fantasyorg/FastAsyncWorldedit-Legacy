package com.boydti.fawe.object.schematic;

import com.boydti.fawe.FaweCache;
import com.boydti.fawe.object.FaweInputStream;
import com.boydti.fawe.object.FaweOutputStream;
import com.boydti.fawe.util.MainUtil;
import com.boydti.fawe.util.ReflectionUtils;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.DoubleTag;
import com.sk89q.jnbt.FloatTag;
import com.sk89q.jnbt.IntTag;
import com.sk89q.jnbt.ListTag;
import com.sk89q.jnbt.NamedTag;
import com.sk89q.jnbt.StringTag;
import com.sk89q.jnbt.Tag;
import com.sk89q.worldedit.MutableBlockVector;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.registry.WorldData;
import com.sk89q.worldedit.world.storage.NBTConversions;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class FaweFormat implements ClipboardReader, ClipboardWriter {
    private static final int MAX_SIZE = Short.MAX_VALUE - Short.MIN_VALUE;

    private FaweInputStream in;
    private FaweOutputStream out;

    public FaweFormat(FaweInputStream in) {
        this.in = in;
    }

    public FaweFormat(FaweOutputStream out) {
        this.out = out;
    }

    private boolean compressed = false;

    public boolean compress(int i) throws IOException {
        if (compressed) {
            return false;
        }

        compressed = true;
        if (in != null) {
            in = MainUtil.getCompressedIS(in.getParent());
        } else if (out != null) {
            out = MainUtil.getCompressedOS(out.getParent());
        }

        return true;
    }

    @Override
    public Clipboard read(WorldData data) throws IOException {
        return read(data, UUID.randomUUID());
    }

    public Clipboard read(WorldData worldData, UUID clipboardId) throws IOException {
        int width = in.readInt();
        int height = in.readInt();
        int length = in.readInt();
        int ox = in.readInt();
        int oy = in.readInt();
        int oz = in.readInt();
        int minX = in.readInt();
        int minY = in.readInt();
        int minZ = in.readInt();

        Vector min = new Vector(minX, minY, minZ);
        CuboidRegion region = new CuboidRegion(min, min.add(width, height, length).subtract(Vector.ONE));

        BlockArrayClipboard clipboard = new BlockArrayClipboard(region, clipboardId);
        clipboard.setOrigin(new Vector(ox, oy, oz));

        try {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    for (int z = 0; z < length; z++) {
                        int combined = in.readInt();
                        int id = FaweCache.getId(combined);
                        int data = FaweCache.getData(combined);

                        BaseBlock block = FaweCache.getBlock(id, data);
                        clipboard.setBlock(
                                x + min.getBlockX(),
                                y + min.getBlockY(),
                                z + min.getBlockZ(),
                                block
                        );
                    }
                }
            }
        } catch (WorldEditException e) {
            e.printStackTrace();
            return null;
        }

        try {
            NamedTag namedTag;
            while ((namedTag = in.readNBT()) != null) {
                CompoundTag compound = (CompoundTag) namedTag.getTag();
                Map<String, Tag> map = compound.getValue();
                if (map.containsKey("Rotation")) {
                    // Entity
                    String id = compound.getString("id");
                    Location location = NBTConversions.toLocation(clipboard, compound.getListTag("Pos"), compound.getListTag("Rotation"));
                    if (!id.isEmpty()) {
                        BaseEntity state = new BaseEntity(id, compound);
                        clipboard.createEntity(location, state);
                    }
                } else {
                    // Tile
                    int x = compound.getInt("x");
                    int y = compound.getInt("y");
                    int z = compound.getInt("z");
                    clipboard.setTile(x, y, z, compound);

                }
            }
        } catch (Throwable ignore) {
        }

        return clipboard;
    }

    @Override
    public void write(Clipboard clipboard, WorldData worldData) throws IOException {
        write(clipboard, worldData, "FAWE");
    }

    public void write(Clipboard clipboard, WorldData worldData, String owner) throws IOException {
        Region region = clipboard.getRegion();
        int width = region.getWidth();
        int height = region.getHeight();
        int length = region.getLength();

        if (width > MAX_SIZE) {
            throw new IllegalArgumentException("Width of region too large for a .nbt");
        }
        if (height > MAX_SIZE) {
            throw new IllegalArgumentException("Height of region too large for a .nbt");
        }
        if (length > MAX_SIZE) {
            throw new IllegalArgumentException("Length of region too large for a .nbt");
        }

        Vector min = clipboard.getMinimumPoint();
        Vector max = clipboard.getMaximumPoint();
        Vector origin = clipboard.getOrigin().subtract(min);

        // Mode
        ArrayDeque<CompoundTag> tiles = new ArrayDeque<>();

        // Dimensions
        out.writeInt(width);
        out.writeInt(height);
        out.writeInt(length);
        out.writeInt(origin.getBlockX());
        out.writeInt(origin.getBlockY());
        out.writeInt(origin.getBlockZ());

        out.writeInt(min.getBlockX());
        out.writeInt(min.getBlockY());
        out.writeInt(min.getBlockZ());

        MutableBlockVector mutable = new MutableBlockVector(0, 0, 0);

        for (int y = min.getBlockY(); y <= max.getBlockY(); y++) {
            mutable.mutY(y);

            for (int x = min.getBlockX(); x <= max.getBlockX(); x++) {
                mutable.mutX(x);

                for (int z = min.getBlockZ(); z <= max.getBlockZ(); z++) {
                    mutable.mutZ(z);

                    BaseBlock block = clipboard.getBlock(mutable);
                    if (block.getId() == 0) {
                        out.writeInt(0);
                    } else {
                        out.writeInt(FaweCache.getCombined(block));

                        if (block.hasNbtData()) {
                            CompoundTag tile = block.getNbtData();
                            Map<String, Tag> map = ReflectionUtils.getMap(tile.getValue());
                            map.put("id", new StringTag(block.getNbtId()));
                            map.put("x", new IntTag(x - min.getBlockX()));
                            map.put("y", new IntTag(y - min.getBlockY()));
                            map.put("z", new IntTag(z - min.getBlockZ()));
                            tiles.add(tile);
                        }
                    }
                }
            }
        }

        for (CompoundTag tile : tiles) {
            out.writeNBT("", FaweCache.asTag(tile));
        }

        for (Entity entity : clipboard.getEntities()) {
            BaseEntity state = entity.getState();
            if (state != null) {
                CompoundTag entityTag = state.getNbtData();
                Map<String, Tag> map = ReflectionUtils.getMap(entityTag.getValue());
                map.put("id", new StringTag(state.getTypeId()));
                map.put("Pos", writeVector(entity.getLocation().toVector()));
                map.put("Rotation", writeRotation(entity.getLocation()));
                out.writeNBT("", entityTag);
            }
        }

        close();
    }

    @Override
    public void close() throws IOException {
        if (in != null) {
            in.close();
        }

        if (out != null) {
            out.flush();
            out.close();
        }
    }

    private Tag writeVector(Vector vector) {
        List<DoubleTag> list = new ArrayList<>();
        list.add(new DoubleTag(vector.getX()));
        list.add(new DoubleTag(vector.getY()));
        list.add(new DoubleTag(vector.getZ()));
        return new ListTag(DoubleTag.class, list);
    }

    private Tag writeRotation(Location location) {
        List<FloatTag> list = new ArrayList<>();
        list.add(new FloatTag(location.getYaw()));
        list.add(new FloatTag(location.getPitch()));
        return new ListTag(FloatTag.class, list);
    }
}
