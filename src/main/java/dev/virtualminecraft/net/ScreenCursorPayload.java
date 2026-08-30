package dev.virtualminecraft.net;

import dev.virtualminecraft.VirtualMinecraft;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> client: the hardware cursor of an indexed-colour screen (the Computer, ROADMAP §7h §3, U1.3): where it
 * is and whether it shows, plus — when the shape changed or the viewer is new — the sprite as RGBA8 with its hot
 * spot. The client draws it over the picture at the last pointer position, so a hover costs no redraw and no frame.
 * An empty {@code rgba} means "shape unchanged".
 */
public record ScreenCursorPayload(UUID vm, int x, int y, boolean visible, int hotX, int hotY, int w, int h, byte[] rgba) implements CustomPacketPayload {
	public static final Type<ScreenCursorPayload> TYPE = new Type<>(VirtualMinecraft.id("screen_cursor"));
	public static final StreamCodec<FriendlyByteBuf, ScreenCursorPayload> CODEC = CustomPacketPayload.codec(ScreenCursorPayload::write, ScreenCursorPayload::new);
	public static final int MAX_SIZE = 32;

	private ScreenCursorPayload(final FriendlyByteBuf buf) {
		this(buf.readUUID(), buf.readInt(), buf.readInt(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readByteArray(MAX_SIZE * MAX_SIZE * 4));
	}

	private void write(final FriendlyByteBuf buf) {
		buf.writeUUID(vm);
		buf.writeInt(x);
		buf.writeInt(y);
		buf.writeBoolean(visible);
		buf.writeVarInt(hotX);
		buf.writeVarInt(hotY);
		buf.writeVarInt(w);
		buf.writeVarInt(h);
		buf.writeByteArray(rgba);
	}

	@Override
	public Type<ScreenCursorPayload> type() {
		return TYPE;
	}
}
