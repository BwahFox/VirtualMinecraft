package dev.virtualminecraft.net;

import dev.virtualminecraft.VirtualMinecraft;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server heartbeat: "I am looking at this screen, keep streaming", optionally requesting a full frame,
 * and at which level of detail — {@code lod} 0 is full resolution and each level halves both dimensions. The client
 * chooses the level because only it knows how large the monitor is on screen (milestone 5 A3, ROADMAP §7l).
 */
public record ViewerPayload(UUID vm, boolean needFullFrame, int lod) implements CustomPacketPayload {
	public static final Type<ViewerPayload> TYPE = new Type<>(VirtualMinecraft.id("viewer"));
	public static final StreamCodec<FriendlyByteBuf, ViewerPayload> CODEC = CustomPacketPayload.codec(ViewerPayload::write, ViewerPayload::new);
	public static final int MAX_LOD = 3;

	private ViewerPayload(final FriendlyByteBuf buf) {
		this(buf.readUUID(), buf.readBoolean(), buf.readByte());
	}

	private void write(final FriendlyByteBuf buf) {
		buf.writeUUID(vm);
		buf.writeBoolean(needFullFrame);
		buf.writeByte(Math.clamp(lod, 0, MAX_LOD));
	}

	@Override
	public Type<ViewerPayload> type() {
		return TYPE;
	}
}
