package com.otectus.otetv.gametest;

import com.otectus.otetv.OteTV;
import com.otectus.otetv.block.TvAssembly;
import com.otectus.otetv.block.TvBlock;
import com.otectus.otetv.block.TvGeometry;
import com.otectus.otetv.block.TvModel;
import com.otectus.otetv.block.TvMount;
import com.otectus.otetv.block.entity.TvBlockEntity;
import com.otectus.otetv.registry.ModBlocks;
import com.otectus.otetv.registry.ModItems;
import com.otectus.otetv.session.TvSession;
import com.otectus.otetv.session.TvSessionService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

import static com.otectus.otetv.gametest.TvTests.CENTER;
import static com.otectus.otetv.gametest.TvTests.CORNER;
import static com.otectus.otetv.gametest.TvTests.PLATFORM;

/** Section 21.2: atomic placement, occupancy and support, exactly-once destruction, boundaries, repair. */
@GameTestHolder(OteTV.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TvAssemblyGameTests {
    private TvAssemblyGameTests() {
    }

    @GameTest(template = PLATFORM)
    public static void everyModelPlacesAtEveryFacing(GameTestHelper helper) {
        ServerPlayer player = TvTests.player(helper, CORNER);
        for (TvModel model : TvModel.values()) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                String what = model.registryName() + " facing " + facing;
                TvBlockEntity be = TvTests.place(helper, player, model, CENTER, facing);
                helper.assertTrue(be.facing() == facing, what + ": controller faces " + be.facing());
                helper.assertTrue(player.getUUID().equals(be.state().owner()), what + ": the placer does not own it");
                TvTests.assertAssembled(helper, be, TvMount.STAND, what);
                // Columns run along facing.getClockWise() and rows run up from the anchor.
                BlockPos last = TvGeometry.cellPos(be.getBlockPos(), facing, model, model.cellCount() - 1);
                BlockPos expected = be.getBlockPos().relative(facing.getClockWise(), model.width() - 1 - model.anchorColumn()).above(model.height() - 1);
                helper.assertTrue(last.equals(expected), what + ": last cell at " + last.toShortString() + ", expected " + expected.toShortString());
                List<BlockPos> cells = TvTests.cells(be);
                TvTests.removeAnchor(helper, be);
                TvTests.assertGone(helper, cells, what + " after removal");
            }
        }
        helper.succeed();
    }

    @GameTest(template = PLATFORM)
    public static void obstructedPlacementPlacesNothing(GameTestHelper helper) {
        ServerPlayer player = TvTests.player(helper, CORNER);
        // A north-facing OLED spans columns -1..1 (east is +1) and two rows; block its top east cell.
        BlockPos blocker = CENTER.east().above();
        helper.setBlock(blocker, Blocks.STONE);
        ItemStack stack = new ItemStack(ModItems.tv(TvModel.OLED));
        player.setYRot(Direction.SOUTH.toYRot());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        helper.placeAt(player, stack, CENTER.below(), Direction.UP);
        for (BlockPos cell : TvGeometry.cells(helper.absolutePos(CENTER), Direction.NORTH, TvModel.OLED)) {
            if (!cell.equals(helper.absolutePos(blocker))) {
                helper.assertTrue(helper.getLevel().getBlockState(cell).isAir(), "a cell was placed despite the obstruction: " + cell.toShortString());
            }
        }
        helper.assertBlockPresent(Blocks.STONE, blocker);
        helper.assertTrue(stack.getCount() == 1, "a refused placement consumed the item");
        helper.succeed();
    }

    @GameTest(template = PLATFORM)
    public static void standMountNeedsFloorSupport(GameTestHelper helper) {
        ServerPlayer player = TvTests.player(helper, CORNER);
        BlockPos floating = new BlockPos(8, 4, 8);
        ItemStack stack = new ItemStack(ModItems.tv(TvModel.CRT));
        player.setYRot(Direction.SOUTH.toYRot());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        helper.placeAt(player, stack, floating.below(), Direction.UP);
        helper.assertBlockNotPresent(ModBlocks.tv(TvModel.CRT), floating);
        // Support under one bottom cell is enough for a stand.
        helper.setBlock(floating.east().below(), Blocks.STONE);
        TvBlockEntity be = TvTests.place(helper, player, TvModel.CRT, floating, Direction.NORTH);
        TvTests.assertAssembled(helper, be, TvMount.STAND, "CRT on partial support");
        helper.succeed();
    }

    @GameTest(template = PLATFORM)
    public static void wallMountNeedsSturdyFaceBehindEveryCell(GameTestHelper helper) {
        ServerPlayer player = TvTests.player(helper, new BlockPos(8, 2, 14));
        for (int x = 5; x <= 11; x++) {
            for (int y = 2; y <= 4; y++) {
                helper.setBlock(x, y, 6, Blocks.STONE);
            }
        }
        // Clicking the wall's south face mounts the plasma TV facing south, its cells at z = 7 (x 7..9, y 2..3).
        ItemStack stack = new ItemStack(ModItems.tv(TvModel.PLASMA));
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        helper.placeAt(player, stack, new BlockPos(8, 2, 5), Direction.SOUTH);
        BlockPos anchorRel = new BlockPos(8, 2, 7);
        if (!(helper.getBlockEntity(anchorRel) instanceof TvBlockEntity be)) {
            helper.fail("the plasma TV was not wall-mounted", anchorRel);
            return;
        }
        helper.assertTrue(be.facing() == Direction.SOUTH, "wall TV faces " + be.facing());
        TvTests.assertAssembled(helper, be, TvMount.WALL, "wall plasma");
        TvTests.removeAnchor(helper, be);
        // Without a sturdy face behind one top cell the same placement is refused.
        helper.setBlock(new BlockPos(7, 3, 6), Blocks.AIR);
        ItemStack again = new ItemStack(ModItems.tv(TvModel.PLASMA));
        player.setItemInHand(InteractionHand.MAIN_HAND, again);
        helper.placeAt(player, again, new BlockPos(8, 2, 5), Direction.SOUTH);
        helper.assertBlockNotPresent(ModBlocks.tv(TvModel.PLASMA), anchorRel);
        helper.succeed();
    }

    @GameTest(template = PLATFORM)
    public static void assembliesStraddleChunkBoundaries(GameTestHelper helper) {
        ServerPlayer player = TvTests.player(helper, CORNER);
        int rx = -1;
        int rz = -1;
        for (int i = 0; i <= 15; i++) {
            BlockPos abs = helper.absolutePos(new BlockPos(i, 1, i));
            if ((abs.getX() & 15) == 15) {
                rx = i;
            }
            if ((abs.getZ() & 15) == 15) {
                rz = i;
            }
        }
        helper.assertTrue(rx >= 0 && rz >= 0, "the platform did not contain a chunk boundary");
        // A north-facing CRT grows east, an east-facing one grows south: both cross into the next chunk.
        straddle(helper, player, new BlockPos(rx, 2, 4), Direction.NORTH);
        straddle(helper, player, new BlockPos(4, 2, rz), Direction.EAST);
        helper.succeed();
    }

    private static void straddle(GameTestHelper helper, ServerPlayer player, BlockPos anchorRel, Direction facing) {
        TvBlockEntity be = TvTests.place(helper, player, TvModel.CRT, anchorRel, facing);
        String what = "CRT across a chunk boundary facing " + facing;
        TvTests.assertAssembled(helper, be, TvMount.STAND, what);
        ChunkPos home = new ChunkPos(be.getBlockPos());
        List<BlockPos> cells = TvTests.cells(be);
        BlockPos far = cells.stream().filter(c -> !new ChunkPos(c).equals(home)).findFirst().orElse(null);
        helper.assertTrue(far != null, what + ": every cell is in one chunk");
        helper.getLevel().destroyBlock(far, true);
        TvTests.assertGone(helper, cells, what);
        int drops = TvTests.droppedTelevisions(helper, TvModel.CRT, be.getBlockPos(), 4);
        helper.assertTrue(drops == 1, what + ": expected one dropped television, found " + drops);
        TvTests.clearItems(helper);
    }

    @GameTest(template = PLATFORM, timeoutTicks = 200)
    public static void breakingAnyPartDropsOneTelevision(GameTestHelper helper) {
        ServerPlayer player = TvTests.player(helper, CORNER);
        for (TvModel model : TvModel.values()) {
            for (int part = 0; part < model.cellCount(); part++) {
                String what = model.registryName() + " broken at part " + part;
                TvBlockEntity be = TvTests.place(helper, player, model, CENTER, Direction.NORTH);
                List<BlockPos> cells = TvTests.cells(be);
                helper.getLevel().destroyBlock(cells.get(part), true);
                TvTests.assertGone(helper, cells, what);
                int televisions = TvTests.droppedTelevisions(helper, model, be.getBlockPos(), 4);
                int all = TvTests.droppedItems(helper, be.getBlockPos(), 4);
                helper.assertTrue(televisions == 1 && all == 1, what + ": expected exactly one drop, found " + televisions + " TVs / " + all + " items");
                TvTests.clearItems(helper);
            }
        }
        helper.succeed();
    }

    @GameTest(template = PLATFORM)
    public static void creativeBreakDropsNothing(GameTestHelper helper) {
        ServerPlayer player = TvTests.player(helper, CORNER);
        player.setGameMode(GameType.CREATIVE);
        TvBlockEntity be = TvTests.place(helper, player, TvModel.PLASMA, CENTER, Direction.NORTH);
        List<BlockPos> cells = TvTests.cells(be);
        helper.assertTrue(player.gameMode.destroyBlock(cells.get(5)), "the creative player could not break the TV");
        TvTests.assertGone(helper, cells, "creative break");
        int all = TvTests.droppedItems(helper, be.getBlockPos(), 4);
        helper.assertTrue(all == 0, "a creative break dropped " + all + " item(s)");
        helper.succeed();
    }

    @GameTest(template = PLATFORM)
    public static void pistonsCannotMoveTelevisions(GameTestHelper helper) {
        ServerPlayer player = TvTests.player(helper, CORNER);
        TvBlockEntity be = TvTests.place(helper, player, TvModel.CRT, CENTER, Direction.NORTH);
        BlockPos piston = CENTER.south();
        helper.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.NORTH));
        helper.setBlock(piston.south(), Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(6, () -> {
            helper.assertBlockProperty(piston, PistonBaseBlock.EXTENDED, false);
            TvTests.assertAssembled(helper, be, TvMount.STAND, "CRT after a piston push");
            helper.succeed();
        });
    }

    @GameTest(template = PLATFORM)
    public static void losingFloorSupportDropsOnce(GameTestHelper helper) {
        ServerPlayer player = TvTests.player(helper, CORNER);
        TvBlockEntity be = TvTests.place(helper, player, TvModel.CRT, CENTER, Direction.NORTH);
        List<BlockPos> cells = TvTests.cells(be);
        BlockPos anchor = be.getBlockPos();
        helper.setBlock(CENTER.below(), Blocks.AIR);
        helper.setBlock(CENTER.east().below(), Blocks.AIR);
        helper.succeedWhen(() -> {
            TvTests.assertGone(helper, cells, "unsupported CRT");
            int drops = TvTests.droppedItems(helper, anchor, 6);
            helper.assertTrue(drops == 1, "expected one drop after support loss, found " + drops);
        });
    }

    @GameTest(template = PLATFORM)
    public static void losingWallSupportDropsOnce(GameTestHelper helper) {
        ServerPlayer player = TvTests.player(helper, new BlockPos(8, 2, 14));
        for (int x = 5; x <= 11; x++) {
            for (int y = 2; y <= 4; y++) {
                helper.setBlock(x, y, 6, Blocks.STONE);
            }
        }
        ItemStack stack = new ItemStack(ModItems.tv(TvModel.OLED));
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        helper.placeAt(player, stack, new BlockPos(8, 2, 5), Direction.SOUTH);
        if (!(helper.getBlockEntity(new BlockPos(8, 2, 7)) instanceof TvBlockEntity be)) {
            helper.fail("the OLED TV was not wall-mounted", new BlockPos(8, 2, 7));
            return;
        }
        List<BlockPos> cells = TvTests.cells(be);
        BlockPos anchor = be.getBlockPos();
        helper.setBlock(new BlockPos(9, 3, 6), Blocks.AIR);
        helper.succeedWhen(() -> {
            TvTests.assertGone(helper, cells, "OLED without its wall");
            int drops = TvTests.droppedItems(helper, anchor, 6);
            helper.assertTrue(drops == 1, "expected one drop after wall loss, found " + drops);
        });
    }

    @GameTest(template = PLATFORM)
    public static void controllerRepairsAMissingPart(GameTestHelper helper) {
        ServerPlayer player = TvTests.player(helper, CORNER);
        TvBlockEntity be = TvTests.place(helper, player, TvModel.OLED, CENTER, Direction.NORTH);
        ServerLevel level = helper.getLevel();
        BlockPos lost = TvTests.cells(be).get(5);
        TvTests.rawSetBlock(level, lost, Blocks.AIR.defaultBlockState());
        helper.assertTrue(level.getBlockState(lost).isAir(), "the raw edit did not remove the part");
        TvAssembly.Integrity first = TvAssembly.validateAndRepair(level, be.getBlockPos(), be.getBlockState());
        helper.assertTrue(first == TvAssembly.Integrity.REPAIRED, "expected REPAIRED, got " + first);
        TvTests.assertAssembled(helper, be, TvMount.STAND, "repaired OLED");
        TvAssembly.Integrity second = TvAssembly.validateAndRepair(level, be.getBlockPos(), be.getBlockState());
        helper.assertTrue(second == TvAssembly.Integrity.INTACT, "expected INTACT after repair, got " + second);
        helper.succeed();
    }

    @GameTest(template = PLATFORM)
    public static void occupiedCellDismantlesWithOneDrop(GameTestHelper helper) {
        ServerPlayer player = TvTests.player(helper, CORNER);
        TvBlockEntity be = TvTests.place(helper, player, TvModel.OLED, CENTER, Direction.NORTH);
        ServerLevel level = helper.getLevel();
        List<BlockPos> cells = TvTests.cells(be);
        BlockPos squatter = cells.get(4);
        TvTests.rawSetBlock(level, squatter, Blocks.STONE.defaultBlockState());
        TvAssembly.Integrity result = TvAssembly.validateAndRepair(level, be.getBlockPos(), be.getBlockState());
        helper.assertTrue(result == TvAssembly.Integrity.DISMANTLED, "expected DISMANTLED, got " + result);
        for (BlockPos cell : cells) {
            if (!cell.equals(squatter)) {
                helper.assertTrue(level.getBlockState(cell).isAir(), "a cell survived at " + cell.toShortString());
            }
        }
        helper.assertTrue(level.getBlockState(squatter).is(Blocks.STONE), "the occupying block was removed");
        int drops = TvTests.droppedItems(helper, be.getBlockPos(), 4);
        helper.assertTrue(drops == 1, "expected one drop, found " + drops);
        helper.succeed();
    }

    @GameTest(template = PLATFORM)
    public static void orphanedPartsAreRemoved(GameTestHelper helper) {
        TvBlock oled = ModBlocks.tv(TvModel.OLED);
        BlockPos orphan = CENTER.above();
        helper.setBlock(orphan, oled.stateFor(Direction.NORTH, TvMount.STAND, 4));
        helper.assertBlockPresent(oled, orphan);
        helper.randomTick(orphan);
        helper.assertBlockNotPresent(oled, orphan);
        int drops = TvTests.droppedItems(helper, helper.absolutePos(orphan), 4);
        helper.assertTrue(drops == 0, "an orphan part dropped " + drops + " item(s)");
        helper.succeed();
    }

    @GameTest(template = PLATFORM)
    public static void copiedControllerGetsAFreshIdentity(GameTestHelper helper) {
        ServerPlayer player = TvTests.player(helper, CORNER);
        TvBlockEntity original = TvTests.place(helper, player, TvModel.PORTABLE, new BlockPos(4, 2, 8), Direction.NORTH);
        TvBlockEntity copy = TvTests.place(helper, player, TvModel.PORTABLE, new BlockPos(12, 2, 8), Direction.NORTH);
        UUID id = original.tvId();
        // A structure tool copies the controller's data, identity included.
        CompoundTag data = original.saveWithoutMetadata();
        copy.load(data);
        helper.assertTrue(id.equals(copy.tvId()), "precondition: the copy carries the original identity");
        TvSessionService svc = TvSessionService.get();
        svc.onControllerLoaded(copy);
        helper.assertFalse(id.equals(copy.tvId()), "a copied controller kept the original identity");
        TvSession originalSession = svc.session(id);
        TvSession copySession = svc.session(copy.tvId());
        helper.assertTrue(originalSession != null && originalSession.pos().equals(original.getBlockPos()), "the original lost its session");
        helper.assertTrue(copySession != null && copySession.pos().equals(copy.getBlockPos()), "the copy has no session of its own");
        helper.succeed();
    }
}
