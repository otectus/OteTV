package com.otectus.otetv.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.otectus.otetv.block.entity.TvBlockEntity;
import com.otectus.otetv.block.entity.TvState;
import com.otectus.otetv.channel.ChannelCatalog;
import com.otectus.otetv.config.OteTvServerConfig;
import com.otectus.otetv.core.Limits;
import com.otectus.otetv.core.channel.ChannelDefinition;
import com.otectus.otetv.core.protocol.RejectReason;
import com.otectus.otetv.core.protocol.TvActionType;
import com.otectus.otetv.core.protocol.TvRole;
import com.otectus.otetv.core.source.MediaSource;
import com.otectus.otetv.core.source.SourceException;
import com.otectus.otetv.core.source.SourceInput;
import com.otectus.otetv.core.text.TextSanitizer;
import com.otectus.otetv.data.OteTvWorldData;
import com.otectus.otetv.network.OteTvNetwork;
import com.otectus.otetv.network.S2C;
import com.otectus.otetv.session.ServerSourcePolicy;
import com.otectus.otetv.session.TvAccess;
import com.otectus.otetv.session.TvActions;
import com.otectus.otetv.session.TvSession;
import com.otectus.otetv.session.TvSessionService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /otetv} (section 19). Coordinate commands resolve the controller of an already loaded assembly and fail
 * cleanly otherwise; they never force chunk loading and work with {@code execute in <dimension> run}. Command
 * availability never implies permission: every mutation goes through the same checks as the remote.
 */
public final class OteTvCommand {
    private static final SuggestionProvider<CommandSourceStack> ROLES = (ctx, b) ->
            SharedSuggestionProvider.suggest(List.of("viewer", "controller", "editor"), b);
    private static final SuggestionProvider<CommandSourceStack> CHANNELS = (ctx, b) -> {
        ChannelCatalog c = ChannelCatalog.get();
        List<String> numbers = new ArrayList<>();
        if (c != null) {
            c.channels().forEach(d -> numbers.add(Integer.toString(d.number())));
        }
        return SharedSuggestionProvider.suggest(numbers, b);
    };

    private OteTvCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("otetv");
        root.then(Commands.literal("help").executes(OteTvCommand::help));
        root.then(Commands.literal("info").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(OteTvCommand::info)));
        root.then(Commands.literal("power").then(Commands.argument("pos", BlockPosArgument.blockPos())
                .then(Commands.literal("on").executes(c -> act(c, TvActionType.SET_POWER, TvActions.Args.ofInt(1))))
                .then(Commands.literal("off").executes(c -> act(c, TvActionType.SET_POWER, TvActions.Args.ofInt(0))))));
        root.then(Commands.literal("channel").then(Commands.argument("pos", BlockPosArgument.blockPos())
                .then(Commands.argument("channel_id", IntegerArgumentType.integer(1, Limits.MAX_CHANNEL_NUMBER)).suggests(CHANNELS)
                        .executes(c -> act(c, TvActionType.SELECT_CHANNEL, TvActions.Args.ofInt(IntegerArgumentType.getInteger(c, "channel_id")))))));
        root.then(Commands.literal("play").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> act(c, TvActionType.PLAY, TvActions.Args.NONE))));
        root.then(Commands.literal("pause").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> act(c, TvActionType.PAUSE, TvActions.Args.NONE))));
        root.then(Commands.literal("stop").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> act(c, TvActionType.STOP, TvActions.Args.NONE))));
        root.then(Commands.literal("seek").then(Commands.argument("pos", BlockPosArgument.blockPos())
                .then(Commands.argument("seconds", IntegerArgumentType.integer(0, Limits.MAX_START_SECONDS))
                        .executes(c -> act(c, TvActionType.SEEK_ABSOLUTE, TvActions.Args.ofLong(IntegerArgumentType.getInteger(c, "seconds") * 1000L))))));
        root.then(Commands.literal("trust").then(Commands.argument("pos", BlockPosArgument.blockPos())
                .then(Commands.argument("player", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(), b))
                        .then(Commands.argument("role", StringArgumentType.word()).suggests(ROLES).executes(OteTvCommand::trust)))));
        root.then(Commands.literal("untrust").then(Commands.argument("pos", BlockPosArgument.blockPos())
                .then(Commands.argument("player", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(), b))
                        .executes(c -> act(c, TvActionType.TRUST_REMOVE, TvActions.Args.ofString(StringArgumentType.getString(c, "player"), 0))))));
        root.then(Commands.literal("reset").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> act(c, TvActionType.RESET, TvActions.Args.NONE))));
        root.then(Commands.literal("reload").requires(s -> s.hasPermission(2)).executes(OteTvCommand::reload));
        root.then(Commands.literal("diagnostics").executes(OteTvCommand::diagnostics));
        root.then(Commands.literal("channels").requires(s -> s.hasPermission(2))
                .then(Commands.literal("list").executes(OteTvCommand::listChannels))
                .then(Commands.literal("add").then(Commands.argument("number", IntegerArgumentType.integer(1, Limits.MAX_CHANNEL_NUMBER))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .then(Commands.argument("link", StringArgumentType.greedyString()).executes(OteTvCommand::addChannel)))))
                .then(Commands.literal("remove").then(Commands.argument("number", IntegerArgumentType.integer(1, Limits.MAX_CHANNEL_NUMBER))
                        .suggests(CHANNELS).executes(OteTvCommand::removeChannel))));
        root.then(Commands.literal("hosts").requires(s -> s.hasPermission(2))
                .then(Commands.literal("list").executes(OteTvCommand::listHosts))
                .then(Commands.literal("add").then(Commands.argument("host", StringArgumentType.word()).executes(c -> editHosts(c, true))))
                .then(Commands.literal("remove").then(Commands.argument("host", StringArgumentType.word()).executes(c -> editHosts(c, false)))));
        root.then(Commands.literal("publiccontrols").then(Commands.argument("pos", BlockPosArgument.blockPos())
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(c -> act(c, TvActionType.SET_PUBLIC_CONTROLS, TvActions.Args.ofInt(BoolArgumentType.getBool(c, "enabled") ? 1 : 0))))));
        dispatcher.register(root);
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        for (String line : List.of("help", "info", "power", "channel", "play", "seek", "trust", "untrust", "reset", "reload", "diagnostics", "channels", "hosts")) {
            ctx.getSource().sendSuccess(() -> Component.translatable("otetv.command.help." + line), false);
        }
        return 1;
    }

    private record Target(TvSessionService svc, TvSession session, TvBlockEntity be) {
    }

    private static Target target(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        TvSessionService svc = TvSessionService.get();
        TvBlockEntity be = TvSessionService.findController(level, pos);
        TvSession session = be == null || svc == null || be.tvId() == null ? null : svc.session(be.tvId());
        if (session == null) {
            ctx.getSource().sendFailure(Component.translatable("otetv.command.no_tv"));
            return null;
        }
        return new Target(svc, session, be);
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Target t = target(ctx);
        if (t == null) {
            return 0;
        }
        TvState s = t.be.state();
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.translatable("otetv.command.info.header", Component.translatable(t.be.model().translationKey()),
                s.displayName().isEmpty() ? "-" : s.displayName()), false);
        src.sendSuccess(() -> Component.translatable("otetv.command.info.identity", t.session.tvId().toString(), s.ownerName().isEmpty() ? "-" : s.ownerName()), false);
        src.sendSuccess(() -> Component.translatable("otetv.command.info.state", s.powered() ? "on" : "off",
                t.session.timeline().state().name().toLowerCase(Locale.ROOT),
                t.session.timeline().targetPositionMs(t.svc.now()) / 1000, s.channelNumber()), false);
        src.sendSuccess(() -> Component.translatable("otetv.command.info.source",
                s.source() == null ? "-" : s.source().type().id() + " " + s.source().displayLabel()), false);
        src.sendSuccess(() -> Component.translatable("otetv.command.info.session", t.session.revision(), t.session.sourceGeneration(),
                t.session.subscribers().size(), t.session.lease() == null ? "-" : t.session.lease().holderName(), s.trusted().size()), false);
        if (s.isLocked()) {
            src.sendSuccess(() -> Component.translatable("otetv.command.info.locked"), false);
        }
        return 1;
    }

    private static int act(CommandContext<CommandSourceStack> ctx, TvActionType type, TvActions.Args args) throws CommandSyntaxException {
        Target t = target(ctx);
        if (t == null) {
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null && !player.hasPermissions(TvAccess.OPERATOR_LEVEL)
                && TvSessionService.distanceToAssembly(player, t.be) > OteTvServerConfig.REMOTE_RANGE.get()) {
            ctx.getSource().sendFailure(Component.translatable(RejectReason.OUT_OF_RANGE.translationKey()));
            return 0;
        }
        TvActions.Outcome outcome = TvActions.perform(t.svc, t.session, t.be, player, type, -1, args);
        if (!outcome.accepted()) {
            ctx.getSource().sendFailure(Component.translatable(outcome.reason().translationKey()).append(outcome.detail().isEmpty() ? "" : " (" + outcome.detail() + ")"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("otetv.command.done", type.name().toLowerCase(Locale.ROOT)), true);
        return 1;
    }

    private static int trust(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String roleName = StringArgumentType.getString(ctx, "role").toLowerCase(Locale.ROOT);
        TvRole role = TvRole.byId(roleName).filter(TvRole::isAssignable).orElse(null);
        if (role == null) {
            ctx.getSource().sendFailure(Component.translatable("otetv.command.bad_role"));
            return 0;
        }
        return act(ctx, TvActionType.TRUST_SET, TvActions.Args.ofString(StringArgumentType.getString(ctx, "player"), role.ordinal()));
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        ChannelCatalog.rebuild(ctx.getSource().getServer());
        ChannelCatalog c = ChannelCatalog.get();
        int count = c == null ? 0 : c.channels().size();
        ctx.getSource().sendSuccess(() -> Component.translatable("otetv.command.reloaded", count, c == null ? 0 : c.diagnostics().size()), true);
        return 1;
    }

    private static int diagnostics(CommandContext<CommandSourceStack> ctx) {
        TvSessionService svc = TvSessionService.get();
        ChannelCatalog c = ChannelCatalog.get();
        int sessions = 0;
        int playing = 0;
        if (svc != null) {
            for (TvSession s : svc.sessions()) {
                sessions++;
                if (s.timeline().state() == com.otectus.otetv.core.state.PlayState.PLAYING) {
                    playing++;
                }
            }
        }
        String summary = "OteTV server: loaded TVs " + sessions + ", playing " + playing + ", registered TVs "
                + OteTvWorldData.get(ctx.getSource().getServer()).televisionCount() + ", channels " + (c == null ? 0 : c.channels().size())
                + ", source policy " + OteTvServerConfig.SOURCE_POLICY.get() + ", YouTube " + (OteTvServerConfig.ALLOW_YOUTUBE.get() ? "allowed" : "disabled")
                + ", protocol " + OteTvNetwork.PROTOCOL;
        ctx.getSource().sendSuccess(() -> Component.literal(summary), false);
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null) {
            // Client-local runtime details are gathered and shown by the client itself; nothing private is sent here.
            OteTvNetwork.sendTo(player, new S2C.ShowDiagnostics(summary));
        }
        return 1;
    }

    private static int listChannels(CommandContext<CommandSourceStack> ctx) {
        ChannelCatalog c = ChannelCatalog.get();
        if (c == null || c.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("otetv.command.no_channels"), false);
            return 0;
        }
        for (ChannelDefinition d : c.channels()) {
            Component name = d.translatable() ? Component.translatable(d.displayName()) : Component.literal(d.displayName());
            ctx.getSource().sendSuccess(() -> Component.literal(d.number() + ": ").append(name)
                    .append(" [" + d.source().type().id() + ", " + d.origin().name().toLowerCase(Locale.ROOT) + (d.enabled() ? "" : ", disabled") + "]"), false);
        }
        for (String diag : c.diagnostics()) {
            ctx.getSource().sendSuccess(() -> Component.literal("! " + diag), false);
        }
        return c.channels().size();
    }

    private static int addChannel(CommandContext<CommandSourceStack> ctx) {
        int number = IntegerArgumentType.getInteger(ctx, "number");
        String name = TextSanitizer.sanitize(StringArgumentType.getString(ctx, "name"), Limits.MAX_DISPLAY_NAME);
        String link = StringArgumentType.getString(ctx, "link");
        MediaSource source;
        try {
            source = link.equals("demo") ? MediaSource.bundled("otetv:welcome", name)
                    : SourceInput.fromLink(link, name, ServerSourcePolicy.structuralPolicy());
        } catch (SourceException e) {
            ctx.getSource().sendFailure(Component.translatable(e.issue().translationKey()));
            return 0;
        }
        ServerSourcePolicy.Verdict verdict = ServerSourcePolicy.check(source);
        if (!verdict.allowed()) {
            ctx.getSource().sendFailure(Component.translatable(verdict.reason().translationKey()).append(" " + verdict.detail()));
            return 0;
        }
        ChannelDefinition def = new ChannelDefinition("otetv_admin:ch" + number, number, name.isEmpty() ? "Channel " + number : name,
                false, "", source, true, ChannelDefinition.Origin.ADMIN);
        OteTvWorldData.get(ctx.getSource().getServer()).putAdminChannel(def);
        ChannelCatalog.rebuild(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.translatable("otetv.command.channel_added", number), true);
        return 1;
    }

    private static int removeChannel(CommandContext<CommandSourceStack> ctx) {
        int number = IntegerArgumentType.getInteger(ctx, "number");
        if (!OteTvWorldData.get(ctx.getSource().getServer()).removeAdminChannel(number)) {
            ctx.getSource().sendFailure(Component.translatable("otetv.command.channel_not_admin", number));
            return 0;
        }
        ChannelCatalog.rebuild(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.translatable("otetv.command.channel_removed", number), true);
        return 1;
    }

    private static int listHosts(CommandContext<CommandSourceStack> ctx) {
        List<? extends String> hosts = OteTvServerConfig.APPROVED_HOSTS.get();
        ctx.getSource().sendSuccess(() -> Component.translatable("otetv.command.hosts", OteTvServerConfig.SOURCE_POLICY.get().name(),
                hosts.isEmpty() ? "-" : String.join(", ", hosts)), false);
        return hosts.size();
    }

    private static int editHosts(CommandContext<CommandSourceStack> ctx, boolean add) {
        String host = StringArgumentType.getString(ctx, "host").toLowerCase(Locale.ROOT).trim();
        if (host.isEmpty() || host.length() > 253 || !host.matches("[*.]?[a-z0-9.-]+")) {
            ctx.getSource().sendFailure(Component.translatable("otetv.command.bad_host"));
            return 0;
        }
        List<String> hosts = new ArrayList<>(OteTvServerConfig.APPROVED_HOSTS.get());
        boolean changed = add ? !hosts.contains(host) && hosts.add(host) : hosts.remove(host);
        if (changed) {
            OteTvServerConfig.APPROVED_HOSTS.set(hosts);
            OteTvServerConfig.APPROVED_HOSTS.save();
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(add ? "otetv.command.host_added" : "otetv.command.host_removed", host), true);
        return changed ? 1 : 0;
    }
}
