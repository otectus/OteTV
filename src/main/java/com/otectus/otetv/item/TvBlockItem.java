package com.otectus.otetv.item;

import com.otectus.otetv.block.TvBlock;
import com.otectus.otetv.block.TvModel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Television item. Item NBT can carry cosmetic choices only: block-state and block-entity tags are ignored so a
 * cloned or edited item can neither place a mis-oriented part nor inject ownership, ACLs or sources (section 9.2).
 */
public class TvBlockItem extends BlockItem {
    public TvBlockItem(TvBlock block, Properties properties) {
        super(block, properties);
    }

    public TvModel model() {
        return ((TvBlock) getBlock()).model();
    }

    @Override
    protected boolean updateCustomBlockEntityTag(BlockPos pos, Level level, @Nullable Player player, ItemStack stack, BlockState state) {
        // Identity, owner and cosmetic name are applied in TvBlockEntity#initializePlacement instead.
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        TvModel m = model();
        tooltip.add(Component.translatable(m.eraKey()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("otetv.tooltip.footprint", m.width(), m.height(), m.depth(),
                Component.translatable(m.isWidescreen() ? "otetv.tooltip.aspect_wide" : "otetv.tooltip.aspect_classic")).withStyle(ChatFormatting.DARK_GRAY));
        if (m.isWallMountable()) {
            tooltip.add(Component.translatable("otetv.tooltip.wall_mountable").withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
