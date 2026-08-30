package dev.virtualminecraft.net;

import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.util.Nums;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server heartbeat: "I am looking at this screen, keep streaming", optionally requesting a full frame,
 * and at which level of detail — {@code lod} 0 is full resolution and each level halves both dimensions. The client
 * chooses the level because only it knows how large the monitor is on screen (milestone 5 A3, ROADMAP §7l).
 */
public record ViewerPayload(UUID vm, boolean needFullFrame, int lod) implements Payload {
	public static final ResourceLocation ID = VirtualMinecraft.id("viewer");
	public static final int MAX_LOD = 3;

	public ViewerPayload(final FriendlyByteBuf buf) {
		this(buf.readUUID(), buf.readBoolean(), buf.readByte());
	}

	@Override
	public void write(final FriendlyByteBuf buf) {
		buf.writeUUID(vm);
		buf.writeBoolean(needFullFrame);
		buf.writeByte(Nums.clamp(lod, 0, MAX_LOD));
	}

	@Override
	public ResourceLocation id() {
		return ID;
	}
}
