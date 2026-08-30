package dev.virtualminecraft.net;

import dev.virtualminecraft.VirtualMinecraft;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: the 256-entry palette of an indexed-colour screen (the Computer, ROADMAP §7h §3). Sent before
 * the first {@link ScreenRectPayload#FORMAT_ZLIB_INDEXED} rectangle and whenever the palette changes; the client
 * keeps the index buffer and re-expands, so a palette change is a 1 KB message, not a frame.
 */
public record ScreenPalettePayload(UUID vm, int[] rgb) implements Payload {
	public static final ResourceLocation ID = VirtualMinecraft.id("screen_palette");

	public ScreenPalettePayload(final FriendlyByteBuf buf) {
		this(buf.readUUID(), read(buf));
	}

	private static int[] read(final FriendlyByteBuf buf) {
		final int[] p = new int[256];
		for (int i = 0; i < 256; i++) {
			p[i] = buf.readInt();
		}
		return p;
	}

	@Override
	public void write(final FriendlyByteBuf buf) {
		buf.writeUUID(vm);
		for (int i = 0; i < 256; i++) {
			buf.writeInt(i < rgb.length ? rgb[i] : 0);
		}
	}

	@Override
	public ResourceLocation id() {
		return ID;
	}
}
