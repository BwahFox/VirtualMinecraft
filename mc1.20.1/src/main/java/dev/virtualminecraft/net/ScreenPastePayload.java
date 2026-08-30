package dev.virtualminecraft.net;

import dev.virtualminecraft.VirtualMinecraft;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: clipboard text pasted into a screen (Ctrl+V in the full-screen view), at most {@link #MAX_BYTES}. */
public record ScreenPastePayload(UUID vm, String text) implements Payload {
	public static final ResourceLocation ID = VirtualMinecraft.id("screen_paste");
	public static final int MAX_BYTES = 64 * 1024;

	public ScreenPastePayload(final FriendlyByteBuf buf) {
		this(buf.readUUID(), buf.readUtf(MAX_BYTES));
	}

	@Override
	public void write(final FriendlyByteBuf buf) {
		buf.writeUUID(vm);
		buf.writeUtf(text.length() > MAX_BYTES ? text.substring(0, MAX_BYTES) : text, MAX_BYTES);
	}

	@Override
	public ResourceLocation id() {
		return ID;
	}
}
