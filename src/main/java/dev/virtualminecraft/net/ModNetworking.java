package dev.virtualminecraft.net;

import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.block.ComputerBlockEntity;
import dev.virtualminecraft.config.VmcConfig;
import dev.virtualminecraft.screen.ScreenSource;
import dev.virtualminecraft.screen.ScreenSources;
import dev.virtualminecraft.screen.ScreenViewers;
import dev.virtualminecraft.vm.VmInstance;
import dev.virtualminecraft.vm.VmManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class ModNetworking {
	private ModNetworking() {
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(ScreenInfoPayload.TYPE, ScreenInfoPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ScreenRectPayload.TYPE, ScreenRectPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(AudioPayload.TYPE, AudioPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ScreenTextPayload.TYPE, ScreenTextPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ScreenPalettePayload.TYPE, ScreenPalettePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ScreenCursorPayload.TYPE, ScreenCursorPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ScreenPastePayload.TYPE, ScreenPastePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ViewerPayload.TYPE, ViewerPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(VmInputPayload.TYPE, VmInputPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(VmControlPayload.TYPE, VmControlPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(ViewerPayload.TYPE, (payload, context) -> {
			// Heartbeats are accepted for any screen id, not only running VMs: a stopped computer's BIOS page is a
			// text screen with viewers too. Distance is the server's call, not the client's: past viewDistance the
			// heartbeat is dropped, so a far player is simply not a viewer.
			final ScreenSource source = ScreenSources.find(context.server(), payload.vm());
			if (source != null && !context.player().blockPosition().closerThan(source.getBlockPos(), VmcConfig.get().viewDistance)) {
				return;
			}
			ScreenViewers.get(context.server()).heartbeat(payload.vm(), context.player(), payload.needFullFrame(), payload.lod());
		});

		ServerPlayNetworking.registerGlobalReceiver(VmInputPayload.TYPE, (payload, context) -> {
			// Routed by screen id through the source, so a non-VM source gets the same events; a player farther than
			// viewDistance from the machine cannot drive it (before this, any client could send input to any VM id).
			final ServerPlayer player = context.player();
			final ScreenSource source = ScreenSources.find(context.server(), payload.vm());
			if (source != null) {
				if (player.blockPosition().closerThan(source.getBlockPos(), VmcConfig.get().viewDistance)) {
					source.screenInput(player, payload.events());
				}
				return;
			}
			final VmInstance vm = VmManager.get(context.server()).get(payload.vm());
			if (vm != null && vm.mayControl(player)) {
				vm.input(payload.events());
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(ScreenPastePayload.TYPE, (payload, context) -> {
			final ServerPlayer player = context.player();
			final ScreenSource source = ScreenSources.find(context.server(), payload.vm());
			if (source != null && player.blockPosition().closerThan(source.getBlockPos(), VmcConfig.get().viewDistance)) {
				source.screenPaste(player, payload.text());
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(VmControlPayload.TYPE, (payload, context) -> {
			final ServerPlayer player = context.player();
			final BlockEntity be = player.level().getBlockEntity(payload.pos());
			if (!(be instanceof ComputerBlockEntity computer)) {
				return;
			}
			if (!player.blockPosition().closerThan(payload.pos(), 12.0)) {
				return;
			}
			if (VmcConfig.get().requireOp && !context.server().getPlayerList().isOp(player.nameAndId())) {
				player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("virtualminecraft.msg.no_permission"));
				return;
			}
			final VmManager manager = VmManager.get(context.server());
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
