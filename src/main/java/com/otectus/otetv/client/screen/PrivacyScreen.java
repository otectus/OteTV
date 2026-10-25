package com.otectus.otetv.client.screen;

import com.otectus.otetv.client.store.ConsentStore;
import com.otectus.otetv.client.store.PrivateHistory;
import com.otectus.otetv.config.OteTvClientConfig;
import com.otectus.otetv.core.net.LanGrant;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * Local trust management (sections 16.4 and 18.2): remembered host/service grants per server with revoke, deliberate
 * LAN grants, the external-media mode and global switch, and history deletion. Changes apply immediately and stop
 * any session that is no longer allowed.
 */
public class PrivacyScreen extends OteTvScreen {
    private EditBox hostBox;
    private EditBox portBox;
    private int page;

    public PrivacyScreen(@Nullable Screen parent) {
        super(Component.translatable("otetv.screen.privacy"), parent);
        this.panelWidth = 340;
        this.panelHeight = 236;
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        int l = left();
        int y = top() + 14;
        OteTvClientConfig.ExternalMedia mode = OteTvClientConfig.EXTERNAL_MEDIA.get();
        addRenderableWidget(button(Component.translatable("otetv.privacy.mode", Component.translatable("otetv.privacy.mode." + mode.name().toLowerCase(Locale.ROOT))), b -> {
            OteTvClientConfig.ExternalMedia[] values = OteTvClientConfig.ExternalMedia.values();
            OteTvClientConfig.EXTERNAL_MEDIA.set(values[(mode.ordinal() + 1) % values.length]);
            rebuild();
        }, l, y, 166, 20));
        boolean disabled = OteTvClientConfig.DISABLE_EXTERNAL_MEDIA.get();
        addRenderableWidget(button(Component.translatable(disabled ? "otetv.privacy.external_disabled" : "otetv.privacy.external_enabled"), b -> {
            OteTvClientConfig.DISABLE_EXTERNAL_MEDIA.set(!disabled);
            rebuild();
        }, l + 174, y, 166, 20));
        y += 26;
        List<ConsentStore.Grant> grants = ConsentStore.grants();
        int per = 4;
        for (int i = page * per; i < Math.min(grants.size(), (page + 1) * per); i++) {
            ConsentStore.Grant g = grants.get(i);
            addRenderableWidget(button(Component.translatable("otetv.privacy.revoke", g.target(), com.otectus.otetv.client.store.ServerContext.describe(g.context())),
                    b -> {
                        ConsentStore.revoke(g.context(), g.target());
                        rebuild();
                    }, l, y, panelWidth, 18));
            y += 20;
        }
        if (grants.size() > per) {
            addRenderableWidget(button(Component.literal("<"), b -> {
                page = Math.max(0, page - 1);
                rebuild();
            }, l, y, 20, 18));
            addRenderableWidget(button(Component.literal(">"), b -> {
                page = Math.min((grants.size() - 1) / per, page + 1);
                rebuild();
            }, l + 24, y, 20, 18));
        }
        y = top() + 136;
        hostBox = new EditBox(font, l, y, 180, 18, Component.translatable("otetv.privacy.lan_host"));
        hostBox.setMaxLength(253);
        addRenderableWidget(hostBox);
        portBox = new EditBox(font, l + 184, y, 60, 18, Component.translatable("otetv.privacy.lan_port"));
        portBox.setMaxLength(5);
        portBox.setFilter(s -> s.chars().allMatch(Character::isDigit));
        addRenderableWidget(portBox);
        addRenderableWidget(button(Component.translatable("otetv.privacy.add_lan"), b -> {
            try {
                ConsentStore.addLanGrant(new LanGrant(hostBox.getValue().trim(), Integer.parseInt(portBox.getValue())));
                setStatus(Component.translatable("otetv.screen.done"), false);
                rebuild();
            } catch (RuntimeException e) {
                setStatus(Component.translatable("otetv.privacy.bad_lan"), true);
            }
        }, l + 248, y - 1, 92, 20));
        y += 22;
        for (LanGrant g : ConsentStore.lanGrants()) {
            if (y > top() + panelHeight - 60) {
                break;
            }
            addRenderableWidget(button(Component.translatable("otetv.privacy.remove_lan", g.toString()), b -> {
                ConsentStore.removeLanGrant(g);
                rebuild();
            }, l, y, panelWidth, 18));
            y += 20;
        }
        int bottom = top() + panelHeight - 26 - 12;
        addRenderableWidget(button(Component.translatable("otetv.privacy.revoke_all"), b -> {
            ConsentStore.revokeAll();
            rebuild();
        }, l, bottom, 110, 20));
        addRenderableWidget(button(Component.translatable("otetv.privacy.clear_history"), b -> {
            PrivateHistory.clearHistory();
            setStatus(Component.translatable("otetv.screen.done"), false);
        }, l + 114, bottom, 110, 20));
        addRenderableWidget(button(Component.translatable("gui.back"), b -> onClose(), l + panelWidth - 80, bottom, 80, 20));
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (ConsentStore.grants().isEmpty()) {
            g.drawString(font, Component.translatable("otetv.privacy.no_grants"), left(), top() + 44, mutedColor());
        }
        g.drawString(font, Component.translatable("otetv.privacy.lan_title"), left(), top() + 126, mutedColor());
    }
}
