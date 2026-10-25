package com.otectus.otetv.gametest;

import com.otectus.otetv.OteTV;
import com.otectus.otetv.block.TvModel;
import com.otectus.otetv.block.entity.TvBlockEntity;
import com.otectus.otetv.block.entity.TvState;
import com.otectus.otetv.core.OteTvCore;
import com.otectus.otetv.core.protocol.RedstoneMode;
import com.otectus.otetv.core.protocol.RejectReason;
import com.otectus.otetv.core.protocol.TvActionType;
import com.otectus.otetv.core.protocol.TvRole;
import com.otectus.otetv.core.protocol.VisualPreset;
import com.otectus.otetv.core.source.MediaSource;
import com.otectus.otetv.core.source.SourceException;
import com.otectus.otetv.core.source.SourceType;
import com.otectus.otetv.data.OteTvWorldData;
import com.otectus.otetv.registry.ModItems;
import com.otectus.otetv.session.TvActions;
import com.otectus.otetv.session.TvSession;
import com.otectus.otetv.session.TvSessionService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.otectus.otetv.gametest.TvTests.CENTER;
import static com.otectus.otetv.gametest.TvTests.CORNER;
import static com.otectus.otetv.gametest.TvTests.PLATFORM;

/** Section 21.2: recipe outputs, loot contents, persistence, migrations and corrupted-state fallback, world data. */
@GameTestHolder(OteTV.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TvDataGameTests {
    private TvDataGameTests() {
    }

    @GameTest(template = PLATFORM)
    public static void everyRecipeCraftsItsResult(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RecipeManager recipes = level.getRecipeManager();
        Map<String, Item> expected = new LinkedHashMap<>();
        for (TvModel model : TvModel.values()) {
            expected.put(model.registryName(), ModItems.tv(model));
        }
        expected.put("woodgrain_color_tv_upgrade", ModItems.tv(TvModel.WOODGRAIN_COLOR));
        expected.put("remote_control", ModItems.REMOTE_CONTROL.get());
        for (Map.Entry<String, Item> e : expected.entrySet()) {
            ResourceLocation id = OteTV.id(e.getKey());
            Optional<? extends Recipe<?>> recipe = recipes.byKey(id);
            helper.assertTrue(recipe.isPresent() && recipe.get() instanceof CraftingRecipe, id + " is missing or not a crafting recipe");
            CraftingRecipe crafting = (CraftingRecipe) recipe.get();
            CraftingContainer grid = gridFor(crafting);
            Optional<CraftingRecipe> match = recipes.getRecipeFor(RecipeType.CRAFTING, grid, level);
            helper.assertTrue(match.isPresent() && match.get().getId().equals(id), id + ": its own ingredients matched " + match.map(Recipe::getId));
            ItemStack out = match.get().assemble(grid, level.registryAccess());
            helper.assertTrue(out.is(e.getValue()) && out.getCount() == 1, id + ": crafted " + out);
        }
        long ours = recipes.getRecipes().stream().filter(r -> r.getId().getNamespace().equals(OteTV.MOD_ID)).count();
        helper.assertTrue(ours == expected.size(), "found " + ours + " OteTV recipes, checked " + expected.size());
        helper.succeed();
    }

    /** A crafting grid filled with the first matching item of each of the recipe's own ingredients. */
    private static CraftingContainer gridFor(CraftingRecipe recipe) {
        AbstractContainerMenu menu = new AbstractContainerMenu(null, -1) {
            @Override
            public ItemStack quickMoveStack(Player player, int index) {
                return ItemStack.EMPTY;
            }

            @Override
            public boolean stillValid(Player player) {
                return true;
            }
        };
        CraftingContainer grid = new TransientCraftingContainer(menu, 3, 3);
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        int width = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : 3;
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            if (ingredient.isEmpty()) {
                continue;
            }
            int slot = recipe instanceof ShapedRecipe ? (i / width) * 3 + i % width : i;
            grid.setItem(slot, ingredient.getItems()[0].copy());
        }
        return grid;
    }

    @GameTest(template = PLATFORM)
    public static void droppedTelevisionKeepsOnlyItsNameAndLook(GameTestHelper helper) {
        ServerPlayer owner = TvTests.player(helper, CORNER);
        ItemStack stack = new ItemStack(ModItems.tv(TvModel.CRT));
        stack.setHoverName(Component.literal("Den TV"));
        stack.getOrCreateTagElement(TvBlockEntity.COSMETIC_TAG).putString("preset", VisualPreset.CLEAR.id());
        TvBlockEntity be = TvTests.place(helper, owner, stack, CENTER, Direction.NORTH);
        helper.assertTrue(be.state().displayName().equals("Den TV"), "the item name was not applied: " + be.state().displayName());
        helper.assertTrue(be.state().preset() == VisualPreset.CLEAR, "the cosmetic preset was not applied");
        be.state().trust(UUID.randomUUID(), "Pal", TvRole.EDITOR);
        helper.getLevel().destroyBlock(TvTests.cells(be).get(3), true);
        List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(be.getBlockPos()).inflate(4), ItemEntity::isAlive);
        helper.assertTrue(drops.size() == 1, "expected one drop, found " + drops.size());
        ItemStack dropped = drops.get(0).getItem();
        helper.assertTrue(dropped.is(ModItems.tv(TvModel.CRT)) && dropped.getCount() == 1, "dropped " + dropped);
        helper.assertTrue(dropped.hasCustomHoverName() && dropped.getHoverName().getString().equals("Den TV"), "the name was lost");
        CompoundTag cosmetic = dropped.getTagElement(TvBlockEntity.COSMETIC_TAG);
        helper.assertTrue(cosmetic != null && cosmetic.getString("preset").equals("clear"), "the preset was lost: " + cosmetic);
        // Identity, ownership, ACL and sources never ride along on the item.
        helper.assertTrue(dropped.getTagElement("BlockEntityTag") == null, "block entity data was copied onto the item");
        helper.assertTrue(cosmetic.getAllKeys().size() == 1, "unexpected cosmetic keys " + cosmetic.getAllKeys());
        helper.succeed();
    }

    @GameTest(template = PLATFORM)
    public static void itemDataCannotInjectIdentityOrOwnership(GameTestHelper helper) {
        ServerPlayer placer = TvTests.player(helper, CORNER);
        UUID forgedTv = UUID.randomUUID();
        UUID forgedOwner = UUID.randomUUID();
        CompoundTag tv = new CompoundTag();
        tv.putInt("schema", OteTvCore.TV_SCHEMA_VERSION);
        tv.putUUID("tv", forgedTv);
        tv.putUUID("owner", forgedOwner);
        tv.putString("ownerName", "Mallory");
        tv.putBoolean("publicControls", true);
        CompoundTag blockEntityTag = new CompoundTag();
        blockEntityTag.put("tv", tv);
        ItemStack stack = new ItemStack(ModItems.tv(TvModel.CRT));
        stack.addTagElement("BlockEntityTag", blockEntityTag);
        TvBlockEntity be = TvTests.place(helper, placer, stack, CENTER, Direction.NORTH);
        helper.assertFalse(forgedTv.equals(be.tvId()), "item NBT injected a television identity");
        helper.assertTrue(placer.getUUID().equals(be.state().owner()), "item NBT injected an owner");
        helper.assertFalse(be.state().publicControls(), "item NBT injected ACL settings");
        helper.succeed();
    }

    @GameTest(template = PLATFORM)
    public static void stateSurvivesSaveLoadAndFallsBackSafely(GameTestHelper helper) {
        ServerPlayer owner = TvTests.player(helper, CORNER);
        TvBlockEntity be = TvTests.place(helper, owner, TvModel.PORTABLE, CENTER, Direction.NORTH);
        TvState s = be.state();
        UUID friend = UUID.randomUUID();
        MediaSource demo;
        try {
            demo = MediaSource.bundled("otetv:welcome", "OteTV Welcome");
        } catch (SourceException e) {
            helper.fail("could not build the demo source: " + e.issue());
            return;
        }
        s.setDisplayName("Kitchen");
        s.setPowered(true);
        s.setPreset(VisualPreset.CLEAR);
        s.setRedstoneMode(RedstoneMode.TOGGLE_POWER_ON_RISING_EDGE);
        s.trust(friend, "Pal", TvRole.EDITOR);
        s.setPublicControls(true);
        s.setSource(demo);
        s.setChannel("otetv:welcome", 1);
        s.setCursor(12_345, false);
        s.setSpeakerGain(0.5f);
        CompoundTag saved = be.saveWithoutMetadata();
        TvState loaded = TvState.load(saved.getCompound("tv"), TvModel.PORTABLE.defaultPreset());
        helper.assertTrue(s.tvId().equals(loaded.tvId()) && owner.getUUID().equals(loaded.owner()), "identity or owner changed");
        helper.assertTrue(loaded.displayName().equals("Kitchen") && loaded.powered() && loaded.preset() == VisualPreset.CLEAR, "display state changed");
        helper.assertTrue(loaded.redstoneMode() == RedstoneMode.TOGGLE_POWER_ON_RISING_EDGE, "redstone mode changed");
        TvState.Trust trust = loaded.trusted().get(friend);
        helper.assertTrue(trust != null && trust.role() == TvRole.EDITOR && trust.name().equals("Pal"), "trust entry changed: " + trust);
        helper.assertTrue(loaded.publicControls(), "public controls changed");
        MediaSource source = loaded.source();
        helper.assertTrue(source != null && source.type() == SourceType.BUNDLED && source.assetId().orElse("").equals("otetv:welcome"),
                "source changed");
        helper.assertTrue(loaded.channelId().equals("otetv:welcome") && loaded.channelNumber() == 1, "channel changed");
        helper.assertTrue(loaded.cursorMs() == 12_345 && Math.abs(loaded.speakerGain() - 0.5f) < 1e-6, "cursor or gain changed");

        // A corrupted source falls back to standby; everything else survives.
        CompoundTag corrupted = saved.getCompound("tv").copy();
        CompoundTag badSource = new CompoundTag();
        badSource.putString("type", "not_a_source_type");
        corrupted.put("source", badSource);
        TvState fallback = TvState.load(corrupted, TvModel.PORTABLE.defaultPreset());
        helper.assertTrue(fallback.source() == null, "a corrupted source was accepted");
        helper.assertTrue(fallback.displayName().equals("Kitchen") && fallback.powered() && fallback.trusted().containsKey(friend),
                "a corrupted source discarded unrelated state");

        // Data saved by a newer OteTV is kept verbatim and the TV becomes read-only.
        CompoundTag future = saved.getCompound("tv").copy();
        future.putInt("schema", OteTvCore.TV_SCHEMA_VERSION + 1);
        future.putString("fromTheFuture", "kept");
        TvState locked = TvState.load(future, TvModel.PORTABLE.defaultPreset());
        helper.assertTrue(locked.isLocked(), "newer data did not lock the TV");
        CompoundTag resaved = new CompoundTag();
        locked.save(resaved);
        helper.assertTrue(resaved.getInt("schema") == OteTvCore.TV_SCHEMA_VERSION + 1 && resaved.getString("fromTheFuture").equals("kept"),
                "newer data was rewritten: " + resaved);
        CompoundTag wrapper = new CompoundTag();
        wrapper.put("tv", future);
        be.load(wrapper);
        TvSessionService svc = TvSessionService.get();
        TvSession session = svc.session(be.tvId());
        helper.assertTrue(session != null, "the TV lost its session");
        TvTests.expect(helper, TvActions.perform(svc, session, be, null, TvActionType.SET_POWER, -1, TvActions.Args.ofInt(0)),
                RejectReason.INCOMPATIBLE_DATA, "action on a locked TV");
        helper.succeed();
    }

    @GameTest(template = PLATFORM)
    public static void worldDataTracksPlacedTelevisions(GameTestHelper helper) {
        ServerPlayer owner = TvTests.player(helper, CORNER);
        OteTvWorldData data = OteTvWorldData.get(helper.getLevel().getServer());
        helper.assertTrue(data.countOwnedBy(owner.getUUID()) == 0, "a new player already owns televisions");
        TvBlockEntity a = TvTests.place(helper, owner, TvModel.PORTABLE, new BlockPos(4, 2, 8), Direction.NORTH);
        TvBlockEntity b = TvTests.place(helper, owner, TvModel.PORTABLE, new BlockPos(12, 2, 8), Direction.NORTH);
        helper.assertTrue(data.countOwnedBy(owner.getUUID()) == 2, "expected two owned televisions, found " + data.countOwnedBy(owner.getUUID()));
        OteTvWorldData.TvRecord record = data.lookup(a.tvId());
        helper.assertTrue(record != null && record.pos().equals(a.getBlockPos()) && record.dimension() == helper.getLevel().dimension()
                && record.model().equals(TvModel.PORTABLE.registryName()), "wrong record " + record);
        UUID removed = a.tvId();
        TvTests.removeAnchor(helper, a);
        helper.assertTrue(data.lookup(removed) == null, "a removed television is still tracked");
        helper.assertTrue(data.countOwnedBy(owner.getUUID()) == 1, "removal did not update the owner's count");
        helper.assertTrue(data.lookup(b.tvId()) != null, "the other television stopped being tracked");
        helper.succeed();
    }
}
