package com.otectus.otetv.datagen;

import com.otectus.otetv.OteTV;
import com.otectus.otetv.advancement.ModCriteria;
import com.otectus.otetv.block.TvModel;
import com.otectus.otetv.registry.ModItems;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.FrameType;
import net.minecraft.advancements.RequirementsStrategy;
import net.minecraft.advancements.critereon.RecipeCraftedTrigger;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.data.ExistingFileHelper;
import net.minecraftforge.common.data.ForgeAdvancementProvider;

import java.util.function.Consumer;

/**
 * Advancements for crafting a television, pairing a remote and playing the bundled demo (section 5.4). None is ever
 * granted for ads, signing in to a service or YouTube view counts.
 */
public final class OteTvAdvancements implements ForgeAdvancementProvider.AdvancementGenerator {
    @Override
    public void generate(HolderLookup.Provider registries, Consumer<Advancement> saver, ExistingFileHelper efh) {
        Advancement.Builder rootBuilder = Advancement.Builder.advancement()
                .display(ModItems.tv(TvModel.CRT), Component.translatable("otetv.advancement.root.title"),
                        Component.translatable("otetv.advancement.root.description"),
                        new ResourceLocation("minecraft", "textures/gui/advancements/backgrounds/stone.png"),
                        FrameType.TASK, true, true, false)
                .requirements(RequirementsStrategy.OR);
        for (String recipe : new String[]{"wooden_monochrome_tv", "woodgrain_color_tv", "woodgrain_color_tv_upgrade", "portable_tv",
                "crt_tv", "plasma_tv", "oled_tv"}) {
            rootBuilder.addCriterion("crafted_" + recipe, RecipeCraftedTrigger.TriggerInstance.craftedItem(OteTV.id(recipe)));
        }
        Advancement root = rootBuilder.save(saver, OteTV.id("root"), efh);

        Advancement.Builder.advancement().parent(root)
                .display(ModItems.REMOTE_CONTROL.get(), Component.translatable("otetv.advancement.pair_remote.title"),
                        Component.translatable("otetv.advancement.pair_remote.description"), null, FrameType.TASK, true, true, false)
                .addCriterion("paired", ModCriteria.PAIRED_REMOTE.instance())
                .save(saver, OteTV.id("pair_remote"), efh);

        Advancement.Builder.advancement().parent(root)
                .display(ModItems.tv(TvModel.WOODEN_MONOCHROME), Component.translatable("otetv.advancement.play_demo.title"),
                        Component.translatable("otetv.advancement.play_demo.description"), null, FrameType.GOAL, true, true, false)
                .addCriterion("played", ModCriteria.PLAYED_DEMO.instance())
                .save(saver, OteTV.id("play_demo"), efh);
    }
}
