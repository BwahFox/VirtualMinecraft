package dev.virtualminecraft.net;

import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.block.ComputerBlockEntity;
import dev.virtualminecraft.config.VmcConfig;
import dev.virtualminecraft.screen.ScreenSource;
import dev.virtualminecraft.screen.ScreenSources;
import dev.virtualminecraft.screen.ScreenViewers;
import dev.virtualminecraft.vm.VmInstance;
import dev.virtualminecraft.vm.VmManager;
import java.util.function.Function;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 1.20.1: Fabric's networking here is (channel id, buffer) rather than 26.2's typed payloads, and a receiver runs
 * on the <em>network</em> thread rather than the server's. Both differences live in this file: {@link #send} is
 * what every {@code ServerPlayNetworking.send(player, payload)} in the shared-shaped code became, and each
 * receiver decodes on the network thread and hops to the server thread before touching the world — which is
 * exactly what 26.2's payload API did for us.
 */
public final class ModNetworking {
	private ModNetworking() {
	}

	/** The one place a payload becomes a packet on the server side. Safe from any thread, like the original. */
	public static void send(final ServerPlayer player, final Payload payload) {
		ServerPlayNetworking.send(player, payload.id(), payload.toBuf());
	}

	private interface OnServer<P> {
		void handle(MinecraftServer server, ServerPlayer player, P payload);
	}

	/** Decode on the network thread (the buffer is released after the receiver returns), act on the server thread. */
	private static <P> void receive(final ResourceLocation id, final Function<FriendlyByteBuf, P> reader, final OnServer<P> handler) {
		ServerPlayNetworking.registerGlobalReceiver(id, (server, player, listener, buf, responseSender) -> {
			final P payload = reader.apply(buf);
			server.execute(() -> handler.handle(server, player, payload));
		});
	}

	public static void register() {
		receive(ViewerPayload.ID, ViewerPayload::new, (server, player, payload) -> {
			// Heartbeats are accepted for any screen id, not only running VMs: a stopped computer's BIOS page is a
			// text screen with viewers too. Distance is the server's call, not the client's: past viewDistance the
			// heartbeat is dropped, so a far player is simply not a viewer.
			final ScreenSource source = ScreenSources.find(server, payload.vm());
			if (source != null && !player.blockPosition().closerThan(source.screenPos(), VmcConfig.get().viewDistance)) {
				return;
			}
			ScreenViewers.get(server).heartbeat(payload.vm(), player, payload.needFullFrame(), payload.lod());
		});

		receive(VmInputPayload.ID, VmInputPayload::new, (server, player, payload) -> {
			// Routed by screen id through the source, so a non-VM source gets the same events; a player farther than
			// viewDistance from the machine cannot drive it (before this, any client could send input to any VM id).
			final ScreenSource source = ScreenSources.find(server, payload.vm());
			if (source != null) {
				if (player.blockPosition().closerThan(source.screenPos(), VmcConfig.get().viewDistance)) {
					source.screenInput(player, payload.events());
				}
				return;
			}
			final VmInstance vm = VmManager.get(server).get(payload.vm());
			if (vm != null && vm.mayControl(player)) {
				vm.input(payload.events());
			}
		});

		receive(ScreenPastePayload.ID, ScreenPastePayload::new, (server, player, payload) -> {
			final ScreenSource source = ScreenSources.find(server, payload.vm());
			if (source != null && player.blockPosition().closerThan(source.screenPos(), VmcConfig.get().viewDistance)) {
				source.screenPaste(player, payload.text());
			}
		});

		receive(VmControlPayload.ID, VmControlPayload::new, (server, player, payload) -> {
			final BlockEntity be = player.level().getBlockEntity(payload.pos());
			if (!(be instanceof ComputerBlockEntity computer)) {
				return;
			}
			if (!player.blockPosition().closerThan(payload.pos(), 12.0)) {
				return;
			}
			if (VmcConfig.get().requireOp && !server.getPlayerList().isOp(player.getGameProfile())) {
				player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("virtualminecraft.msg.no_permission"));
				return;
			}
			final VmManager manager = VmManager.get(server);
			if (!dev.virtualminecraft.vm.VmConfig.isoAllowedFromClient(payload.config().iso)) {
				// Absolute / escaping ISO paths would mount an arbitrary host file into the guest.
				player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("virtualminecraft.msg.iso_outside"));
			}
			computer.setConfig(dev.virtualminecraft.vm.VmConfig.fromClientEdit(computer.getConfig(), payload.config()));
			switch (payload.action()) {
				case SAVE -> {
				}
				case START -> manager.start(computer, player);
				case SHUTDOWN -> manager.shutdown(computer, player);
				case FORCE_STOP -> manager.forceStop(computer, player);
				case RESET -> manager.reset(computer, player);
			}
		});
		VirtualMinecraft.LOGGER.debug("Registered payloads");
	}
}
