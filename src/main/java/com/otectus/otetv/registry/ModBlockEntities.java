package com.otectus.otetv.registry;

import com.otectus.otetv.OteTV;
import com.otectus.otetv.block.entity.TvBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, OteTV.MOD_ID);

    /** One controller block entity type for every television; only the anchor cell of an assembly carries one. */
    @SuppressWarnings("DataFlowIssue")
    public static final RegistryObject<BlockEntityType<TvBlockEntity>> TELEVISION = BLOCK_ENTITIES.register("television",
            () -> BlockEntityType.Builder.of(TvBlockEntity::new,
                    ModBlocks.TVS.values().stream().map(RegistryObject::get).toArray(Block[]::new)).build(null));

    private ModBlockEntities() {
    }
}
