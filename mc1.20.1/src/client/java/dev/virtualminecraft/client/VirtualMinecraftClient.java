package dev.virtualminecraft.client;

import dev.virtualminecraft.ModContent;
import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.block.ComputerBlockEntity;
import dev.virtualminecraft.client.audio.VmAudio;
import dev.virtualminecraft.client.input.InputSender;
import dev.virtualminecraft.net.AudioPayload;
import dev.virtualminecraft.client.render.MonitorRenderer;
import dev.virtualminecraft.client.render.ScreenTextures;
import dev.virtualminecraft.client.screen.ComputerConfigScreen;
import dev.virtualminecraft.client.screen.VmScreen;
import dev.virtualminecraft.screen.ScreenSource;
import dev.virtualminecraft.net.ScreenInfoPayload;
import dev.virtualminecraft.net.ScreenRectPayload;
import dev.virtualminecraft.net.ScreenTextPayload;
import dev.virtualminecraft.block.MonitorBlockEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.core.BlockPos;

public class VirtualMinecraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		net.minecraft.client.gui.screens.MenuScreens.register(dev.virtualminecraft.ModContent.COMPUTER_MENU, dev.virtualminecraft.client.screen.ComputerScreen::new);
		BlockEntityRenderers.register(ModContent.MONITOR_BLOCK_ENTITY, MonitorRenderer::new);
		final String puppetPort = System.getProperty("virtualminecraft.puppet");
		if (puppetPort != null) {
			dev.virtualminecraft.client.dev.Puppet.start(Integer.parseInt(puppetPort));
		}

		ClientNet.receive(ScreenInfoPayload.ID, ScreenInfoPayload::new, ScreenTextures::onInfo);
		ClientNet.receive(ScreenRectPayload.ID, ScreenRectPayload::new, ScreenTextures::onRect);
		ClientNet.receive(dev.virtualminecraft.net.ScreenPalettePayload.ID, dev.virtualminecraft.net.ScreenPalettePayload::new, ScreenTextures::onPalette);
		ClientNet.receive(AudioPayload.ID, AudioPayload::new, VmAudio::onPayload);
		ClientNet.receive(dev.virtualminecraft.net.ScreenCursorPayload.ID, dev.virtualminecraft.net.ScreenCursorPayload::new, ScreenTextures::onCursor);
		ClientNet.receive(ScreenTextPayload.ID, ScreenTextPayload::new, payload -> {
			final Minecraft mc = Minecraft.getInstance();
			if (mc.level != null && mc.level.getBlockEntity(payload.pos()) instanceof MonitorBlockEntity monitor) {
				monitor.applyScreenText(payload);
			}
		});

		// §9 U4.0, the seam: the crosshair is a pointer source like any other, and the VR module registers its own
		// at a higher priority. With nothing else registered this is exactly the in-world hover that came before.
		dev.virtualminecraft.client.pointer.Pointers.register(new dev.virtualminecraft.client.pointer.CameraPointerSource());

		// §9 U4.3: in VR the "use" button is free — the controller is already the mouse — so the VR module claims
		// it for the keyboard. Nothing registers a handler on a desktop client, so this is a lookup that returns
		// null and using a monitor means exactly what it always meant.
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			final dev.virtualminecraft.client.pointer.MonitorUse handler = dev.virtualminecraft.client.pointer.MonitorUse.Registry.get();
			if (handler == null || !world.isClientSide() || player.isShiftKeyDown()) {
				return net.minecraft.world.InteractionResult.PASS; // sneak still opens the full-screen panel
			}
			if (!(world.getBlockEntity(hit.getBlockPos()) instanceof MonitorBlockEntity hitMonitor)) {
				return net.minecraft.world.InteractionResult.PASS;
			}
			final MonitorBlockEntity monitor = hitMonitor.origin() != null ? hitMonitor.origin() : hitMonitor;
			final java.util.UUID id = monitor.getScreenId();
			final ScreenSource src = monitor.getSource();
			if (id == null || src == null || !src.screenActive() || monitor.isTextMode()) {
				return net.minecraft.world.InteractionResult.PASS; // nothing to type at
			}
			return handler.onUse(id, hit.getBlockPos())
				? net.minecraft.world.InteractionResult.SUCCESS
				: net.minecraft.world.InteractionResult.PASS;
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ScreenTextures.clientTick();
			VmAudio.clientTick();
			InputSender.tick();
			dev.virtualminecraft.client.pointer.Pointers.tick();
			debugAutoOpen(client);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			// Fired on the network thread; GPU textures may only be released on the render thread.
			ScreenTextures.clear();
			VmAudio.clear();
			InputSender.endSession();
			dev.virtualminecraft.client.pointer.WorldPointer.release();
		}));

		VirtualMinecraft.localBridge = new VirtualMinecraft.LocalBridge() {
			@Override
			public boolean isLocalViewer(final java.util.UUID playerId) {
				final Minecraft mc = Minecraft.getInstance();
				return mc.hasSingleplayerServer() && mc.player != null && mc.player.getUUID().equals(playerId);
			}

			@Override
			public void screenInfo(final java.util.UUID vm, final int width, final int height, final boolean running, final int lod, final int flags) {
				Minecraft.getInstance().execute(() -> ScreenTextures.onInfo(new ScreenInfoPayload(vm, width, height, running, lod, flags)));
			}

			@Override
			public void screenRect(final java.util.UUID vm, final int x, final int y, final int width, final int height, final byte[] rgb) {
				Minecraft.getInstance().execute(() -> ScreenTextures.onRawRect(vm, x, y, width, height, rgb));
			}

			@Override
			public void screenRectIndexed(final java.util.UUID vm, final int x, final int y, final int width, final int height, final byte[] indices) {
				final byte[] copy = indices.clone();
				Minecraft.getInstance().execute(() -> ScreenTextures.onRawIndexed(vm, x, y, width, height, copy));
			}

			@Override
			public void screenPalette(final java.util.UUID vm, final int[] rgb) {
				Minecraft.getInstance().execute(() -> ScreenTextures.onPalette(vm, rgb));
			}

			@Override
			public void screenCursor(final java.util.UUID vm, final int x, final int y, final boolean visible, final int hotX, final int hotY, final int w, final int h, final byte[] rgba) {
				final byte[] copy = rgba.length == 0 ? rgba : rgba.clone();
				Minecraft.getInstance().execute(() -> ScreenTextures.onCursor(vm, x, y, visible, hotX, hotY, w, h, copy));
			}

			@Override
			public void audio(final java.util.UUID vm, final byte[] ulaw) {
				Minecraft.getInstance().execute(() -> VmAudio.push(vm, ulaw));
			}

			@Override
			public void audioAt(final java.util.UUID vm, final byte[] ulaw, final BlockPos pos) {
				Minecraft.getInstance().execute(() -> {
					VmAudio.setPosition(vm, net.minecraft.world.phys.Vec3.atCenterOf(pos));
					VmAudio.push(vm, ulaw);
				});
			}
		};

		VirtualMinecraft.clientHooks = new VirtualMinecraft.ClientHooks() {
			@Override
			public void openComputerScreen(final BlockPos pos) {
				final Minecraft mc = Minecraft.getInstance();
				if (mc.level != null && mc.level.getBlockEntity(pos) instanceof ComputerBlockEntity) {
					mc.setScreen(new ComputerConfigScreen(pos));
				}
			}

			@Override
			public void openMonitorScreen(final BlockPos sourcePos) {
				final Minecraft mc = Minecraft.getInstance();
				if (mc.level != null && mc.level.getBlockEntity(sourcePos) instanceof ScreenSource source) {
					mc.setScreen(new VmScreen(source.screenId(), source.screenName(), sourcePos));
				}
			}
		};
	}

	// Development aid: -Dvirtualminecraft.debugOpenScreen=x,y,z opens the VM screen of that computer once after joining.
	private static int debugTicks;
	private static boolean debugOpened;

	private static void debugAutoOpen(final Minecraft client) {
		final String prop = System.getProperty("virtualminecraft.debugOpenScreen");
		if (prop == null || debugOpened || client.level == null || client.player == null) {
			return;
		}
		if (++debugTicks < 100) {
			return;
		}
		final String[] parts = prop.split(",");
		if (parts.length != 3) {
			debugOpened = true;
			return;
		}
		debugOpened = true;
		VirtualMinecraft.clientHooks.openMonitorScreen(new BlockPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())));
	}
}
