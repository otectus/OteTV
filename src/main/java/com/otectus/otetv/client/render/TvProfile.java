package com.otectus.otetv.client.render;

import com.otectus.otetv.block.TvGeometry;
import com.otectus.otetv.block.TvModel;
import com.otectus.otetv.block.TvMount;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Visual profile of one TV model and mount (section 20.2): the media aperture rectangle, period effects and indicator
 * positions, in model pixels (front faces north, anchor cell spans x 0..16). The aperture plane {@code z} is where the
 * screen quad is drawn, slightly in front of the glass so it never Z-fights.
 */
public record TvProfile(Aperture aperture, boolean monochrome, boolean scanlines, boolean vignette, List<Indicator> indicators) {
    public record Aperture(double x0, double y0, double x1, double y1, double z) {
        public double width() {
            return x1 - x0;
        }

        public double height() {
            return y1 - y0;
        }

        public double aspect() {
            return width() / height();
        }
    }

    public enum IndicatorType { LED, DIAL }

    public record Indicator(IndicatorType type, double x, double y, double z, double size) {
    }

    /** Maps a model-space point (pixels) to world coordinates for a placed TV. */
    public static Vec3 toWorld(BlockPos anchor, Direction facing, double px, double py, double pz) {
        double x = px / 16.0;
        double z = pz / 16.0;
        double wx;
        double wz;
        switch (TvGeometry.yRotation(facing)) {
            case 90 -> {
                wx = 1 - z;
                wz = x;
            }
            case 180 -> {
                wx = 1 - x;
                wz = 1 - z;
            }
            case 270 -> {
                wx = z;
                wz = 1 - x;
            }
            default -> {
                wx = x;
                wz = z;
            }
        }
        return new Vec3(anchor.getX() + wx, anchor.getY() + py / 16.0, anchor.getZ() + wz);
    }

    public Vec3 apertureCenter(BlockPos anchor, Direction facing) {
        return toWorld(anchor, facing, (aperture.x0 + aperture.x1) / 2, (aperture.y0 + aperture.y1) / 2, aperture.z);
    }

    /** Outward unit normal of the screen in world space. */
    public static Vec3 normal(Direction facing) {
        return new Vec3(facing.getStepX(), 0, facing.getStepZ());
    }

    /** Model-space bounds a profile aperture must stay inside. */
    public static boolean withinModel(TvModel model, TvMount mount, Aperture a) {
        double minX = -16.0 * model.anchorColumn();
        double maxX = 16.0 * (model.width() - model.anchorColumn());
        return a.x0 >= minX && a.x1 <= maxX && a.y0 >= 0 && a.y1 <= 16.0 * model.height() && a.z >= 0 && a.z <= 16
                && a.width() >= 2 && a.height() >= 2;
    }
}
