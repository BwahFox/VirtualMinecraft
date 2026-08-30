package dev.virtualminecraft.net;

import dev.virtualminecraft.VirtualMinecraft;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: a screen's <em>full</em> dimensions, whether its source is running, and the level of detail
 * the rectangles that follow are sent at, plus capability {@code flags}. Sent on subscribe, on resize and whenever the
 * level changes; the client (re)creates its texture at {@link #scaled(int, int)} of the full size.
 */
public record ScreenInfoPayload(UUID vm, int width, int height, boolean running, int lod, int flags) implements Payload {
	/** The source takes {@code SCANCODE} input events (QEMU D-Bus display attached); otherwise send keysyms. */
	public static final int FLAG_SCANCODES = 1;
	/** The source also wants typed characters ({@code CHAR} events) alongside scancodes — the Computer, which has no guest layout. */
	public static final int FLAG_CHARS = 2;
	public static final ResourceLocation ID = VirtualMinecraft.id("screen_info");

	/** A full-resolution dimension at a level of detail: halved per level, rounded up so the last partial block survives. */
	public static int scaled(final int full, final int lod) {
		return Math.max(1, (full + (1 << lod) - 1) >> lod);
	}

	public int scaledWidth() {
		return scaled(width, lod);
	}

	public int scaledHeight() {
		return scaled(height, lod);
	}

	public ScreenInfoPayload(final FriendlyByteBuf buf) {
		this(buf.readUUID(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readByte(), buf.readByte());
	}

	@Override
	public void write(final FriendlyByteBuf buf) {
		buf.writeUUID(vm);
		buf.writeVarInt(width);
		buf.writeVarInt(height);
		buf.writeBoolean(running);
		buf.writeByte(lod);
		buf.writeByte(flags);
	}

	@Override
	public ResourceLocation id() {
		return ID;
	}
}
