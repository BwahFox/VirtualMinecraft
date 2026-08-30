package dev.virtualminecraft;

import dev.virtualminecraft.config.VmcConfig;
import dev.virtualminecraft.net.ModNetworking;
import dev.virtualminecraft.vm.VmManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VirtualMinecraft implements ModInitializer {
	public static final String MOD_ID = "virtualminecraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Hooks implemented by the client entrypoint so common block code can open screens without touching client classes. */
	public interface ClientHooks {
		void openComputerScreen(BlockPos pos);

		/** Opens the full-screen view of the screen owned by the {@code ScreenSource} at this position. */
		void openMonitorScreen(BlockPos sourcePos);
	}

	/**
	 * In singleplayer / LAN-host the VM and the host player's screen live in the same JVM. The client installs this
	 * bridge so frames for that player skip compression and the network stack entirely.
	 */
	public interface LocalBridge {
		boolean isLocalViewer(java.util.UUID playerId);

		/** {@code width}/{@code height} are the full screen size; rectangles follow at {@code lod} (see {@code ScreenInfoPayload}). */
		void screenInfo(java.util.UUID vm, int width, int height, boolean running, int lod, int flags);

		/** {@code rgb} is tightly packed RGB8 for the given rectangle; the callee must not keep a reference. */
		void screenRect(java.util.UUID vm, int x, int y, int width, int height, byte[] rgb);

		/** One byte per pixel into the screen's palette (the Computer); the callee must not keep a reference. */
		default void screenRectIndexed(final java.util.UUID vm, final int x, final int y, final int width, final int height, final byte[] indices) {
		}

		default void screenPalette(final java.util.UUID vm, final int[] rgb) {
		}

		/** The hardware cursor (U1.3): position/visibility, and the RGBA sprite when it changed ({@code rgba} empty otherwise). */
		default void screenCursor(final java.util.UUID vm, final int x, final int y, final boolean visible, final int hotX, final int hotY, final int w, final int h, final byte[] rgba) {
		}

		void audio(java.util.UUID vm, byte[] ulaw);

		/** Audio from a machine the client may not have placed yet (a Computer): the sound comes from {@code pos}. */
		default void audioAt(final java.util.UUID vm, final byte[] ulaw, final BlockPos pos) {
			audio(vm, ulaw);
		}
	}

	public static LocalBridge localBridge = new LocalBridge() {
		@Override
		public boolean isLocalViewer(final java.util.UUID playerId) {
			return false;
		}

		@Override
		public void screenInfo(final java.util.UUID vm, final int width, final int height, final boolean running, final int lod, final int flags) {
		}

		@Override
		public void screenRect(final java.util.UUID vm, final int x, final int y, final int width, final int height, final byte[] rgb) {
		}

		@Override
		public void audio(final java.util.UUID vm, final byte[] ulaw) {
		}
	};

	public static ClientHooks clientHooks = new ClientHooks() {
		@Override
		public void openComputerScreen(final BlockPos pos) {
		}

		@Override
		public void openMonitorScreen(final BlockPos sourcePos) {
		}
	};

	@Override
	public void onInitialize() {
		VmcConfig.get();
		ModContent.init();
		dev.virtualminecraft.bus.Components.registerBuiltins();
		dev.virtualminecraft.bus.ChatComponent.register();
		ModNetworking.register();
		VmcCommand.register();

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			dev.virtualminecraft.screen.ScreenViewers.get(server).tick();
			VmManager.get(server).tick();
			dev.virtualminecraft.computer.ComputerManager.get(server).tick();
			// §9 U9: hand queued messages to machines that have woken, and write the bus map when it changes.
			dev.virtualminecraft.bus.BusWake.tick(server);
			if (server.getTickCount() % 200 == 0) {
				dev.virtualminecraft.bus.BusRegistry.save(server);
			}
		});
		ServerLifecycleEvents.SERVER_STARTING.register(dev.virtualminecraft.worldgen.VillageStores::addToPools);
		dev.virtualminecraft.worldgen.ManualBook.register(); // §9 U3c: the book that points at the Manual
		ServerLifecycleEvents.SERVER_STARTED.register(dev.virtualminecraft.bus.BusRegistry::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(VmManager::shutdownServer);
		ServerLifecycleEvents.SERVER_STOPPING.register(dev.virtualminecraft.computer.ComputerManager::shutdownServer);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			dev.virtualminecraft.bus.BusRegistry.save(server);
			dev.virtualminecraft.bus.BusRegistry.reset();
			dev.virtualminecraft.bus.BusWake.reset();
		});

		LOGGER.info("VirtualMinecraft loaded (QEMU: {}, KVM: {})", VmcConfig.get().qemuBinary, dev.virtualminecraft.vm.QemuLauncher.kvmAvailable());
	}

	public static Identifier id(final String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
