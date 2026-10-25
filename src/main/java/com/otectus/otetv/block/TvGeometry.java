package com.otectus.otetv.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Footprint arithmetic shared by placement, breaking, collision, rendering and tests. One rotation convention is used
 * everywhere: it matches blockstate {@code y} rotation, where a north-facing model rotated by 90 faces east.
 * Columns run along {@code facing.getClockWise()}; rows run up.
 */
public final class TvGeometry {
    private static final Map<String, VoxelShape> SHAPES = new ConcurrentHashMap<>();
    private static final VoxelShape FALLBACK = Shapes.box(0.25, 0, 0.25, 0.75, 0.25, 0.75);

    private TvGeometry() {
    }

    /** Blockstate-style y rotation in degrees for a facing: north 0, east 90, south 180, west 270. */
    public static int yRotation(Direction facing) {
        return switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
    }

    public static BlockPos cellPos(BlockPos anchor, Direction facing, int column, int row) {
        return anchor.relative(facing.getClockWise(), column).above(row);
    }

    public static BlockPos cellPos(BlockPos anchor, Direction facing, TvModel model, int part) {
        return cellPos(anchor, facing, model.columnOf(part), model.rowOf(part));
    }

    /** The anchor position for a part at {@code pos}. */
    public static BlockPos anchorOf(BlockPos pos, Direction facing, TvModel model, int part) {
        return pos.relative(facing.getClockWise(), -model.columnOf(part)).below(model.rowOf(part));
    }

    public static List<BlockPos> cells(BlockPos anchor, Direction facing, TvModel model) {
        List<BlockPos> list = new ArrayList<>(model.cellCount());
        for (int part = 0; part < model.cellCount(); part++) {
            list.add(cellPos(anchor, facing, model, part));
        }
        return list;
    }

    /** Rotates a model-space box (pixels, already translated into one cell) into world-oriented block units. */
    static AABB rotate(double x0, double y0, double z0, double x1, double y1, double z1, Direction facing) {
        double ax;
        double az;
        double bx;
        double bz;
        switch (yRotation(facing)) {
            case 90 -> {
                ax = 16 - z0;
                az = x0;
                bx = 16 - z1;
                bz = x1;
            }
            case 180 -> {
                ax = 16 - x0;
                az = 16 - z0;
                bx = 16 - x1;
                bz = 16 - z1;
            }
            case 270 -> {
                ax = z0;
                az = 16 - x0;
                bx = z1;
                bz = 16 - x1;
            }
            default -> {
                ax = x0;
                az = z0;
                bx = x1;
                bz = z1;
            }
        }
        return new AABB(Math.min(ax, bx) / 16.0, y0 / 16.0, Math.min(az, bz) / 16.0,
                Math.max(ax, bx) / 16.0, y1 / 16.0, Math.max(az, bz) / 16.0);
    }

    /** Collision and outline shape of one part: the model boxes clipped to that cell, rotated to the facing. */
    public static VoxelShape partShape(TvModel model, TvMount mount, Direction facing, int part) {
        String key = model.ordinal() + ":" + mount.ordinal() + ":" + facing.get2DDataValue() + ":" + part;
        return SHAPES.computeIfAbsent(key, k -> buildPartShape(model, mount, facing, part));
    }

    private static VoxelShape buildPartShape(TvModel model, TvMount mount, Direction facing, int part) {
        double cx0 = model.columnOf(part) * 16.0;
        double cy0 = model.rowOf(part) * 16.0;
        VoxelShape shape = Shapes.empty();
        for (TvModel.Box b : model.boxes(mount)) {
            double x0 = Math.max(b.x0(), cx0);
            double x1 = Math.min(b.x1(), cx0 + 16);
            double y0 = Math.max(b.y0(), cy0);
            double y1 = Math.min(b.y1(), cy0 + 16);
            double z0 = Math.max(b.z0(), 0);
            double z1 = Math.min(b.z1(), 16);
            if (x1 - x0 <= 1e-6 || y1 - y0 <= 1e-6 || z1 - z0 <= 1e-6) {
                continue;
            }
            AABB box = rotate(x0 - cx0, y0 - cy0, z0, x1 - cx0, y1 - cy0, z1, facing);
            shape = Shapes.joinUnoptimized(shape, Shapes.create(box), BooleanOp.OR);
        }
        // Every cell must be targetable; otherwise a visible TV could be walked or built through (section 5.2).
        return shape.isEmpty() ? FALLBACK : shape.optimize();
    }

    /** World-space bounds of the whole assembly, for render culling. */
    public static AABB assemblyBounds(BlockPos anchor, Direction facing, TvModel model) {
        AABB bounds = null;
        for (BlockPos cell : cells(anchor, facing, model)) {
            AABB a = new AABB(cell);
            bounds = bounds == null ? a : bounds.minmax(a);
        }
        return bounds == null ? new AABB(anchor) : bounds.inflate(0.0625);
    }
}
