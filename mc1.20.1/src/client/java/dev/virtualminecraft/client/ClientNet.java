package dev.virtualminecraft.client;

import dev.virtualminecraft.net.Payload;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.20.1 only: the client half of what {@code ModNetworking} is on the server. {@code ClientPlayNetworking} here
 * speaks (channel id, buffer) and runs receivers on the network thread; every call site that was
 * {@code ClientPlayNetworking.send(payload)} on 26.2 is {@code ClientNet.send(payload)} here, and receivers decode
 * on the network thread and hop to the client thread before touching textures or the level.
 */
public final class ClientNet {
	private ClientNet() {
	}

	public static boolean canSend(final ResourceLocation id) {
		return ClientPlayNetworking.canSend(id);
	}

	public static void send(final Payload payload) {
		ClientPlayNetworking.send(payload.id(), payload.toBuf());
	}

	public static <P> void receive(final ResourceLocation id, final Function<FriendlyByteBuf, P> reader, final Consumer<P> onClientThread) {
		ClientPlayNetworking.registerGlobalReceiver(id, (client, handler, buf, responseSender) -> {
			final P payload = reader.apply(buf);
			client.execute(() -> onClientThread.accept(payload));
		});
	}
}
