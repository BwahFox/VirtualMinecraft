package dev.virtualminecraft.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;

/**
 * A GPU texture holding one VM framebuffer; updated with sub-rectangle uploads. (1.20.1: a plain GL texture
 * through {@code GlStateManager}, where 26.2 goes through the {@code GpuDevice} abstraction; the shape and the
 * callers are the same.)
 */
public final class ScreenTexture extends AbstractTexture {
	public final ResourceLocation id;
	public final int width;
	public final int height;

	public ScreenTexture(final ResourceLocation id, final int width, final int height) {
		this(id, width, height, false);
	}

	/** {@code nearest}: no filtering and a transparent start — the hardware cursor sprite (U1.3). */
	public ScreenTexture(final ResourceLocation id, final int width, final int height, final boolean nearest) {
		this.id = id;
		this.width = width;
		this.height = height;
		RenderSystem.assertOnRenderThreadOrInit();
		TextureUtil.prepareImage(getId(), width, height);
		bind();
		setFilter(!nearest, false);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		// Black (or clear, for a cursor) until the first rectangle arrives, exactly like the 26.2 texture.
		final ByteBuffer start = MemoryUtil.memCalloc(width * height * 4);
		try {
			if (!nearest) {
				for (int i = 3; i < width * height * 4; i += 4) {
					start.put(i, (byte) 0xFF);
				}
			}
			upload(0, 0, width, height, start);
		} finally {
			MemoryUtil.memFree(start);
		}
	}

	@Override
	public void load(final ResourceManager manager) {
		// nothing to load: every pixel arrives over the network
	}

	/** Uploads a tightly packed RGBA8 rectangle. {@code rgba} must be a direct buffer. Render thread only. */
	public void upload(final int x, final int y, final int w, final int h, final ByteBuffer rgba) {
		if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > width || y + h > height) {
			return;
		}
		RenderSystem.assertOnRenderThreadOrInit();
		bind();
		GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
		GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
		GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
		GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
		GlStateManager._texSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(rgba));
	}
}
