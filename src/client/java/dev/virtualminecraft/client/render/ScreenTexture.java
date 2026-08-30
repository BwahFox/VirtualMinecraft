package dev.virtualminecraft.client.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

/** A GPU texture holding one VM framebuffer; updated with sub-rectangle uploads. */
public final class ScreenTexture extends AbstractTexture {
	public final Identifier id;
	public final int width;
	public final int height;

	public ScreenTexture(final Identifier id, final int width, final int height) {
		this(id, width, height, false);
	}

	/** {@code nearest}: no filtering and a transparent start — the hardware cursor sprite (U1.3). */
	public ScreenTexture(final Identifier id, final int width, final int height, final boolean nearest) {
		this.id = id;
		this.width = width;
		this.height = height;
		final GpuDevice device = RenderSystem.getDevice();
		this.texture = device.createTexture(id::toString, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
		this.textureView = device.createTextureView(this.texture);
		this.sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, nearest ? FilterMode.NEAREST : FilterMode.LINEAR, FilterMode.NEAREST, false);
		try (NativeImage black = new NativeImage(width, height, true)) {
			black.fillRect(0, 0, width, height, nearest ? 0 : 0xFF000000);
			device.createCommandEncoder().writeToTexture(this.texture, black);
		}
	}

	/** Uploads a tightly packed RGBA8 rectangle. {@code rgba} must be a direct buffer. */
	public void upload(final int x, final int y, final int w, final int h, final ByteBuffer rgba) {
		if (this.texture == null || this.texture.isClosed()) {
			return;
		}
		if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > width || y + h > height) {
			return;
		}
		RenderSystem.getDevice().createCommandEncoder().writeToTexture(this.texture, rgba, 0, 0, x, y, w, h);
	}
}
