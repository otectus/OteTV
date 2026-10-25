package com.otectus.otetv.client.screen;

import com.otectus.otetv.client.ClientHooks;
import com.otectus.otetv.client.tv.ClientTvManager;
import com.otectus.otetv.core.protocol.RedstoneMode;
import com.otectus.otetv.core.protocol.TvActionType;
import com.otectus.otetv.core.protocol.TvPermission;
import com.otectus.otetv.core.protocol.TvRole;
import com.otectus.otetv.core.protocol.VisualPreset;
import com.otectus.otetv.network.C2S;
import com.otectus.otetv.network.OteTvNetwork;
import com.otectus.otetv.network.S2C;
import com.otectus.otetv.network.TvSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Shared TV settings for editors and the owner (section 6.3): name, picture preset, redstone mode, TV speaker level,
 * public controls and the trusted-player list. Each change is a validated server action; nothing here is trusted
 * locally.
 */
public class TvSettingsScreen extends OteTvScreen {
    private final UUID tv;
    private EditBox nameBox;
    private EditBox playerBox;
    private TvRole newRole = TvRole.CONTROLLER;
    private long seenRevision = -2;
    private int aclPage;
    private int trustedLabelY;
    private final Consumer<S2C.ActionResult> listener = this::onResult;

    public TvSettingsScreen(@Nullable Screen parent, UUID tv) {
        super(Component.translatable("otetv.screen.settings.title"), parent);
        this.tv = tv;
        this.panelWidth = 320;
        this.panelHeight = 240;
    }

    @Override
    protected void init() {
        ClientTvManager.get().addResultListener(listener);
        ClientTvManager.get().requestSnapshot(tv);
        OteTvNetwork.sendToServer(new C2S.RequestAcl(tv));
        rebuild();
    }

    @Override
    public void removed() {
        ClientTvManager.get().removeResultListener(listener);
    }

    private void onResult(S2C.ActionResult r) {
        if (r.actionId() < 0) {
            return;
        }
        setStatus(r.accepted() ? Component.translatable("otetv.screen.done") : Component.translatable(r.reason().translationKey())
                .append(r.detail().isEmpty() ? "" : " (" + r.detail() + ")"), !r.accepted());
        OteTvNetwork.sendToServer(new C2S.RequestAcl(tv));
    }

    private void rebuild() {
        String nameDraft = nameBox == null ? null : nameBox.getValue();
        String playerDraft = playerBox == null ? "" : playerBox.getValue();
        clearWidgets();
        TvSnapshot s = ClientTvManager.get().snapshot(tv);
        int l = left();
        int t = top() + 16;
        if (s == null) {
            addRenderableWidget(button(Component.translatable("gui.back"), b -> onClose(), l, top() + panelHeight - 26, 80, 20));
            return;
        }
        boolean settings = s.permissions().contains(TvPermission.SETTINGS);
        boolean acl = s.permissions().contains(TvPermission.ACL);
        Component noPerm = Component.translatable("otetv.disabled.no_permission");
        nameBox = new EditBox(font, l, t, panelWidth - 84, 18, Component.translatable("otetv.settings.name"));
        nameBox.setMaxLength(64);
        nameBox.setValue(nameDraft != null ? nameDraft : s.displayName());
        nameBox.setEditable(settings);
        addRenderableWidget(nameBox);
        addRenderableWidget(button(Component.translatable("otetv.settings.save"),
                b -> ClientTvManager.get().sendAction(tv, TvActionType.SET_DISPLAY_NAME, 0, 0, nameBox.getValue(), null, null),
                l + panelWidth - 80, t - 1, 80, 20, settings ? null : noPerm));
        t += 24;
        VisualPreset next = s.preset() == VisualPreset.AUTHENTIC ? VisualPreset.CLEAR : VisualPreset.AUTHENTIC;
        addRenderableWidget(button(Component.translatable("otetv.settings.picture", Component.translatable(s.preset().translationKey())),
                b -> ClientTvManager.get().sendAction(tv, TvActionType.SET_VISUAL_PRESET, 0, next.ordinal(), "", null, null),
                l, t, 156, 20, settings ? null : noPerm));
        RedstoneMode nextMode = s.redstoneMode().next();
        addRenderableWidget(button(Component.translatable("otetv.settings.redstone", Component.translatable(s.redstoneMode().translationKey())),
                b -> ClientTvManager.get().sendAction(tv, TvActionType.SET_REDSTONE_MODE, 0, nextMode.ordinal(), "", null, null),
                l + 164, t, 156, 20, settings ? null : noPerm));
        t += 24;
        AbstractSliderButton gain = new AbstractSliderButton(l, t, 156, 20, Component.empty(), s.speakerGain()) {
            {
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                setMessage(Component.translatable("otetv.settings.speaker", Math.round(value * 100)));
            }

            @Override
            protected void applyValue() {
            }

            @Override
            public void onRelease(double mouseX, double mouseY) {
                super.onRelease(mouseX, mouseY);
                // Coalesced: one action when the slider is released, not one per pixel of movement (section 9.3).
                ClientTvManager.get().sendAction(tv, TvActionType.SET_SPEAKER_GAIN, Math.round(value * 1000), 0, "", null, null);
            }
        };
        gain.active = s.permissions().contains(TvPermission.PLAYBACK);
        addRenderableWidget(gain);
        addRenderableWidget(button(Component.translatable(s.publicControls() ? "otetv.settings.public_on" : "otetv.settings.public_off"),
                b -> ClientTvManager.get().sendAction(tv, TvActionType.SET_PUBLIC_CONTROLS, 0, s.publicControls() ? 0 : 1, "", null, null),
                l + 164, t, 156, 20, acl ? null : noPerm));
        t += 28;
        trustedLabelY = t;
        t += 14;
        playerBox = new EditBox(font, l, t, 120, 18, Component.translatable("otetv.settings.player"));
        playerBox.setMaxLength(16);
        playerBox.setValue(playerDraft);
        playerBox.setEditable(acl);
        addRenderableWidget(playerBox);
        addRenderableWidget(button(Component.translatable(newRole.translationKey()), b -> {
            newRole = newRole == TvRole.VIEWER ? TvRole.CONTROLLER : newRole == TvRole.CONTROLLER ? TvRole.EDITOR : TvRole.VIEWER;
            rebuild();
        }, l + 124, t - 1, 100, 20, acl ? null : noPerm));
        addRenderableWidget(button(Component.translatable("otetv.settings.trust"),
                b -> ClientTvManager.get().sendAction(tv, TvActionType.TRUST_SET, 0, newRole.ordinal(), playerBox.getValue().trim(), null, null),
                l + 228, t - 1, 92, 20, acl ? null : noPerm));
        t += 24;
        List<S2C.AclEntry> entries = ClientHooks.acl(tv);
        int per = 3;
        for (int i = aclPage * per; i < Math.min(entries.size(), (aclPage + 1) * per); i++) {
            S2C.AclEntry e = entries.get(i);
            addRenderableWidget(button(Component.translatable("otetv.settings.untrust", e.name(), Component.translatable(e.role().translationKey())),
                    b -> ClientTvManager.get().sendAction(tv, TvActionType.TRUST_REMOVE, 0, 0, "", null, e.player()), l, t, panelWidth, 18,
                    acl ? null : noPerm));
            t += 20;
        }
        if (entries.size() > per) {
            // Page arrows share the heading row, so a full page never runs into the bottom buttons.
            addRenderableWidget(button(Component.literal("<"), b -> {
                aclPage = Math.max(0, aclPage - 1);
                rebuild();
            }, l + panelWidth - 44, trustedLabelY - 5, 20, 18));
            addRenderableWidget(button(Component.literal(">"), b -> {
                aclPage = Math.min((entries.size() - 1) / per, aclPage + 1);
                rebuild();
            }, l + panelWidth - 20, trustedLabelY - 5, 20, 18));
        }
        int bottom = top() + panelHeight - 26 - 12;
        addRenderableWidget(button(Component.translatable("otetv.settings.reset"), b -> minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                ClientTvManager.get().sendAction(tv, TvActionType.RESET);
            }
            minecraft.setScreen(this);
        }, Component.translatable("otetv.settings.reset_title"), Component.translatable("otetv.settings.reset_body"))),
                l, bottom, 120, 20, s.permissions().contains(TvPermission.RESET) ? null : noPerm));
        addRenderableWidget(button(Component.translatable("gui.back"), b -> onClose(), l + panelWidth - 80, bottom, 80, 20));
    }

    @Override
    public void tick() {
        TvSnapshot s = ClientTvManager.get().snapshot(tv);
        long rev = (s == null ? -1 : s.revision()) * 1000 + ClientHooks.acl(tv).size();
        if (rev != seenRevision && (nameBox == null || !nameBox.isFocused()) && (playerBox == null || !playerBox.isFocused())) {
            seenRevision = rev;
            rebuild();
        }
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (trustedLabelY > 0) {
            g.drawString(font, Component.translatable("otetv.settings.trusted_players"), left(), trustedLabelY, mutedColor());
        }
    }
}
