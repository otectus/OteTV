package com.otectus.otetv.registry;

import com.otectus.otetv.OteTV;
import com.otectus.otetv.block.TvBlock;
import com.otectus.otetv.block.TvModel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, OteTV.MOD_ID);

    public static final Map<TvModel, RegistryObject<TvBlock>> TVS = new EnumMap<>(TvModel.class);

    static {
        for (TvModel model : TvModel.values()) {
            TVS.put(model, BLOCKS.register(model.registryName(), () -> TvBlock.create(model, properties(model))));
        }
    }

    private static BlockBehaviour.Properties properties(TvModel model) {
        return BlockBehaviour.Properties.of()
                .mapColor(model.mapColor())
                .strength(1.5f, 4.0f)
                .sound(model.isWooden() ? SoundType.WOOD : SoundType.METAL)
                .noOcclusion()
                .isRedstoneConductor((state, level, pos) -> false)
                .isSuffocating((state, level, pos) -> false)
                .isViewBlocking((state, level, pos) -> false)
                // Moving one cell of a multi-block assembly would tear it apart: pistons are refused (section 5.2).
                .pushReaction(PushReaction.BLOCK)
                .randomTicks();
    }

    public static TvBlock tv(TvModel model) {
        return TVS.get(model).get();
    }

    private ModBlocks() {
    }
}
