package com.otectus.otetv.gametest;

import com.otectus.otetv.OteTV;
import com.otectus.otetv.block.TvModel;
import com.otectus.otetv.block.entity.TvBlockEntity;
import com.otectus.otetv.config.OteTvServerConfig;
import com.otectus.otetv.core.protocol.RedstoneMode;
import com.otectus.otetv.core.protocol.RejectReason;
import com.otectus.otetv.core.protocol.TvActionType;
import com.otectus.otetv.core.protocol.TvRole;
import com.otectus.otetv.data.OteTvWorldData;
import com.otectus.otetv.item.RemoteControlItem;
import com.otectus.otetv.registry.ModItems;
import com.otectus.otetv.session.TvActions;
import com.otectus.otetv.session.TvNetworkHandler;
import com.otectus.otetv.session.TvSession;
import com.otectus.otetv.session.TvSessionService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

import static com.otectus.otetv.gametest.TvTests.CENTER;
import static com.otectus.otetv.gametest.TvTests.CORNER;
import static com.otectus.otetv.gametest.TvTests.PLATFORM;

/** Section 21.2: remote pairing, range, dimension, ACLs, stale references, redstone, operator commands. */
@GameTestHolder(OteTV.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TvControlGameTests {
    private static final BlockPos FRONT = new BlockPos(8, 2, 3);

    private TvControlGameTests() {
    }

    @GameTest(template = PLATFORM)
    public static void remotePairsByIdentityAndIgnoresReplacements(GameTestHelper helper) {
        ServerPlayer owner = TvTests.player(helper, FRONT);
        TvBlockEntity be = TvTests.place(helper, owner, TvModel.CRT, CENTER, Direction.NORTH);
        UUID first = be.tvId();
        ItemStack remote = new ItemStack(ModItems.REMOTE_CONTROL.get());
        owner.setItemInHand(InteractionHand.MAIN_HAND, remote);
        owner.setShiftKeyDown(true);
        // Sneak-use on any cell pairs with the controller.
        BlockPos topCell = TvTests.cells(be).get(3);
        remote.useOn(new UseOnContext(owner, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(topCell), Direction.NORTH, topCell, false)));
        owner.setShiftKeyDown(false);
        RemoteControlItem.Pairing pairing = RemoteControlItem.pairing(remote);
        helper.assertTrue(pairing != null, "sneak-use did not pair the remote");
        helper.assertTrue(first.equals(pairing.tv()), "the remote stored another identity");
        helper.assertTrue(OteTvWorldData.get(helper.getLevel().getServer()).worldNamespace().equals(pairing.namespace()), "wrong world namespace");
        helper.assertTrue(helper.getLevel().dimension().location().toString().equals(pairing.dimension()), "wrong dimension " + pairing.dimension());
        helper.assertTrue(be.getBlockPos().equals(pairing.hint()), "the position hint is not the controller");

        TvSessionService svc = TvSessionService.get();
        TvTests.removeAnchor(helper, be);
        helper.assertTrue(svc.session(first) == null, "the broken TV still has a session");
        TvBlockEntity replacement = TvTests.place(helper, owner, TvModel.CRT, CENTER, Direction.NORTH);
        helper.assertFalse(first.equals(replacement.tvId()), "the replacement reused the old identity");
        // The packet handler resolves by identity: the stale pairing cannot reach the new TV at the same spot.
        TvNetworkHandler.action(owner, TvTests.action(pairing.tv(), TvActionType.SET_POWER, 1));
        helper.assertFalse(replacement.state().powered(), "a stale remote powered the replacement");
        TvNetworkHandler.action(owner, TvTests.action(replacement.tvId(), TvActionType.SET_POWER, 1));
        helper.assertTrue(replacement.state().powered(), "the owner in range could not power the replacement");
        helper.succeed();
    }

    @GameTest(template = PLATFORM)
    public static void remoteActionsRespectRangeAndDimension(GameTestHelper helper) {
        ServerPlayer owner = TvTests.player(helper, FRONT);
        TvBlockEntity be = TvTests.place(helper, owner, TvModel.CRT, CENTER, Direction.NORTH);
        UUID id = be.tvId();
        // Beyond the remote range (32 blocks by default).
        TvTests.moveTo(helper, owner, CENTER.south(40));
        TvNetworkHandler.action(owner, TvTests.action(id, TvActionType.SET_POWER, 1));
        helper.assertFalse(be.state().powered(), "an out-of-range action was applied");
        // The same identity standing at the same coordinates in another dimension.
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "the nether is not loaded on this server");
        Vec3 front = Vec3.atBottomCenterOf(helper.absolutePos(FRONT));
        ServerPlayer elsewhere = TvTests.playerIn(nether, owner.getGameProfile(), front);
        TvNetworkHandler.action(elsewhere, TvTests.action(id, TvActionType.SET_POWER, 1));
        helper.assertFalse(be.state().powered(), "an action from another dimension was applied");
        // In range, in the TV's dimension.
        TvTests.moveTo(helper, owner, FRONT);
        TvNetworkHandler.action(owner, TvTests.action(id, TvActionType.SET_POWER, 1));
        helper.assertTrue(be.state().powered(), "an in-range owner action was refused");
        helper.succeed();
    }

    @GameTest(template = PLATFORM)
    public static void rolesAndLeasesGateSharedActions(GameTestHelper helper) {
        ServerPlayer owner = TvTests.player(helper, new BlockPos(6, 2, 3));
        ServerPlayer stranger = TvTests.player(helper, new BlockPos(8, 2, 3));
        ServerPlayer friend = TvTests.player(helper, new BlockPos(10, 2, 3));
        TvBlockEntity be = TvTests.place(helper, owner, TvModel.CRT, CENTER, Direction.NORTH);
        TvSessionService svc = TvSessionService.get();
        TvSession session = svc.session(be.tvId());
        helper.assertTrue(session != null, "the placed TV has no session");

        TvTests.expect(helper, power(svc, session, be, stranger, true), RejectReason.NO_PERMISSION, "stranger power");
        be.state().trust(friend.getUUID(), friend.getGameProfile().getName(), TvRole.CONTROLLER);
        TvTests.expect(helper, power(svc, session, be, friend, true), RejectReason.NONE, "controller power");
        helper.assertTrue(session.lease() != null && session.lease().heldBy(friend.getUUID()), "the controller does not hold the lease");
        // The owner may reclaim a held lease; the controller cannot take it back while it is valid.
        TvTests.expect(helper, power(svc, session, be, owner, false), RejectReason.NONE, "owner reclaim");
        helper.assertTrue(session.lease().heldBy(owner.getUUID()), "the owner did not take the lease");
        TvTests.expect(helper, power(svc, session, be, friend, true), RejectReason.LEASE_HELD, "controller while owner holds lease");
        // Non-idempotent actions against an old revision are refused.
        TvTests.expect(helper, TvActions.perform(svc, session, be, owner, TvActionType.CHANNEL_STEP, session.revision() - 1, TvActions.Args.ofInt(1)),
                RejectReason.STALE_REVISION, "stale channel step");
        be.state().untrust(friend.getUUID());
        TvTests.expect(helper, power(svc, session, be, friend, true), RejectReason.NO_PERMISSION, "revoked controller");
        helper.succeed();
    }

    // Changes a server-wide setting, so it runs in its own batch.
    @GameTest(template = PLATFORM, batch = "otetv_public_controls")
    public static void publicControlsNeedServerAndOwnerOptIn(GameTestHelper helper) {
        ServerPlayer owner = TvTests.player(helper, new BlockPos(6, 2, 3));
        ServerPlayer stranger = TvTests.player(helper, new BlockPos(10, 2, 3));
        TvBlockEntity be = TvTests.place(helper, owner, TvModel.CRT, CENTER, Direction.NORTH);
        TvSessionService svc = TvSessionService.get();
        TvSession session = svc.session(be.tvId());
        boolean before = OteTvServerConfig.ALLOW_PUBLIC_CONTROLS.get();
        try {
            OteTvServerConfig.ALLOW_PUBLIC_CONTROLS.set(false);
            be.state().setPublicControls(true);
            TvTests.expect(helper, power(svc, session, be, stranger, true), RejectReason.NO_PERMISSION, "owner opted in, server did not");
            OteTvServerConfig.ALLOW_PUBLIC_CONTROLS.set(true);
            TvTests.expect(helper, power(svc, session, be, stranger, true), RejectReason.NONE, "both opted in");
            be.state().setPublicControls(false);
            TvTests.expect(helper, power(svc, session, be, stranger, false), RejectReason.NO_PERMISSION, "owner opted out again");
        } finally {
            OteTvServerConfig.ALLOW_PUBLIC_CONTROLS.set(before);
        }
        helper.succeed();
    }

    private static TvActions.Outcome power(TvSessionService svc, TvSession session, TvBlockEntity be, ServerPlayer player, boolean on) {
        return TvActions.perform(svc, session, be, player, TvActionType.SET_POWER, -1, TvActions.Args.ofInt(on ? 1 : 0));
    }

    @GameTest(template = PLATFORM, timeoutTicks = 200)
    public static void redstonePowerFollowsTheSignal(GameTestHelper helper) {
        ServerPlayer owner = TvTests.player(helper, CORNER);
        TvBlockEntity be = TvTests.place(helper, owner, TvModel.CRT, CENTER, Direction.NORTH);
        be.state().setRedstoneMode(RedstoneMode.POWER_WHEN_HIGH);
        BlockPos source = CENTER.west();
        helper.startSequence()
                .thenExecute(() -> helper.setBlock(source, Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> helper.assertTrue(be.state().powered(), "a high signal did not power the TV"))
                .thenExecute(() -> helper.setBlock(source, Blocks.AIR))
                .thenWaitUntil(() -> helper.assertFalse(be.state().powered(), "a low signal did not power the TV off"))
                .thenSucceed();
    }

    @GameTest(template = PLATFORM, timeoutTicks = 200)
    public static void risingEdgeTogglesOncePerPulse(GameTestHelper helper) {
        ServerPlayer owner = TvTests.player(helper, CORNER);
        TvBlockEntity be = TvTests.place(helper, owner, TvModel.CRT, CENTER, Direction.NORTH);
        be.state().setRedstoneMode(RedstoneMode.TOGGLE_POWER_ON_RISING_EDGE);
        BlockPos west = CENTER.west();
        BlockPos east = CENTER.east(2);
        helper.startSequence()
                .thenIdle(25) // past the placement validation tick
                .thenExecute(() -> helper.setBlock(west, Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> helper.assertTrue(be.state().powered(), "the first rising edge did not power the TV"))
                // A second source while the input is already high is not a new edge.
                .thenExecute(() -> helper.setBlock(east, Blocks.REDSTONE_BLOCK))
                .thenIdle(10)
                .thenExecute(() -> helper.assertTrue(be.state().powered(), "a steady high input toggled the TV"))
                // Falling edges do nothing.
                .thenExecute(() -> {
                    helper.setBlock(west, Blocks.AIR);
                    helper.setBlock(east, Blocks.AIR);
                })
                .thenIdle(10)
                .thenExecute(() -> helper.assertTrue(be.state().powered(), "a falling edge toggled the TV"))
                .thenExecute(() -> helper.setBlock(west, Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> helper.assertFalse(be.state().powered(), "the second rising edge did not toggle the TV off"))
                .thenSucceed();
    }

    /**
     * The one-change-per-second limit is measured on the session clock (wall time), not in ticks, so the waits here
     * use that clock too: a server catching up on lag runs many ticks in well under a second.
     */
    @GameTest(template = PLATFORM, timeoutTicks = 400)
    public static void redstoneChannelStepsAreDebounced(GameTestHelper helper) {
        ServerPlayer owner = TvTests.player(helper, CORNER);
        TvBlockEntity be = TvTests.place(helper, owner, TvModel.CRT, CENTER, Direction.NORTH);
        TvSessionService svc = TvSessionService.get();
        TvSession session = svc.session(be.tvId());
        TvTests.expect(helper, TvActions.perform(svc, session, be, null, TvActionType.SET_POWER, -1, TvActions.Args.ofInt(1)),
                RejectReason.NONE, "server power on");
        be.state().setRedstoneMode(RedstoneMode.NEXT_CHANNEL_ON_RISING_EDGE);
        BlockPos source = CENTER.west();
        int generation = session.sourceGeneration();
        long[] firstStep = {-1};
        helper.startSequence()
                .thenIdle(25)
                .thenExecute(() -> helper.setBlock(source, Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> helper.assertTrue(session.sourceGeneration() == generation + 1, "the first edge did not step the channel"))
                .thenExecute(() -> {
                    firstStep[0] = svc.now();
                    helper.assertTrue(be.state().channelNumber() == 1, "stepped to channel " + be.state().channelNumber());
                })
                // A second edge inside the window is ignored.
                .thenExecute(() -> helper.setBlock(source, Blocks.AIR))
                .thenIdle(3)
                .thenExecute(() -> helper.setBlock(source, Blocks.REDSTONE_BLOCK))
                .thenIdle(3)
                .thenExecute(() -> {
                    if (svc.now() - firstStep[0] < 900_000_000L) {
                        helper.assertTrue(session.sourceGeneration() == generation + 1, "a second edge within one second stepped again");
                    }
                })
                // Once the window has passed, the next edge steps again (the one-channel catalog wraps around).
                .thenExecute(() -> helper.setBlock(source, Blocks.AIR))
                .thenWaitUntil(() -> helper.assertTrue(svc.now() - firstStep[0] > 1_100_000_000L, "the debounce window is still open"))
                .thenExecute(() -> helper.setBlock(source, Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> helper.assertTrue(session.sourceGeneration() == generation + 2, "an edge after the window did not step"))
                .thenSucceed();
    }

    @GameTest(template = PLATFORM)
    public static void commandsResolveLoadedTelevisionsOnly(GameTestHelper helper) {
        ServerPlayer owner = TvTests.player(helper, FRONT);
        TvBlockEntity be = TvTests.place(helper, owner, TvModel.PLASMA, CENTER, Direction.NORTH);
        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack console = server.createCommandSourceStack().withLevel(helper.getLevel()).withSuppressedOutput();
        List<BlockPos> cells = TvTests.cells(be);
        BlockPos anchor = be.getBlockPos();
        BlockPos part = cells.get(5);
        BlockPos empty = helper.absolutePos(new BlockPos(2, 2, 14));
        BlockPos unloaded = anchor.offset(1_000_000, 0, 0);

        helper.assertTrue(run(server, console, "otetv info " + coords(anchor)) == 1, "info on the controller failed");
        helper.assertTrue(run(server, console, "otetv info " + coords(part)) == 1, "info on a part cell did not resolve the controller");
        helper.assertTrue(run(server, console, "otetv info " + coords(empty)) == 0, "info on an empty block succeeded");
        helper.assertTrue(run(server, console, "otetv power " + coords(unloaded) + " on") == 0, "a command on an unloaded position succeeded");
        helper.assertFalse(helper.getLevel().isLoaded(unloaded), "a command loaded a chunk");
        helper.assertTrue(run(server, console, "otetv power " + coords(part) + " on") == 1 && be.state().powered(), "console power on failed");
        helper.assertTrue(run(server, console, "otetv power " + coords(anchor) + " off") == 1 && !be.state().powered(), "console power off failed");
        // A non-operator owner is held to the remote range.
        TvTests.moveTo(helper, owner, CENTER.south(45));
        helper.assertTrue(run(server, owner.createCommandSourceStack().withSuppressedOutput(), "otetv power " + coords(anchor) + " on") == 0,
                "an out-of-range owner command succeeded");
        helper.assertFalse(be.state().powered(), "an out-of-range owner command changed the TV");
        TvTests.moveTo(helper, owner, FRONT);
        helper.assertTrue(run(server, owner.createCommandSourceStack().withSuppressedOutput(), "otetv power " + coords(anchor) + " on") == 1
                && be.state().powered(), "an in-range owner command failed");
        helper.succeed();
    }

    private static int run(MinecraftServer server, CommandSourceStack source, String command) {
        return server.getCommands().performPrefixedCommand(source, command);
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
