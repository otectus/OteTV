package com.otectus.otetv.item;

import com.otectus.otetv.advancement.ModCriteria;
import com.otectus.otetv.block.TvBlock;
import com.otectus.otetv.block.TvAssembly;
import com.otectus.otetv.block.entity.TvBlockEntity;
import com.otectus.otetv.client.ClientHooks;
import com.otectus.otetv.core.Limits;
import com.otectus.otetv.core.text.TextSanitizer;
import com.otectus.otetv.data.OteTvWorldData;
import com.otectus.otetv.registry.ModSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * The universal remote (section 6.1). It stores the TV's persistent identity, the world namespace, dimension and a
 * display label; coordinates are only a hint. Pairing grants no permission, a copied remote cannot bypass the ACL,
 * and a replaced TV at the same position is never controlled because identities differ.
 */
public class RemoteControlItem extends Item {
    public static final String TAG = "OteTvRemote";

    public RemoteControlItem(Properties properties) {
        super(properties);
    }

    public record Pairing(UUID tv, UUID namespace, String dimension, String label, BlockPos hint) {
    }

    @Nullable
    public static Pairing pairing(ItemStack stack) {
        CompoundTag t = stack.getTagElement(TAG);
        if (t == null || !t.hasUUID("tv") || !t.hasUUID("ns")) {
            return null;
        }
        return new Pairing(t.getUUID("tv"), t.getUUID("ns"), t.getString("dim"),
                TextSanitizer.sanitize(t.getString("label"), Limits.MAX_DISPLAY_NAME), BlockPos.of(t.getLong("hint")));
    }

    public static void pair(ItemStack stack, ServerPlayer player, TvBlockEntity be) {
        CompoundTag t = stack.getOrCreateTagElement(TAG);
        t.putUUID("tv", be.tvId());
        t.putUUID("ns", OteTvWorldData.get(player.server).worldNamespace());
        t.putString("dim", player.level().dimension().location().toString());
        String label = be.state().displayName().isEmpty() ? Component.translatable(be.model().translationKey()).getString() : be.state().displayName();
        // Server-side translation falls back to the key; store the model id so clients can localize it.
        t.putString("label", be.state().displayName().isEmpty() ? "" : label);
        t.putString("model", be.model().registryName());
        t.putLong("hint", be.getBlockPos().asLong());
        player.level().playSound(null, player.blockPosition(), ModSounds.REMOTE_PAIR.get(), SoundSource.PLAYERS, 0.6f, 1.0f);
        player.displayClientMessage(Component.translatable("otetv.remote.paired"), true);
        ModCriteria.PAIRED_REMOTE.trigger(player);
    }

    public static void unpair(ItemStack stack) {
        stack.removeTagKey(TAG);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.openRemote(hand));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof TvBlock)) {
            return InteractionResult.PASS;
        }
        Player player = ctx.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        BlockPos anchor = TvAssembly.anchorOf(pos, state);
        if (player.isSecondaryUseActive()) {
            // Sneak-use pairs directly (section 6.1).
            if (!level.isClientSide && player instanceof ServerPlayer sp && level.getBlockEntity(anchor) instanceof TvBlockEntity be && be.tvId() != null) {
                pair(ctx.getItemInHand(), sp, be);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        // Ordinary use on a TV offers an explicit pair/switch choice instead of silently rebinding.
        if (level.isClientSide) {
            InteractionHand hand = ctx.getHand();
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.remoteUsedOnTelevision(hand, anchor));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        Pairing p = pairing(stack);
        if (p == null) {
            tooltip.add(Component.translatable("otetv.remote.not_paired").withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable("otetv.remote.how_to_pair").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            CompoundTag t = stack.getTagElement(TAG);
            Component label = p.label().isEmpty() && t != null
                    ? Component.translatable("block.otetv." + TextSanitizer.sanitize(t.getString("model"), 64))
                    : Component.literal(p.label());
            tooltip.add(Component.translatable("otetv.remote.paired_with", label).withStyle(ChatFormatting.GRAY));
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return false;
    }
}
