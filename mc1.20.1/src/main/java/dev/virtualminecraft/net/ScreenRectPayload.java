package dev.virtualminecraft.net;

import dev.virtualminecraft.VirtualMinecraft;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: one rectangle of a screen, in the coordinates of the level of detail the viewer was last told
 * about. {@code format} says how {@code data} is encoded; only {@link #FORMAT_ZLIB_RGB} exists today. The byte is
 * there so an indexed-colour framebuffer (ROADMAP §7l) can be added without a second payload.
 */
public record ScreenRectPayload(UUID vm, int x, int y, int width, int height, int format, byte[] data) implements Payload {
	public static final ResourceLocation ID = VirtualMinecraft.id("screen_rect");

	/** Tightly packed RGB8, zlib-compressed. */
	public static final int FORMAT_ZLIB_RGB = 0;
	/** One byte per pixel, an index into the screen's palette ({@link ScreenPalettePayload}), zlib-compressed. */
	public static final int FORMAT_ZLIB_INDEXED = 1;

	public ScreenRectPayload(final FriendlyByteBuf buf) {
		this(buf.readUUID(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByte(), buf.readByteArray());
	}

	@Override
	public void write(final FriendlyByteBuf buf) {
		buf.writeUUID(vm);
		buf.writeVarInt(x);
		buf.writeVarInt(y);
		buf.writeVarInt(width);
		buf.writeVarInt(height);
		buf.writeByte(format);
		buf.writeByteArray(data);
	}

	@Override
	public ResourceLocation id() {
		return ID;
	}
}
