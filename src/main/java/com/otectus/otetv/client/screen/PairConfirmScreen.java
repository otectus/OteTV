package com.otectus.otetv.client.screen;

import com.otectus.otetv.item.RemoteControlItem;
import com.otectus.otetv.network.C2S;
import com.otectus.otetv.network.OteTvNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.InteractionHand;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Using a remote on a TV it is not paired with offers an explicit choice instead of silently rebinding (section 6.1).
 */
public class PairConfirmScreen extends OteTvScreen {
    private final InteractionHand hand;
    private final BlockPos anchor;
    @Nullable
    private final UUID targetTv;
    @Nullable
    private final RemoteControlItem.Pairing current;

    public PairConfirmScreen(InteractionHand hand, BlockPos anchor, @Nullable UUID targetTv, @Nullable RemoteControlItem.Pairing current) {
        super(Component.translatable("otetv.pair.title"), null);
        this.hand = hand;
        this.anchor = anchor;
        this.targetTv = targetTv;
        this.current = current;
        this.panelWidth = 300;
        this.panelHeight = 120;
    }

    @Override
    protected void init() {
        int l = left();
        int y = top() + panelHeight - 26;
        addRenderableWidget(button(Component.translatable(current == null ? "otetv.pair.pair" : "otetv.pair.switch"), b -> {
            OteTvNetwork.sendToServer(new C2S.PairRemote(hand, anchor));
            onClose();
        }, l, y, 96, 20));
        addRenderableWidget(button(Component.translatable("otetv.pair.open_menu"), b -> {
            if (targetTv != null) {
                minecraft.setScreen(new TvMenuScreen(targetTv));
            } else {
                onClose();
            }
        }, l + 102, y, 96, 20));
        addRenderableWidget(button(Component.translatable("gui.cancel"), b -> onClose(), l + 204, y, 96, 20));
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Component text = current == null ? Component.translatable("otetv.pair.unpaired_body")
                : Component.translatable("otetv.pair.switch_body", current.label().isEmpty() ? Component.translatable("otetv.pair.another_tv") : Component.literal(current.label()));
        int y = top() + 18;
        for (FormattedCharSequence line : font.split(text, panelWidth)) {
            g.drawString(font, line, left(), y, textColor());
            y += 10;
        }
        g.drawString(font, Component.translatable("otetv.pair.no_permission_note"), left(), y + 6, mutedColor());
    }
}
