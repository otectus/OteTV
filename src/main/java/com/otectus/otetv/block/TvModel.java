package com.otectus.otetv.block;

import com.otectus.otetv.core.protocol.VisualPreset;
import net.minecraft.world.level.material.MapColor;

import java.util.List;
import java.util.Locale;

/**
 * The six launch televisions (section 5.1). Era names describe visual inspiration, not reproductions. Each model is
 * code-registered with a fixed footprint, anchor column and collision geometry; resource-pack profiles may only
 * restyle textures, aperture placement and labels within validated bounds (section 20.2).
 * <p>
 * Model space: units are pixels (1/16 block), the front faces north (-Z), the anchor cell spans x 0..16, columns
 * extend along +X from {@code -anchorColumn}, rows extend up from the anchor row.
 */
public enum TvModel {
    WOODEN_MONOCHROME("wooden_monochrome_tv", "1950s", 2, 2, 0, false, true, MapColor.WOOD, VisualPreset.AUTHENTIC,
            List.of(new Box(1, 0, 1, 31, 31.5, 16)),
            List.of()),
    WOODGRAIN_COLOR("woodgrain_color_tv", "1970s", 2, 1, 0, false, true, MapColor.COLOR_BROWN, VisualPreset.AUTHENTIC,
            List.of(new Box(0.5, 0, 2, 31.5, 15.5, 15)),
            List.of()),
    PORTABLE("portable_tv", "1980s", 1, 1, 0, false, false, MapColor.TERRACOTTA_WHITE, VisualPreset.AUTHENTIC,
            List.of(new Box(2, 0, 4, 14, 11, 13), new Box(4, 11, 7.5, 12, 14, 9.5)),
            List.of()),
    CRT("crt_tv", "1990s", 2, 2, 0, false, false, MapColor.COLOR_GRAY, VisualPreset.AUTHENTIC,
            List.of(new Box(0.5, 0, 1, 31.5, 30.5, 12), new Box(6, 6, 12, 26, 25, 15.5)),
            List.of()),
    PLASMA("plasma_tv", "2000s", 3, 2, 1, true, false, MapColor.COLOR_LIGHT_GRAY, VisualPreset.CLEAR,
            List.of(new Box(-2, 0, 4, 18, 1.5, 13), new Box(5, 1.5, 8, 11, 6, 11), new Box(-15, 5.5, 6, 31, 31, 11)),
            List.of(new Box(-15, 4, 11, 31, 29.5, 16))),
    OLED("oled_tv", "modern", 3, 2, 1, true, false, MapColor.COLOR_BLACK, VisualPreset.CLEAR,
            List.of(new Box(-11, 0, 4, -7, 0.5, 12), new Box(23, 0, 4, 27, 0.5, 12), new Box(-15.5, 0.5, 6.5, 31.5, 30, 9)),
            List.of(new Box(-15.5, 3, 14.5, 31.5, 30, 16)));

    /** An axis-aligned box in model pixels. */
    public record Box(double x0, double y0, double z0, double x1, double y1, double z1) {
    }

    private final String registryName;
    private final String era;
    private final int width;
    private final int height;
    private final int anchorColumn;
    private final boolean wallMountable;
    private final boolean wooden;
    private final MapColor mapColor;
    private final VisualPreset defaultPreset;
    private final List<Box> standBoxes;
    private final List<Box> wallBoxes;

    TvModel(String registryName, String era, int width, int height, int anchorColumn, boolean wallMountable, boolean wooden,
            MapColor mapColor, VisualPreset defaultPreset, List<Box> standBoxes, List<Box> wallBoxes) {
        this.registryName = registryName;
        this.era = era;
        this.width = width;
        this.height = height;
        this.anchorColumn = anchorColumn;
        this.wallMountable = wallMountable;
        this.wooden = wooden;
        this.mapColor = mapColor;
        this.defaultPreset = defaultPreset;
        this.standBoxes = standBoxes;
        this.wallBoxes = wallBoxes;
    }

    public String registryName() {
        return registryName;
    }

    public String era() {
        return era;
    }

    /** Columns across the face. */
    public int width() {
        return width;
    }

    /** Rows of blocks. */
    public int height() {
        return height;
    }

    /** Depth in blocks; every launch model is one block deep. */
    public int depth() {
        return 1;
    }

    public int anchorColumn() {
        return anchorColumn;
    }

    public int cellCount() {
        return width * height;
    }

    public boolean isWallMountable() {
        return wallMountable;
    }

    public boolean isWooden() {
        return wooden;
    }

    public MapColor mapColor() {
        return mapColor;
    }

    public VisualPreset defaultPreset() {
        return defaultPreset;
    }

    public boolean isWidescreen() {
        return this == PLASMA || this == OLED;
    }

    /** Media aperture aspect ratio (section 5.1). */
    public double apertureAspect() {
        return isWidescreen() ? 16.0 / 9.0 : 4.0 / 3.0;
    }

    public List<Box> boxes(TvMount mount) {
        return mount == TvMount.WALL && wallMountable ? wallBoxes : standBoxes;
    }

    /** Part index of the anchor (controller) cell. */
    public int anchorPart() {
        return partIndex(0, 0);
    }

    /** Column (relative to the anchor) and row of a part index. */
    public int columnOf(int part) {
        return part % width - anchorColumn;
    }

    public int rowOf(int part) {
        return part / width;
    }

    public int partIndex(int column, int row) {
        return row * width + (column + anchorColumn);
    }

    public String translationKey() {
        return "block.otetv." + registryName;
    }

    public String eraKey() {
        return "otetv.era." + registryName;
    }

    public static TvModel byName(String name) {
        for (TvModel m : values()) {
            if (m.registryName.equals(name) || m.name().toLowerCase(Locale.ROOT).equals(name)) {
                return m;
            }
        }
        return null;
    }
}
