package com.otectus.otetv.datagen;

import com.otectus.otetv.OteTV;
import com.otectus.otetv.block.TvModel;
import com.otectus.otetv.registry.ModItems;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.Tags;

import java.util.function.Consumer;

/**
 * Vanilla-ingredient recipes (section 5.4): no netherite, no power systems, no disposable batteries. The woodgrain set
 * can be upgraded from the wooden console and also has a direct recipe for modpack flexibility.
 */
public final class OteTvRecipes extends RecipeProvider {
    public OteTvRecipes(PackOutput output) {
        super(output);
    }

    @Override
    protected void buildRecipes(Consumer<FinishedRecipe> out) {
        ShapedRecipeBuilder.shaped(RecipeCategory.TOOLS, ModItems.REMOTE_CONTROL.get())
                .pattern(" B ").pattern("QRQ").pattern(" C ")
                .define('B', Items.STONE_BUTTON).define('Q', Items.QUARTZ).define('R', Items.REDSTONE).define('C', Items.COPPER_INGOT)
                .unlockedBy("has_redstone", has(Items.REDSTONE)).save(out, OteTV.id("remote_control"));

        ShapedRecipeBuilder.shaped(RecipeCategory.DECORATIONS, ModItems.tv(TvModel.WOODEN_MONOCHROME))
                .pattern("PPP").pattern("GNP").pattern("CRC")
                .define('P', ItemTags.PLANKS).define('G', Items.GLASS).define('N', Items.NOTE_BLOCK).define('C', Items.COPPER_INGOT)
                .define('R', Items.REDSTONE)
                .unlockedBy("has_note_block", has(Items.NOTE_BLOCK)).save(out, OteTV.id("wooden_monochrome_tv"));

        ShapelessRecipeBuilder.shapeless(RecipeCategory.DECORATIONS, ModItems.tv(TvModel.WOODGRAIN_COLOR))
                .requires(ModItems.tv(TvModel.WOODEN_MONOCHROME)).requires(Items.REDSTONE)
                .requires(Items.RED_DYE).requires(Items.GREEN_DYE).requires(Items.BLUE_DYE)
                .unlockedBy("has_console", has(ModItems.tv(TvModel.WOODEN_MONOCHROME))).save(out, OteTV.id("woodgrain_color_tv_upgrade"));
        ShapedRecipeBuilder.shaped(RecipeCategory.DECORATIONS, ModItems.tv(TvModel.WOODGRAIN_COLOR))
                .pattern("PPP").pattern("GRD").pattern("CNC")
                .define('P', ItemTags.PLANKS).define('G', Items.GLASS).define('R', Items.REDSTONE).define('D', Tags.Items.DYES)
                .define('C', Items.COPPER_INGOT).define('N', Items.NOTE_BLOCK)
                .unlockedBy("has_glass", has(Items.GLASS)).save(out, OteTV.id("woodgrain_color_tv"));

        ShapedRecipeBuilder.shaped(RecipeCategory.DECORATIONS, ModItems.tv(TvModel.PORTABLE))
                .pattern(" C ").pattern("IGI").pattern("IRI")
                .define('C', Items.COPPER_INGOT).define('I', Items.IRON_INGOT).define('G', Items.GLASS).define('R', Items.REDSTONE)
                .unlockedBy("has_iron", has(Items.IRON_INGOT)).save(out, OteTV.id("portable_tv"));

        ShapedRecipeBuilder.shaped(RecipeCategory.DECORATIONS, ModItems.tv(TvModel.CRT))
                .pattern("IIQ").pattern("GGR").pattern("III")
                .define('I', Items.IRON_INGOT).define('Q', Items.QUARTZ).define('G', Items.GLASS).define('R', Items.REDSTONE)
                .unlockedBy("has_quartz", has(Items.QUARTZ)).save(out, OteTV.id("crt_tv"));

        ShapedRecipeBuilder.shaped(RecipeCategory.DECORATIONS, ModItems.tv(TvModel.PLASMA))
                .pattern("GGG").pattern("QRQ").pattern("CIC")
                .define('G', Items.GLASS).define('Q', Items.QUARTZ).define('R', Items.REDSTONE).define('C', Items.COPPER_INGOT)
                .define('I', Items.IRON_INGOT)
                .unlockedBy("has_quartz", has(Items.QUARTZ)).save(out, OteTV.id("plasma_tv"));

        ShapedRecipeBuilder.shaped(RecipeCategory.DECORATIONS, ModItems.tv(TvModel.OLED))
                .pattern("GGG").pattern("QRQ").pattern("QCQ")
                .define('G', Items.GLASS).define('Q', Items.QUARTZ).define('R', Items.REDSTONE).define('C', Items.COPPER_INGOT)
                .unlockedBy("has_quartz", has(Items.QUARTZ)).save(out, OteTV.id("oled_tv"));
    }
}
