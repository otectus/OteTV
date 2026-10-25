package com.otectus.otetv.block;

import net.minecraft.util.StringRepresentable;

/** Floor stand or wall mount. Wall mounting is an alternate placement configuration for the flat panels only. */
public enum TvMount implements StringRepresentable {
    STAND("stand"),
    WALL("wall");

    private final String name;

    TvMount(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
