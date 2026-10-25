package com.otectus.otetv.datagen;

import com.otectus.otetv.block.TvBlock;
import com.otectus.otetv.block.TvModel;
import com.otectus.otetv.registry.ModBlocks;
import com.otectus.otetv.registry.ModItems;
import net.minecraft.advancements.critereon.StatePropertiesPredicate;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.CopyNameFunction;
import net.minecraft.world.level.storage.loot.functions.CopyNbtFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemBlockStatePropertyCondition;
import net.minecraft.world.level.storage.loot.providers.nbt.ContextNbtProvider;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraftforge.registries.RegistryObject;

import java.util.Set;

/**
 * One television per assembly (section 5.2): only the anchor cell's table yields the item, carrying the cosmetic name
 * and picture preset, never ACLs, sources or identity. Non-anchor cells have empty tables; breaking them drops the
 * anchor's loot through the dismantle path. TVs always survive explosions as items.
 */
public final class OteTvBlockLoot extends BlockLootSubProvider {
    public OteTvBlockLoot() {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags());
    }

    @Override
    protected void generate() {
        for (TvModel model : TvModel.values()) {
            TvBlock block = ModBlocks.tv(model);
            LootPool.Builder pool = LootPool.lootPool().setRolls(ConstantValue.exactly(1))
                    .add(LootItem.lootTableItem(ModItems.tv(model))
                            .apply(CopyNameFunction.copyName(CopyNameFunction.NameSource.BLOCK_ENTITY))
                            .apply(CopyNbtFunction.copyData(ContextNbtProvider.BLOCK_ENTITY).copy("tv.preset", "OteTvCosmetic.preset")));
            if (block.partProperty() != null) {
                pool.when(LootItemBlockStatePropertyCondition.hasBlockStateProperties(block)
                        .setProperties(StatePropertiesPredicate.Builder.properties().hasProperty(block.partProperty(), model.anchorPart())));
            }
            add(block, LootTable.lootTable().withPool(pool));
        }
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return ModBlocks.BLOCKS.getEntries().stream().map(RegistryObject::get).map(b -> (Block) b)::iterator;
    }
}
