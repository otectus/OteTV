package com.otectus.otetv.datagen;

import com.otectus.otetv.OteTV;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraftforge.common.data.ExistingFileHelper;
import net.minecraftforge.common.data.ForgeAdvancementProvider;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Data generation (section 5.4 and 21.4): recipes with recipe-book unlocks, loot tables that drop one television per
 * assembly, mining tags, the television item tag and advancements. Output goes to src/generated/resources via
 * {@code gradlew runData}. Visual assets come from tools/assets/generate_assets.py.
 */
@Mod.EventBusSubscriber(modid = OteTV.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class OteTvDataGen {
    private OteTvDataGen() {
    }

    @SubscribeEvent
    public static void gather(GatherDataEvent event) {
        DataGenerator gen = event.getGenerator();
        PackOutput out = gen.getPackOutput();
        ExistingFileHelper efh = event.getExistingFileHelper();
        CompletableFuture<HolderLookup.Provider> lookup = event.getLookupProvider();
        boolean server = event.includeServer();
        gen.addProvider(server, new OteTvRecipes(out));
        gen.addProvider(server, new LootTableProvider(out, Set.of(),
                List.of(new LootTableProvider.SubProviderEntry(OteTvBlockLoot::new, LootContextParamSets.BLOCK))));
        OteTvTags.Blocks blockTags = new OteTvTags.Blocks(out, lookup, efh);
        gen.addProvider(server, blockTags);
        gen.addProvider(server, new OteTvTags.Items(out, lookup, blockTags.contentsGetter(), efh));
        gen.addProvider(server, new ForgeAdvancementProvider(out, lookup, efh, List.of(new OteTvAdvancements())));
    }
}
