package com.otectus.otetv.client.media;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.otectus.otetv.core.ipc.PixelFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A per-session dynamic texture (section 14.2). Creation, upload and deletion happen on the render thread; storage is
 * reallocated only when the decoded size changes, never per frame. Uploads are CPU RGBA/BGRA; shared-texture paths
 * are a later optimization.
 */
public final class VideoTexture extends AbstractTexture {
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final ResourceLocation location;
    private int width;
    private int height;
    private boolean registered;

    public VideoTexture() {
        this.location = new ResourceLocation("otetv", "dynamic/screen_" + COUNTER.incrementAndGet());
    }

    @Override
    public void load(ResourceManager manager) {
        // Pixels come from the playback worker, not from resources.
    }

    public ResourceLocation location() {
        return location;
    }

    public boolean hasContent() {
        return width > 0 && height > 0;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Uploads one frame. Must be called on the render thread. */
    public void upload(ByteBuffer pixels, int frameWidth, int frameHeight, int stride, PixelFormat format) {
        RenderSystem.assertOnRenderThread();
        if (!registered) {
            Minecraft.getInstance().getTextureManager().register(location, this);
            registered = true;
        }
        int texId = getId();
        if (frameWidth != width || frameHeight != height) {
            TextureUtil.prepareImage(texId, 0, frameWidth, frameHeight);
            setFilter(true, false);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            width = frameWidth;
            height = frameHeight;
        }
        GlStateManager._bindTexture(texId);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, stride / format.bytesPerPixel());
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, frameWidth, frameHeight,
                format == PixelFormat.RGBA ? GL11.GL_RGBA : GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, pixels);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
    }

    /** Releases the GL texture on the render thread. */
    public void release() {
        Runnable task = () -> {
            if (registered) {
                Minecraft.getInstance().getTextureManager().release(location);
                registered = false;
            } else {
                releaseId();
            }
            width = 0;
            height = 0;
        };
        if (RenderSystem.isOnRenderThread()) {
            task.run();
        } else {
            RenderSystem.recordRenderCall(task::run);
        }
    }
}
