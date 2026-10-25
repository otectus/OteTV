package com.otectus.otetv.datagen;

import com.otectus.otetv.OteTV;
import com.otectus.otetv.block.TvModel;
import com.otectus.otetv.registry.ModBlocks;
import com.otectus.otetv.registry.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.ItemTagsProvider;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.data.BlockTagsProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

import java.util.concurrent.CompletableFuture;

/** Mining tags (wooden sets with an axe, the rest with a pickaxe; no tool tier required) and the television tag. */
public final class OteTvTags {
    public static final TagKey<Item> TELEVISION_ITEMS = TagKey.create(Registries.ITEM, OteTV.id("televisions"));
    public static final TagKey<Block> TELEVISION_BLOCKS = TagKey.create(Registries.BLOCK, OteTV.id("televisions"));

    private OteTvTags() {
    }

    public static final class Blocks extends BlockTagsProvider {
        public Blocks(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup, ExistingFileHelper efh) {
            super(output, lookup, OteTV.MOD_ID, efh);
        }

        @Override
        protected void addTags(HolderLookup.Provider provider) {
            for (TvModel model : TvModel.values()) {
                Block block = ModBlocks.tv(model);
                tag(TELEVISION_BLOCKS).add(block);
                tag(model.isWooden() ? BlockTags.MINEABLE_WITH_AXE : BlockTags.MINEABLE_WITH_PICKAXE).add(block);
            }
        }
    }

    public static final class Items extends ItemTagsProvider {
        public Items(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup,
                     CompletableFuture<TagLookup<Block>> blockTags, ExistingFileHelper efh) {
            super(output, lookup, blockTags, OteTV.MOD_ID, efh);
        }

        @Override
        protected void addTags(HolderLookup.Provider provider) {
            for (TvModel model : TvModel.values()) {
                tag(TELEVISION_ITEMS).add(ModItems.tv(model));
            }
        }
    }
}
