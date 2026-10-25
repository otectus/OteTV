package com.otectus.otetv.client.screen;

import com.otectus.otetv.config.OteTvClientConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

/**
 * Shared look and behaviour for OteTV screens: a centred panel, readable focus, optional high-contrast colours,
 * a status line for action results, and disabled controls that explain why (section 7.3).
 */
public abstract class OteTvScreen extends Screen {
    @Nullable
    protected final Screen parent;
    protected Component status = Component.empty();
    protected boolean statusIsError;
    protected int panelWidth = 300;
    protected int panelHeight = 220;

    protected OteTvScreen(Component title, @Nullable Screen parent) {
        super(title);
        this.parent = parent;
    }

    protected boolean highContrast() {
        return OteTvClientConfig.HIGH_CONTRAST.get();
    }

    protected int left() {
        return (width - panelWidth) / 2;
    }

    protected int top() {
        return Math.max(4, (height - panelHeight) / 2);
    }

    protected int textColor() {
        return highContrast() ? 0xFFFFFFFF : 0xFFE6E6E6;
    }

    protected int mutedColor() {
        return highContrast() ? 0xFFFFFF55 : 0xFFA8A8A8;
    }

    protected int accentColor() {
        return highContrast() ? 0xFF55FFFF : 0xFFF2C14E;
    }

    protected Button button(Component label, Button.OnPress press, int x, int y, int w, int h) {
        return Button.builder(label, press).bounds(x, y, w, h).build();
    }

    /** A button that is disabled with an explanatory tooltip when {@code reason} is non-null. */
    protected Button button(Component label, Button.OnPress press, int x, int y, int w, int h, @Nullable Component reason) {
        Button b = button(label, press, x, y, w, h);
        if (reason != null) {
            b.active = false;
            b.setTooltip(Tooltip.create(reason));
        }
        return b;
    }

    protected void setStatus(Component message, boolean error) {
        status = message;
        statusIsError = error;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int l = left();
        int t = top();
        g.fill(l - 4, t - 4, l + panelWidth + 4, t + panelHeight + 4, highContrast() ? 0xFF000000 : 0xE0101014);
        g.renderOutline(l - 4, t - 4, panelWidth + 8, panelHeight + 8, highContrast() ? 0xFFFFFFFF : 0xFF3C3C46);
        g.drawCenteredString(font, title, width / 2, t, accentColor());
        renderContent(g, mouseX, mouseY, partialTick);
        if (!status.getString().isEmpty()) {
            g.drawCenteredString(font, status, width / 2, t + panelHeight - 10, statusIsError ? 0xFFFF6E6E : 0xFF8CE68C);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
