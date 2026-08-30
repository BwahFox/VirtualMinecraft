package dev.virtualminecraft.net;

import dev.virtualminecraft.VirtualMinecraft;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> server: clipboard text pasted into a screen (Ctrl+V in the full-screen view), at most {@link #MAX_BYTES}. */
public record ScreenPastePayload(UUID vm, String text) implements CustomPacketPayload {
	public static final Type<ScreenPastePayload> TYPE = new Type<>(VirtualMinecraft.id("screen_paste"));
	public static final StreamCodec<FriendlyByteBuf, ScreenPastePayload> CODEC = CustomPacketPayload.codec(ScreenPastePayload::write, ScreenPastePayload::new);
	public static final int MAX_BYTES = 64 * 1024;

	private ScreenPastePayload(final FriendlyByteBuf buf) {
		this(buf.readUUID(), buf.readUtf(MAX_BYTES));
	}

	private void write(final FriendlyByteBuf buf) {
		buf.writeUUID(vm);
		buf.writeUtf(text.length() > MAX_BYTES ? text.substring(0, MAX_BYTES) : text, MAX_BYTES);
	}

	@Override
	public Type<ScreenPastePayload> type() {
		return TYPE;
	}
}
