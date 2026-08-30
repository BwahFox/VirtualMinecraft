package dev.virtualminecraft.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.20.1 only: what 26.2's {@code CustomPacketPayload} is here. Fabric's networking on 1.20.1 sends a channel id
 * and a buffer, so each payload record names its channel and writes itself; {@link ModNetworking#send} and the
 * client's {@code ClientNet.send} are the two places that turn one into a packet.
 */
public interface Payload {
	ResourceLocation id();

	void write(FriendlyByteBuf buf);

	/** The payload as a fresh buffer, ready to send. */
	default FriendlyByteBuf toBuf() {
		final FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		write(buf);
		return buf;
	}
}
