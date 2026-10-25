package com.otectus.otetv.client.screen;

import com.otectus.otetv.client.store.ConsentStore;
import com.otectus.otetv.client.store.ServerContext;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Local consent before any external request (section 7.1): names the host or service the player's own computer will
 * contact, explains that it sees ordinary network information such as the IP address, and offers "allow once" or a
 * remembered, revocable grant scoped to this server. The Minecraft server can never answer this for the player.
 */
public class ConsentScreen extends OteTvScreen {
    private final String target;
    private final String context;

    public ConsentScreen(@Nullable Screen parent, String target) {
        super(Component.translatable("otetv.consent.title"), parent);
        this.target = target;
        this.context = ServerContext.current();
        this.panelWidth = 320;
        this.panelHeight = 170;
    }

    @Override
    protected void init() {
        int l = left();
        int y = top() + panelHeight - 50;
        addRenderableWidget(button(Component.translatable("otetv.consent.once"), b -> {
            ConsentStore.grant(context, target, false);
            onClose();
        }, l, y, 104, 20));
        addRenderableWidget(button(Component.translatable("otetv.consent.always"), b -> {
            ConsentStore.grant(context, target, true);
            onClose();
        }, l + 108, y, 104, 20));
        addRenderableWidget(button(Component.translatable("gui.cancel"), b -> onClose(), l + 216, y, 104, 20));
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int l = left();
        int y = top() + 16;
        Component body = Component.translatable(target.equals("youtube.com") ? "otetv.consent.body_youtube" : "otetv.consent.body", target,
                ServerContext.describe(context));
        List<FormattedCharSequence> lines = font.split(body, panelWidth);
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, l, y, textColor());
            y += 10;
        }
        y += 4;
        for (FormattedCharSequence line : font.split(Component.translatable("otetv.consent.note"), panelWidth)) {
            g.drawString(font, line, l, y, mutedColor());
            y += 10;
        }
    }
}
