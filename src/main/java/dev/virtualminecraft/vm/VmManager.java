package dev.virtualminecraft.vm;

import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.block.ComputerBlockEntity;
import dev.virtualminecraft.config.VmcConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/** One per running (integrated or dedicated) server. Owns all live {@link VmInstance}s. */
public final class VmManager {
	private static final Map<MinecraftServer, VmManager> INSTANCES = new WeakHashMap<>();

	private final MinecraftServer server;
	private final Path root;
	private final Map<UUID, VmInstance> vms = new ConcurrentHashMap<>();

	private VmManager(final MinecraftServer server) {
		this.server = server;
		this.root = server.getWorldPath(new LevelResource("virtualminecraft"));
	}

	public static synchronized VmManager get(final MinecraftServer server) {
		return INSTANCES.computeIfAbsent(server, VmManager::new);
	}

	public static void shutdownServer(final MinecraftServer server) {
		final VmManager m;
		synchronized (VmManager.class) {
			m = INSTANCES.remove(server);
		}
		if (m != null) {
			m.shutdownAll();
		}
	}

	public MinecraftServer server() {
		return server;
	}

	public Path vmDir(final UUID id) {
		return root.resolve(id.toString());
	}

	/** Where disk items keep their images: {@code <world>/virtualminecraft/items/<uuid>.qcow2}. */
	public Path itemsDir() {
		return root.resolve("items");
	}

	public @Nullable VmInstance get(final UUID id) {
		return vms.get(id);
	}

	/** Every instance this server owns, running or not (server thread). */
	public Iterable<VmInstance> instances() {
		return vms.values();
	}

	public int runningCount() {
		int n = 0;
		for (final VmInstance vm : vms.values()) {
			if (vm.isAlive()) {
				n++;
			}
		}
		return n;
	}

	/** Called when a computer block entity starts ticking so a live VM re-finds its block after chunk reloads. */
	public void attach(final ComputerBlockEntity be) {
		final VmInstance vm = vms.get(be.getVmId());
		if (vm != null && be.getLevel() instanceof ServerLevel level) {
			vm.setOwner(level, be.getBlockPos());
		}
	}

	public void start(final ComputerBlockEntity be, final @Nullable ServerPlayer player) {
		final VmInstance existing = vms.get(be.getVmId());
		if (existing != null && existing.isAlive()) {
			message(player, "virtualminecraft.msg.already_running");
			return;
		}
		if (runningCount() >= VmcConfig.get().maxRunningVms) {
			message(player, "virtualminecraft.msg.too_many");
			be.setStatus(VmStatus.ERROR, "Too many running VMs (max " + VmcConfig.get().maxRunningVms + ")");
			return;
		}
		if (!(be.getLevel() instanceof ServerLevel level)) {
			return;
		}
		if (existing != null) {
			existing.forceStop();
		}
		final List<Attachment> disks = Attachments.collect(this, level, be);
		if (!Attachments.anyBootable(disks)) {
			// Nothing to boot at all: show the "BIOS" on the monitors instead of a SeaBIOS "No bootable device".
			if (hasSnapshot(be.getVmId())) {
				discardSnapshot(be.getVmId());
			}
			vms.remove(be.getVmId()); // a dead instance left here would keep syncing its old status over ours
			be.showFirmware(level, Attachments.bootOrder(disks), "No bootable device.");
			be.setStatus(VmStatus.STOPPED, "No bootable device");
			message(player, "virtualminecraft.msg.no_bootable");
			return;
		}
		final VmInstance vm = new VmInstance(this, be.getVmId(), be.getConfig().copy(), level, be.getBlockPos(), disks);
		vms.put(be.getVmId(), vm);
		vm.start();
		message(player, "virtualminecraft.msg.starting");
	}

	public void shutdown(final ComputerBlockEntity be, final @Nullable ServerPlayer player) {
		final VmInstance vm = vms.get(be.getVmId());
		if (vm == null || !vm.isAlive()) {
			message(player, "virtualminecraft.msg.not_running");
			return;
		}
		vm.acpiShutdown();
		message(player, "virtualminecraft.msg.shutdown_sent");
	}

	/** Force stop = pull the plug: a live VM is killed; a suspended one loses its saved state. */
	public void forceStop(final ComputerBlockEntity be, final @Nullable ServerPlayer player) {
		final VmInstance vm = vms.get(be.getVmId());
		if (vm != null && vm.isAlive()) {
			vm.forceStop();
			message(player, "virtualminecraft.msg.stopped");
			return;
		}
		if (hasSnapshot(be.getVmId())) {
			discardSnapshot(be.getVmId());
			if (vm != null) {
				vm.forceStop();
			}
			be.setStatus(VmStatus.STOPPED, "");
			message(player, "virtualminecraft.msg.snapshot_discarded");
			return;
		}
		if (vm != null) {
			vm.forceStop();
		}
		message(player, "virtualminecraft.msg.not_running");
	}

	/** Snapshot + quit now (also what chunk unload and server stop do); resumes on the next start/load. */
	public void suspend(final ComputerBlockEntity be, final @Nullable ServerPlayer player) {
		final VmInstance vm = vms.get(be.getVmId());
		if (vm == null || !vm.isAlive()) {
			message(player, "virtualminecraft.msg.not_running");
			return;
		}
		if (vm.suspend() != null) {
			message(player, "virtualminecraft.msg.suspending");
		}
	}

	public boolean hasSnapshot(final UUID id) {
		return Files.isRegularFile(vmDir(id).resolve(VmInstance.SUSPEND_MARKER));
	}

	private void discardSnapshot(final UUID id) {
		final VmInstance vm = vms.get(id);
		VmInstance.discardSnapshotFiles(VmcConfig.get(), vmDir(id), vm == null ? null : vm.currentAttachments());
	}


	public void reset(final ComputerBlockEntity be, final @Nullable ServerPlayer player) {
		final VmInstance vm = vms.get(be.getVmId());
		if (vm == null || !vm.isAlive()) {
			message(player, "virtualminecraft.msg.not_running");
			return;
		}
		vm.reset();
	}

	/**
	 * Drops an instance without touching the process or its files. For the one case that needs it: a VM whose block
	 * was removed without side effects has suspended (or stopped) itself and has nothing left to tick.
	 */
	void forget(final UUID id) {
		vms.remove(id);
	}

	/** Stops the VM and forgets it. Optionally deletes the VM directory (disk image) if the global config allows it. */
	public void remove(final UUID id, final boolean blockBroken) {
		final VmInstance vm = vms.remove(id);
		if (vm != null) {
			vm.forceStop();
		}
		if (blockBroken && VmcConfig.get().deleteDiskOnBreak) {
			deleteDir(vmDir(id));
		}
	}

	private static void deleteDir(final Path dir) {
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> s = Files.walk(dir)) {
			final List<Path> paths = new ArrayList<>(s.sorted(Comparator.reverseOrder()).toList());
			for (final Path p : paths) {
				Files.deleteIfExists(p);
			}
		} catch (final IOException e) {
			VirtualMinecraft.LOGGER.warn("Could not delete VM directory {}: {}", dir, e.toString());
		}
	}

	public void tick() {
		for (final VmInstance vm : vms.values()) {
			vm.tick();
		}
	}

	/** Server stopping: suspend every live VM that wants it (waiting for the snapshots), kill the rest. */
	public void shutdownAll() {
		final List<VmInstance> suspending = new ArrayList<>();
		for (final VmInstance vm : vms.values()) {
			if (vm.isAlive() && vm.config().suspend && vm.suspend() != null) {
				suspending.add(vm);
			}
		}
		for (final VmInstance vm : suspending) {
			vm.finishSuspend(120_000);
		}
		for (final VmInstance vm : vms.values()) {
			if (vm.status() != VmStatus.SUSPENDED) {
				vm.forceStop();
			}
		}
		vms.clear();
	}

	private static void message(final @Nullable ServerPlayer player, final String key) {
		if (player != null) {
			player.sendOverlayMessage(Component.translatable(key));
		}
	}
}
