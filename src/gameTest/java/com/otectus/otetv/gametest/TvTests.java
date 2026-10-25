package com.otectus.otetv.gametest;

import com.mojang.authlib.GameProfile;
import com.otectus.otetv.block.TvBlock;
import com.otectus.otetv.block.TvGeometry;
import com.otectus.otetv.block.TvModel;
import com.otectus.otetv.block.TvMount;
import com.otectus.otetv.block.TvPlacement;
import com.otectus.otetv.block.entity.TvBlockEntity;
import com.otectus.otetv.core.protocol.RejectReason;
import com.otectus.otetv.core.protocol.TvActionType;
import com.otectus.otetv.network.C2S;
import com.otectus.otetv.registry.ModBlocks;
import com.otectus.otetv.registry.ModItems;
import com.otectus.otetv.session.TvActions;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Shared fixtures for OteTV GameTests: real item placement by a server player, raw block edits that bypass block
 * callbacks (to simulate lost or corrupted cells), and drop counting.
 */
final class TvTests {
    /**
     * A 17 x 6 x 17 smooth-stone floor with air above; wider than a chunk on both axes. GameTest coordinates are
     * relative to the structure block, and the template is loaded one block above it, so the floor is at y = 1.
     */
    static final String PLATFORM = "platform";
    static final BlockPos CENTER = new BlockPos(8, 2, 8);
    static final BlockPos CORNER = new BlockPos(1, 2, 1);

    private TvTests() {
    }

    /**
     * A non-operator server player standing at {@code rel}. Vanilla's mock player cannot be used under Forge: its
     * connection has no channel and Forge's login handling dereferences it. This player is never logged in; packets
     * the server sends it are buffered by an embedded channel.
     */
    static ServerPlayer player(GameTestHelper helper, BlockPos rel) {
        ServerPlayer player = connectedPlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "otetv_test_" + (++created)));
        moveTo(helper, player, rel);
        return player;
    }

    private static int created;

    private static ServerPlayer connectedPlayer(ServerLevel level, GameProfile profile) {
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        new ServerGamePacketListenerImpl(level.getServer(), connection, player);
        return player;
    }

    static void moveTo(GameTestHelper helper, ServerPlayer player, BlockPos rel) {
        BlockPos abs = helper.absolutePos(rel);
        player.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, player.getYRot(), 0);
    }

    /** The same identity standing in another level (not added to it, so none of its chunks are loaded around it). */
    static ServerPlayer playerIn(ServerLevel level, GameProfile profile, Vec3 pos) {
        ServerPlayer player = connectedPlayer(level, profile);
        player.moveTo(pos.x, pos.y, pos.z, 0, 0);
        return player;
    }

    static TvBlockEntity place(GameTestHelper helper, ServerPlayer player, TvModel model, BlockPos anchorRel, Direction facing) {
        return place(helper, player, new ItemStack(ModItems.tv(model)), anchorRel, facing);
    }

    /**
     * Places a stand-mounted TV from an item exactly as a player does (item use, Forge placement events, setPlacedBy),
     * with the front facing {@code facing}: the player looks the opposite way, toward the TV.
     */
    static TvBlockEntity place(GameTestHelper helper, ServerPlayer player, ItemStack stack, BlockPos anchorRel, Direction facing) {
        TvBlock block = (TvBlock) ((net.minecraft.world.item.BlockItem) stack.getItem()).getBlock();
        player.setYRot(facing.getOpposite().toYRot());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        helper.placeAt(player, stack, anchorRel.below(), Direction.UP);
        BlockPos abs = helper.absolutePos(anchorRel);
        if (helper.getLevel().getBlockEntity(abs) instanceof TvBlockEntity be) {
            return be;
        }
        TvPlacement.Result r = TvPlacement.compute(helper.getLevel(), player, block, abs, Direction.UP, player.getDirection(), stack);
        helper.fail(block.model().registryName() + " facing " + facing + " was not placed (" + r.problem() + ")", anchorRel);
        throw new IllegalStateException("unreachable");
    }

    static List<BlockPos> cells(TvBlockEntity be) {
        return TvGeometry.cells(be.getBlockPos(), be.facing(), be.model());
    }

    /** Every cell holds the right part with the right orientation; only the anchor has a block entity. */
    static void assertAssembled(GameTestHelper helper, TvBlockEntity be, TvMount mount, String what) {
        ServerLevel level = helper.getLevel();
        TvModel model = be.model();
        TvBlock block = ModBlocks.tv(model);
        List<BlockPos> cells = cells(be);
        helper.assertTrue(be.tvId() != null, what + ": controller has no identity");
        for (int part = 0; part < cells.size(); part++) {
            BlockPos cell = cells.get(part);
            BlockState s = level.getBlockState(cell);
            helper.assertTrue(s.getBlock() == block, what + ": part " + part + " at " + cell.toShortString() + " is " + s);
            helper.assertTrue(block.part(s) == part, what + ": cell " + cell.toShortString() + " has part " + block.part(s) + ", expected " + part);
            helper.assertTrue(s.getValue(TvBlock.FACING) == be.facing() && s.getValue(TvBlock.MOUNT) == mount,
                    what + ": part " + part + " is " + s.getValue(TvBlock.FACING) + "/" + s.getValue(TvBlock.MOUNT));
            helper.assertTrue((level.getBlockEntity(cell) != null) == (part == model.anchorPart()),
                    what + ": block entity presence is wrong at part " + part);
            helper.assertFalse(s.getShape(level, cell).isEmpty(), what + ": part " + part + " cannot be targeted");
        }
    }

    static void assertGone(GameTestHelper helper, List<BlockPos> cells, String what) {
        for (BlockPos cell : cells) {
            BlockState s = helper.getLevel().getBlockState(cell);
            helper.assertFalse(s.getBlock() instanceof TvBlock, what + ": a television cell survived at " + cell.toShortString());
        }
    }

    /** Removes the controller the way a non-player removal does; the rest of the assembly must follow. */
    static void removeAnchor(GameTestHelper helper, TvBlockEntity be) {
        helper.getLevel().setBlock(be.getBlockPos(), Blocks.AIR.defaultBlockState(), 3);
    }

    /** Total count of dropped item stacks of any kind within {@code radius} of {@code abs}. */
    static int droppedItems(GameTestHelper helper, BlockPos abs, double radius) {
        int n = 0;
        for (ItemEntity e : helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(abs).inflate(radius), ItemEntity::isAlive)) {
            n += e.getItem().getCount();
        }
        return n;
    }

    static int droppedTelevisions(GameTestHelper helper, TvModel model, BlockPos abs, double radius) {
        int n = 0;
        for (ItemEntity e : helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(abs).inflate(radius), ItemEntity::isAlive)) {
            if (e.getItem().is(ModItems.tv(model))) {
                n += e.getItem().getCount();
            }
        }
        return n;
    }

    static void clearItems(GameTestHelper helper) {
        helper.killAllEntitiesOfClass(ItemEntity.class);
    }

    /**
     * Writes a block straight into the chunk section: no removal callbacks, neighbour updates or drops. This is how a
     * cell is "lost" outside normal play (a chunk that failed to save, external world editing).
     */
    static void rawSetBlock(ServerLevel level, BlockPos pos, BlockState state) {
        LevelChunk chunk = level.getChunkAt(pos);
        chunk.getSection(chunk.getSectionIndex(pos.getY())).setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state);
    }

    /** A remote action packet as the client would send it (no revision expectation). */
    static C2S.TvAction action(UUID tv, TvActionType type, int intArg) {
        return new C2S.TvAction(tv, -1, 1, type, 0, intArg, "", null, null);
    }

    static void expect(GameTestHelper helper, TvActions.Outcome outcome, RejectReason expected, String what) {
        helper.assertTrue(outcome.reason() == expected, what + ": expected " + expected + ", got " + outcome.reason()
                + (outcome.detail().isEmpty() ? "" : " (" + outcome.detail() + ")"));
    }
}
